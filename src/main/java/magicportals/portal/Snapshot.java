package magicportals.portal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

/**
 * A box of real block states around a portal.
 * Coordinates are always counted from the viewer's point of view:
 * rx = sideways (east positive, -SIDE..SIDE), ry = height above the portal floor (-BELOW..ABOVE-1),
 * d  = distance behind the portal plane (0..DEPTH-1).
 */
public final class Snapshot {
    public static final int SIDE = 56, WIDTH = 2 * SIDE + 1, BELOW = 16, ABOVE = 40, HEIGHT = BELOW + ABOVE, DEPTH = 80;
    public static final int SIZE = WIDTH * HEIGHT * DEPTH;
    /** Depth behind the own portal, used for the all-round view when a camera flies inside. */
    public static final int BACK_DEPTH = 24;
    public static final int MAGIC = 0x44505432;
    static final BlockData AIR = Material.AIR.createBlockData();

    final List<BlockData> palette = new ArrayList<>();
    private final Map<BlockData, Short> ids = new HashMap<>();
    final short[] data;

    public Snapshot() { this(DEPTH); }
    public Snapshot(int depth) { data = new short[WIDTH * HEIGHT * depth]; id(AIR); }

    public static int index(int rx, int ry, int d) { return (d * HEIGHT + (ry + BELOW)) * WIDTH + (rx + SIDE); }
    BlockData block(int i) { return palette.get(data[i]); }

    private short id(BlockData block) {
        return ids.computeIfAbsent(block, k -> { palette.add(k); return (short) (palette.size() - 1); });
    }

    private static BlockData rotated(BlockData block) {
        if (block.getMaterial().isAir() || block.getMaterial() == Material.BARRIER) return AIR;
        block = block.clone();
        block.rotate(StructureRotation.CLOCKWISE_180);
        return block;
    }

    /** Front of the own portal, for the other side: rotated by 180° so the viewer over there looks north
     *  and sees what lies south of this portal. Row z is the portal wall, the view starts right in front of it. */
    void captureFront(World w, int x, int y, int z, int d) {
        for (int ry = -BELOW; ry < ABOVE; ry++) {
            int wy = y + ry;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            for (int rx = -SIDE; rx <= SIDE; rx++) data[index(rx, ry, d)] = id(rotated(w.getBlockData(x - rx, wy, z + 1 + d)));
        }
    }

    /** Back of the own portal, for the other side's in-between view, also rotated by 180°.
     *  d = 0 is the row of the portal wall; the wall itself consists of display entities and barriers
     *  and therefore does not show up, the ground of that row does. */
    void captureBack(World w, int x, int y, int z, int d) {
        for (int ry = -BELOW; ry < ABOVE; ry++) {
            int wy = y + ry;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            for (int rx = -SIDE; rx <= SIDE; rx++) data[index(rx, ry, d)] = id(rotated(w.getBlockData(x - rx, wy, z - d)));
        }
    }

    /** The real blocks behind this portal, unrotated – what players see without the portal; used to
     *  restore fallback blocks when a player looks away. */
    void captureBehind(World w, int x, int y, int z, int d) {
        for (int ry = -BELOW; ry < ABOVE; ry++) {
            int wy = y + ry;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            for (int rx = -SIDE; rx <= SIDE; rx++) {
                BlockData b = w.getBlockData(x + rx, wy, z - 1 - d);
                data[index(rx, ry, d)] = id(b.getMaterial().isAir() ? AIR : b);
            }
        }
    }

    /** Format: MAGIC, palette size, palette (block states as text), then one palette id per cell. */
    public byte[] toBytes() {
        var buffer = new ByteArrayOutputStream(data.length * 2 + 8192);
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(palette.size());
            for (BlockData b : palette) {
                byte[] s = b.getAsString().getBytes(StandardCharsets.UTF_8);
                out.writeShort(s.length);
                out.write(s);
            }
            for (short s : data) out.writeShort(s);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return buffer.toByteArray();
    }

    /** Reads a snapshot of normal depth (DEPTH). */
    static Snapshot read(byte[] bytes, Logger log) throws IOException {
        var s = new Snapshot();
        s.palette.clear();
        s.ids.clear();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid portal snapshot");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] raw = new byte[in.readUnsignedShort()];
                in.readFully(raw);
                String text = new String(raw, StandardCharsets.UTF_8);
                BlockData b;
                try { b = Bukkit.createBlockData(text); }
                catch (IllegalArgumentException e) { log.warning("Unknown block in snapshot: " + text); b = AIR; }
                s.palette.add(b);
            }
            for (int i = 0; i < s.data.length; i++) s.data[i] = in.readShort();
        }
        return s;
    }
}
