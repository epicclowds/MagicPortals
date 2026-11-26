package magicportals;

import io.papermc.paper.math.Position;
import java.io.IOException;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/* Ein Tor auf diesem Server, das zu einem anderen Server im Netzwerk führt.
 * Lage: Die Öffnung (3x3) liegt in der Blockreihe z, von x-1 bis x+1 und y bis y+2. Man steht südlich
 * davor (größeres z) und blickt nach Norden hindurch. Die Torebene ist die Nordseite der Reihe z. */
final class Tor {
    final MagicPortals plugin;
    final World welt;
    final String ziel, name;
    final int x, y, z;
    final Material aussen, innen;
    final boolean riss;   // Stil "riss": schwebender Dimensionsriss statt Tor in einer Wand

    private final List<Entity> deko = new ArrayList<>();
    private final List<Location> barrieren = new ArrayList<>();
    private BlockDisplay marker;
    private TextDisplay schild;
    private Integer szene;
    boolean erreichbar;

    // Scheinblöcke (Ersatz ohne Ressourcenpaket)
    private Ausschnitt fernBlick, hinten, aufnahmeVorne, aufnahmeHinten, aufnahmeRueck;
    private final BitSet unterschied = new BitSet(Ausschnitt.GROESSE);
    private final Map<UUID, Sicht> sichten = new HashMap<>();
    private int version, scheibe;
    private long bildzeit;

    private static final class Sicht {
        BitSet gezeigt = new BitSet();
        int version = -1;
        double ex = Double.NaN, ey, ez;
        int naechsteVoll;
    }

    Tor(MagicPortals plugin, World welt, String ziel, String name, int x, int y, int z, Material aussen, Material innen, boolean riss) {
        this.plugin = plugin; this.welt = welt; this.ziel = ziel; this.name = name;
        this.x = x; this.y = y; this.z = z; this.aussen = aussen; this.innen = innen; this.riss = riss;
    }

    /* Dateinamen im Austauschordner: "<server>@<ziel>" = Blick vor dem Tor von <server> nach <ziel>. */
    static String schluessel(String server, String ziel) { return server + "@" + ziel; }

    boolean nahe(int px, int pz, int abstand) { return Math.abs(px - x) < abstand && Math.abs(pz - z) < abstand; }

    // ---------- Bauen ----------

    private Location ort(double px, double py, double pz) { return new Location(welt, px, py, pz); }

    private BlockDisplay stein(double px, double py, double pz, float w, float h, float t, Material art, boolean leuchtend) {
        var d = welt.spawn(ort(px, py, pz), BlockDisplay.class, e -> {
            e.setPersistent(false); e.setBlock(art.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(w, h, t), new Quaternionf()));
            if (leuchtend) e.setBrightness(new Display.Brightness(15, 15));
        });
        deko.add(d); return d;
    }

    /* Setzt einen unsichtbaren Hilfsblock (Barriere oder Licht), nur wo Luft ist; wird beim Abbauen entfernt. */
    private void hilfsblock(int bx, int by, int bz, Material art, int lichtstufe) {
        var block = welt.getBlockAt(bx, by, bz);
        if (!block.getType().isAir() && block.getType() != art) return;
        var daten = art.createBlockData();
        if (daten instanceof org.bukkit.block.data.Levelled l) l.setLevel(lichtstufe);
        block.setBlockData(daten, false);
        barrieren.add(block.getLocation());
    }

    private void barriere(int bx, int by, int bz) { hilfsblock(bx, by, bz, Material.BARRIER, 0); }

    void bauen() {
        welt.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(plugin);
        // Direkt hinter der Öffnung (3x3x3) zeigt der Shader den Zwischenraum; dort kommen normale Spieler nicht hin.
        for (int cx = x - 1; cx <= x + 1; cx++) for (int cy = y; cy <= y + 2; cy++) for (int cz = z - 3; cz <= z - 1; cz++) barriere(cx, cy, cz);
        if (riss) rissBauen(); else wandBauen();
        // Marker für den Portal-Shader an der unteren Westecke der Öffnung auf der Torebene.
        // Seine Leuchtfarbe trägt Stil und Szenennummer (R=254, G=1+Stil, B=160+Szene).
        marker = welt.spawn(ort(x - 1, y, z), BlockDisplay.class, d -> {
            d.setPersistent(false); d.setBlock(Material.WHITE_CONCRETE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(.001f, .001f, .001f), new Quaternionf()));
            d.setDisplayWidth(10f); d.setDisplayHeight(7f); d.setViewRange(2f);
        });
        deko.add(marker);
        markerSetzen(szene);
        double schildHoehe = riss ? 5.0 : 5.5, schildZ = riss ? .5 : 1.2;
        schild = welt.spawn(ort(x + .5, y + schildHoehe, z + schildZ), TextDisplay.class, d -> {
            d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setShadowed(true);
        });
        deko.add(schild);
        schildSetzen();
    }

    /* Riss: keine Wand, nur unsichtbares Licht, damit die Umgebung vom Riss angestrahlt wirkt. */
    private void rissBauen() {
        hilfsblock(x, y + 1, z + 1, Material.LIGHT, 12);
        hilfsblock(x, y + 3, z + 1, Material.LIGHT, 9);
    }

    /* Dicke Wand (7 breit, 5 hoch, 1 tief) aus Blockanzeigen mit der 3x3-Öffnung und Barrieren in der Wand. */
    private void wandBauen() {
        for (int cx = x - 3; cx <= x + 3; cx++) for (int cy = y; cy <= y + 4; cy++) {
            if (cx >= x - 1 && cx <= x + 1 && cy <= y + 2) continue;
            boolean rand = cx >= x - 2 && cx <= x + 2 && cy <= y + 3;
            stein(cx, cy, z, 1f, 1f, 1f, rand ? innen : aussen, false);
            barriere(cx, cy, z);
        }
        for (double px : new double[]{x - 1.0, x + 1.94}) stein(px, y, z + .02, .06f, 3f, .96f, Material.AMETHYST_BLOCK, true);
        stein(x - 1.0, y + 2.94, z + .02, 3f, .06f, .96f, Material.AMETHYST_BLOCK, true);
        for (double px : new double[]{x - 1.0, x + 1.95}) stein(px, y, z - .01, .05f, 3f, .05f, Material.LIGHT_BLUE_STAINED_GLASS, true);
        stein(x - 1.0, y + 2.95, z - .01, 3f, .05f, .05f, Material.LIGHT_BLUE_STAINED_GLASS, true);
        stein(x - 1.0, y, z - .01, 3f, .03f, .05f, Material.LIGHT_BLUE_STAINED_GLASS, true);
    }

    void abbauen() {
        for (Player p : welt.getPlayers()) { var s = sichten.get(p.getUniqueId()); if (s != null) zuruecksetzen(p, s); }
        sichten.clear();
        for (var e : deko) e.remove();
        deko.clear();
        for (var o : barrieren) {
            var typ = o.getBlock().getType();
            if (typ == Material.BARRIER || typ == Material.LIGHT) o.getBlock().setType(Material.AIR, false);
        }
        barrieren.clear();
        welt.removePluginChunkTicket(x >> 4, z >> 4, plugin);
    }

    void markerSetzen(Integer neu) {
        szene = neu;
        if (marker == null) return;
        if (neu == null || neu < 0 || neu >= 16) { marker.setGlowing(false); return; }
        marker.setGlowing(true);
        marker.setGlowColorOverride(Color.fromRGB(254, riss ? 2 : 1, 160 + neu));
    }

    Integer szene() { return szene; }

    private void schildSetzen() {
        if (schild == null) return;
        schild.text(erreichbar ? Component.text("✦  " + name + "  ✦")
                : Component.text("✦  " + name + "  ✦", NamedTextColor.GRAY).append(Component.newline())
                .append(Component.text(plugin.lang().t("offline.sign"), NamedTextColor.RED)));
    }

    void erreichbarSetzen(boolean jetzt) {
        if (jetzt == erreichbar) return;
        erreichbar = jetzt; version++;
        schildSetzen();
    }

    // ---------- Aufnahme ----------

    /* Fertige Aufnahme dieses Tores, wird vom Plugin außerhalb des Server-Threads gespeichert. */
    record Aufnahme(String schluessel, Ausschnitt vorne, Ausschnitt rueck) { }

    /* Nimmt alles sofort auf (beim Start und vor dem Backen). */
    Aufnahme allesAufnehmen(String lokal) {
        var vorne = new Ausschnitt(); var neuHinten = new Ausschnitt(); var rueck = new Ausschnitt(Ausschnitt.RUECK);
        for (int b = 0; b < Ausschnitt.TIEFE; b++) {
            vorne.erfasseVorne(welt, x, y, z, b); neuHinten.erfasseHinten(welt, x, y, z, b);
            if (b < Ausschnitt.RUECK) rueck.erfasseRueck(welt, x, y, z, b);
        }
        hinten = neuHinten; neuVergleichen();
        aufnahmeVorne = aufnahmeHinten = aufnahmeRueck = null;
        return new Aufnahme(schluessel(lokal, ziel), vorne, rueck);
    }

    void aufnahmeStarten() {
        aufnahmeVorne = new Ausschnitt(); aufnahmeHinten = new Ausschnitt(); aufnahmeRueck = new Ausschnitt(Ausschnitt.RUECK); scheibe = 0;
    }

    /* Zwei Schichten pro Tick, damit der Server nicht stockt. Liefert die fertige Aufnahme, sonst null. */
    Aufnahme aufnahmeSchritt(String lokal) {
        if (aufnahmeVorne == null) return null;
        for (int n = 0; n < 2 && scheibe < Ausschnitt.TIEFE; n++, scheibe++) {
            aufnahmeVorne.erfasseVorne(welt, x, y, z, scheibe);
            aufnahmeHinten.erfasseHinten(welt, x, y, z, scheibe);
            if (scheibe < Ausschnitt.RUECK) aufnahmeRueck.erfasseRueck(welt, x, y, z, scheibe);
        }
        if (scheibe < Ausschnitt.TIEFE) return null;
        var fertig = new Aufnahme(schluessel(lokal, ziel), aufnahmeVorne, aufnahmeRueck);
        hinten = aufnahmeHinten; neuVergleichen();
        aufnahmeVorne = aufnahmeHinten = aufnahmeRueck = null;
        return fertig;
    }

    /* Stand des Blicks der Gegenseite, den die Scheinblöcke gerade benutzen. */
    long fernStand() { return bildzeit; }

    /* Neuer Blick der Gegenseite (nur für die Scheinblöcke nötig), im Server-Thread aufrufen. */
    void fernSetzen(byte[] daten, long stand) {
        try { fernBlick = Ausschnitt.lesen(daten, plugin.getLogger()); bildzeit = stand; neuVergleichen(); }
        catch (IOException e) { plugin.getLogger().warning("Blick von " + ziel + ": " + e.getMessage()); }
    }

    private void neuVergleichen() {
        unterschied.clear();
        if (fernBlick != null && hinten != null)
            for (int i = 0; i < Ausschnitt.GROESSE; i++) if (!fernBlick.block(i).equals(hinten.block(i))) unterschied.set(i);
        version++;
    }

    // ---------- Scheinblöcke pro Spieler ----------

    private boolean vorDemTor(Location auge) {
        return auge.getWorld() == welt && auge.getZ() > z + .03 && auge.getZ() < z + 40
                && Math.abs(auge.getX() - (x + .5)) < 40 && Math.abs(auge.getY() - (y + 1.5)) < 30;
    }

    void blickAktualisieren(Player p, int tick) {
        Sicht s = sichten.computeIfAbsent(p.getUniqueId(), k -> new Sicht());
        Location auge = p.getEyeLocation();
        if (!erreichbar || fernBlick == null || hinten == null || !vorDemTor(auge)) { zuruecksetzen(p, s); return; }
        boolean voll = s.version != version || tick >= s.naechsteVoll && tick - s.naechsteVoll < 2;
        double ex = auge.getX(), ey = auge.getY(), ez = auge.getZ();
        if (!voll && Math.abs(ex - s.ex) < .04 && Math.abs(ey - s.ey) < .04 && Math.abs(ez - s.ez) < .04) return;
        s.ex = ex; s.ey = ey; s.ez = ez;
        BitSet neu = sichtkegel(ex, ey, ez);
        neu.and(unterschied);
        var aenderung = new HashMap<Position, BlockData>();
        var dazu = (BitSet) neu.clone();
        if (!voll) dazu.andNot(s.gezeigt);
        else s.naechsteVoll = (tick + 200) % 1200;
        for (int i = dazu.nextSetBit(0); i >= 0; i = dazu.nextSetBit(i + 1)) aenderung.put(pos(i), fernBlick.block(i));
        var weg = (BitSet) s.gezeigt.clone(); weg.andNot(neu);
        for (int i = weg.nextSetBit(0); i >= 0; i = weg.nextSetBit(i + 1)) aenderung.put(pos(i), hinten.block(i));
        s.gezeigt = neu; s.version = version;
        if (!aenderung.isEmpty()) p.sendMultiBlockChange(aenderung);
    }

    private BitSet sichtkegel(double ex, double ey, double ez) {
        var bits = new BitSet(Ausschnitt.GROESSE);
        boolean imTunnel = ez <= z + 1;
        if (imTunnel && (ex <= x - 1 || ex >= x + 2 || ey <= y || ey >= y + 3)) return bits;
        double hintenAbstand = Math.max(.03, ez - z), vorne = ez - (z + 1);
        for (int b = 0; b < Ausschnitt.TIEFE; b++) {
            double nah = ez - (z - b), fern = ez - (z - b - 1);
            double s1 = nah / hintenAbstand, s2 = fern / hintenAbstand;
            double xa = Math.min(ex + (x - 1 - ex) * s1, ex + (x - 1 - ex) * s2);
            double xb = Math.max(ex + (x + 2 - ex) * s1, ex + (x + 2 - ex) * s2);
            double ya = Math.min(ey + (y - ey) * s1, ey + (y - ey) * s2);
            double yb = Math.max(ey + (y + 3 - ey) * s1, ey + (y + 3 - ey) * s2);
            if (!imTunnel) {
                double t1 = nah / vorne, t2 = fern / vorne;
                xa = Math.max(xa, Math.min(ex + (x - 1 - ex) * t1, ex + (x - 1 - ex) * t2));
                xb = Math.min(xb, Math.max(ex + (x + 2 - ex) * t1, ex + (x + 2 - ex) * t2));
                ya = Math.max(ya, Math.min(ey + (y - ey) * t1, ey + (y - ey) * t2));
                yb = Math.min(yb, Math.max(ey + (y + 3 - ey) * t1, ey + (y + 3 - ey) * t2));
            }
            int rx0 = Math.max(-Ausschnitt.SEITE, (int) Math.floor(xa) - x), rx1 = Math.min(Ausschnitt.SEITE, (int) Math.ceil(xb) - 1 - x);
            int ry0 = Math.max(-Ausschnitt.UNTEN, (int) Math.floor(ya) - y), ry1 = Math.min(Ausschnitt.OBEN - 1, (int) Math.ceil(yb) - 1 - y);
            if (rx0 > rx1 || ry0 > ry1) continue;
            for (int ry = ry0; ry <= ry1; ry++) {
                int start = Ausschnitt.index(rx0, ry, b);
                bits.set(start, start + (rx1 - rx0) + 1);
            }
        }
        return bits;
    }

    private Position pos(int i) {
        int ebene = Ausschnitt.HOEHE * Ausschnitt.BREITE;
        int b = i / ebene, rest = i % ebene;
        return Position.block(x + rest % Ausschnitt.BREITE - Ausschnitt.SEITE, y + rest / Ausschnitt.BREITE - Ausschnitt.UNTEN, z - 1 - b);
    }

    void zuruecksetzen(Player p) { var s = sichten.remove(p.getUniqueId()); if (s != null) zuruecksetzen(p, s); }

    private void zuruecksetzen(Player p, Sicht s) {
        if (s.gezeigt.isEmpty() || hinten == null) return;
        var aenderung = new HashMap<Position, BlockData>();
        for (int i = s.gezeigt.nextSetBit(0); i >= 0; i = s.gezeigt.nextSetBit(i + 1)) aenderung.put(pos(i), hinten.block(i));
        s.gezeigt = new BitSet(); s.ex = Double.NaN;
        if (p.isOnline()) p.sendMultiBlockChange(aenderung);
    }

    // ---------- Partikel, Durchgang, Ankunft ----------

    private static final Particle.DustOptions[] RISS_STAUB = {
            new Particle.DustOptions(Color.fromRGB(64, 217, 255), 1.1f),
            new Particle.DustOptions(Color.fromRGB(255, 77, 242), 1.1f),
            new Particle.DustOptions(Color.fromRGB(255, 209, 90), 0.9f)};

    /* Zufälliger Punkt am Rand der Grundform des Risses (hohe, schmale Spalte wie im Shader, ohne Zacken). */
    private Location rissRand(double aussen) {
        var zufall = java.util.concurrent.ThreadLocalRandom.current();
        double t = zufall.nextDouble();
        double halb = .8 * Math.pow(Math.sin(Math.PI * t), .95) * .9 + aussen;
        double seite = zufall.nextBoolean() ? 1 : -1;
        return ort(x - 1 + 1.5 + seite * halb + (zufall.nextDouble() - .5) * .3, y - .25 + 4.6 * t, z + .02);
    }

    private int rissTakt;

    void partikel() {
        if (riss) { rissPartikel(); return; }
        var zufall = java.util.concurrent.ThreadLocalRandom.current();
        for (Player p : welt.getPlayers()) {
            if (p.getLocation().distanceSquared(ort(x + .5, y + 1.5, z)) > 400) continue;
            for (int i = 0; i < 3; i++) {
                double u = zufall.nextDouble() * 12, px, py;
                if (u < 3) { px = x - 1 + u; py = y + 3; } else if (u < 6) { px = x + 2; py = y + 3 - (u - 3); }
                else if (u < 9) { px = x + 2 - (u - 6); py = y; } else { px = x - 1; py = y + (u - 9); }
                p.spawnParticle(erreichbar ? Particle.END_ROD : Particle.SMOKE, px, py, z + .05, 0, 0, .02, 0, .3);
            }
            if (!erreichbar) p.spawnParticle(Particle.PORTAL, x + .5, y + 1.5, z + .1, 12, .9, .9, .05, .2);
        }
    }

    private void rissPartikel() {
        var zufall = java.util.concurrent.ThreadLocalRandom.current();
        Location mitte = ort(x + .5, y + 1.65, z);
        rissTakt++;
        for (Player p : welt.getPlayers()) {
            double abstand = p.getLocation().distanceSquared(mitte);
            if (abstand > 900) continue;
            for (int i = 0; i < 6; i++)
                p.spawnParticle(Particle.DUST, rissRand(zufall.nextDouble() * .2), 1, .03, .03, .03, 0, RISS_STAUB[zufall.nextInt(RISS_STAUB.length)]);
            for (int i = 0; i < 2; i++) p.spawnParticle(Particle.ELECTRIC_SPARK, rissRand(.05), 2, .08, .08, .08, .05);
            // Teilchen aus der Umgebung werden in den Riss gezogen
            if (zufall.nextInt(2) == 0) p.spawnParticle(Particle.REVERSE_PORTAL, rissRand(1.0), 1, .2, .2, .2, .02);
            if (abstand > 256) continue;
            if (rissTakt % 20 == 0) p.playSound(mitte, "minecraft:block.beacon.ambient", .35f, 1.5f);
            if (zufall.nextInt(45) == 0) p.playSound(mitte, "minecraft:block.amethyst_block.resonate", .25f, .6f + zufall.nextFloat() * .4f);
            if (zufall.nextInt(70) == 0) p.playSound(mitte, "minecraft:entity.lightning_bolt.impact", .06f, 1.8f);
        }
    }

    /* Läuft der Spieler von Süden durch die Öffnung? */
    boolean durchschritten(Location von, Location nach) {
        double grenze = z + .45;
        if (von.getZ() <= grenze || nach.getZ() > grenze || nach.distanceSquared(von) > 16) return false;
        double anteil = (von.getZ() - grenze) / (von.getZ() - nach.getZ());
        double quer = von.getX() + (nach.getX() - von.getX()) * anteil;
        double fuss = von.getY() + (nach.getY() - von.getY()) * anteil;
        return Math.abs(quer - (x + .5)) <= 1.5 && fuss >= y - 1.1 && fuss < y + 3;
    }

    Location ankunft() {
        Location ort = ort(x + .5, y, z + 2.5);
        int boden = welt.getHighestBlockYAt(x, z + 2, HeightMap.MOTION_BLOCKING) + 1;
        if (Math.abs(boden - y) <= 2) ort.setY(boden);
        ort.setYaw(0); ort.setPitch(0);
        return ort;
    }
}
