package magicportals.portal;

import io.papermc.paper.math.Position;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A portal on this server that leads to another server of the network.
 * Layout: the opening (width x height blocks) lies in block row z, from column x0 = x - (width-1)/2 to
 * x0 + width - 1 and from y to y + height - 1; x is the centre column (left of centre for even widths).
 * Players stand south of it (larger z) and look north through it. The portal plane is the north face of row z.
 */
public final class Portal {
    public static final int MAX_SIZE = 7;
    public final String target, name;
    public final int x, y, z, width, height;
    public final boolean rift;         // style "rift": floating dimensional crack instead of a gate
    public final boolean insideView;   // cameras can fly "into" the destination behind the opening
    private final int x0;              // west-most column of the opening

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

    public Portal(Plugin plugin, World world, String target, String name, int x, int y, int z, int width, int height,
                  Material outer, Material inner, boolean rift, boolean insideView, String offlineLabel) {
        this.plugin = plugin; this.world = world; this.target = target; this.name = name;
        this.x = x; this.y = y; this.z = z; this.outer = outer; this.inner = inner; this.rift = rift;
        this.width = Math.max(1, Math.min(MAX_SIZE, width));
        this.height = Math.max(1, Math.min(MAX_SIZE, height));
        this.x0 = x - (this.width - 1) / 2;
        this.insideView = insideView;
        this.offlineLabel = offlineLabel;
    }

    /** Column the destination's view is mirrored around (the other side's portal centre). */
    private int mirrorX() { return x + (width - 1) % 2; }

    /** Marker glow green channel: 1 + style + 2*(no inside view) + 4*(width-1) + 32*(height-1). */
    private int markerCode() { return 1 + (rift ? 1 : 0) + (insideView ? 0 : 2) + 4 * (width - 1) + 32 * (height - 1); }

    /** Exchange key: "<server>@<target>" = the view in front of the portal from server to target. */
    public static String key(String server, String target) { return server + "@" + target; }

    public boolean near(int px, int pz, int distance) { return Math.abs(px - x) < distance && Math.abs(pz - z) < distance; }
    public boolean reachable() { return reachable; }
    public Integer scene() { return scene; }

    // ---------- building ----------

    private Location at(double px, double py, double pz) { return new Location(world, px, py, pz); }

    private BlockDisplay block(double px, double py, double pz, float w, float h, float d, Material material, boolean bright) {
        var display = world.spawn(at(px, py, pz), BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setBlock(material.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(w, h, d), new Quaternionf()));
            if (bright) e.setBrightness(new Display.Brightness(15, 15));
        });
        decoration.add(display);
        return display;
    }

    /** Places an invisible helper block (barrier or light) where there is air; removed again on teardown. */
    private void helperBlock(int bx, int by, int bz, Material material, int lightLevel) {
        var block = world.getBlockAt(bx, by, bz);
        if (!block.getType().isAir() && block.getType() != material) return;
        var data = material.createBlockData();
        if (data instanceof Levelled l) l.setLevel(lightLevel);
        block.setBlockData(data, false);
        helperBlocks.add(block.getLocation());
    }

    private void barrier(int bx, int by, int bz) { helperBlock(bx, by, bz, Material.BARRIER, 0); }

    public void build() {
        world.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(plugin);
        // With the in-between view, the shader shows the destination all around a camera right behind the
        // opening (3 blocks deep); barriers keep normal players out of there.
        if (insideView) for (int cx = x0; cx < x0 + width; cx++) for (int cy = y; cy < y + height; cy++) for (int cz = z - 3; cz <= z - 1; cz++) barrier(cx, cy, cz);
        if (rift) buildRift(); else if (outer != null) buildWall();
        // Marker for the portal shader at the lower west corner of the opening on the portal plane.
        // Its glow colour carries scene, style, options and size (R=254, G=markerCode, B=160+scene).
        marker = world.spawn(at(x0, y, z), BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(Material.WHITE_CONCRETE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(.001f, .001f, .001f), new Quaternionf()));
            d.setDisplayWidth(2f * width + 6f); d.setDisplayHeight(2f * height + 4f); d.setViewRange(2f);
        });
        decoration.add(marker);
        setScene(scene);
        double labelHeight = rift ? height * 1.53 + .5 : height + (outer != null ? 2.5 : 1.0), labelZ = rift || outer == null ? .5 : 1.2;
        label = world.spawn(at(x0 + width / 2.0, y + labelHeight, z + labelZ), TextDisplay.class, d -> {
            d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setShadowed(true);
        });
        decoration.add(label);
        updateLabel();
    }

    /** Rift: no wall, only invisible light so the surroundings look lit by the rift. */
    private void buildRift() {
        helperBlock(x, y + 1, z + 1, Material.LIGHT, 12);
        helperBlock(x, y + height, z + 1, Material.LIGHT, 9);
    }

    /** Thick wall of block displays (2 blocks around the opening, 1 deep), barriers inside the wall. */
    private void buildWall() {
        float w = width, h = height;
        for (int cx = x0 - 2; cx <= x0 + width + 1; cx++) for (int cy = y; cy <= y + height + 1; cy++) {
            if (cx >= x0 && cx < x0 + width && cy < y + height) continue;
            boolean rim = cx >= x0 - 1 && cx <= x0 + width && cy <= y + height;
            block(cx, cy, z, 1f, 1f, 1f, rim ? inner : outer, false);
            barrier(cx, cy, z);
        }
        for (double px : new double[]{x0, x0 + w - .06}) block(px, y, z + .02, .06f, h, .96f, Material.AMETHYST_BLOCK, true);
        block(x0, y + h - .06, z + .02, w, .06f, .96f, Material.AMETHYST_BLOCK, true);
        for (double px : new double[]{x0, x0 + w - .05}) block(px, y, z - .01, .05f, h, .05f, Material.LIGHT_BLUE_STAINED_GLASS, true);
        block(x0, y + h - .05, z - .01, w, .05f, .05f, Material.LIGHT_BLUE_STAINED_GLASS, true);
        block(x0, y, z - .01, w, .03f, .05f, Material.LIGHT_BLUE_STAINED_GLASS, true);
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
        world.removePluginChunkTicket(x >> 4, z >> 4, plugin);
    }

    public void setScene(Integer newScene) {
        scene = newScene;
        if (marker == null) return;
        if (newScene == null || newScene < 0 || newScene >= 16) { marker.setGlowing(false); return; }
        marker.setGlowing(true);
        marker.setGlowColorOverride(Color.fromRGB(254, markerCode(), 160 + newScene));
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
        for (int d = 0; d < Snapshot.DEPTH; d++) {
            front.captureFront(world, mirrorX(), y, z, d);
            newBehind.captureBehind(world, x, y, z, d);
            if (d < Snapshot.BACK_DEPTH) back.captureBack(world, mirrorX(), y, z, d);
        }
        behind = newBehind;
        compare();
        captureFront = captureBehind = captureBack = null;
        return new Capture(key(server, target), front, back);
    }

    public void startCapture() {
        captureFront = new Snapshot(); captureBehind = new Snapshot(); captureBack = new Snapshot(Snapshot.BACK_DEPTH);
        slice = 0;
    }

    /** Two slices per tick so the server does not stutter. Returns the finished capture, else null. */
    public Capture captureStep(String server) {
        if (captureFront == null) return null;
        for (int n = 0; n < 2 && slice < Snapshot.DEPTH; n++, slice++) {
            captureFront.captureFront(world, mirrorX(), y, z, slice);
            captureBehind.captureBehind(world, x, y, z, slice);
            if (slice < Snapshot.BACK_DEPTH) captureBack.captureBack(world, mirrorX(), y, z, slice);
        }
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
        try { remoteView = Snapshot.read(data, plugin.getLogger()); remoteVersion = newVersion; compare(); }
        catch (IOException e) { plugin.getLogger().warning("View from " + target + ": " + e.getMessage()); }
    }

    private void compare() {
        difference.clear();
        if (remoteView != null && behind != null)
            for (int i = 0; i < Snapshot.SIZE; i++) if (!remoteView.block(i).equals(behind.block(i))) difference.set(i);
        version++;
    }

    // ---------- fallback blocks per player ----------

    private boolean inFront(Location eye) {
        return eye.getWorld() == world && eye.getZ() > z + .03 && eye.getZ() < z + 40
                && Math.abs(eye.getX() - (x0 + width / 2.0)) < 40 && Math.abs(eye.getY() - (y + 1.5)) < 30;
    }

    public void updateFallback(Player p, int tick) {
        View v = views.computeIfAbsent(p.getUniqueId(), k -> new View());
        Location eye = p.getEyeLocation();
        if (!reachable || remoteView == null || behind == null || !inFront(eye)) { reset(p, v); return; }
        boolean full = v.version != version || tick >= v.nextFull && tick - v.nextFull < 2;
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
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
     * All cells behind the portal plane that are visible from the eye through the opening. The opening is a
     * 3x3 tunnel through wall row z: back plane z, front plane z+1, each from x-1..x+2 and y..y+3. A cell
     * counts only if the line of sight passes both planes, like a window in a thick wall.
     */
    private BitSet visibleCells(double ex, double ey, double ez) {
        var bits = new BitSet(Snapshot.SIZE);
        boolean inTunnel = ez <= z + 1;
        if (inTunnel && (ex <= x0 || ex >= x0 + width || ey <= y || ey >= y + height)) return bits;
        double toBack = Math.max(.03, ez - z), toFront = ez - (z + 1);
        for (int d = 0; d < Snapshot.DEPTH; d++) {
            double near = ez - (z - d), far = ez - (z - d - 1);
            double s1 = near / toBack, s2 = far / toBack;
            double xa = Math.min(ex + (x0 - ex) * s1, ex + (x0 - ex) * s2);
            double xb = Math.max(ex + (x0 + width - ex) * s1, ex + (x0 + width - ex) * s2);
            double ya = Math.min(ey + (y - ey) * s1, ey + (y - ey) * s2);
            double yb = Math.max(ey + (y + height - ey) * s1, ey + (y + height - ey) * s2);
            if (!inTunnel) {
                double t1 = near / toFront, t2 = far / toFront;
                xa = Math.max(xa, Math.min(ex + (x0 - ex) * t1, ex + (x0 - ex) * t2));
                xb = Math.min(xb, Math.max(ex + (x0 + width - ex) * t1, ex + (x0 + width - ex) * t2));
                ya = Math.max(ya, Math.min(ey + (y - ey) * t1, ey + (y - ey) * t2));
                yb = Math.min(yb, Math.max(ey + (y + height - ey) * t1, ey + (y + height - ey) * t2));
            }
            int rx0 = Math.max(-Snapshot.SIDE, (int) Math.floor(xa) - x), rx1 = Math.min(Snapshot.SIDE, (int) Math.ceil(xb) - 1 - x);
            int ry0 = Math.max(-Snapshot.BELOW, (int) Math.floor(ya) - y), ry1 = Math.min(Snapshot.ABOVE - 1, (int) Math.ceil(yb) - 1 - y);
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
        return Position.block(x + rest % Snapshot.WIDTH - Snapshot.SIDE, y + rest / Snapshot.WIDTH - Snapshot.BELOW, z - 1 - d);
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
        return at(x0 + width / 2.0 + side * half + (random.nextDouble() - .5) * .3, y - .25 + height * 1.533 * t, z + .02);
    }

    public void particles() {
        if (rift) { riftParticles(); return; }
        var random = ThreadLocalRandom.current();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(at(x0 + width / 2.0, y + height / 2.0, z)) > 400 + width * width * 4) continue;
            for (int i = 0; i < 3; i++) {
                // sparks wandering along the edge of the opening
                double u = random.nextDouble() * (2 * width + 2 * height), px, py;
                if (u < width) { px = x0 + u; py = y + height; }
                else if (u < width + height) { px = x0 + width; py = y + height - (u - width); }
                else if (u < 2 * width + height) { px = x0 + width - (u - width - height); py = y; }
                else { px = x0; py = y + (u - 2 * width - height); }
                p.spawnParticle(reachable ? Particle.END_ROD : Particle.SMOKE, px, py, z + .05, 0, 0, .02, 0, .3);
            }
            if (!reachable) p.spawnParticle(Particle.PORTAL, x0 + width / 2.0, y + height / 2.0, z + .1, 12, width * .3, height * .3, .05, .2);
        }
    }

    private void riftParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(x0 + width / 2.0, y + height * .55, z);
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

    /** Did the player walk from the south through the opening? */
    public boolean walkedThrough(Location from, Location to) {
        double plane = z + .45;
        if (from.getZ() <= plane || to.getZ() > plane || to.distanceSquared(from) > 16) return false;
        double share = (from.getZ() - plane) / (from.getZ() - to.getZ());
        double across = from.getX() + (to.getX() - from.getX()) * share;
        double feet = from.getY() + (to.getY() - from.getY()) * share;
        return across >= x0 && across <= x0 + width && feet >= y - 1.1 && feet < y + height;
    }

    /** Where players arriving through this portal's counterpart are placed: in front of it, facing south. */
    public Location arrival() {
        Location spot = at(x0 + width / 2.0, y, z + 2.5);
        int ground = world.getHighestBlockYAt(x, z + 2, HeightMap.MOTION_BLOCKING) + 1;
        if (Math.abs(ground - y) <= 2) spot.setY(ground);
        spot.setYaw(0);
        spot.setPitch(0);
        return spot;
    }
}
