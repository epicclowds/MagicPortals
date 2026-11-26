package magicportals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/* Alles, was die Server untereinander austauschen. Wird nur außerhalb des Server-Threads benutzt.
 *
 *   Lebenszeichen  welcher Server läuft gerade (gilt 16 s)
 *   Ankunft        wer gerade von welchem Server herüberkommt (gilt 30 s, wird beim Abholen gelöscht)
 *   Aufnahme       Blöcke vor ("blick") und hinter ("rueck") einem Tor, Schlüssel "<server>@<ziel>"
 *   Szenen         Szenennummer je Aufnahme, vom Backen vergeben */
interface Austausch extends AutoCloseable {
    long GUELTIG_ONLINE = 16_000, GUELTIG_ANKUNFT = 30_000;

    void lebenszeichen(String server) throws IOException;
    void abmelden(String server) throws IOException;
    Set<String> online() throws IOException;

    void ankunftSetzen(String ziel, UUID spieler, String herkunft) throws IOException;
    /* Liefert die Herkunft und löscht den Eintrag, oder null. */
    String ankunftHolen(String server, UUID spieler) throws IOException;

    void aufnahmeSpeichern(String schluessel, String art, byte[] daten) throws IOException;
    /* Zeitpunkt der letzten Änderung, 0 wenn es sie nicht gibt. */
    long aufnahmeStand(String schluessel, String art) throws IOException;
    byte[] aufnahmeLaden(String schluessel, String art) throws IOException;
    void aufnahmeLoeschen(String schluessel) throws IOException;
    List<String> aufnahmen() throws IOException;

    /* Stand der Szenenliste (ändert sich bei jedem Backen), 0 wenn es keine gibt. */
    long szenenStand() throws IOException;
    Map<String, Integer> szenen() throws IOException;
    void szenenSetzen(Map<String, Integer> szenen) throws IOException;

    /* Ordner mit allen Aufnahmen als Dateien, zum Backen. */
    Path zumBacken(Path arbeitsordner) throws IOException;

    @Override void close();

    static Austausch aus(ConfigurationSection config, Path datenordner, java.util.logging.Logger log) throws IOException {
        // shorthand: "exchange: <folder>"
        if (config.isString("exchange")) return new DateiAustausch(Path.of(config.getString("exchange")).toAbsolutePath().normalize());
        var a = config.getConfigurationSection("exchange");
        if (a == null) return new DateiAustausch(Path.of("../portal-exchange").toAbsolutePath().normalize());
        String art = a.getString("type", "folder").toLowerCase(Locale.ROOT);
        return switch (art) {
            case "folder", "datei" -> new DateiAustausch(Path.of(a.getString("folder", "../portal-exchange")).toAbsolutePath().normalize());
            case "mysql", "mariadb" -> new MysqlAustausch(a.getConfigurationSection("mysql"), datenordner, log);
            default -> throw new IOException("Unknown exchange type '" + art + "' (use folder or mysql)");
        };
    }
}
