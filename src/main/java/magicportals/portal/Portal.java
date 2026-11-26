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
    /** id = name of the portal in the config; link = connection ID shared with its counterpart (null: classic pairing by server). */
    public final String id, target, link, name;
    public final int x, y, z, width, height;
    public final Facing facing;
    public final Style style;
    public final boolean rift;         // any free-standing style (everything except gate): no wall
    public final boolean sound;        // ambient sounds (opt-in)
    public final float volume;         // their volume, 1 = normal
    public final double particleAmount; // particle multiplier, 1 = normal
    public final boolean effects;      // special effects on nearby players (fire, frost, darkness, …)
    public final boolean insideView;   // cameras can fly "into" the destination behind the opening
    public final boolean barriers;     // with insideView: invisible barriers keep players out of the space behind
    public final boolean showLabel;    // floating name above the portal
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

    public Portal(Plugin plugin, World world, String id, String target, String link, String name, int x, int y, int z, Facing facing, int width, int height,
                  Material outer, Material inner, Style style, boolean insideView, boolean barriers, boolean showLabel,
                  boolean sound, float volume, double particleAmount, boolean effects, String offlineLabel) {
        this.plugin = plugin; this.world = world; this.id = id; this.target = target; this.link = link; this.name = name;
        this.x = x; this.y = y; this.z = z; this.facing = facing;
        this.outer = outer; this.inner = inner; this.style = style; this.rift = style != Style.GATE; this.insideView = insideView;
        this.sound = sound; this.volume = Math.max(0f, volume);
        this.particleAmount = Math.max(0, Math.min(5, particleAmount)); this.effects = effects; this.barriers = barriers; this.showLabel = showLabel;
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

    /** Exchange key with a connection ID: "<server>@<target>#<link>". */
    public static String key(String server, String target, String link) { return link == null ? key(server, target) : key(server, target) + "#" + link; }

    /** Key of what this portal captures (its front), shown by the counterpart. */
    public String ownKey(String server) { return key(server, target, link); }

    /** Key of the scene this portal shows: what its counterpart on the target server captures. */
    public String sceneKey(String server) { return key(target, server, link); }

    public boolean near(int px, int pz, int distance) { return Math.abs(px - x) < distance && Math.abs(pz - z) < distance; }
    public World world() { return world; }
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
    private int markerCode() { return 1 + (style.index() & 1) + (insideView ? 0 : 2) + 4 * (width - 1) + 32 * (height - 1); }

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
        // options and size (R=254-(style>>1), G=markerCode, B=160+scene+16*facing).
        marker = world.spawn(origin, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setBlock(Material.WHITE_CONCRETE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(.001f, .001f, .001f), new Quaternionf()));
            d.setDisplayWidth(2f * width + 6f); d.setDisplayHeight(2f * height + 4f); d.setViewRange(2f);
        });
        decoration.add(marker);
        setScene(scene);
        if (!showLabel) return;
        double labelHeight = style == Style.RIFT ? height * 1.53 + 1.1 : rift ? height + 1.4 : height + (outer != null ? 2.5 : 1.0), labelZ = rift || outer == null ? .5 : 1.2;
        label = world.spawn(at(width / 2.0, labelHeight, labelZ), TextDisplay.class, d -> {
            d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setShadowed(true);
        });
        decoration.add(label);
        updateLabel();
    }

    /** Rift: no wall, only invisible light so the surroundings look lit by the rift. */
    private void buildRift() {
        int glow = switch (style) { case INFERNO -> 15; case VOID -> 7; case FROST -> 10; default -> 12; };
        helperBlock(cell(anchor, 1, 1), Material.LIGHT, glow);
        helperBlock(cell(anchor, height, 1), Material.LIGHT, Math.max(0, glow - 3));
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
        clearEffects();
        if (!filled.isEmpty()) { var real = fillBlocks(true); for (UUID id : filled) { Player p = Bukkit.getPlayer(id); if (p != null && p.getWorld() == world) p.sendMultiBlockChange(real); } filled.clear(); }
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
        marker.setGlowColorOverride(Color.fromRGB(254 - (style.index() >> 1), markerCode(), 160 + newScene + 16 * facing.index));
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
        return new Capture(ownKey(server), front, back);
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
        var done = new Capture(ownKey(server), captureFront, captureBack);
        behind = captureBehind;
        compare();
        captureFront = captureBehind = captureBack = null;
        return done;
    }

    /**
     * Captures the far terrain in front of this portal (see {@link FarTerrain}). Chunks are loaded
     * asynchronously and only if they were generated already; the heights are computed off the main thread.
     * Must be called on the main thread.
     */
    public java.util.concurrent.CompletableFuture<FarTerrain> captureFar() {
        int columns = FarTerrain.COLUMNS, rows = FarTerrain.TOTAL_ROWS, step = FarTerrain.STEP;
        int[] wx = new int[columns * rows], wz = new int[columns * rows];
        var chunks = new HashMap<Long, java.util.concurrent.CompletableFuture<ChunkSnapshot>>();
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
            int rx = (c - columns / 2) * step, d = (r - FarTerrain.BACK_ROWS) * step;
            // mirrored like Snapshot.captureFront: the viewer over there looks through and sees what lies in front of this portal
            Location l = at(width - 1 - anchor - rx + .5, 0, 1 + d + .5);
            int i = FarTerrain.index(c, r);
            wx[i] = l.getBlockX(); wz[i] = l.getBlockZ();
            int cx = wx[i] >> 4, cz = wz[i] >> 4;
            chunks.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), key ->
                    world.getChunkAtAsync(cx, cz, false).thenApply(chunk -> chunk == null ? null : chunk.getChunkSnapshot(true, false, false)));
        }
        boolean ceiling = world.hasCeiling();
        int top = Math.min(world.getMaxHeight() - 1, y + Snapshot.ABOVE), bottom = world.getMinHeight();
        return java.util.concurrent.CompletableFuture.allOf(chunks.values().toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenApplyAsync(ignored -> {
                    var far = new FarTerrain();
                    far.cave = ceiling;
                    far.end = world.getEnvironment() == World.Environment.THE_END;
                    // nether: only up to the bedrock roof (logical height), not the empty space above it
                    int maxY = Math.min(world.getMaxHeight(), world.getMinHeight() + world.getLogicalHeight()) - 1;
                    for (int i = 0; i < wx.length; i++) {
                        ChunkSnapshot s = chunks.get(((long) (wx[i] >> 4) << 32) | ((wz[i] >> 4) & 0xFFFFFFFFL)).join();
                        if (s == null) continue;
                        int bx = wx[i] & 15, bz = wz[i] & 15, c = i % FarTerrain.COLUMNS, r = i / FarTerrain.COLUMNS;
                        if (!ceiling) {
                            // open sky: the surface is the highest block
                            for (int yy = Math.min(s.getHighestBlockYAt(bx, bz), top + 200); yy >= bottom; yy--) {
                                BlockData b = s.getBlockData(bx, yy, bz);
                                if (empty(b)) continue;
                                far.set(c, r, yy + 1 - y, b.getAsString());
                                break;
                            }
                            continue;
                        }
                        // cave world (nether): the floor and the ceiling of the cave around the portal's eye height;
                        // a column that is solid there becomes a pillar up to the next open space
                        int eye = Math.max(bottom, Math.min(maxY, y + 2));
                        int floorTop;
                        if (empty(s.getBlockData(bx, eye, bz))) {
                            int yy = eye;
                            while (yy > bottom && empty(s.getBlockData(bx, yy - 1, bz))) yy--;
                            floorTop = yy;   // first empty block above the floor
                        } else {
                            int yy = eye;
                            while (yy < maxY && !empty(s.getBlockData(bx, yy + 1, bz))) yy++;
                            floorTop = yy + 1;
                        }
                        if (floorTop - 1 >= bottom) far.set(c, r, floorTop - y, s.getBlockData(bx, floorTop - 1, bz).getAsString());
                        int yy = floorTop;
                        while (yy <= maxY && empty(s.getBlockData(bx, yy, bz))) yy++;
                        if (yy <= maxY) far.setCeiling(c, r, yy - y, s.getBlockData(bx, yy, bz).getAsString());
                    }
                    return far;
                });
    }

    private static boolean empty(BlockData b) {
        Material m = b.getMaterial();
        return m.isAir() || m == Material.BARRIER || m == Material.LIGHT;
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
        return at(width / 2.0 + side * half + (random.nextDouble() - .5) * .3, .35 + height * 1.533 * t, .02);   // lifted 0.6 like in the shader
    }

    /** Random point on the edge of the opening of the styles inferno, storm, void and frost (as in the shader). */
    private Location edge(double outside) {
        if (style == Style.RIFT) return riftEdge(outside);
        var random = ThreadLocalRandom.current();
        double w = width, h = height;
        if (style == Style.INFERNO) {
            // outline of the main flame (as in the shader, without swaying)
            double t = random.nextDouble(), fh = h * 1.55;
            double profile = t < .3 ? .78 + .22 * Math.sqrt(Math.max(0, 1 - Math.pow((.3 - t) / .3, 2))) : Math.pow(1 - (t - .3) / .7, 1.4);
            double half = w * .5 * profile + outside;
            return at(w / 2 + (random.nextBoolean() ? half : -half), -.1 + t * fh, .02);
        }
        if (style == Style.FROST) {
            // outline of the ice crystal: two sides, two roof edges
            double cw = w * .5 * 1.08, cx = w / 2, ch = h * 1.6, s = random.nextBoolean() ? 1 : -1, t = random.nextDouble();
            if (random.nextDouble() < .55) return at(cx + s * (cw + (cw * .9 - cw) * t + outside), -.2 + (ch * .62 + .2) * t, .02);
            return at(cx + s * (cw * .9 * (1 - t) + outside), ch * .62 + ch * .38 * t + outside * .5, .02);
        }
        double rx = style == Style.STORM ? Math.min(w, h) / 2 : w / 2, ry = style == Style.STORM ? rx : h / 2;
        double t = random.nextDouble() * 2 * Math.PI;
        return at(w / 2 + Math.cos(t) * (rx + outside), h / 2 + Math.sin(t) * (ry + outside), .02);
    }

    /** Sound slots a portal can play; each can be replaced by a custom sound (option "sounds"). */
    public static final List<String> SOUND_SLOTS = List.of("ambient", "hum", "accent", "crackle", "strike", "thunder", "enter");

    /** Custom sounds per slot ("none" = silent), set by the plugin from the config. */
    public final Map<String, String> customSounds = new HashMap<>();

    /** Send the destination as client-side blocks too (for players without the shader); option "fallback-blocks". */
    public boolean fallback;

    /**
     * Portal surface for players without the shader (option "fallback": nether / end): the opening is filled
     * with nether portal or end gateway blocks, only on the clients. Players with the shader do not see it –
     * the shader draws the destination over it.
     */
    public String fill = "none";
    private final Set<UUID> filled = new HashSet<>();

    private Map<Position, BlockData> fillBlocks(boolean real) {
        var changes = new HashMap<Position, BlockData>();
        BlockData surface = null;
        if (!real) {
            if (fill.equals("end")) surface = Material.END_GATEWAY.createBlockData();
            else {
                var portal = (org.bukkit.block.data.Orientable) Material.NETHER_PORTAL.createBlockData();
                // the portal blocks lie in the plane of the opening
                portal.setAxis(facing == Facing.SOUTH || facing == Facing.NORTH ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
                surface = portal;
            }
        }
        for (int i = 0; i < width; i++) for (int j = 0; j < height; j++) {
            Block b = cell(i, j, 0);
            if (!b.getType().isAir()) continue;   // only empty cells of the opening
            changes.put(Position.block(b.getX(), b.getY(), b.getZ()), real ? b.getBlockData() : surface);
        }
        return changes;
    }

    /** Every 10 ticks: send the surface to players near the portal, take it back from players who left. */
    public void updateFill(Player p, int tick) {
        if (fill.equals("none")) return;
        boolean near = p.getWorld() == world && p.getLocation().distanceSquared(at(width / 2.0, height / 2.0, 0)) < 64 * 64;
        UUID id = p.getUniqueId();
        if (near && (filled.add(id) || tick % 100 == 0)) p.sendMultiBlockChange(fillBlocks(false));   // resent now and then: chunk updates overwrite it
        else if (!near && filled.remove(id) && p.getWorld() == world) p.sendMultiBlockChange(fillBlocks(true));
    }

    public void resetFill(Player p) { filled.remove(p.getUniqueId()); }

    /**
     * Plays the sound of a slot if sounds are enabled for this portal, scaled by its volume. A custom sound
     * replaces the built-in one and plays at full volume and normal pitch.
     */
    private void sound(Player p, Location at, String slot, String key, float volume, float pitch) {
        if (!sound || this.volume <= 0) return;
        String custom = customSounds.get(slot);
        if (custom != null) {
            if (custom.equals("none")) return;
            key = custom; volume = 1f; pitch = 1f;
        }
        p.playSound(at, key, SoundCategory.AMBIENT, volume * this.volume, pitch);
    }

    /** Sound for a player walking through (slot "enter"; silent unless a sound is configured). */
    public void playEnter(Player p) {
        String custom = customSounds.get("enter");
        if (sound && custom != null && !custom.equals("none")) p.playSound(p, custom, SoundCategory.AMBIENT, this.volume, 1f);
    }

    private static float pitch(float min, float max) { return min + ThreadLocalRandom.current().nextFloat() * (max - min); }

    /** Called every 4 ticks. */
    public void particles() {
        ticks++;
        switch (style) {
            case GATE -> gateParticles();
            case RIFT -> riftParticles();
            case INFERNO -> infernoParticles();
            case STORM -> stormParticles();
            case VOID -> voidParticles();
            case FROST -> frostParticles();
        }
        if (effects) playerEffects();
    }

    /** Number of particles for a base amount, scaled by the particle option (randomly rounded). */
    private int n(int base) {
        double v = base * particleAmount;
        int whole = (int) v;
        return whole + (ThreadLocalRandom.current().nextDouble() < v - whole ? 1 : 0);
    }

    private final Set<UUID> burning = new HashSet<>();
    private final Map<UUID, Integer> darkened = new HashMap<>();

    /**
     * Special effects on players close to the portal (option "effects"): inferno sets them visually on
     * fire, frost freezes the screen like powder snow (never long enough to hurt), void pulls a darkness
     * vignette over the screen. Storm shakes the camera of players next to a lightning strike (see strike).
     */
    private void playerEffects() {
        Location centre = at(width / 2.0, height / 2.0, 0);
        for (Player p : world.getPlayers()) {
            double[] l = local(p.getLocation());
            double distance = Math.sqrt(p.getLocation().distanceSquared(centre));
            switch (style) {
                case INFERNO -> {
                    boolean close = l[2] > -1 && l[2] < 2.2 && l[0] > -1.2 && l[0] < width + 1.2 && l[1] > -1 && l[1] < height * 1.55;
                    if (close && burning.add(p.getUniqueId())) p.setVisualFire(net.kyori.adventure.util.TriState.TRUE);
                    else if (!close && burning.remove(p.getUniqueId())) p.setVisualFire(net.kyori.adventure.util.TriState.NOT_SET);
                }
                case FROST -> {
                    if (distance > 4.5 || p.getGameMode() == GameMode.SPECTATOR) continue;
                    // ticks thaw by 2 per tick on their own; this runs every 4 ticks
                    int add = 8 + (int) Math.round(12 * (1 - distance / 4.5));
                    p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() - 20, p.getFreezeTicks() + add));
                }
                case VOID -> {
                    if (distance > 5) continue;
                    int last = darkened.getOrDefault(p.getUniqueId(), -100);
                    if (ticks - last < 8) continue;   // every 32 ticks
                    darkened.put(p.getUniqueId(), ticks);
                    p.sendPotionEffectChange(p, new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 50, 0, true, false, false));
                }
                default -> { }
            }
        }
    }

    /** Removes effects that would otherwise stay (visual fire). */
    private void clearEffects() {
        for (UUID id : burning) { Player p = Bukkit.getPlayer(id); if (p != null) p.setVisualFire(net.kyori.adventure.util.TriState.NOT_SET); }
        burning.clear();
        darkened.clear();
    }

    /** Players close enough to see and hear the portal's effects. */
    private List<Player> audience(Location centre, double distance) {
        var list = new ArrayList<Player>();
        for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(centre) <= distance * distance) list.add(p);
        return list;
    }

    private static final Particle.DustOptions[] EMBERS = {
            new Particle.DustOptions(Color.fromRGB(255, 120, 20), 0.8f),
            new Particle.DustOptions(Color.fromRGB(255, 200, 60), 0.6f),
            new Particle.DustOptions(Color.fromRGB(200, 40, 10), 0.9f)};

    private void infernoParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height / 2.0, 0);
        for (Player p : audience(centre, 32)) {
            for (int i = 0, k = n(8); i < k; i++) p.spawnParticle(Particle.FLAME, edge(random.nextDouble() * .15), 0, 0, 1, 0, .03 + random.nextDouble() * .05);
            for (int i = 0, k = n(2); i < k; i++) p.spawnParticle(Particle.SMALL_FLAME, edge(.1), 0, 0, 1, 0, .04);
            for (int i = 0, k = n(2); i < k; i++) p.spawnParticle(Particle.DUST, edge(random.nextDouble() * .6), 1, .1, .3, .1, 0, EMBERS[random.nextInt(EMBERS.length)]);
            if (random.nextInt(4) == 0) p.spawnParticle(Particle.LAVA, edge(0), 1, 0, 0, 0, 0);
            if (random.nextInt(3) == 0) p.spawnParticle(Particle.LARGE_SMOKE, at(width / 2.0, height + .3, .1), 0, (random.nextDouble() - .5) * .1, 1, 0, .05);
            if (p.getLocation().distanceSquared(centre) > 256) continue;
            if (random.nextInt(5) == 0) sound(p, centre, "ambient", "minecraft:block.fire.ambient", .6f, pitch(.8f, 1.2f));
            if (random.nextInt(12) == 0) sound(p, centre, "crackle", "minecraft:block.lava.pop", .5f, pitch(.8f, 1.2f));
            if (random.nextInt(40) == 0) sound(p, centre, "accent", "minecraft:block.lava.ambient", .4f, pitch(.8f, 1.0f));
            if (random.nextInt(60) == 0) sound(p, centre, "hum", "minecraft:entity.blaze.burn", .3f, pitch(.6f, .9f));
        }
    }

    private static final Particle.DustOptions[] STORM_DUST = {
            new Particle.DustOptions(Color.fromRGB(150, 120, 255), 1.0f),
            new Particle.DustOptions(Color.fromRGB(235, 235, 255), 0.8f)};
    private static final Particle.DustOptions BOLT = new Particle.DustOptions(Color.fromRGB(235, 235, 255), 1.6f);
    private static final Particle.DustOptions BOLT_GLOW = new Particle.DustOptions(Color.fromRGB(150, 130, 255), 2.2f);

    private void stormParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height / 2.0, 0);
        List<Player> players = audience(centre, 48);
        if (players.isEmpty()) return;
        for (Player p : players) {
            for (int i = 0, k = n(4); i < k; i++) p.spawnParticle(Particle.ELECTRIC_SPARK, edge(random.nextDouble() * .2), 2, .1, .1, .1, .08);
            for (int i = 0, k = n(3); i < k; i++) p.spawnParticle(Particle.DUST, edge(random.nextDouble() * .3), 1, .03, .03, .03, 0, STORM_DUST[random.nextInt(STORM_DUST.length)]);
            if (p.getLocation().distanceSquared(centre) > 400) continue;
            if (ticks % 25 == 0) sound(p, centre, "hum", "minecraft:block.beacon.ambient", .4f, .5f);
            if (random.nextInt(10) == 0) sound(p, centre, "crackle", "minecraft:entity.lightning_bolt.impact", .12f, pitch(1.6f, 2f));
        }
        // a lightning bolt strikes in front of the portal every few seconds
        if (random.nextInt(12) == 0) strike(players);
    }

    /** A harmless lightning strike: a jagged bolt of particles from the sky, a flash of light and thunder. */
    private void strike(List<Player> players) {
        var random = ThreadLocalRandom.current();
        Location spot = at(random.nextDouble(-3, width + 3), 0, random.nextDouble(1.5, 7));
        int ground = world.getHighestBlockYAt(spot.getBlockX(), spot.getBlockZ(), HeightMap.MOTION_BLOCKING);
        if (Math.abs(ground + 1 - y) > 6) ground = y - 1;
        Location impact = new Location(world, spot.getX(), ground + 1, spot.getZ());
        // zig-zag path from 16 blocks up down to the ground
        var points = new ArrayList<Location>();
        double px = impact.getX(), pz = impact.getZ();
        for (double h = 16; h >= 0; h -= .25) {
            if ((int) (h * 4) % 6 == 0) { px += random.nextDouble(-.5, .5); pz += random.nextDouble(-.5, .5); }
            double fade = h / 16;
            points.add(new Location(world, px + (impact.getX() - px) * (1 - fade) * .6, impact.getY() + h, pz + (impact.getZ() - pz) * (1 - fade) * .6));
        }
        Block lightSpot = impact.getBlock();
        boolean light = lightSpot.getType().isAir();
        BlockData lightData = Material.LIGHT.createBlockData(d -> ((Levelled) d).setLevel(15));
        for (Player p : players) {
            for (Location l : points) {
                p.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, BOLT);
                p.spawnParticle(Particle.DUST, l, 1, .08, .08, .08, 0, BOLT_GLOW);
            }
            p.spawnParticle(Particle.ELECTRIC_SPARK, impact, 30, .4, .1, .4, .3);
            p.spawnParticle(Particle.FIREWORK, impact, 12, .2, .1, .2, .15);
            p.spawnParticle(Particle.LARGE_SMOKE, impact, 6, .3, .1, .3, .02);
            if (light) p.sendBlockChange(lightSpot.getLocation(), lightData);
            sound(p, impact, "strike", "minecraft:entity.lightning_bolt.impact", 1.0f, pitch(.8f, 1.1f));
            sound(p, impact, "thunder", "minecraft:entity.lightning_bolt.thunder", .5f, pitch(.8f, 1.2f));
            if (effects && p.getLocation().distanceSquared(impact) < 36) p.sendHurtAnimation((float) random.nextDouble(0, 360));
        }
        if (light) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : players) if (p.isOnline()) p.sendBlockChange(lightSpot.getLocation(), lightSpot.getBlockData());
        }, 4);
    }

    private static final Particle.DustOptions[] VOID_DUST = {
            new Particle.DustOptions(Color.fromRGB(140, 40, 240), 1.0f),
            new Particle.DustOptions(Color.fromRGB(240, 90, 220), 0.8f),
            new Particle.DustOptions(Color.fromRGB(20, 0, 40), 1.2f)};

    private void voidParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height / 2.0, 0);
        for (Player p : audience(centre, 32)) {
            // particles of the surroundings are sucked into the vortex
            for (int i = 0, k = n(3); i < k; i++) p.spawnParticle(Particle.PORTAL, edge(random.nextDouble() * .3), 2, .2, .2, .2, .6);
            for (int i = 0, k = n(2); i < k; i++) p.spawnParticle(Particle.REVERSE_PORTAL, edge(.8 + random.nextDouble()), 1, .2, .2, .2, .02);
            for (int i = 0, k = n(3); i < k; i++) p.spawnParticle(Particle.DUST, edge(random.nextDouble() * .25), 1, .03, .03, .03, 0, VOID_DUST[random.nextInt(VOID_DUST.length)]);
            if (random.nextInt(6) == 0) p.spawnParticle(Particle.SQUID_INK, edge(.1), 0, 0, 0, 0, 0);
            if (p.getLocation().distanceSquared(centre) > 256) continue;
            if (random.nextInt(14) == 0) sound(p, centre, "ambient", "minecraft:block.portal.ambient", .25f, pitch(.5f, .8f));
            if (ticks % 25 == 0) sound(p, centre, "hum", "minecraft:block.beacon.ambient", .35f, .5f);
            if (random.nextInt(60) == 0) sound(p, centre, "accent", "minecraft:entity.enderman.teleport", .3f, pitch(.5f, .7f));
        }
    }

    private static final Particle.DustOptions[] FROST_DUST = {
            new Particle.DustOptions(Color.fromRGB(170, 230, 255), 0.9f),
            new Particle.DustOptions(Color.fromRGB(240, 250, 255), 0.7f)};

    private void frostParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height / 2.0, 0);
        for (Player p : audience(centre, 32)) {
            for (int i = 0, k = n(5); i < k; i++) p.spawnParticle(Particle.SNOWFLAKE, edge(random.nextDouble() * .4), 1, .1, .1, .1, .01);
            for (int i = 0, k = n(3); i < k; i++) p.spawnParticle(Particle.DUST, edge(random.nextDouble() * .2), 1, .03, .03, .03, 0, FROST_DUST[random.nextInt(FROST_DUST.length)]);
            if (random.nextInt(3) == 0) p.spawnParticle(Particle.END_ROD, edge(random.nextDouble() * .5), 0, 0, -1, 0, .01);
            if (random.nextInt(2) == 0) p.spawnParticle(Particle.SNOWFLAKE, at(random.nextDouble(-1, width + 1), height + 1.5, random.nextDouble(.2, 2.5)), 0, 0, -1, 0, .03);
            if (p.getLocation().distanceSquared(centre) > 256) continue;
            if (random.nextInt(8) == 0) sound(p, centre, "ambient", "minecraft:block.amethyst_block.chime", .4f, pitch(1.2f, 1.9f));
            if (random.nextInt(25) == 0) sound(p, centre, "accent", "minecraft:block.amethyst_cluster.hit", .25f, pitch(1.4f, 2f));
            if (random.nextInt(30) == 0) sound(p, centre, "crackle", "minecraft:block.powder_snow.step", .4f, pitch(.7f, 1f));
        }
    }

    private void gateParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height / 2.0, 0);
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(centre) > 400 + width * width * 4) continue;
            for (int i = 0, k = n(3); i < k; i++) {
                // sparks wandering along the edge of the opening
                double u = random.nextDouble() * (2 * width + 2 * height), px, py;
                if (u < width) { px = u; py = height; }
                else if (u < width + height) { px = width; py = height - (u - width); }
                else if (u < 2 * width + height) { px = width - (u - width - height); py = 0; }
                else { px = 0; py = u - 2 * width - height; }
                p.spawnParticle(reachable ? Particle.END_ROD : Particle.SMOKE, at(px, py, .05), 0, 0, .02, 0, .3);
            }
            if (!reachable) p.spawnParticle(Particle.PORTAL, at(width / 2.0, height / 2.0, .1), 12, width * .3, height * .3, width * .3, .2);
            if (ticks % 25 == 0 && p.getLocation().distanceSquared(centre) < 144) sound(p, centre, "hum", "minecraft:block.beacon.ambient", .25f, 1.2f);
        }
    }

    private void riftParticles() {
        var random = ThreadLocalRandom.current();
        Location centre = at(width / 2.0, height * .55 + .6, 0);
        for (Player p : world.getPlayers()) {
            double distance = p.getLocation().distanceSquared(centre);
            if (distance > 900) continue;
            for (int i = 0, k = n(6); i < k; i++)
                p.spawnParticle(Particle.DUST, riftEdge(random.nextDouble() * .2), 1, .03, .03, .03, 0, RIFT_DUST[random.nextInt(RIFT_DUST.length)]);
            for (int i = 0, k = n(2); i < k; i++) p.spawnParticle(Particle.ELECTRIC_SPARK, riftEdge(.05), 2, .08, .08, .08, .05);
            // particles from the surroundings are pulled into the rift
            if (random.nextInt(2) == 0) p.spawnParticle(Particle.REVERSE_PORTAL, riftEdge(1.0), 1, .2, .2, .2, .02);
            if (distance > 256) continue;
            if (ticks % 20 == 0) sound(p, centre, "hum", "minecraft:block.beacon.ambient", .35f, 1.5f);
            if (random.nextInt(45) == 0) sound(p, centre, "accent", "minecraft:block.amethyst_block.resonate", .25f, pitch(.6f, 1f));
            if (random.nextInt(70) == 0) sound(p, centre, "crackle", "minecraft:entity.lightning_bolt.impact", .06f, 1.8f);
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
