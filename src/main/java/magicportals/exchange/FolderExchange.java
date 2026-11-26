package magicportals.exchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exchange through a shared folder. Only suitable when all servers run on the same machine. */
public final class FolderExchange implements Exchange {
    private final Path folder;

    public FolderExchange(Path folder) throws IOException {
        this.folder = folder;
        Files.createDirectories(folder);
    }

    private static long modified(Path p) throws IOException { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0; }

    private void write(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override public void heartbeat(String server) throws IOException { Files.write(folder.resolve(server + ".online"), new byte[0]); }
    @Override public void signOff(String server) throws IOException { Files.deleteIfExists(folder.resolve(server + ".online")); }

    @Override public Set<String> online() throws IOException {
        var result = new HashSet<String>();
        long now = System.currentTimeMillis();
        try (var files = Files.list(folder)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".online") && now - modified(p) < HEARTBEAT_VALID) result.add(n.substring(0, n.length() - 7));
            }
        }
        return result;
    }

    @Override public void setArrival(String target, UUID player, String origin) throws IOException {
        Files.writeString(folder.resolve(target + "-" + player + ".arrival"), origin, StandardCharsets.UTF_8);
    }

    @Override public String takeArrival(String server, UUID player) throws IOException {
        Path file = folder.resolve(server + "-" + player + ".arrival");
        if (!Files.exists(file)) return null;
        boolean fresh = System.currentTimeMillis() - modified(file) < ARRIVAL_VALID;
        String origin = Files.readString(file, StandardCharsets.UTF_8).trim();
        Files.deleteIfExists(file);
        return fresh ? origin : null;
    }

    @Override public void saveSnapshot(String key, String kind, byte[] data) throws IOException { write(folder.resolve(key + "." + kind), data); }
    @Override public long snapshotVersion(String key, String kind) throws IOException { return modified(folder.resolve(key + "." + kind)); }

    @Override public byte[] loadSnapshot(String key, String kind) throws IOException {
        Path p = folder.resolve(key + "." + kind);
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    @Override public void deleteSnapshots(String key) throws IOException {
        for (String kind : List.of(FRONT, BACK)) Files.deleteIfExists(folder.resolve(key + "." + kind));
    }

    @Override public List<String> snapshotKeys() throws IOException {
        var keys = new ArrayList<String>();
        try (var files = Files.list(folder)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith("." + FRONT) && n.indexOf('@') > 0) keys.add(n.substring(0, n.length() - FRONT.length() - 1));
            }
        }
        Collections.sort(keys);
        return keys;
    }

    @Override public long scenesVersion() throws IOException { return modified(folder.resolve("scenes.txt")); }

    @Override public Map<String, Integer> scenes() throws IOException {
        var result = new HashMap<String, Integer>();
        Path file = folder.resolve("scenes.txt");
        if (!Files.exists(file)) return result;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) result.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
        }
        return result;
    }

    @Override public void setScenes(Map<String, Integer> scenes) throws IOException {
        var text = new StringBuilder();
        scenes.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(e -> text.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        write(folder.resolve("scenes.txt"), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override public Path exportForBaking(Path workFolder) { return folder; }
    @Override public void close() { }
}
