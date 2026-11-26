package magicportals.portal;

import io.papermc.paper.math.Position;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A portal on this server that leads to another server of the network.
 * <p>
 * Everything is computed in the portal's local frame (see {@link Facing}): the opening covers local cells
 * i = 0..width-1, j = 0..height-1 in the wall row k = 0; players stand at k &gt; 0 and look through the
 * opening towards k &lt; 0. The portal plane is the back face of the wall row (local z = 0). The local
 * origin is the lower corner of the opening on that plane, where the shader marker sits.
 * In the config, x/y/z is the "anchor" cell (i = (width-1)/2, j = 0, k = 0).
 */
public final class Portal {
    public static final int MAX_SIZE = 7;
    public final String target, name;
    public final int x, y, z, width, height;
    public final Facing facing;
    public final boolean rift;         // style "rift": floating dimensional crack instead of a gate
    public final boolean insideView;   // cameras can fly "into" the destination behind the opening
    public final boolean barriers;     // with insideView: invisible barriers keep players out of the space behind
    private final int anchor;          // local column of the anchor cell
    private final double ox, oy, oz;   // world position of the local origin

    private final Plugin plugin;
    private final World world;
    private final Material outer, inner;   // null = no frame (the portal sits in a frame built by hand)
    private final String offlineLabel;

    private final List<Entity> decoration = new ArrayList<>();
    private final List<Location> helperBlocks = new ArrayList<>();
    private BlockDisplay marker;
    private TextDisplay label;
    private Integer scene;
    private boolean reachable;

    // fallback blocks (for players without the resource pack)
    private Snapshot remoteView, behind, captureFront, captureBehind, captureBack;
    private final BitSet difference = new BitSet(Snapshot.SIZE);
    private final Map<UUID, View> views = new HashMap<>();
    private int version, slice, ticks;
    private long remoteVersion;

    private static final class View {
        BitSet shown = new BitSet();
        int version = -1;
        double ex = Double.NaN, ey, ez;
        int nextFull;
    }

    /** A finished capture of this portal, stored by the plugin off the main thread. */
    public record Capture(String key, Snapshot front, Snapshot back) { }

    public Portal(Plugin plugin, World world, String target, String name, int x, int y, int z, Facing facing, int width, int height,
                  Material outer, Material inner, boolean rift, boolean insideView, boolean barriers, String offlineLabel) {
        this.plugin = plugin; this.world = world; this.target = target; this.name = name;
        this.x = x; this.y = y; this.z = z; this.facing = facing;
        this.outer = outer; this.inner = inner; this.rift = rift; this.insideView = insideView; this.barriers = barriers;
        this.width = Math.max(1, Math.min(MAX_SIZE, width));
        this.height = Math.max(1, Math.min(MAX_SIZE, height));
        this.anchor = (this.width - 1) / 2;
        this.offlineLabel = offlineLabel;
        // origin = centre of the anchor cell minus (anchor+.5, .5, .5) in local axes
        this.ox = x + .5 - (anchor + .5) * facing.xx - .5 * facing.zx;
        this.oy = y;
        this.oz = z + .5 - (anchor + .5) * facing.xz - .5 * facing.zz;
    }

    /** Exchange key: "<server>@<target>" = the view in front of the portal from server to target. */
    public static String key(String server, String target) { return server + "@" + target; }

    public boolean near(int px, int pz, int distance) { return Math.abs(px - x) < distance && Math.abs(pz - z) < distance; }
    public boolean reachable() { return reachable; }
    public Integer scene() { return scene; }

    // ---------- local frame ----------

    private Location at(double lx, double ly, double lz) {
        return new Location(world, ox + lx * facing.xx + lz * facing.zx, oy + ly, oz + lx * facing.xz + lz * facing.zz);
    }

    private Block cell(int i, int j, int k) { return at(i + .5, j + .5, k + .5).getBlock(); }

    /** World point → local coordinates {lx, ly, lz}. */
    private double[] local(Location l) {
        double dx = l.getX() - ox, dz = l.getZ() - oz;
        return new double[]{dx * facing.xx + dz * facing.xz, l.getY() - oy, dx * facing.zx + dz * facing.zz};
    }

    private BlockData read(int i, int j, int k) {
        int wy = y + j;
        if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) return Snapshot.AIR;
        return cell(i, j, k).getBlockData();
    }

    /** Marker glow green channel: 1 + style + 2*(no inside view) + 4*(width-1) + 32*(height-1). */
    private int markerCode() { return 1 + (rift ? 1 : 0) + (insideView ? 0 : 2) + 4 * (width - 1) + 32 * (height - 1); }

    // ---------- building ----------

    /** Block display covering the local box (lx..lx+sx, ly..ly+sy, lz..lz+sz). */
    private void box(double lx, double ly, double lz, double sx, double sy, double sz, Material material, boolean bright) {
        Location a = at(lx, ly, lz), b = at(lx + sx, ly + sy, lz + sz);
        Location min = new Location(world, Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        var size = new Vector3f((float) Math.abs(a.getX() - b.getX()), (float) Math.abs(a.getY() - b.getY()), (float) Math.abs(a.getZ() - b.getZ()));
        decoration.add(world.spawn(min, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setBlock(material.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), size, new Quaternionf()));
            if (bright) e.setBrightness(new Display.Brightness(15, 15));
        }));
    }

    /** Places an invisible helper block (barrier or light) where there is air; removed again on teardown. */
    private void helperBlock(Block block, Material material, int lightLevel) {
        if (!block.getType().isAir() && block.getType() != material) return;
        var data = material.createBlockData();
        if (data instanceof Levelled l) l.setLevel(lightLevel);
        block.setBlockData(data, false);
        helperBlocks.add(block.getLocation());
    }

    private void barrier(int i, int j, int k) { helperBlock(cell(i, j, k), Material.BARRIER, 0); }

    public void build() {
        Location origin = at(0, 0, 0);
        world.getChunkAt(origin.getBlockX() >> 4, origin.getBlockZ() >> 4).addPluginChunkTicket(plugin);
        // With the in-between view, the shader shows the destination all around a camera right behind the
        // opening (3 blocks deep); barriers keep normal players out of there (option "barriers").
        if (insideView && barriers) for (int i = 0; i < width; i++) for (int j = 0; j < height; j++) for (int k = -3; k <= -1; k++) barrier(i, j, k);
        if (rift) buildRift(); else if (outer != null) buildWall();
        // Marker for the portal shader at the local origin. Its glow colour carries scene, facing, style,
        // options and size (R=254, G=markerCode, B=160+scene+16*facing).
        marker = world.spawn(origin, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(Material.WHITE_CONCRETE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(.001f, .001f, .001f), new Quaternionf()));
            d.setDisplayWidth(2f * width + 6f); d.setDisplayHeight(2f * height + 4f); d.setViewRange(2f);
        });
        decoration.add(marker);
        setScene(scene);
        double labelHeight = rift ? height * 1.53 + .5 : height + (outer != null ? 2.5 : 1.0), labelZ = rift || outer == null ? .5 : 1.2;
        label = world.spawn(at(width / 2.0, labelHeight, labelZ), TextDisplay.class, d -> {
            d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setShadowed(true);
        });
        decoration.add(label);
        updateLabel();
    }

    /** Rift: no wall, only invisible light so the surroundings look lit by the rift. */
    private void buildRift() {
        helperBlock(cell(anchor, 1, 1), Material.LIGHT, 12);
        helperBlock(cell(anchor, height, 1), Material.LIGHT, 9);
    }

    /** Thick wall of block displays (2 blocks around the opening, 1 deep), barriers inside the wall. */
    private void buildWall() {
        for (int i = -2; i <= width + 1; i++) for (int j = 0; j <= height + 1; j++) {
            if (i >= 0 && i < width && j < height) continue;
            boolean rim = i >= -1 && i <= width && j <= height;
            box(i, j, 0, 1, 1, 1, rim ? inner : outer, false);
            barrier(i, j, 0);
        }
        double w = width, h = height;
        box(0, 0, .02, .06, h, .96, Material.AMETHYST_BLOCK, true);
        box(w - .06, 0, .02, .06, h, .96, Material.AMETHYST_BLOCK, true);
        box(0, h - .06, .02, w, .06, .96, Material.AMETHYST_BLOCK, true);
        box(0, 0, -.01, .05, h, .05, Material.LIGHT_BLUE_STAINED_GLASS, true);
        box(w - .05, 0, -.01, .05, h, .05, Material.LIGHT_BLUE_STAINED_GLASS, true);
        box(0, h - .05, -.01, w, .05, .05, Material.LIGHT_BLUE_STAINED_GLASS, true);
        box(0, 0, -.01, w, .03, .05, Material.LIGHT_BLUE_STAINED_GLASS, true);
    }

    public void tearDown() {
        for (Player p : world.getPlayers()) { var v = views.get(p.getUniqueId()); if (v != null) reset(p, v); }
        views.clear();
        for (var e : decoration) e.remove();
        decoration.clear();
        for (var l : helperBlocks) {
            var type = l.getBlock().getType();
            if (type == Material.BARRIER || type == Material.LIGHT) l.getBlock().setType(Material.AIR, false);
        }
        helperBlocks.clear();
        Location origin = at(0, 0, 0);
        world.removePluginChunkTicket(origin.getBlockX() >> 4, origin.getBlockZ() >> 4, plugin);
    }

    public void setScene(Integer newScene) {
        scene = newScene;
        if (marker == null) return;
        if (newScene == null || newScene < 0 || newScene >= 16) { marker.setGlowing(false); return; }
        marker.setGlowing(true);
        marker.setGlowColorOverride(Color.fromRGB(254, markerCode(), 160 + newScene + 16 * facing.index));
    }

    private void updateLabel() {
        if (label == null) return;
        label.text(reachable ? Component.text("✦  " + name + "  ✦")
                : Component.text("✦  " + name + "  ✦", NamedTextColor.GRAY).append(Component.newline())
                .append(Component.text(offlineLabel, NamedTextColor.RED)));
    }

    public void setReachable(boolean now) {
        if (now == reachable) return;
        reachable = now;
        version++;
        updateLabel();
    }

    // ---------- capturing ----------

    /** Captures everything at once (on start and before baking). */
    public Capture captureAll(String server) {
        var front = new Snapshot(); var newBehind = new Snapshot(); var back = new Snapshot(Snapshot.BACK_DEPTH);
        for (int d = 0; d < Snapshot.DEPTH; d++) captureSlice(front, newBehind, back, d);
        behind = newBehind;
        compare();
        captureFront = captureBehind = captureBack = null;
        return new Capture(key(server, target), front, back);
    }

    private void captureSlice(Snapshot front, Snapshot behindSnapshot, Snapshot back, int d) {
        front.captureFront(this::read, width, facing.captureRotation(), d);
        behindSnapshot.captureBehind(this::read, width, d);
        if (d < Snapshot.BACK_DEPTH) back.captureBack(this::read, width, facing.captureRotation(), d);
    }

    public void startCapture() {
        captureFront = new Snapshot(); captureBehind = new Snapshot(); captureBack = new Snapshot(Snapshot.BACK_DEPTH);
        slice = 0;
    }

    /** Two slices per tick so the server does not stutter. Returns the finished capture, else null. */
    public Capture captureStep(String server) {
        if (captureFront == null) return null;
        for (int n = 0; n < 2 && slice < Snapshot.DEPTH; n++, slice++) captureSlice(captureFront, captureBehind, captureBack, slice);
        if (slice < Snapshot.DEPTH) return null;
        var done = new Capture(key(server, target), captureFront, captureBack);
        behind = captureBehind;
        compare();
        captureFront = captureBehind = captureBack = null;
        return done;
    }

    /** Version of the other side's view currently used by the fallback blocks. */
    public long remoteVersion() { return remoteVersion; }

    /** New view of the other side (fallback blocks only); call on the main thread. */
    public void setRemoteView(byte[] data, long newVersion) {
        try {
            remoteView = Snapshot.read(data, plugin.getLogger());
            remoteView.rotatePalette(facing.localToWorld);   // the view is stored in the portal frame
            remoteVersion = newVersion;
            compare();
        } catch (IOException e) { plugin.getLogger().warning("View from " + target + ": " + e.getMessage()); }
    }

    private void compare() {
        difference.clear();
        if (remoteView != null && behind != null)
            for (int i = 0; i < Snapshot.SIZE; i++) if (!remoteView.block(i).equals(behind.block(i))) difference.set(i);
        version++;
    }

    // ---------- fallback blocks per player ----------

    private boolean inFront(double[] eye) {
        return eye[2] > .03 && eye[2] < 40 && Math.abs(eye[0] - width / 2.0) < 40 && Math.abs(eye[1] - 1.5) < 30;
    }

    public void updateFallback(Player p, int tick) {
        View v = views.computeIfAbsent(p.getUniqueId(), k -> new View());
        Location eyeLocation = p.getEyeLocation();
        double[] eye = local(eyeLocation);
        if (!reachable || remoteView == null || behind == null || eyeLocation.getWorld() != world || !inFront(eye)) { reset(p, v); return; }
        boolean full = v.version != version || tick >= v.nextFull && tick - v.nextFull < 2;
        double ex = eye[0], ey = eye[1], ez = eye[2];
        if (!full && Math.abs(ex - v.ex) < .04 && Math.abs(ey - v.ey) < .04 && Math.abs(ez - v.ez) < .04) return;
        v.ex = ex; v.ey = ey; v.ez = ez;
        BitSet visible = visibleCells(ex, ey, ez);
        visible.and(difference);
        var changes = new HashMap<Position, BlockData>();
        var added = (BitSet) visible.clone();
        if (!full) added.andNot(v.shown);
        else v.nextFull = (tick + 200) % 1200;
        for (int i = added.nextSetBit(0); i >= 0; i = added.nextSetBit(i + 1)) changes.put(position(i), remoteView.block(i));
        var removed = (BitSet) v.shown.clone();
        removed.andNot(visible);
        for (int i = removed.nextSetBit(0); i >= 0; i = removed.nextSetBit(i + 1)) changes.put(position(i), behind.block(i));
        v.shown = visible;
        v.version = version;
        if (!changes.isEmpty()) p.sendMultiBlockChange(changes);
    }

    /**
     * All cells behind the portal plane that are visible from the (local) eye through the opening. The opening
     * is a tunnel through the wall row: back plane z = 0, front plane z = 1, x 0..width, y 0..height. A cell
     * counts only if the line of sight passes both planes, like a window in a thick wall.
     */
    private BitSet visibleCells(double ex, double ey, double ez) {
        var bits = new BitSet(Snapshot.SIZE);
        boolean inTunnel = ez <= 1;
        if (inTunnel && (ex <= 0 || ex >= width || ey <= 0 || ey >= height)) return bits;
        double toBack = Math.max(.03, ez), toFront = ez - 1;
        for (int d = 0; d < Snapshot.DEPTH; d++) {
            double near = ez + d, far = ez + d + 1;
            double s1 = near / toBack, s2 = far / toBack;
            double xa = Math.min(ex - ex * s1, ex - ex * s2);
            double xb = Math.max(ex + (width - ex) * s1, ex + (width - ex) * s2);
            double ya = Math.min(ey - ey * s1, ey - ey * s2);
            double yb = Math.max(ey + (height - ey) * s1, ey + (height - ey) * s2);
            if (!inTunnel) {
                double t1 = near / toFront, t2 = far / toFront;
                xa = Math.max(xa, Math.min(ex - ex * t1, ex - ex * t2));
                xb = Math.min(xb, Math.max(ex + (width - ex) * t1, ex + (width - ex) * t2));
                ya = Math.max(ya, Math.min(ey - ey * t1, ey - ey * t2));
                yb = Math.min(yb, Math.max(ey + (height - ey) * t1, ey + (height - ey) * t2));
            }
            int rx0 = Math.max(-Snapshot.SIDE, (int) Math.floor(xa) - anchor), rx1 = Math.min(Snapshot.SIDE, (int) Math.ceil(xb) - 1 - anchor);
            int ry0 = Math.max(-Snapshot.BELOW, (int) Math.floor(ya)), ry1 = Math.min(Snapshot.ABOVE - 1, (int) Math.ceil(yb) - 1);
            if (rx0 > rx1 || ry0 > ry1) continue;
            for (int ry = ry0; ry <= ry1; ry++) {
                int start = Snapshot.index(rx0, ry, d);
                bits.set(start, start + (rx1 - rx0) + 1);
            }
        }
        return bits;
    }

    private Position position(int i) {
        int layer = Snapshot.HEIGHT * Snapshot.WIDTH;
        int d = i / layer, rest = i % layer;
        Block b = cell(anchor + rest % Snapshot.WIDTH - Snapshot.SIDE, rest / Snapshot.WIDTH - Snapshot.BELOW, -1 - d);
        return Position.block(b.getX(), b.getY(), b.getZ());
    }

    public void reset(Player p) { var v = views.remove(p.getUniqueId()); if (v != null) reset(p, v); }

    private void reset(Player p, View v) {
        if (v.shown.isEmpty() || behind == null) return;
        var changes = new HashMap<Position, BlockData>();
        for (int i = v.shown.nextSetBit(0); i >= 0; i = v.shown.nextSetBit(i + 1)) changes.put(position(i), behind.block(i));
        v.shown = new BitSet();
        v.ex = Double.NaN;
        if (p.isOnline()) p.sendMultiBlockChange(changes);
    }

    // ---------- particles, walking through, arrival ----------

    private static final Particle.DustOptions[] RIFT_DUST = {
            new Particle.DustOptions(Color.fromRGB(64, 217, 255), 1.1f),
            new Particle.DustOptions(Color.fromRGB(255, 77, 242), 1.1f),
            new Particle.DustOptions(Color.fromRGB(255, 209, 90), 0.9f)};

    /** Random point on the edge of the rift's base shape (tall narrow slit as in the shader, without spikes). */
    private Location riftEdge(double outside) {
        var random = ThreadLocalRandom.current();
        double t = random.nextDouble();
        double half = .8 * width / 3.0 * Math.pow(Math.sin(Math.PI * t), .95) * .9 + outside;
        double side = random.nextBoolean() ? 1 : -1;
        return at(width / 2.0 + side * half + (random.nextDouble() - .5) * .3, -.25 + height * 1.533 * t, .02);
    }

    public void particles() {
        if (rift) { riftParticles(); return; }
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height / 2.0, 0);
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(centre) > 400 + width * width * 4) continue;
            for (int i = 0; i < 3; i++) {
                // sparks wandering along the edge of the opening
                double u = random.nextDouble() * (2 * width + 2 * height), px, py;
                if (u < width) { px = u; py = height; }
                else if (u < width + height) { px = width; py = height - (u - width); }
                else if (u < 2 * width + height) { px = width - (u - width - height); py = 0; }
                else { px = 0; py = u - 2 * width - height; }
                p.spawnParticle(reachable ? Particle.END_ROD : Particle.SMOKE, at(px, py, .05), 0, 0, .02, 0, .3);
            }
            if (!reachable) p.spawnParticle(Particle.PORTAL, at(width / 2.0, height / 2.0, .1), 12, width * .3, height * .3, width * .3, .2);
        }
    }

    private void riftParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height * .55, 0);
        ticks++;
        for (Player p : world.getPlayers()) {
            double distance = p.getLocation().distanceSquared(centre);
            if (distance > 900) continue;
            for (int i = 0; i < 6; i++)
                p.spawnParticle(Particle.DUST, riftEdge(random.nextDouble() * .2), 1, .03, .03, .03, 0, RIFT_DUST[random.nextInt(RIFT_DUST.length)]);
            for (int i = 0; i < 2; i++) p.spawnParticle(Particle.ELECTRIC_SPARK, riftEdge(.05), 2, .08, .08, .08, .05);
            // particles from the surroundings are pulled into the rift
            if (random.nextInt(2) == 0) p.spawnParticle(Particle.REVERSE_PORTAL, riftEdge(1.0), 1, .2, .2, .2, .02);
            if (distance > 256) continue;
            if (ticks % 20 == 0) p.playSound(centre, "minecraft:block.beacon.ambient", .35f, 1.5f);
            if (random.nextInt(45) == 0) p.playSound(centre, "minecraft:block.amethyst_block.resonate", .25f, .6f + random.nextFloat() * .4f);
            if (random.nextInt(70) == 0) p.playSound(centre, "minecraft:entity.lightning_bolt.impact", .06f, 1.8f);
        }
    }

    /** Did the player walk from the front side through the opening? */
    public boolean walkedThrough(Location from, Location to) {
        if (from.getWorld() != world || to.getWorld() != world) return false;
        double[] a = local(from), b = local(to);
        double plane = .45;
        if (a[2] <= plane || b[2] > plane || to.distanceSquared(from) > 16) return false;
        double share = (a[2] - plane) / (a[2] - b[2]);
        double across = a[0] + (b[0] - a[0]) * share;
        double feet = a[1] + (b[1] - a[1]) * share;
        return across >= 0 && across <= width && feet >= -1.1 && feet < height;
    }

    /** Where players arriving through this portal's counterpart are placed: in front of it, facing away. */
    public Location arrival() {
        Location spot = at(width / 2.0, 0, 2.5);
        int ground = world.getHighestBlockYAt(spot.getBlockX(), spot.getBlockZ(), HeightMap.MOTION_BLOCKING) + 1;
        if (Math.abs(ground - y) <= 2) spot.setY(ground);
        spot.setYaw(facing.yaw);
        spot.setPitch(0);
        return spot;
    }
}
