package magicportals.exchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Everything the servers share with each other. Only used off the main thread.
 * <ul>
 *   <li>heartbeat – which servers are running (valid for 16 s)</li>
 *   <li>arrival – who is currently coming over from which server (valid for 30 s, deleted on pickup)</li>
 *   <li>snapshot – blocks in front of ("front") and behind ("back") a portal, key "&lt;server&gt;@&lt;target&gt;"</li>
 *   <li>scenes – scene number per snapshot, assigned by /magicportals update</li>
 * </ul>
 */
public interface Exchange extends AutoCloseable {
    long HEARTBEAT_VALID = 16_000, ARRIVAL_VALID = 30_000;
    /** Snapshot kinds; "view" holds the view settings (time, fog, …) of the portal that shows the scene. */
    String FRONT = "front", BACK = "back", VIEW = "view", FAR = "far";

    void heartbeat(String server) throws IOException;
    void signOff(String server) throws IOException;
    Set<String> online() throws IOException;

    void setArrival(String target, UUID player, String origin) throws IOException;
    /** Returns the origin server and deletes the entry, or null. */
    String takeArrival(String server, UUID player) throws IOException;

    void saveSnapshot(String key, String kind, byte[] data) throws IOException;
    /** Time of the last change, 0 if the snapshot does not exist. */
    long snapshotVersion(String key, String kind) throws IOException;
    byte[] loadSnapshot(String key, String kind) throws IOException;
    void deleteSnapshots(String key) throws IOException;
    List<String> snapshotKeys() throws IOException;

    /** Version of the scene list (changes with every update), 0 if there is none. */
    long scenesVersion() throws IOException;
    Map<String, Integer> scenes() throws IOException;
    void setScenes(Map<String, Integer> scenes) throws IOException;

    /** Folder containing all snapshots as files ("&lt;key&gt;.front" / ".back"), for baking. */
    Path exportForBaking(Path workFolder) throws IOException;

    @Override void close();

    /** Creates the exchange configured under "exchange" in config.yml. */
    static Exchange fromConfig(ConfigurationSection config, Logger log) throws IOException {
        if (config.isString("exchange")) return new FolderExchange(Path.of(config.getString("exchange")).toAbsolutePath().normalize());
        var section = config.getConfigurationSection("exchange");
        if (section == null) return new FolderExchange(Path.of("../portal-exchange").toAbsolutePath().normalize());
        String type = section.getString("type", "folder").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "folder" -> new FolderExchange(Path.of(section.getString("folder", "../portal-exchange")).toAbsolutePath().normalize());
            case "mysql", "mariadb" -> new MySqlExchange(section.getConfigurationSection("mysql"), log);
            default -> throw new IOException("Unknown exchange type '" + type + "' (use folder or mysql)");
        };
    }
}
