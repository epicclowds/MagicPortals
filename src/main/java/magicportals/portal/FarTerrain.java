package magicportals.portal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Coarse terrain far in front of a portal, beyond the block snapshot: for every 2×2 block column the height
 * of the surface and its top block. The shader draws it as a soft, slightly blurred landscape so the view
 * reaches far without fog.
 * <p>
 * Cell (c, r): sideways rx = (c - COLUMNS/2) * STEP (viewer's frame, like {@link Snapshot}), distance behind
 * the portal plane d = r * STEP. Heights are relative to the portal floor; {@link #NONE} = no data.
 */
public final class FarTerrain {
    public static final int STEP = 2, COLUMNS = 128, ROWS = 120, MAGIC = 0x4D504654;
    public static final short NONE = Short.MIN_VALUE;

    public final short[] height = new short[COLUMNS * ROWS];
    public final short[] block = new short[COLUMNS * ROWS];
    public final List<String> palette = new ArrayList<>();
    private final Map<String, Short> ids = new HashMap<>();

    public FarTerrain() { Arrays.fill(height, NONE); }

    public static int index(int c, int r) { return r * COLUMNS + c; }

    void set(int c, int r, int surface, String state) {
        int i = index(c, r);
        height[i] = (short) Math.max(-2000, Math.min(2000, surface));
        block[i] = ids.computeIfAbsent(state, k -> { palette.add(k); return (short) (palette.size() - 1); });
    }

    /** Format: MAGIC, palette size, palette (block states), then height and palette id per cell. */
    public byte[] toBytes() {
        var buffer = new ByteArrayOutputStream(COLUMNS * ROWS * 4 + 4096);
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(palette.size());
            for (String s : palette) { byte[] b = s.getBytes(StandardCharsets.UTF_8); out.writeShort(b.length); out.write(b); }
            for (int i = 0; i < height.length; i++) { out.writeShort(height[i]); out.writeShort(block[i]); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return buffer.toByteArray();
    }

    public static FarTerrain read(byte[] bytes) throws IOException {
        var far = new FarTerrain();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid far terrain");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] raw = new byte[in.readUnsignedShort()];
                in.readFully(raw);
                far.palette.add(new String(raw, StandardCharsets.UTF_8));
            }
            for (int i = 0; i < far.height.length; i++) { far.height[i] = in.readShort(); far.block[i] = in.readShort(); }
        }
        return far;
    }
}
