package magicportals;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.configuration.ConfigurationSection;

/* Austausch über eine MySQL- oder MariaDB-Datenbank, für Server auf verschiedenen Rechnern.
 * Alle Zeiten kommen von der Datenbank, damit abweichende Uhren der Server keine Rolle spielen.
 * Aufnahmen werden gzip-komprimiert und nur geschrieben, wenn sich ihr Inhalt geändert hat. */
final class MysqlAustausch implements Austausch {
    private static final String JETZT = "ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000)";
    private final String url, benutzer, passwort, p;
    private final java.util.logging.Logger log;
    private final Map<String, String> geschrieben = new HashMap<>();   // Prüfsumme je Aufnahme
    private Connection verbindung;

    MysqlAustausch(ConfigurationSection c, Path datenordner, java.util.logging.Logger log) throws IOException {
        if (c == null) throw new IOException("config.yml is missing the section exchange.mysql");
        this.log = log;
        String host = c.getString("host", "localhost");
        int port = c.getInt("port", 3306);
        String datenbank = c.getString("database", "");
        benutzer = c.getString("username", "");
        passwort = c.getString("password", "");
        p = c.getString("table-prefix", "magicportals_").replaceAll("[^A-Za-z0-9_]", "");
        String optionen = c.getString("options", "useSSL=false&allowPublicKeyRetrieval=true");
        url = "jdbc:mysql://" + host + ":" + port + "/" + datenbank + "?" + optionen + "&connectTimeout=5000&socketTimeout=15000&tcpKeepAlive=true";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            tabellenAnlegen();
        } catch (ClassNotFoundException | SQLException e) { throw new IOException("MySQL: " + e.getMessage(), e); }
    }

    private synchronized Connection verbindung() throws SQLException {
        if (verbindung == null || !verbindung.isValid(2)) {
            if (verbindung != null) try { verbindung.close(); } catch (SQLException ignored) { }
            verbindung = DriverManager.getConnection(url, benutzer, passwort);
        }
        return verbindung;
    }

    private void tabellenAnlegen() throws SQLException {
        try (var st = verbindung().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + p + "server (name VARCHAR(64) PRIMARY KEY, zuletzt BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + p + "ankunft (spieler CHAR(36) NOT NULL, ziel VARCHAR(64) NOT NULL, herkunft VARCHAR(64) NOT NULL, zeit BIGINT NOT NULL, PRIMARY KEY (spieler, ziel))");
            st.execute("CREATE TABLE IF NOT EXISTS " + p + "aufnahme (schluessel VARCHAR(140) NOT NULL, art VARCHAR(8) NOT NULL, daten LONGBLOB NOT NULL, pruefsumme CHAR(64) NOT NULL, zeit BIGINT NOT NULL, PRIMARY KEY (schluessel, art))");
            st.execute("CREATE TABLE IF NOT EXISTS " + p + "szene (schluessel VARCHAR(140) PRIMARY KEY, nummer INT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + p + "stand (name VARCHAR(32) PRIMARY KEY, zeit BIGINT NOT NULL)");
        }
    }

    /* Führt eine Datenbankaktion aus; bei abgerissener Verbindung einmal neu verbinden und wiederholen. */
    private interface Aktion<T> { T mit(Connection c) throws SQLException; }
    private synchronized <T> T ausfuehren(Aktion<T> aktion) throws IOException {
        try { return aktion.mit(verbindung()); }
        catch (SQLException erst) {
            try { if (verbindung != null) verbindung.close(); } catch (SQLException ignored) { }
            verbindung = null;
            try { return aktion.mit(verbindung()); }
            catch (SQLException e) { throw new IOException("MySQL: " + e.getMessage(), e); }
        }
    }

    @Override public void lebenszeichen(String server) throws IOException {
        ausfuehren(c -> {
            try (var s = c.prepareStatement("INSERT INTO " + p + "server (name, zuletzt) VALUES (?, " + JETZT + ") ON DUPLICATE KEY UPDATE zuletzt = " + JETZT)) {
                s.setString(1, server); return s.executeUpdate();
            }
        });
    }

    @Override public void abmelden(String server) throws IOException {
        ausfuehren(c -> { try (var s = c.prepareStatement("DELETE FROM " + p + "server WHERE name = ?")) { s.setString(1, server); return s.executeUpdate(); } });
    }

    @Override public Set<String> online() throws IOException {
        return ausfuehren(c -> {
            var ergebnis = new HashSet<String>();
            try (var s = c.prepareStatement("SELECT name FROM " + p + "server WHERE zuletzt > " + JETZT + " - ?")) {
                s.setLong(1, GUELTIG_ONLINE);
                try (var rs = s.executeQuery()) { while (rs.next()) ergebnis.add(rs.getString(1)); }
            }
            return ergebnis;
        });
    }

    @Override public void ankunftSetzen(String ziel, UUID spieler, String herkunft) throws IOException {
        ausfuehren(c -> {
            try (var s = c.prepareStatement("REPLACE INTO " + p + "ankunft (spieler, ziel, herkunft, zeit) VALUES (?, ?, ?, " + JETZT + ")")) {
                s.setString(1, spieler.toString()); s.setString(2, ziel); s.setString(3, herkunft); return s.executeUpdate();
            }
        });
    }

    @Override public String ankunftHolen(String server, UUID spieler) throws IOException {
        return ausfuehren(c -> {
            String herkunft = null;
            try (var s = c.prepareStatement("SELECT herkunft FROM " + p + "ankunft WHERE spieler = ? AND ziel = ? AND zeit > " + JETZT + " - ?")) {
                s.setString(1, spieler.toString()); s.setString(2, server); s.setLong(3, GUELTIG_ANKUNFT);
                try (var rs = s.executeQuery()) { if (rs.next()) herkunft = rs.getString(1); }
            }
            try (var s = c.prepareStatement("DELETE FROM " + p + "ankunft WHERE spieler = ? OR zeit < " + JETZT + " - 600000")) {
                s.setString(1, spieler.toString()); s.executeUpdate();
            }
            return herkunft;
        });
    }

    @Override public void aufnahmeSpeichern(String schluessel, String art, byte[] daten) throws IOException {
        String summe = pruefsumme(daten), id = schluessel + "." + art;
        synchronized (geschrieben) { if (summe.equals(geschrieben.get(id))) return; }
        byte[] gepackt = packen(daten);
        int n = ausfuehren(c -> {
            try (var s = c.prepareStatement("INSERT INTO " + p + "aufnahme (schluessel, art, daten, pruefsumme, zeit) VALUES (?, ?, ?, ?, " + JETZT + ") "
                    + "ON DUPLICATE KEY UPDATE zeit = IF(pruefsumme = VALUES(pruefsumme), zeit, VALUES(zeit)), daten = VALUES(daten), pruefsumme = VALUES(pruefsumme)")) {
                s.setString(1, schluessel); s.setString(2, art); s.setBytes(3, gepackt); s.setString(4, summe);
                return s.executeUpdate();
            }
        });
        synchronized (geschrieben) { geschrieben.put(id, summe); }
        if (n > 0) log.fine("Aufnahme " + id + " gespeichert (" + gepackt.length / 1024 + " KB)");
    }

    @Override public long aufnahmeStand(String schluessel, String art) throws IOException {
        return ausfuehren(c -> {
            try (var s = c.prepareStatement("SELECT zeit FROM " + p + "aufnahme WHERE schluessel = ? AND art = ?")) {
                s.setString(1, schluessel); s.setString(2, art);
                try (var rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
            }
        });
    }

    @Override public byte[] aufnahmeLaden(String schluessel, String art) throws IOException {
        byte[] gepackt = ausfuehren(c -> {
            try (var s = c.prepareStatement("SELECT daten FROM " + p + "aufnahme WHERE schluessel = ? AND art = ?")) {
                s.setString(1, schluessel); s.setString(2, art);
                try (var rs = s.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; }
            }
        });
        return gepackt == null ? null : entpacken(gepackt);
    }

    @Override public void aufnahmeLoeschen(String schluessel) throws IOException {
        ausfuehren(c -> { try (var s = c.prepareStatement("DELETE FROM " + p + "aufnahme WHERE schluessel = ?")) { s.setString(1, schluessel); return s.executeUpdate(); } });
        synchronized (geschrieben) { geschrieben.keySet().removeIf(k -> k.startsWith(schluessel + ".")); }
    }

    @Override public List<String> aufnahmen() throws IOException {
        return ausfuehren(c -> {
            var liste = new ArrayList<String>();
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT schluessel FROM " + p + "aufnahme WHERE art = 'blick' ORDER BY schluessel")) {
                while (rs.next()) liste.add(rs.getString(1));
            }
            return liste;
        });
    }

    @Override public long szenenStand() throws IOException {
        return ausfuehren(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT zeit FROM " + p + "stand WHERE name = 'szenen'")) { return rs.next() ? rs.getLong(1) : 0L; }
        });
    }

    @Override public Map<String, Integer> szenen() throws IOException {
        return ausfuehren(c -> {
            var ergebnis = new HashMap<String, Integer>();
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT schluessel, nummer FROM " + p + "szene")) {
                while (rs.next()) ergebnis.put(rs.getString(1), rs.getInt(2));
            }
            return ergebnis;
        });
    }

    @Override public void szenenSetzen(Map<String, Integer> szenen) throws IOException {
        ausfuehren(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (var st = c.createStatement(); var s = c.prepareStatement("INSERT INTO " + p + "szene (schluessel, nummer) VALUES (?, ?)")) {
                st.executeUpdate("DELETE FROM " + p + "szene");
                for (var e : szenen.entrySet()) { s.setString(1, e.getKey()); s.setInt(2, e.getValue()); s.addBatch(); }
                s.executeBatch();
                st.executeUpdate("INSERT INTO " + p + "stand (name, zeit) VALUES ('szenen', " + JETZT + ") ON DUPLICATE KEY UPDATE zeit = " + JETZT);
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(auto); }
            return null;
        });
    }

    /* Schreibt alle Aufnahmen als Dateien in den Arbeitsordner, damit das Backen sie lesen kann. */
    @Override public Path zumBacken(Path arbeitsordner) throws IOException {
        Files.createDirectories(arbeitsordner);
        try (var alte = Files.list(arbeitsordner)) { for (Path alt : alte.toList()) Files.deleteIfExists(alt); }
        for (String s : aufnahmen()) for (String art : List.of("blick", "rueck")) {
            byte[] daten = aufnahmeLaden(s, art);
            if (daten != null) Files.write(arbeitsordner.resolve(s + "." + art), daten);
        }
        return arbeitsordner;
    }

    @Override public synchronized void close() {
        if (verbindung != null) try { verbindung.close(); } catch (SQLException ignored) { }
        verbindung = null;
    }

    private static String pruefsumme(byte[] daten) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] packen(byte[] daten) throws IOException {
        var aus = new ByteArrayOutputStream(daten.length / 8);
        try (var gz = new GZIPOutputStream(aus)) { gz.write(daten); }
        return aus.toByteArray();
    }

    private static byte[] entpacken(byte[] gepackt) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(gepackt))) { return gz.readAllBytes(); }
    }
}
