package magicportals;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/* Bakes the portal scenes for the MagicPortals shader (scene baker).
 *
 * In game: /magicportals update (on the server that builds the resource pack).
 * By hand:  java -cp MagicPortals.jar magicportals.PortalBacken <snapshot folder> <minecraft-client.jar> <output>
 *
 * Every snapshot "<server>@<target>.blick" (+ ".rueck") becomes one scene; numbers are assigned in
 * alphabetical order and written to szenen.txt. Each block state is resolved through the client's
 * blockstates/models into a shape and textures. Output:
 *   assets/minecraft/textures/effect/magicportals/{voxel,palette,atlas}.png
 *   assets/minecraft/shaders/include/magicportals_scene.glsl
 *   assets/minecraft/post_effect/entity_outline.json */
public final class PortalBacken {
    static final int MAX_SZENEN = 16;
    // Aufbau der .blick-Dateien (siehe Ausschnitt.java)
    static final int SEITE = 56, BREITE = 2 * SEITE + 1, UNTEN = 16, OBEN = 40, HOEHE = UNTEN + OBEN, TIEFE = 80;
    static final int MAGIC = 0x44505432;
    // Ausschnitt für den Shader
    static final int RX0 = -47, W = 96, H = HOEHE, D = TIEFE, DF = 24, DT = D + DF, HB = (H + 7) / 8;
    static final int GX0 = RX0 + 1, GY0 = -UNTEN;
    static final int ATLAS_SPALTEN = 32, ATLAS_GROESSE = 512, PAL_BREITE = 16;

    public static void main(String[] a) throws Exception {
        backen(Path.of(a[0]), Path.of(a[1]), Path.of(a[2]), System.out::println);
    }

    /* Liefert die vergebenen Szenennummern (Schlüssel "<server>@<ziel>" -> Nummer). */
    static Map<String, Integer> backen(Path daten, Path clientJar, Path ziel, Consumer<String> log) throws IOException {
        List<String> schluessel = new ArrayList<>();
        try (var dateien = Files.list(daten)) {
            for (Path p : dateien.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".blick") && n.indexOf('@') > 0) schluessel.add(n.substring(0, n.length() - 6));
            }
        }
        Collections.sort(schluessel);
        if (schluessel.isEmpty()) throw new IOException("Keine Aufnahmen (<server>@<ziel>.blick) in " + daten);
        if (schluessel.size() > MAX_SZENEN) throw new IOException("Höchstens " + MAX_SZENEN + " Szenen möglich, gefunden: " + schluessel.size());
        try (var jar = new ZipFile(clientJar.toFile())) {
            var b = new PortalBacken(jar, log);
            List<int[]> szenen = new ArrayList<>();
            for (String s : schluessel) szenen.add(b.szeneLesen(daten.resolve(s + ".blick")));
            b.schreiben(szenen, ziel);
        }
        var zeilen = new StringBuilder();
        for (int i = 0; i < schluessel.size(); i++) zeilen.append(schluessel.get(i)).append('=').append(i).append('\n');
        Path tmp = daten.resolve("szenen.txt.part");
        Files.writeString(tmp, zeilen, StandardCharsets.UTF_8);
        Files.move(tmp, daten.resolve("szenen.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.accept("Szenen: " + String.join(", ", schluessel));
        var nummern = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < schluessel.size(); i++) nummern.put(schluessel.get(i), i);
        return nummern;
    }

    // ---------------- Modelle ----------------

    final ZipFile jar;
    final Map<String, Object> jsonCache = new HashMap<>();
    final Map<String, Integer> palette = new LinkedHashMap<>();
    final List<Form> formen = new ArrayList<>();
    final Map<String, Integer> kacheln = new LinkedHashMap<>();
    final List<BufferedImage> kachelBilder = new ArrayList<>();

    final Consumer<String> log;

    PortalBacken(ZipFile jar, Consumer<String> log) {
        this.log = log;
        this.jar = jar;
        palette.put("minecraft:air", 0);
        formen.add(Form.luft());
    }

    static final class Form {
        int art;               // 0 Luft, 1 Quader, 2 Kreuz
        boolean fluessig, undurchsichtig;
        double[] lo = {0, 0, 0}, hi = {1, 1, 1};
        int[] kachel = new int[6];
        int tint = 0xFFFFFF, tintMaske;
        static Form luft() { return new Form(); }
    }

    Object json(String pfad) {
        return jsonCache.computeIfAbsent(pfad, p -> {
            var e = jar.getEntry(p);
            if (e == null) return null;
            try (var in = jar.getInputStream(e)) { return new Json(new String(in.readAllBytes(), StandardCharsets.UTF_8)).wert(); }
            catch (IOException ex) { throw new UncheckedIOException(ex); }
        });
    }

    static String ohneNs(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    int eintrag(String zustand) {
        Integer n = palette.get(zustand);
        if (n != null) return n;
        Form f;
        try { f = formAufloesen(zustand); }
        catch (RuntimeException ex) { log.accept("  ! " + zustand + ": " + ex.getMessage()); f = Form.luft(); }
        if (f.art == 0) { palette.put(zustand, 0); return 0; }
        palette.put(zustand, formen.size());
        formen.add(f);
        return formen.size() - 1;
    }

    @SuppressWarnings("unchecked")
    Form formAufloesen(String zustand) {
        String name = zustand, props = "";
        int k = zustand.indexOf('[');
        if (k >= 0) { name = zustand.substring(0, k); props = zustand.substring(k + 1, zustand.length() - 1); }
        name = ohneNs(name);
        Map<String, String> eigen = new HashMap<>();
        if (!props.isEmpty()) for (String p : props.split(",")) { var kv = p.split("="); eigen.put(kv[0], kv[1]); }
        if (name.endsWith("air")) return Form.luft();

        if (name.equals("water") || name.equals("bubble_column")) return fluessig("block/water_still", 0x3F76E4);
        if (name.equals("lava")) return fluessig("block/lava_still", -1);

        var bs = (Map<String, Object>) json("assets/minecraft/blockstates/" + name + ".json");
        if (bs == null) return Form.luft();
        List<Map<String, Object>> modelle = new ArrayList<>();
        if (bs.containsKey("variants")) {
            var varianten = (Map<String, Object>) bs.get("variants");
            for (var e : varianten.entrySet()) {
                if (passt(e.getKey(), eigen)) { modelle.add(erstes(e.getValue())); break; }
            }
            if (modelle.isEmpty() && !varianten.isEmpty()) modelle.add(erstes(varianten.values().iterator().next()));
        } else if (bs.containsKey("multipart")) {
            for (Object teil : (List<Object>) bs.get("multipart")) {
                var t = (Map<String, Object>) teil;
                if (!t.containsKey("when") || bedingung(t.get("when"), eigen)) modelle.add(erstes(t.get("apply")));
            }
        }
        Form f = new Form();
        boolean gefunden = false;
        double[] lo = {1, 1, 1}, hi = {0, 0, 0};
        Map<Integer, double[]> flaecheGroesse = new HashMap<>();
        String[] flaecheTextur = new String[6];
        boolean[] flaecheTint = new boolean[6];
        for (var v : modelle) {
            String modell = ohneNs((String) v.get("model"));
            int rx = ((Number) v.getOrDefault("x", 0)).intValue(), ry = ((Number) v.getOrDefault("y", 0)).intValue();
            var texturen = new HashMap<String, String>();
            var elemente = elementeLaden(modell, texturen);
            if (elemente == null || elemente.isEmpty()) continue;
            if (istKreuz(elemente)) {
                var flaechen = (Map<String, Object>) elemente.get(0).get("faces");
                var fl = (Map<String, Object>) flaechen.values().iterator().next();
                f.art = 2;
                int kachel = kachel(textur((String) fl.get("texture"), texturen));
                Arrays.fill(f.kachel, kachel);
                if (fl.containsKey("tintindex")) { f.tintMaske = 1; f.tint = tintFuer(name); }
                return f;
            }
            for (var el : elemente) {
                double[] von = zahlen(el.get("from")), bis = zahlen(el.get("to"));
                double[][] box = drehen(new double[][]{{von[0] / 16, von[1] / 16, von[2] / 16}, {bis[0] / 16, bis[1] / 16, bis[2] / 16}}, rx, ry);
                for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], box[0][i]); hi[i] = Math.max(hi[i], box[1][i]); }
                var flaechen = (Map<String, Object>) el.get("faces");
                if (flaechen == null) continue;
                for (var fe : flaechen.entrySet()) {
                    int idx = flaecheDrehen(flaecheIndex(fe.getKey()), rx, ry);
                    double groesse = flaecheFlaeche(box, idx);
                    var alt = flaecheGroesse.get(idx);
                    // äußerste und größte Fläche je Richtung gewinnt
                    double aussen = idx == 0 ? box[1][1] : idx == 1 ? -box[0][1] : idx == 2 ? -box[0][2] : idx == 3 ? box[1][2] : idx == 4 ? box[1][0] : -box[0][0];
                    if (alt == null || aussen > alt[0] + 1e-6 || (Math.abs(aussen - alt[0]) < 1e-6 && groesse > alt[1])) {
                        flaecheGroesse.put(idx, new double[]{aussen, groesse});
                        var fl = (Map<String, Object>) fe.getValue();
                        flaecheTextur[idx] = textur((String) fl.get("texture"), texturen);
                        flaecheTint[idx] = fl.containsKey("tintindex");
                    }
                }
                gefunden = true;
            }
        }
        if (!gefunden) return Form.luft();
        f.art = 1;
        f.lo = lo; f.hi = hi;
        String ersatz = null;
        for (String t : flaecheTextur) if (t != null) { ersatz = t; break; }
        for (int i = 0; i < 6; i++) {
            f.kachel[i] = kachel(flaecheTextur[i] != null ? flaecheTextur[i] : ersatz);
            if (flaecheTint[i]) f.tintMaske |= 1 << i;
        }
        if (f.tintMaske != 0) f.tint = tintFuer(name);
        boolean voll = lo[0] <= 0 && lo[1] <= 0 && lo[2] <= 0 && hi[0] >= 1 && hi[1] >= 1 && hi[2] >= 1;
        f.undurchsichtig = voll && !name.contains("leaves") && !name.contains("glass") && !name.contains("ice")
                && !name.equals("barrier") && !name.contains("slime") && !name.contains("honey");
        if (name.equals("barrier")) return Form.luft();
        return f;
    }

    Form fluessig(String textur, int tint) {
        Form f = new Form();
        f.art = 1; f.fluessig = true;
        f.hi = new double[]{1, 14 / 16.0, 1};
        Arrays.fill(f.kachel, kachel(textur));
        if (tint >= 0) { f.tint = tint; f.tintMaske = 63; }
        return f;
    }

    static int tintFuer(String name) {
        if (name.equals("spruce_leaves")) return 0x619961;
        if (name.equals("birch_leaves")) return 0x80A755;
        if (name.equals("lily_pad")) return 0x208030;
        if (name.contains("leaves") || name.equals("vine")) return 0x77AB2F;
        return 0x91BD59; // Gras, Farne, Zuckerrohr
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> erstes(Object o) { return o instanceof List<?> l ? (Map<String, Object>) l.get(0) : (Map<String, Object>) o; }

    static boolean passt(String schluessel, Map<String, String> eigen) {
        if (schluessel.isEmpty() || schluessel.equals("normal")) return true;
        for (String p : schluessel.split(",")) {
            var kv = p.split("=");
            if (!kv[1].equals(eigen.get(kv[0]))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static boolean bedingung(Object w, Map<String, String> eigen) {
        var m = (Map<String, Object>) w;
        if (m.containsKey("OR")) { for (Object o : (List<Object>) m.get("OR")) if (bedingung(o, eigen)) return true; return false; }
        if (m.containsKey("AND")) { for (Object o : (List<Object>) m.get("AND")) if (!bedingung(o, eigen)) return false; return true; }
        for (var e : m.entrySet()) {
            boolean ok = false;
            for (String wert : String.valueOf(e.getValue()).split("\\|")) if (wert.equals(eigen.get(e.getKey()))) ok = true;
            if (!ok) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> elementeLaden(String modell, Map<String, String> texturen) {
        List<Map<String, Object>> elemente = null;
        String aktuell = modell;
        for (int tiefe = 0; aktuell != null && tiefe < 16; tiefe++) {
            var m = (Map<String, Object>) json("assets/minecraft/models/" + ohneNs(aktuell) + ".json");
            if (m == null) break;
            if (m.get("textures") instanceof Map<?, ?> t)
                for (var e : ((Map<String, Object>) t).entrySet()) {
                    Object wert = e.getValue();
                    if (wert instanceof Map<?, ?> obj) wert = obj.get("sprite");   // 26.x: {"sprite": ..., "force_translucent": ...}
                    if (wert != null) texturen.putIfAbsent(e.getKey(), String.valueOf(wert));
                }
            if (elemente == null && m.get("elements") instanceof List<?> l) elemente = (List<Map<String, Object>>) l;
            aktuell = (String) m.get("parent");
        }
        return elemente;
    }

    @SuppressWarnings("unchecked")
    static boolean istKreuz(List<Map<String, Object>> elemente) {
        for (var el : elemente) {
            if (el.get("rotation") instanceof Map<?, ?> r && Math.abs(((Number) ((Map<String, Object>) r).get("angle")).doubleValue()) == 45) {
                double[] von = zahlen(el.get("from")), bis = zahlen(el.get("to"));
                if (von[0] == bis[0] || von[2] == bis[2]) return true;
            }
        }
        return false;
    }

    static String textur(String ref, Map<String, String> texturen) {
        for (int i = 0; ref != null && ref.startsWith("#") && i < 10; i++) ref = texturen.get(ref.substring(1));
        if (ref == null) return "block/stone";
        return ohneNs(ref);
    }

    @SuppressWarnings("unchecked")
    static double[] zahlen(Object o) {
        var l = (List<Object>) o;
        return new double[]{((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue()};
    }

    static int flaecheIndex(String n) {
        return switch (n) { case "up" -> 0; case "down" -> 1; case "north" -> 2; case "south" -> 3; case "east" -> 4; default -> 5; };
    }

    // Variantendrehung: x zuerst (oben -> Süden), dann y (Norden -> Osten), jeweils in 90°-Schritten.
    static int flaecheDrehen(int f, int rx, int ry) {
        for (int i = 0; i < ((rx / 90) & 3); i++) f = switch (f) { case 0 -> 3; case 3 -> 1; case 1 -> 2; case 2 -> 0; default -> f; };
        for (int i = 0; i < ((ry / 90) & 3); i++) f = switch (f) { case 2 -> 4; case 4 -> 3; case 3 -> 5; case 5 -> 2; default -> f; };
        return f;
    }

    static double[][] drehen(double[][] box, int rx, int ry) {
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

    static double flaecheFlaeche(double[][] box, int f) {
        double dx = box[1][0] - box[0][0], dy = box[1][1] - box[0][1], dz = box[1][2] - box[0][2];
        return f <= 1 ? dx * dz : f <= 3 ? dx * dy : dz * dy;
    }

    int kachel(String textur) {
        return kacheln.computeIfAbsent(textur, t -> {
            BufferedImage kachel = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var e = jar.getEntry("assets/minecraft/textures/" + t + ".png");
            if (e != null) {
                try (var in = jar.getInputStream(e)) {
                    BufferedImage bild = ImageIO.read(in);
                    int g = bild.getWidth();
                    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                        kachel.setRGB(x, y, bild.getRGB(x * g / 16, y * g / 16));
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            } else {
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) kachel.setRGB(x, y, 0xFFFF00FF);
                log.accept("  ! Textur fehlt: " + t);
            }
            kachelBilder.add(kachel);
            return kachelBilder.size() - 1;
        });
    }

    // ---------------- Szenen ----------------

    /* Rasterreihe kk: 0..DF-1 liegt vor der Torebene (Rückseite des Zieltors, nur im Zwischenraum
     * sichtbar), DF.. dahinter (der normale Blick durchs Tor). Lokal z = DF - 1 - kk. */
    int[] szeneLesen(Path blick) throws IOException {
        int[] voxel = new int[W * H * DT];
        ausschnittLesen(blick, TIEFE, voxel, b -> DF + b);
        Path rueck = blick.resolveSibling(blick.getFileName().toString().replace(".blick", ".rueck"));
        if (Files.exists(rueck)) ausschnittLesen(rueck, DF, voxel, c -> DF - 1 - c);
        else log.accept("  ! keine Rückseite: " + rueck);
        return voxel;
    }

    void ausschnittLesen(Path datei, int tiefe, int[] voxel, java.util.function.IntUnaryOperator reihe) throws IOException {
        log.accept("Lese " + datei);
        List<String> zustaende = new ArrayList<>();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(datei), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("Unbekanntes Format: " + datei);
            int n = in.readInt();
            for (int i = 0; i < n; i++) { byte[] s = new byte[in.readUnsignedShort()]; in.readFully(s); zustaende.add(new String(s, StandardCharsets.UTF_8)); }
            int[] eintraege = new int[n];
            for (int i = 0; i < n; i++) eintraege[i] = eintrag(zustaende.get(i));
            for (int b = 0; b < tiefe; b++) for (int ry = 0; ry < HOEHE; ry++) for (int rx = 0; rx < BREITE; rx++) {
                int wert = eintraege[in.readShort()];
                int i = rx - SEITE - RX0;
                if (i < 0 || i >= W) continue;
                voxel[(reihe.applyAsInt(b) * H + ry) * W + i] = wert;
            }
        }
    }

    /* Himmelslicht wie in Minecraft: von oben 15, durch Undurchsichtiges 0, seitlich -1 pro Block. */
    int[] lichtBerechnen(int[] voxel) {
        int[] licht = new int[voxel.length];
        var schlange = new ArrayDeque<Integer>();
        for (int k = 0; k < DT; k++) for (int i = 0; i < W; i++) {
            int stufe = 15;
            for (int j = H - 1; j >= 0; j--) {
                int idx = (k * H + j) * W + i;
                Form f = formen.get(voxel[idx]);
                if (f.undurchsichtig) stufe = 0;
                else if (f.fluessig || (f.art == 1 && stufe > 0 && voxel[idx] != 0)) stufe = Math.max(0, stufe - 1);
                licht[idx] = stufe;
                if (stufe > 1) schlange.add(idx);
            }
        }
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!schlange.isEmpty()) {
            int idx = schlange.poll();
            int i = idx % W, j = (idx / W) % H, k = idx / (W * H);
            for (int[] n : nb) {
                int ni = i + n[0], nj = j + n[1], nk = k + n[2];
                if (ni < 0 || nj < 0 || nk < 0 || ni >= W || nj >= H || nk >= DT) continue;
                int nidx = (nk * H + nj) * W + ni;
                if (formen.get(voxel[nidx]).undurchsichtig) continue;
                if (licht[nidx] < licht[idx] - 1) { licht[nidx] = licht[idx] - 1; schlange.add(nidx); }
            }
        }
        return licht;
    }

    void schreiben(List<int[]> szenen, Path ziel) throws IOException {
        int szenenAnzahl = szenen.size();
        BufferedImage voxelBild = new BufferedImage(W * 8, DT * HB * szenenAnzahl, BufferedImage.TYPE_INT_ARGB);
        for (int s = 0; s < szenenAnzahl; s++) {
            int[] voxel = szenen.get(s);
            int[] licht = lichtBerechnen(voxel);
            for (int k = 0; k < DT; k++) for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
                int idx = (k * H + j) * W + i;
                int e = voxel[idx];
                Form f = formen.get(e);
                boolean wasserOben = f.fluessig && j + 1 < H && formen.get(voxel[idx + W]).fluessig;
                int b = (licht[idx] & 15) | (wasserOben ? 16 : 0);
                int px = i + (j % 8) * W, py = k + (j / 8) * DT + s * DT * HB;
                voxelBild.setRGB(px, py, 0xFF000000 | ((e & 255) << 16) | (((e >> 8) & 255) << 8) | b);
            }
        }
        int palHoehe = Math.max(16, formen.size());
        BufferedImage palBild = new BufferedImage(PAL_BREITE, palHoehe, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < palHoehe; y++) for (int x = 0; x < PAL_BREITE; x++) palBild.setRGB(x, y, 0xFF000000);
        for (int e = 0; e < formen.size(); e++) {
            Form f = formen.get(e);
            int merkmale = (f.fluessig ? 1 : 0) | (f.undurchsichtig ? 2 : 0);
            palBild.setRGB(0, e, 0xFF000000 | (f.art << 16) | (merkmale << 8));
            palBild.setRGB(1, e, 0xFF000000 | (sechzehntel(f.lo[0]) << 16) | (sechzehntel(f.lo[1]) << 8) | sechzehntel(f.lo[2]));
            palBild.setRGB(2, e, 0xFF000000 | (sechzehntel(f.hi[0]) << 16) | (sechzehntel(f.hi[1]) << 8) | sechzehntel(f.hi[2]));
            palBild.setRGB(3, e, 0xFF000000 | f.tint);
            palBild.setRGB(4, e, 0xFF000000 | (f.tintMaske << 16));
            for (int i = 0; i < 6; i++) palBild.setRGB(5 + i, e, 0xFF000000 | (((f.kachel[i] >> 8) & 255) << 16) | ((f.kachel[i] & 255) << 8));
        }
        if (kachelBilder.size() > (ATLAS_GROESSE / 16) * (ATLAS_GROESSE / 16)) throw new IOException("Zu viele Texturen: " + kachelBilder.size());
        BufferedImage atlas = new BufferedImage(ATLAS_GROESSE, ATLAS_GROESSE, BufferedImage.TYPE_INT_ARGB);
        for (int k = 0; k < kachelBilder.size(); k++)
            atlas.getGraphics().drawImage(kachelBilder.get(k), (k % ATLAS_SPALTEN) * 16, (k / ATLAS_SPALTEN) * 16, null);

        Path tex = ziel.resolve("assets/minecraft/textures/effect/magicportals");
        Files.createDirectories(tex);
        ImageIO.write(voxelBild, "png", tex.resolve("voxel.png").toFile());
        ImageIO.write(palBild, "png", tex.resolve("palette.png").toFile());
        ImageIO.write(atlas, "png", tex.resolve("atlas.png").toFile());

        Path inc = ziel.resolve("assets/minecraft/shaders/include");
        Files.createDirectories(inc);
        Files.writeString(inc.resolve("magicportals_scene.glsl"), """
                #ifndef MAGICPORTALS_SCENE_GLSL
                #define MAGICPORTALS_SCENE_GLSL
                // Automatisch erzeugt von PortalBacken.java
                #define DP_W %d
                #define DP_H %d
                #define DP_D %d
                #define DP_DF %d
                #define DP_DT %d
                #define DP_HB %d
                #define DP_GX0 %d
                #define DP_GY0 %d
                #define DP_SZENEN %d
                #define DP_ATLAS_SPALTEN %d
                #define DP_HORIZONT vec3(0.75, 0.85, 1.0)
                #define DP_ZENIT vec3(0.47, 0.65, 1.0)
                #endif
                """.formatted(W, H, D, DF, DT, HB, GX0, GY0, szenenAnzahl, ATLAS_SPALTEN));

        Path post = ziel.resolve("assets/minecraft/post_effect");
        Files.createDirectories(post);
        Files.writeString(post.resolve("entity_outline.json"), postEffekt(voxelBild.getWidth(), voxelBild.getHeight(), palHoehe));
        log.accept(String.format("Fertig: %d Szenen, %d Blockformen, %d Texturen", szenenAnzahl, formen.size(), kachelBilder.size()));
    }

    static int sechzehntel(double v) { return (int) Math.round(Math.max(0, Math.min(1, v)) * 16); }

    static String postEffekt(int vw, int vh, int ph) {
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
                """.formatted(vw, vh, PAL_BREITE, ph, ATLAS_GROESSE, ATLAS_GROESSE);
    }

    // ---------------- kleiner JSON-Leser ----------------

    static final class Json {
        final String s; int p;
        Json(String s) { this.s = s; }
        Object wert() {
            leer();
            char c = s.charAt(p);
            if (c == '{') { p++; var m = new LinkedHashMap<String, Object>(); leer();
                if (s.charAt(p) == '}') { p++; return m; }
                while (true) { leer(); String k = text(); leer(); p++; m.put(k, wert()); leer(); if (s.charAt(p++) == '}') return m; } }
            if (c == '[') { p++; var l = new ArrayList<Object>(); leer();
                if (s.charAt(p) == ']') { p++; return l; }
                while (true) { l.add(wert()); leer(); if (s.charAt(p++) == ']') return l; } }
            if (c == '"') return text();
            if (s.startsWith("true", p)) { p += 4; return true; }
            if (s.startsWith("false", p)) { p += 5; return false; }
            if (s.startsWith("null", p)) { p += 4; return null; }
            int a = p; while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            return Double.parseDouble(s.substring(a, p));
        }
        String text() {
            p++; var b = new StringBuilder();
            while (s.charAt(p) != '"') { char c = s.charAt(p++); if (c == '\\') { char e = s.charAt(p++); b.append(e == 'n' ? '\n' : e == 'u' ? (char) Integer.parseInt(s.substring(p, p += 4), 16) : e); } else b.append(c); }
            p++; return b.toString();
        }
        void leer() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
    }
}
