package magicportals.bake;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import magicportals.portal.Snapshot;

/**
 * Bakes the portal scenes for the MagicPortals shader.
 * <p>
 * In game: {@code /magicportals update} on the server that builds the resource pack.<br>
 * By hand: {@code java -cp MagicPortals.jar magicportals.bake.SceneBaker <snapshot folder> <minecraft-client.jar> <output>}
 * <p>
 * Every snapshot {@code <server>@<target>.front} (+ {@code .back}) becomes one scene; numbers are assigned in
 * alphabetical order. Each block state is resolved through the client's blockstates/models into a shape
 * and textures. Output:
 * <pre>
 *   assets/minecraft/textures/effect/magicportals/{voxel,palette,atlas}.png
 *   assets/minecraft/shaders/include/magicportals_scene.glsl
 *   assets/minecraft/post_effect/entity_outline.json
 * </pre>
 */
public final class SceneBaker {
    public static final int MAX_SCENES = 16;
    // part of a snapshot used by the shader
    static final int RX0 = -47, W = 96, H = Snapshot.HEIGHT, D = Snapshot.DEPTH, DB = Snapshot.BACK_DEPTH, DT = D + DB, HB = (H + 7) / 8;
    static final int GX0 = RX0 + 1, GY0 = -Snapshot.BELOW;
    static final int ATLAS_COLUMNS = 32, ATLAS_SIZE = 512, PALETTE_WIDTH = 16;

    public static void main(String[] args) throws Exception {
        bake(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), System.out::println);
    }

    /** Bakes all snapshots in {@code input} into {@code output} and returns the scene numbers ("server@target" → number). */
    public static Map<String, Integer> bake(Path input, Path clientJar, Path output, Consumer<String> log) throws IOException {
        List<String> keys = new ArrayList<>();
        try (var files = Files.list(input)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".front") && n.indexOf('@') > 0) keys.add(n.substring(0, n.length() - 6));
            }
        }
        Collections.sort(keys);
        if (keys.isEmpty()) throw new IOException("No snapshots (<server>@<target>.front) in " + input);
        if (keys.size() > MAX_SCENES) throw new IOException("At most " + MAX_SCENES + " scenes are possible, found " + keys.size());
        try (var jar = new ZipFile(clientJar.toFile())) {
            var baker = new SceneBaker(jar, log);
            List<int[]> scenes = new ArrayList<>();
            for (String key : keys) scenes.add(baker.readScene(input.resolve(key + ".front")));
            List<double[]> views = new ArrayList<>();
            for (String key : keys) {
                Path view = input.resolve(key + ".view");
                views.add(viewParameters(Files.exists(view) ? Files.readString(view) : ""));
            }
            baker.write(scenes, views, output);
        }
        log.accept("Scenes: " + String.join(", ", keys));
        var numbers = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < keys.size(); i++) numbers.put(keys.get(i), i);
        return numbers;
    }

    // ---------------- block models ----------------

    private final ZipFile jar;
    private final Consumer<String> log;
    private final Map<String, Object> jsonCache = new HashMap<>();
    private final Map<String, Integer> palette = new LinkedHashMap<>();
    private final List<Shape> shapes = new ArrayList<>();
    private final Map<String, Integer> tiles = new LinkedHashMap<>();
    private final List<BufferedImage> tileImages = new ArrayList<>();

    private SceneBaker(ZipFile jar, Consumer<String> log) {
        this.jar = jar;
        this.log = log;
        palette.put("minecraft:air", 0);
        shapes.add(Shape.air());
    }

    /** How the shader draws one block state. */
    static final class Shape {
        int kind;                        // 0 air, 1 box, 2 cross (plants)
        boolean fluid, opaque;
        double[] min = {0, 0, 0}, max = {1, 1, 1};
        int[] tile = new int[6];         // faces: up, down, north, south, east, west
        int tint = 0xFFFFFF, tintMask;
        int emission;                    // light the block gives off (torches, lanterns, lava …)
        static Shape air() { return new Shape(); }
    }

    private Object json(String path) {
        return jsonCache.computeIfAbsent(path, p -> {
            var entry = jar.getEntry(p);
            if (entry == null) return null;
            try (var in = jar.getInputStream(entry)) { return MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static String stripNamespace(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    private int paletteId(String state) {
        Integer n = palette.get(state);
        if (n != null) return n;
        Shape shape;
        try { shape = resolve(state); }
        catch (RuntimeException e) { log.accept("  ! " + state + ": " + e.getMessage()); shape = Shape.air(); }
        if (shape.kind == 0) { palette.put(state, 0); return 0; }
        shape.emission = emission(state);
        palette.put(state, shapes.size());
        shapes.add(shape);
        return shapes.size() - 1;
    }

    @SuppressWarnings("unchecked")
    private Shape resolve(String state) {
        String name = state, props = "";
        int bracket = state.indexOf('[');
        if (bracket >= 0) { name = state.substring(0, bracket); props = state.substring(bracket + 1, state.length() - 1); }
        name = stripNamespace(name);
        Map<String, String> properties = new HashMap<>();
        if (!props.isEmpty()) for (String p : props.split(",")) { var kv = p.split("="); properties.put(kv[0], kv[1]); }
        if (name.endsWith("air") || name.equals("barrier") || name.equals("light")) return Shape.air();

        if (name.equals("water") || name.equals("bubble_column")) return fluid("block/water_still", 0x3F76E4);
        if (name.equals("lava")) return fluid("block/lava_still", -1);

        var blockstate = (Map<String, Object>) json("assets/minecraft/blockstates/" + name + ".json");
        if (blockstate == null) return Shape.air();
        List<Map<String, Object>> models = new ArrayList<>();
        if (blockstate.containsKey("variants")) {
            var variants = (Map<String, Object>) blockstate.get("variants");
            for (var e : variants.entrySet()) if (matches(e.getKey(), properties)) { models.add(first(e.getValue())); break; }
            if (models.isEmpty() && !variants.isEmpty()) models.add(first(variants.values().iterator().next()));
        } else if (blockstate.containsKey("multipart")) {
            for (Object part : (List<Object>) blockstate.get("multipart")) {
                var p = (Map<String, Object>) part;
                if (!p.containsKey("when") || condition(p.get("when"), properties)) models.add(first(p.get("apply")));
            }
        }
        Shape shape = new Shape();
        boolean found = false;
        double[] min = {1, 1, 1}, max = {0, 0, 0};
        Map<Integer, double[]> faceRank = new HashMap<>();
        String[] faceTexture = new String[6];
        boolean[] faceTint = new boolean[6];
        for (var variant : models) {
            String model = stripNamespace((String) variant.get("model"));
            int rx = ((Number) variant.getOrDefault("x", 0)).intValue(), ry = ((Number) variant.getOrDefault("y", 0)).intValue();
            var textures = new HashMap<String, String>();
            var elements = loadElements(model, textures);
            if (elements == null || elements.isEmpty()) continue;
            if (isCross(elements)) {
                var faces = (Map<String, Object>) elements.get(0).get("faces");
                var face = (Map<String, Object>) faces.values().iterator().next();
                shape.kind = 2;
                Arrays.fill(shape.tile, tile(texture((String) face.get("texture"), textures)));
                if (face.containsKey("tintindex")) { shape.tintMask = 1; shape.tint = tintFor(name); }
                return shape;
            }
            for (var element : elements) {
                double[] from = numbers(element.get("from")), to = numbers(element.get("to"));
                double[][] box = rotate(new double[][]{{from[0] / 16, from[1] / 16, from[2] / 16}, {to[0] / 16, to[1] / 16, to[2] / 16}}, rx, ry);
                for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], box[0][i]); max[i] = Math.max(max[i], box[1][i]); }
                var faces = (Map<String, Object>) element.get("faces");
                if (faces == null) continue;
                for (var e : faces.entrySet()) {
                    int f = rotateFace(faceIndex(e.getKey()), rx, ry);
                    double area = faceArea(box, f);
                    var previous = faceRank.get(f);
                    // the outermost and then largest face per direction wins
                    double outward = f == 0 ? box[1][1] : f == 1 ? -box[0][1] : f == 2 ? -box[0][2] : f == 3 ? box[1][2] : f == 4 ? box[1][0] : -box[0][0];
                    if (previous == null || outward > previous[0] + 1e-6 || (Math.abs(outward - previous[0]) < 1e-6 && area > previous[1])) {
                        faceRank.put(f, new double[]{outward, area});
                        var face = (Map<String, Object>) e.getValue();
                        faceTexture[f] = texture((String) face.get("texture"), textures);
                        faceTint[f] = face.containsKey("tintindex");
                    }
                }
                found = true;
            }
        }
        if (!found) return Shape.air();
        shape.kind = 1;
        shape.min = min;
        shape.max = max;
        String fallback = null;
        for (String t : faceTexture) if (t != null) { fallback = t; break; }
        for (int i = 0; i < 6; i++) {
            shape.tile[i] = tile(faceTexture[i] != null ? faceTexture[i] : fallback);
            if (faceTint[i]) shape.tintMask |= 1 << i;
        }
        if (shape.tintMask != 0) shape.tint = tintFor(name);
        boolean full = min[0] <= 0 && min[1] <= 0 && min[2] <= 0 && max[0] >= 1 && max[1] >= 1 && max[2] >= 1;
        shape.opaque = full && !name.contains("leaves") && !name.contains("glass") && !name.contains("ice")
                && !name.contains("slime") && !name.contains("honey");
        return shape;
    }

    private Shape fluid(String texture, int tint) {
        Shape shape = new Shape();
        shape.kind = 1;
        shape.fluid = true;
        shape.max = new double[]{1, 14 / 16.0, 1};
        Arrays.fill(shape.tile, tile(texture));
        if (tint >= 0) { shape.tint = tint; shape.tintMask = 63; }
        return shape;
    }

    /** Fixed biome tints (plains-like). */
    private static int tintFor(String name) {
        if (name.equals("spruce_leaves")) return 0x619961;
        if (name.equals("birch_leaves")) return 0x80A755;
        if (name.equals("lily_pad")) return 0x208030;
        if (name.contains("leaves") || name.equals("vine")) return 0x77AB2F;
        return 0x91BD59;   // grass, ferns, sugar cane
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Object o) { return o instanceof List<?> l ? (Map<String, Object>) l.get(0) : (Map<String, Object>) o; }

    private static boolean matches(String variantKey, Map<String, String> properties) {
        if (variantKey.isEmpty() || variantKey.equals("normal")) return true;
        for (String p : variantKey.split(",")) {
            var kv = p.split("=");
            if (!kv[1].equals(properties.get(kv[0]))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean condition(Object when, Map<String, String> properties) {
        var m = (Map<String, Object>) when;
        if (m.containsKey("OR")) { for (Object o : (List<Object>) m.get("OR")) if (condition(o, properties)) return true; return false; }
        if (m.containsKey("AND")) { for (Object o : (List<Object>) m.get("AND")) if (!condition(o, properties)) return false; return true; }
        for (var e : m.entrySet()) {
            boolean ok = false;
            for (String value : String.valueOf(e.getValue()).split("\\|")) if (value.equals(properties.get(e.getKey()))) ok = true;
            if (!ok) return false;
        }
        return true;
    }

    /** Follows the parent chain of a model; collects textures and returns the first element list found. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadElements(String model, Map<String, String> textures) {
        List<Map<String, Object>> elements = null;
        String current = model;
        for (int depth = 0; current != null && depth < 16; depth++) {
            var m = (Map<String, Object>) json("assets/minecraft/models/" + stripNamespace(current) + ".json");
            if (m == null) break;
            if (m.get("textures") instanceof Map<?, ?> t)
                for (var e : ((Map<String, Object>) t).entrySet()) {
                    Object value = e.getValue();
                    if (value instanceof Map<?, ?> obj) value = obj.get("sprite");   // 26.x: {"sprite": ..., "force_translucent": ...}
                    if (value != null) textures.putIfAbsent(e.getKey(), String.valueOf(value));
                }
            if (elements == null && m.get("elements") instanceof List<?> l) elements = (List<Map<String, Object>>) l;
            current = (String) m.get("parent");
        }
        return elements;
    }

    @SuppressWarnings("unchecked")
    private static boolean isCross(List<Map<String, Object>> elements) {
        for (var e : elements) {
            if (e.get("rotation") instanceof Map<?, ?> r && Math.abs(((Number) ((Map<String, Object>) r).get("angle")).doubleValue()) == 45) {
                double[] from = numbers(e.get("from")), to = numbers(e.get("to"));
                if (from[0] == to[0] || from[2] == to[2]) return true;
            }
        }
        return false;
    }

    private static String texture(String ref, Map<String, String> textures) {
        for (int i = 0; ref != null && ref.startsWith("#") && i < 10; i++) ref = textures.get(ref.substring(1));
        return ref == null ? "block/stone" : stripNamespace(ref);
    }

    @SuppressWarnings("unchecked")
    private static double[] numbers(Object o) {
        var l = (List<Object>) o;
        return new double[]{((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue()};
    }

    private static int faceIndex(String n) {
        return switch (n) { case "up" -> 0; case "down" -> 1; case "north" -> 2; case "south" -> 3; case "east" -> 4; default -> 5; };
    }

    /** Variant rotation: x first (up → south), then y (north → east), in steps of 90°. */
    private static int rotateFace(int f, int rx, int ry) {
        for (int i = 0; i < ((rx / 90) & 3); i++) f = switch (f) { case 0 -> 3; case 3 -> 1; case 1 -> 2; case 2 -> 0; default -> f; };
        for (int i = 0; i < ((ry / 90) & 3); i++) f = switch (f) { case 2 -> 4; case 4 -> 3; case 3 -> 5; case 5 -> 2; default -> f; };
        return f;
    }

    private static double[][] rotate(double[][] box, int rx, int ry) {
        double[] a = box[0].clone(), b = box[1].clone();
        for (int i = 0; i < ((rx / 90) & 3); i++) {   // (y,z) -> (1-z, y)
            double[] na = {a[0], 1 - b[2], a[1]}, nb = {b[0], 1 - a[2], b[1]};
            a = na; b = nb;
        }
        for (int i = 0; i < ((ry / 90) & 3); i++) {   // (x,z) -> (1-z, x)
            double[] na = {1 - b[2], a[1], a[0]}, nb = {1 - a[2], b[1], b[0]};
            a = na; b = nb;
        }
        return new double[][]{a, b};
    }

    private static double faceArea(double[][] box, int f) {
        double dx = box[1][0] - box[0][0], dy = box[1][1] - box[0][1], dz = box[1][2] - box[0][2];
        return f <= 1 ? dx * dz : f <= 3 ? dx * dy : dz * dy;
    }

    /** Index of a 16x16 texture tile in the atlas (first animation frame). */
    private int tile(String texture) {
        return tiles.computeIfAbsent(texture, t -> {
            BufferedImage tile = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var entry = jar.getEntry("assets/minecraft/textures/" + t + ".png");
            if (entry != null) {
                try (var in = jar.getInputStream(entry)) {
                    BufferedImage image = ImageIO.read(in);
                    int size = image.getWidth();
                    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) tile.setRGB(x, y, image.getRGB(x * size / 16, y * size / 16));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            } else {
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) tile.setRGB(x, y, 0xFFFF00FF);
                log.accept("  ! missing texture: " + t);
            }
            tileImages.add(tile);
            return tileImages.size() - 1;
        });
    }

    // ---------------- scenes ----------------

    /** Grid row k: 0..DB-1 lies in front of the portal plane (the area behind the destination portal, only
     *  visible from the in-between space), DB.. lies behind it (the normal view). Local z = DB - 1 - k. */
    private int[] readScene(Path front) throws IOException {
        int[] voxels = new int[W * H * DT];
        readSnapshot(front, D, voxels, d -> DB + d);
        Path back = front.resolveSibling(front.getFileName().toString().replace(".front", ".back"));
        if (Files.exists(back)) readSnapshot(back, DB, voxels, d -> DB - 1 - d);
        else log.accept("  ! no back snapshot: " + back);
        return voxels;
    }

    private void readSnapshot(Path file, int depth, int[] voxels, IntUnaryOperator row) throws IOException {
        log.accept("Reading " + file.getFileName());
        List<String> states = new ArrayList<>();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != Snapshot.MAGIC) throw new IOException("Unknown format: " + file);
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] s = new byte[in.readUnsignedShort()];
                in.readFully(s);
                states.add(new String(s, StandardCharsets.UTF_8));
            }
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) ids[i] = paletteId(states.get(i));
            for (int d = 0; d < depth; d++) for (int ry = 0; ry < Snapshot.HEIGHT; ry++) for (int rx = 0; rx < Snapshot.WIDTH; rx++) {
                int value = ids[in.readShort()];
                int i = rx - Snapshot.SIDE - RX0;
                if (i < 0 || i >= W) continue;
                voxels[(row.applyAsInt(d) * H + ry) * W + i] = value;
            }
        }
    }

    /** Light level a block state gives off. */
    private static int emission(String state) {
        try { return org.bukkit.Bukkit.createBlockData(state).getLightEmission(); }
        catch (Throwable e) {
            // without a server (tools): the most common light sources
            String s = stripNamespace(state);
            if (s.contains("lit=false")) return 0;
            if (s.startsWith("glowstone") || s.startsWith("sea_lantern") || s.startsWith("lava") || s.startsWith("jack_o_lantern")
                    || s.startsWith("shroomlight") || s.startsWith("beacon") || s.endsWith("froglight") || s.startsWith("lantern")
                    || s.startsWith("campfire") || s.startsWith("redstone_lamp")) return 15;
            if (s.startsWith("torch") || s.startsWith("wall_torch") || s.startsWith("end_rod")) return 14;
            if (s.startsWith("soul_")) return 10;
            return 0;
        }
    }

    /** Block light like Minecraft: the emission of light sources, -1 per block, blocked by opaque blocks. */
    private int[] blockLight(int[] voxels) {
        int[] light = new int[voxels.length];
        var queue = new ArrayDeque<Integer>();
        for (int idx = 0; idx < voxels.length; idx++) {
            int e = shapes.get(voxels[idx]).emission;
            if (e > 0) { light[idx] = e; queue.add(idx); }
        }
        int[][] neighbours = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int i = idx % W, j = (idx / W) % H, k = idx / (W * H);
            for (int[] n : neighbours) {
                int ni = i + n[0], nj = j + n[1], nk = k + n[2];
                if (ni < 0 || nj < 0 || nk < 0 || ni >= W || nj >= H || nk >= DT) continue;
                int nIdx = (nk * H + nj) * W + ni;
                if (shapes.get(voxels[nIdx]).opaque) continue;
                if (light[nIdx] < light[idx] - 1) { light[nIdx] = light[idx] - 1; queue.add(nIdx); }
            }
        }
        return light;
    }

    /**
     * View settings of the portal showing a scene ("time=night;fog=2;fog-color=#3050ff;tint=…;tint-strength=0.5;weather=snow")
     * as 12 numbers: time, fog, weather, tint strength | tint rgb, custom fog | fog rgb, 0.
     */
    static double[] viewParameters(String text) {
        var p = new HashMap<String, String>();
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) p.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
        }
        double time = switch (p.getOrDefault("time", "day")) { case "sunset", "dusk", "abend" -> 1; case "night", "nacht" -> 2; default -> 0; };
        double weather = switch (p.getOrDefault("weather", "none")) { case "rain", "regen" -> 1; case "snow", "schnee" -> 2; case "ash", "asche" -> 3; case "embers", "glut" -> 4; default -> 0; };
        double fog = number(p.get("fog"), 1, 0, 8);
        double[] tint = rgb(p.get("tint")), fogColor = rgb(p.get("fog-color"));
        double strength = tint == null ? 0 : number(p.get("tint-strength"), .5, 0, 1);
        if (tint == null) tint = new double[]{1, 1, 1};
        return new double[]{time, fog, weather, strength, tint[0], tint[1], tint[2], fogColor == null ? 0 : 1,
                fogColor == null ? 0 : fogColor[0], fogColor == null ? 0 : fogColor[1], fogColor == null ? 0 : fogColor[2], 0};
    }

    /** The view parameters of all scenes as GLSL vec4 list. */
    private static String viewArray(List<double[]> views) {
        var parts = new ArrayList<String>();
        for (double[] v : views)
            for (int i = 0; i < 12; i += 4)
                parts.add(String.format(Locale.ROOT, "vec4(%.4f, %.4f, %.4f, %.4f)", v[i], v[i + 1], v[i + 2], v[i + 3]));
        return String.join(", ", parts);
    }

    private static double number(String text, double fallback, double min, double max) {
        try { return text == null ? fallback : Math.max(min, Math.min(max, Double.parseDouble(text))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static double[] rgb(String text) {
        if (text == null || !text.matches("#?[0-9a-f]{6}")) return null;
        int c = Integer.parseInt(text.replace("#", ""), 16);
        return new double[]{(c >> 16 & 255) / 255.0, (c >> 8 & 255) / 255.0, (c & 255) / 255.0};
    }

    /** Sky light like Minecraft: 15 from above, 0 through opaque blocks, -1 per block sideways. */
    private int[] skyLight(int[] voxels) {
        int[] light = new int[voxels.length];
        var queue = new ArrayDeque<Integer>();
        for (int k = 0; k < DT; k++) for (int i = 0; i < W; i++) {
            int level = 15;
            for (int j = H - 1; j >= 0; j--) {
                int idx = (k * H + j) * W + i;
                Shape s = shapes.get(voxels[idx]);
                if (s.opaque) level = 0;
                else if (s.fluid || (s.kind == 1 && level > 0 && voxels[idx] != 0)) level = Math.max(0, level - 1);
                light[idx] = level;
                if (level > 1) queue.add(idx);
            }
        }
        int[][] neighbours = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int i = idx % W, j = (idx / W) % H, k = idx / (W * H);
            for (int[] n : neighbours) {
                int ni = i + n[0], nj = j + n[1], nk = k + n[2];
                if (ni < 0 || nj < 0 || nk < 0 || ni >= W || nj >= H || nk >= DT) continue;
                int nIdx = (nk * H + nj) * W + ni;
                if (shapes.get(voxels[nIdx]).opaque) continue;
                if (light[nIdx] < light[idx] - 1) { light[nIdx] = light[idx] - 1; queue.add(nIdx); }
            }
        }
        return light;
    }

    private void write(List<int[]> scenes, List<double[]> views, Path output) throws IOException {
        int count = scenes.size();
        // voxel texture: R,G = palette id, B = sky light (bits 0-3) + "water above" flag (bit 4) + block light / 2 (bits 5-7)
        BufferedImage voxelImage = new BufferedImage(W * 8, DT * HB * count, BufferedImage.TYPE_INT_ARGB);
        for (int s = 0; s < count; s++) {
            int[] voxels = scenes.get(s);
            int[] light = skyLight(voxels), blockLight = blockLight(voxels);
            for (int k = 0; k < DT; k++) for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
                int idx = (k * H + j) * W + i;
                int id = voxels[idx];
                boolean waterAbove = shapes.get(id).fluid && j + 1 < H && shapes.get(voxels[idx + W]).fluid;
                int b = (light[idx] & 15) | (waterAbove ? 16 : 0) | (Math.min(7, (blockLight[idx] + 1) / 2) << 5);
                int px = i + (j % 8) * W, py = k + (j / 8) * DT + s * DT * HB;
                voxelImage.setRGB(px, py, 0xFF000000 | ((id & 255) << 16) | (((id >> 8) & 255) << 8) | b);
            }
        }
        // palette texture: one row per shape
        int paletteHeight = Math.max(16, shapes.size());
        BufferedImage paletteImage = new BufferedImage(PALETTE_WIDTH, paletteHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < paletteHeight; y++) for (int x = 0; x < PALETTE_WIDTH; x++) paletteImage.setRGB(x, y, 0xFF000000);
        for (int e = 0; e < shapes.size(); e++) {
            Shape s = shapes.get(e);
            int flags = (s.fluid ? 1 : 0) | (s.opaque ? 2 : 0);
            paletteImage.setRGB(0, e, 0xFF000000 | (s.kind << 16) | (flags << 8));
            paletteImage.setRGB(1, e, 0xFF000000 | (sixteenths(s.min[0]) << 16) | (sixteenths(s.min[1]) << 8) | sixteenths(s.min[2]));
            paletteImage.setRGB(2, e, 0xFF000000 | (sixteenths(s.max[0]) << 16) | (sixteenths(s.max[1]) << 8) | sixteenths(s.max[2]));
            paletteImage.setRGB(3, e, 0xFF000000 | s.tint);
            paletteImage.setRGB(4, e, 0xFF000000 | (s.tintMask << 16));
            for (int i = 0; i < 6; i++) paletteImage.setRGB(5 + i, e, 0xFF000000 | (((s.tile[i] >> 8) & 255) << 16) | ((s.tile[i] & 255) << 8));
        }
        if (tileImages.size() > (ATLAS_SIZE / 16) * (ATLAS_SIZE / 16)) throw new IOException("Too many textures: " + tileImages.size());
        BufferedImage atlas = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        var g = atlas.getGraphics();
        for (int k = 0; k < tileImages.size(); k++) g.drawImage(tileImages.get(k), (k % ATLAS_COLUMNS) * 16, (k / ATLAS_COLUMNS) * 16, null);
        g.dispose();

        Path textures = output.resolve("assets/minecraft/textures/effect/magicportals");
        Files.createDirectories(textures);
        ImageIO.write(voxelImage, "png", textures.resolve("voxel.png").toFile());
        ImageIO.write(paletteImage, "png", textures.resolve("palette.png").toFile());
        ImageIO.write(atlas, "png", textures.resolve("atlas.png").toFile());

        Path include = output.resolve("assets/minecraft/shaders/include");
        Files.createDirectories(include);
        Files.writeString(include.resolve("magicportals_scene.glsl"), """
                #ifndef MAGICPORTALS_SCENE_GLSL
                #define MAGICPORTALS_SCENE_GLSL
                // Generated by MagicPortals (SceneBaker)
                #define DP_W %d
                #define DP_H %d
                #define DP_D %d
                #define DP_DB %d
                #define DP_DT %d
                #define DP_HB %d
                #define DP_RX0 %d
                #define DP_GY0 %d
                #define DP_SCENES %d
                #define DP_ATLAS_COLUMNS %d
                #define DP_HORIZON vec3(0.75, 0.85, 1.0)
                #define DP_ZENITH vec3(0.47, 0.65, 1.0)
                // per scene: (time, fog, weather, tint strength), (tint rgb, custom fog), (fog rgb, 0)
                const vec4 DP_VIEW[%d] = vec4[](%s);
                #endif
                """.formatted(W, H, D, DB, DT, HB, RX0, GY0, count, ATLAS_COLUMNS, count * 3, viewArray(views)));

        Path post = output.resolve("assets/minecraft/post_effect");
        Files.createDirectories(post);
        Files.writeString(post.resolve("entity_outline.json"), postEffect(voxelImage.getWidth(), voxelImage.getHeight(), paletteHeight));
        log.accept(String.format("Done: %d scenes, %d block shapes, %d textures", count, shapes.size(), tileImages.size()));
    }

    private static int sixteenths(double v) { return (int) Math.round(Math.max(0, Math.min(1, v)) * 16); }

    /** The vanilla entity_outline post effect with the MagicPortals passes in front of it. */
    private static String postEffect(int voxelWidth, int voxelHeight, int paletteHeight) {
        return """
                {
                    "targets": { "swap": {}, "mp_image": {}, "mp_clean": {} },
                    "passes": [
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/magicportals",
                            "inputs": [
                                { "sampler_name": "In", "target": "minecraft:main" },
                                { "sampler_name": "MainDepth", "target": "minecraft:main", "use_depth_buffer": true },
                                { "sampler_name": "Data", "target": "minecraft:entity_outline" },
                                { "sampler_name": "Voxel", "location": "minecraft:magicportals/voxel", "width": %d, "height": %d, "bilinear": false },
                                { "sampler_name": "Palette", "location": "minecraft:magicportals/palette", "width": %d, "height": %d, "bilinear": false },
                                { "sampler_name": "Atlas", "location": "minecraft:magicportals/atlas", "width": %d, "height": %d, "bilinear": false }
                            ],
                            "output": "mp_image"
                        },
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/blit",
                            "inputs": [ { "sampler_name": "In", "target": "mp_image" } ],
                            "uniforms": { "BlitConfig": [ { "name": "ColorModulate", "type": "vec4", "value": [ 1.0, 1.0, 1.0, 1.0 ] } ] },
                            "output": "minecraft:main"
                        },
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/magicportals_scrub",
                            "inputs": [ { "sampler_name": "In", "target": "minecraft:entity_outline" } ],
                            "output": "mp_clean"
                        },
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/entity_sobel",
                            "inputs": [ { "sampler_name": "In", "target": "mp_clean" } ],
                            "output": "swap"
                        },
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/entity_outline_box_blur",
                            "inputs": [ { "sampler_name": "In", "target": "swap", "bilinear": true } ],
                            "output": "minecraft:entity_outline",
                            "uniforms": { "BlurConfig": [ { "name": "BlurDir", "type": "vec2", "value": [ 1.0, 0.0 ] }, { "name": "Radius", "type": "float", "value": 2.0 } ] }
                        },
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/entity_outline_box_blur",
                            "inputs": [ { "sampler_name": "In", "target": "minecraft:entity_outline", "bilinear": true } ],
                            "output": "swap",
                            "uniforms": { "BlurConfig": [ { "name": "BlurDir", "type": "vec2", "value": [ 0.0, 1.0 ] }, { "name": "Radius", "type": "float", "value": 2.0 } ] }
                        },
                        {
                            "vertex_shader": "minecraft:core/screenquad",
                            "fragment_shader": "minecraft:post/blit",
                            "inputs": [ { "sampler_name": "In", "target": "swap" } ],
                            "uniforms": { "BlitConfig": [ { "name": "ColorModulate", "type": "vec4", "value": [ 1.0, 1.0, 1.0, 1.0 ] } ] },
                            "output": "minecraft:entity_outline"
                        }
                    ]
                }
                """.formatted(voxelWidth, voxelHeight, PALETTE_WIDTH, paletteHeight, ATLAS_SIZE, ATLAS_SIZE);
    }
}
