package magicportals.portal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Coarse terrain far in front of a portal, beyond the block snapshot: for every 2×2 block column the height
 * of the surface and its top block, and in worlds with a ceiling (nether, cave worlds) also the height of the
 * ceiling above and its bottom block. The shader draws it as a soft, slightly blurred landscape so the view
 * reaches far without fog.
 * <p>
 * Cell (c, r): sideways rx = (c - COLUMNS/2) * STEP (viewer's frame, like {@link Snapshot}), distance behind
 * the portal plane d = r * STEP. Heights are relative to the portal floor; {@link #NONE} = no data.
 */
public final class FarTerrain {
    public static final int STEP = 2, COLUMNS = 128, ROWS = 120, MAGIC = 0x4D504655;
    private static final int OLD_MAGIC = 0x4D504654;
    public static final short NONE = Short.MIN_VALUE;

    /** The world has a ceiling: the view gets a rock ceiling and nether haze instead of sky. */
    public boolean cave;
    public final short[] height = new short[COLUMNS * ROWS], block = new short[COLUMNS * ROWS];
    public final short[] ceiling = new short[COLUMNS * ROWS], ceilingBlock = new short[COLUMNS * ROWS];
    public final List<String> palette = new ArrayList<>();
    private final Map<String, Short> ids = new HashMap<>();

    public FarTerrain() { Arrays.fill(height, NONE); Arrays.fill(ceiling, NONE); }

    public static int index(int c, int r) { return r * COLUMNS + c; }

    private short id(String state) { return ids.computeIfAbsent(state, k -> { palette.add(k); return (short) (palette.size() - 1); }); }
    private static short clamp(int v) { return (short) Math.max(-2000, Math.min(2000, v)); }

    void set(int c, int r, int surface, String state) {
        int i = index(c, r);
        height[i] = clamp(surface);
        block[i] = id(state);
    }

    void setCeiling(int c, int r, int bottom, String state) {
        int i = index(c, r);
        ceiling[i] = clamp(bottom);
        ceilingBlock[i] = id(state);
    }

    /** Format: MAGIC, cave flag, palette size, palette (block states), then per cell height, block, ceiling, ceiling block. */
    public byte[] toBytes() {
        var buffer = new ByteArrayOutputStream(COLUMNS * ROWS * 8 + 4096);
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeBoolean(cave);
            out.writeInt(palette.size());
            for (String s : palette) { byte[] b = s.getBytes(StandardCharsets.UTF_8); out.writeShort(b.length); out.write(b); }
            for (int i = 0; i < height.length; i++) {
                out.writeShort(height[i]); out.writeShort(block[i]); out.writeShort(ceiling[i]); out.writeShort(ceilingBlock[i]);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return buffer.toByteArray();
    }

    public static FarTerrain read(byte[] bytes) throws IOException {
        var far = new FarTerrain();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != OLD_MAGIC) throw new IOException("Invalid far terrain");
            boolean withCeiling = magic == MAGIC;
            if (withCeiling) far.cave = in.readBoolean();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] raw = new byte[in.readUnsignedShort()];
                in.readFully(raw);
                far.palette.add(new String(raw, StandardCharsets.UTF_8));
            }
            for (int i = 0; i < far.height.length; i++) {
                far.height[i] = in.readShort(); far.block[i] = in.readShort();
                if (withCeiling) { far.ceiling[i] = in.readShort(); far.ceilingBlock[i] = in.readShort(); }
            }
        }
        return far;
    }
}
