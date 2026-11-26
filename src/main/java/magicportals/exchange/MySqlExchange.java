package magicportals.exchange;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Exchange through a MySQL or MariaDB database, for servers on different machines.
 * All timestamps come from the database, so different clocks on the servers do not matter.
 * Snapshots are gzipped and only written when their content changed.
 */
public final class MySqlExchange implements Exchange {
    private static final String NOW = "ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000)";
    private final String url, username, password, prefix;
    private final Logger log;
    private final Map<String, String> written = new HashMap<>();   // checksum per snapshot
    private Connection connection;

    public MySqlExchange(ConfigurationSection c, Logger log) throws IOException {
        if (c == null) throw new IOException("config.yml is missing the section exchange.mysql");
        this.log = log;
        String host = c.getString("host", "localhost");
        int port = c.getInt("port", 3306);
        String database = c.getString("database", "");
        username = c.getString("username", "");
        password = c.getString("password", "");
        prefix = c.getString("table-prefix", "magicportals_").replaceAll("[^A-Za-z0-9_]", "");
        String options = c.getString("options", "useSSL=false&allowPublicKeyRetrieval=true");
        url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + options + "&connectTimeout=5000&socketTimeout=15000&tcpKeepAlive=true";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            createTables();
        } catch (ClassNotFoundException | SQLException e) { throw new IOException("MySQL: " + e.getMessage(), e); }
    }

    private synchronized Connection connection() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            connection = DriverManager.getConnection(url, username, password);
        }
        return connection;
    }

    private void createTables() throws SQLException {
        try (var st = connection().createStatement()) {
            // Tables of the predecessor "Dorfportal" (German column names) only hold data that is
            // re-created automatically, so they are replaced.
            try (var rs = st.executeQuery("SELECT last_seen FROM " + prefix + "server LIMIT 1")) { rs.next(); }
            catch (SQLException missing) { st.execute("DROP TABLE IF EXISTS " + prefix + "server"); }
            for (String old : java.util.List.of("ankunft", "aufnahme", "szene", "stand")) st.execute("DROP TABLE IF EXISTS " + prefix + old);
            st.execute("CREATE TABLE IF NOT EXISTS " + prefix + "server (name VARCHAR(64) PRIMARY KEY, last_seen BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + prefix + "arrival (player CHAR(36) NOT NULL, target VARCHAR(64) NOT NULL, origin VARCHAR(64) NOT NULL, time BIGINT NOT NULL, PRIMARY KEY (player, target))");
            st.execute("CREATE TABLE IF NOT EXISTS " + prefix + "snapshot (snapshot_key VARCHAR(140) NOT NULL, kind VARCHAR(8) NOT NULL, data LONGBLOB NOT NULL, checksum CHAR(64) NOT NULL, time BIGINT NOT NULL, PRIMARY KEY (snapshot_key, kind))");
            st.execute("CREATE TABLE IF NOT EXISTS " + prefix + "scene (snapshot_key VARCHAR(140) PRIMARY KEY, number INT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + prefix + "state (name VARCHAR(32) PRIMARY KEY, time BIGINT NOT NULL)");
        }
    }

    /** Runs a database action; on a broken connection it reconnects once and retries. */
    private interface Action<T> { T run(Connection c) throws SQLException; }

    private synchronized <T> T execute(Action<T> action) throws IOException {
        try { return action.run(connection()); }
        catch (SQLException first) {
            try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
            connection = null;
            try { return action.run(connection()); }
            catch (SQLException e) { throw new IOException("MySQL: " + e.getMessage(), e); }
        }
    }

    @Override public void heartbeat(String server) throws IOException {
        execute(c -> {
            try (var s = c.prepareStatement("INSERT INTO " + prefix + "server (name, last_seen) VALUES (?, " + NOW + ") ON DUPLICATE KEY UPDATE last_seen = " + NOW)) {
                s.setString(1, server);
                return s.executeUpdate();
            }
        });
    }

    @Override public void signOff(String server) throws IOException {
        execute(c -> { try (var s = c.prepareStatement("DELETE FROM " + prefix + "server WHERE name = ?")) { s.setString(1, server); return s.executeUpdate(); } });
    }

    @Override public Set<String> online() throws IOException {
        return execute(c -> {
            var result = new HashSet<String>();
            try (var s = c.prepareStatement("SELECT name FROM " + prefix + "server WHERE last_seen > " + NOW + " - ?")) {
                s.setLong(1, HEARTBEAT_VALID);
                try (var rs = s.executeQuery()) { while (rs.next()) result.add(rs.getString(1)); }
            }
            return result;
        });
    }

    @Override public void setArrival(String target, UUID player, String origin) throws IOException {
        execute(c -> {
            try (var s = c.prepareStatement("REPLACE INTO " + prefix + "arrival (player, target, origin, time) VALUES (?, ?, ?, " + NOW + ")")) {
                s.setString(1, player.toString()); s.setString(2, target); s.setString(3, origin);
                return s.executeUpdate();
            }
        });
    }

    @Override public String takeArrival(String server, UUID player) throws IOException {
        return execute(c -> {
            String origin = null;
            try (var s = c.prepareStatement("SELECT origin FROM " + prefix + "arrival WHERE player = ? AND target = ? AND time > " + NOW + " - ?")) {
                s.setString(1, player.toString()); s.setString(2, server); s.setLong(3, ARRIVAL_VALID);
                try (var rs = s.executeQuery()) { if (rs.next()) origin = rs.getString(1); }
            }
            // remove this player's entries and anything older than 10 minutes
            try (var s = c.prepareStatement("DELETE FROM " + prefix + "arrival WHERE player = ? OR time < " + NOW + " - 600000")) {
                s.setString(1, player.toString());
                s.executeUpdate();
            }
            return origin;
        });
    }

    @Override public void saveSnapshot(String key, String kind, byte[] data) throws IOException {
        String checksum = checksum(data), id = key + "." + kind;
        synchronized (written) { if (checksum.equals(written.get(id))) return; }
        byte[] packed = gzip(data);
        execute(c -> {
            try (var s = c.prepareStatement("INSERT INTO " + prefix + "snapshot (snapshot_key, kind, data, checksum, time) VALUES (?, ?, ?, ?, " + NOW + ") "
                    + "ON DUPLICATE KEY UPDATE time = IF(checksum = VALUES(checksum), time, VALUES(time)), data = VALUES(data), checksum = VALUES(checksum)")) {
                s.setString(1, key); s.setString(2, kind); s.setBytes(3, packed); s.setString(4, checksum);
                return s.executeUpdate();
            }
        });
        synchronized (written) { written.put(id, checksum); }
        log.fine("Snapshot " + id + " saved (" + packed.length / 1024 + " KB)");
    }

    @Override public long snapshotVersion(String key, String kind) throws IOException {
        return execute(c -> {
            try (var s = c.prepareStatement("SELECT time FROM " + prefix + "snapshot WHERE snapshot_key = ? AND kind = ?")) {
                s.setString(1, key); s.setString(2, kind);
                try (var rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
            }
        });
    }

    @Override public byte[] loadSnapshot(String key, String kind) throws IOException {
        byte[] packed = execute(c -> {
            try (var s = c.prepareStatement("SELECT data FROM " + prefix + "snapshot WHERE snapshot_key = ? AND kind = ?")) {
                s.setString(1, key); s.setString(2, kind);
                try (var rs = s.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; }
            }
        });
        return packed == null ? null : gunzip(packed);
    }

    @Override public void deleteSnapshots(String key) throws IOException {
        execute(c -> { try (var s = c.prepareStatement("DELETE FROM " + prefix + "snapshot WHERE snapshot_key = ?")) { s.setString(1, key); return s.executeUpdate(); } });
        synchronized (written) { written.keySet().removeIf(k -> k.startsWith(key + ".")); }
    }

    @Override public List<String> snapshotKeys() throws IOException {
        return execute(c -> {
            var keys = new ArrayList<String>();
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT snapshot_key FROM " + prefix + "snapshot WHERE kind = '" + FRONT + "' ORDER BY snapshot_key")) {
                while (rs.next()) keys.add(rs.getString(1));
            }
            return keys;
        });
    }

    @Override public long scenesVersion() throws IOException {
        return execute(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT time FROM " + prefix + "state WHERE name = 'scenes'")) { return rs.next() ? rs.getLong(1) : 0L; }
        });
    }

    @Override public Map<String, Integer> scenes() throws IOException {
        return execute(c -> {
            var result = new HashMap<String, Integer>();
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT snapshot_key, number FROM " + prefix + "scene")) {
                while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
            }
            return result;
        });
    }

    @Override public void setScenes(Map<String, Integer> scenes) throws IOException {
        execute(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (var st = c.createStatement(); var s = c.prepareStatement("INSERT INTO " + prefix + "scene (snapshot_key, number) VALUES (?, ?)")) {
                st.executeUpdate("DELETE FROM " + prefix + "scene");
                for (var e : scenes.entrySet()) { s.setString(1, e.getKey()); s.setInt(2, e.getValue()); s.addBatch(); }
                s.executeBatch();
                st.executeUpdate("INSERT INTO " + prefix + "state (name, time) VALUES ('scenes', " + NOW + ") ON DUPLICATE KEY UPDATE time = " + NOW);
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(auto); }
            return null;
        });
    }

    /** Writes all snapshots as files into the work folder so the scene baker can read them. */
    @Override public Path exportForBaking(Path workFolder) throws IOException {
        Files.createDirectories(workFolder);
        try (var old = Files.list(workFolder)) { for (Path p : old.toList()) Files.deleteIfExists(p); }
        for (String key : snapshotKeys()) for (String kind : List.of(FRONT, BACK)) {
            byte[] data = loadSnapshot(key, kind);
            if (data != null) Files.write(workFolder.resolve(key + "." + kind), data);
        }
        return workFolder;
    }

    @Override public synchronized void close() {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        connection = null;
    }

    private static String checksum(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream(data.length / 8);
        try (var gz = new GZIPOutputStream(out)) { gz.write(data); }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] packed) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(packed))) { return gz.readAllBytes(); }
    }
}
