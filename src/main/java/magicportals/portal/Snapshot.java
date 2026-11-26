package magicportals.portal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

    /** Reads the block in a cell of the capturing portal's local grid (see {@link Portal}): i along the
     *  opening, j up from the portal floor, k towards the viewer side (k = 0 is the wall row). */
    interface CellReader { BlockData at(int i, int j, int k); }

    static BlockData rotated(BlockData block, StructureRotation rotation) {
        if (block.getMaterial().isAir() || block.getMaterial() == Material.BARRIER) return AIR;
        if (rotation == StructureRotation.NONE) return block;
        block = block.clone();
        block.rotate(rotation);
        return block;
    }

    /** Front of the own portal, for the other side. Mirrored around the opening and rotated into the
     *  viewer's frame, so the viewer over there looks through and sees what lies in front of this portal.
     *  The view starts right in front of the wall row. */
    void captureFront(CellReader r, int width, StructureRotation rotation, int d) {
        int a = (width - 1) / 2;
        for (int ry = -BELOW; ry < ABOVE; ry++)
            for (int rx = -SIDE; rx <= SIDE; rx++) data[index(rx, ry, d)] = id(rotated(r.at(width - 1 - a - rx, ry, 1 + d), rotation));
    }

    /** Back of the own portal, for the other side's in-between view, mirrored and rotated the same way.
     *  d = 0 is the wall row; the wall itself consists of display entities and barriers and therefore
     *  does not show up, the ground of that row does. */
    void captureBack(CellReader r, int width, StructureRotation rotation, int d) {
        int a = (width - 1) / 2;
        for (int ry = -BELOW; ry < ABOVE; ry++)
            for (int rx = -SIDE; rx <= SIDE; rx++) data[index(rx, ry, d)] = id(rotated(r.at(width - 1 - a - rx, ry, -d), rotation));
    }

    /** The real blocks behind this portal in world orientation – what players see without the portal;
     *  used to restore fallback blocks when a player looks away. */
    void captureBehind(CellReader r, int width, int d) {
        int a = (width - 1) / 2;
        for (int ry = -BELOW; ry < ABOVE; ry++)
            for (int rx = -SIDE; rx <= SIDE; rx++) {
                BlockData b = r.at(a + rx, ry, -1 - d);
                data[index(rx, ry, d)] = id(b.getMaterial().isAir() ? AIR : b);
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
    /** Rotates every block state of the palette (e.g. from the viewer's frame back into world orientation). */
    void rotatePalette(StructureRotation rotation) {
        if (rotation == StructureRotation.NONE) return;
        palette.replaceAll(b -> b.getMaterial().isAir() ? b : rotated(b, rotation));
    }

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
