package magicportals;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

/* Ein Quader aus echten Blockzuständen im Blickraum eines Tores.
 * Koordinaten sind immer aus Sicht des Betrachters gezählt:
 * rx = seitlich (Osten positiv, -SEITE..SEITE), ry = Höhe ab Torboden (-UNTEN..OBEN-1),
 * b  = Abstand hinter der Torebene (0..TIEFE-1). */
final class Ausschnitt {
    static final int SEITE = 56, BREITE = 2 * SEITE + 1, UNTEN = 16, OBEN = 40, HOEHE = UNTEN + OBEN, TIEFE = 80;
    static final int GROESSE = BREITE * HOEHE * TIEFE;
    static final int RUECK = 24;   // Tiefe hinter dem eigenen Tor, für den Rundumblick im Zwischenraum
    private static final int MAGIC = 0x44505432;
    static final BlockData LUFT = Material.AIR.createBlockData();

    final List<BlockData> palette = new ArrayList<>();
    private final Map<BlockData, Short> nummern = new HashMap<>();
    final short[] daten;

    Ausschnitt() { this(TIEFE); }
    Ausschnitt(int tiefe) { daten = new short[BREITE * HOEHE * tiefe]; nummer(LUFT); }

    /* Rückseite des eigenen Tores für die Gegenseite, ebenfalls um 180° gedreht.
     * c = 0 ist die Reihe der Torwand. Die Wand selbst besteht aus Anzeige-Entities und Barrieren
     * und taucht deshalb nicht auf; Boden und Gelände dieser Reihe bleiben erhalten. */
    void erfasseRueck(World w, int x, int y, int z, int c) {
        for (int ry = -UNTEN; ry < OBEN; ry++) {
            int wy = y + ry;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            for (int rx = -SEITE; rx <= SEITE; rx++) {
                BlockData d = w.getBlockData(x - rx, wy, z - c);
                if (d.getMaterial() == Material.BARRIER) d = LUFT;
                if (!d.getMaterial().isAir()) { d = d.clone(); d.rotate(StructureRotation.CLOCKWISE_180); }
                daten[index(rx, ry, c)] = nummer(d.getMaterial().isAir() ? LUFT : d);
            }
        }
    }

    static int index(int rx, int ry, int b) { return (b * HOEHE + (ry + UNTEN)) * BREITE + (rx + SEITE); }
    BlockData block(int i) { return palette.get(daten[i]); }

    private short nummer(BlockData d) {
        return nummern.computeIfAbsent(d, k -> { palette.add(k); return (short) (palette.size() - 1); });
    }

    /* Vorderseite des eigenen Tores für die Gegenseite aufnehmen: um 180° gedreht,
     * damit der Betrachter drüben nach Norden schaut und hier den Süden sieht. */
    void erfasseVorne(World w, int x, int y, int z, int b) {
        for (int ry = -UNTEN; ry < OBEN; ry++) {
            int wy = y + ry;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            for (int rx = -SEITE; rx <= SEITE; rx++) {
                // Reihe z ist die eigene Torwand, der Blick beginnt direkt davor.
                BlockData d = w.getBlockData(x - rx, wy, z + 1 + b);
                if (!d.getMaterial().isAir()) { d = d.clone(); d.rotate(StructureRotation.CLOCKWISE_180); }
                daten[index(rx, ry, b)] = nummer(d.getMaterial().isAir() ? LUFT : d);
            }
        }
    }

    /* Rückseite des eigenen Tores unverändert aufnehmen: das sind die echten Blöcke, die
     * der Spieler ohne Portal sehen würde und die nach dem Wegsehen zurückgesetzt werden. */
    void erfasseHinten(World w, int x, int y, int z, int b) {
        for (int ry = -UNTEN; ry < OBEN; ry++) {
            int wy = y + ry;
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            for (int rx = -SEITE; rx <= SEITE; rx++) {
                BlockData d = w.getBlockData(x + rx, wy, z - 1 - b);
                daten[index(rx, ry, b)] = nummer(d.getMaterial().isAir() ? LUFT : d);
            }
        }
    }

    /* Dateiformat: MAGIC, Palette (Blockzustände als Text), dann je Feld die Palettennummer. */
    byte[] alsBytes() {
        var puffer = new ByteArrayOutputStream(daten.length * 2 + 8192);
        try (var out = new DataOutputStream(puffer)) {
            out.writeInt(MAGIC);
            out.writeInt(palette.size());
            for (BlockData d : palette) {
                byte[] s = d.getAsString().getBytes(StandardCharsets.UTF_8);
                out.writeShort(s.length); out.write(s);
            }
            for (short s : daten) out.writeShort(s);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return puffer.toByteArray();
    }

    /* Liest eine Aufnahme normaler Tiefe (TIEFE). */
    static Ausschnitt lesen(byte[] bytes, java.util.logging.Logger log) throws IOException {
        var a = new Ausschnitt();
        a.palette.clear(); a.nummern.clear();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Ungültiger Portalausschnitt");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] s = new byte[in.readUnsignedShort()]; in.readFully(s);
                String text = new String(s, StandardCharsets.UTF_8);
                BlockData d;
                try { d = Bukkit.createBlockData(text); }
                catch (IllegalArgumentException e) { log.warning("Unbekannter Block im Ausschnitt: " + text); d = LUFT; }
                a.palette.add(d);
            }
            for (int i = 0; i < a.daten.length; i++) a.daten[i] = in.readShort();
        }
        return a;
    }
}
