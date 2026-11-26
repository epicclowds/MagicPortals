package magicportals.pack;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds the MagicPortals resource pack and delivers it.
 * <ul>
 *   <li>oraxen – MagicPortals.zip goes into plugins/Oraxen/pack/uploads, then "oraxen reload pack"</li>
 *   <li>itemsadder – files go into plugins/ItemsAdder/contents/magicportals/resourcepack, then "iazip"</li>
 *   <li>standalone – a small built-in web server hosts the zip and MagicPortals sends it to joining players</li>
 *   <li>none – only build plugins/MagicPortals/MagicPortals-pack.zip, deliver it yourself</li>
 *   <li>auto – oraxen if installed, else itemsadder if installed, else standalone</li>
 * </ul>
 */
public final class ResourcePack {
    static final int PACK_FORMAT = 97;                 // Minecraft 26.3
    static final String OVERLAY = "magicportals_26_3";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("magicportals:pack".getBytes(StandardCharsets.UTF_8));
    private static final List<String> SHADERS = List.of(
            "assets/minecraft/shaders/core/rendertype_outline.vsh",
            "assets/minecraft/shaders/core/rendertype_outline.fsh",
            "assets/minecraft/shaders/include/magicportals_marker.glsl",
            "assets/minecraft/shaders/post/magicportals.fsh",
            "assets/minecraft/shaders/post/magicportals_scrub.fsh",
            "assets/minecraft/shaders/post/magicportals_blur.fsh");

    private final JavaPlugin plugin;
    private HttpServer webServer;
    private volatile byte[] zipData;
    private volatile String zipSha1;

    public ResourcePack(JavaPlugin plugin) { this.plugin = plugin; }

    private ConfigurationSection config() {
        var c = plugin.getConfig().getConfigurationSection("resource-pack");
        return c != null ? c : plugin.getConfig().createSection("resource-pack");
    }

    public boolean enabled() { return config().getBoolean("enabled", false); }

    public String provider() {
        String p = config().getString("provider", "auto").toLowerCase(Locale.ROOT);
        if (!p.equals("auto")) return p;
        var plugins = Bukkit.getPluginManager();
        if (plugins.getPlugin("Oraxen") != null) return "oraxen";
        if (plugins.getPlugin("ItemsAdder") != null) return "itemsadder";
        return "standalone";
    }

    private boolean ultimateUi() {
        String u = config().getString("ultimateui", "auto").toLowerCase(Locale.ROOT);
        if (u.equals("true")) return true;
        if (u.equals("false")) return false;
        return Bukkit.getPluginManager().getPlugin("UltimateUI") != null;
    }

    // ---------- Minecraft client jar (block models and textures for baking) ----------

    /** Path to the client jar; downloads it from Mojang into plugins/MagicPortals/cache once if needed.
     *  {@code onDownload} receives the version before a download starts. */
    public Path clientJar(Consumer<String> onDownload) throws IOException, InterruptedException {
        String configured = config().getString("client-jar", "");
        if (!configured.isBlank()) {
            Path p = Path.of(configured);
            if (!Files.isRegularFile(p)) throw new IOException("client-jar not found: " + p);
            return p;
        }
        String version = Bukkit.getMinecraftVersion();
        Path target = plugin.getDataFolder().toPath().resolve("cache").resolve("client-" + version + ".jar");
        if (Files.isRegularFile(target)) return target;
        onDownload.accept(version);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        String manifest = http.send(HttpRequest.newBuilder(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        var entry = Pattern.compile("\\{\"id\": ?\"" + Pattern.quote(version) + "\"[^}]*?\"url\": ?\"([^\"]+)\"").matcher(manifest);
        if (!entry.find()) throw new IOException("Minecraft " + version + " not found in Mojang's version list. Set resource-pack.client-jar manually.");
        String details = http.send(HttpRequest.newBuilder(URI.create(entry.group(1))).build(), HttpResponse.BodyHandlers.ofString()).body();
        var client = Pattern.compile("\"client\": ?\\{[^}]*?\"url\": ?\"([^\"]+)\"").matcher(details);
        if (!client.find()) throw new IOException("No client download for Minecraft " + version);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        http.send(HttpRequest.newBuilder(URI.create(client.group(1))).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    // ---------- building ----------

    /** Copies the shaders into the baked folder and packs everything into MagicPortals-pack.zip. */
    public Path build(Path baked) throws IOException {
        boolean uui = ultimateUi();
        for (String file : SHADERS) {
            String text;
            try (var in = plugin.getResource("pack/" + file)) {
                if (in == null) throw new IOException("Missing shader in plugin jar: " + file);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (file.endsWith("rendertype_outline.vsh")) {
                text = text.replace("//@ULTIMATEUI_INCLUDE", uui ? "#include <minecraft:hud.glsl>" : "")
                           .replace("//@ULTIMATEUI_HUD", uui ? "if (make_hud()) {\n        return;\n    }" : "");
            }
            Path target = baked.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
        }
        bundleSounds(baked);
        Path zip = plugin.getDataFolder().toPath().resolve("MagicPortals-pack.zip");
        Path tmp = zip.resolveSibling("MagicPortals-pack.zip.part");
        try (var out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            entry(out, "pack.mcmeta", ("""
                    {"pack":{"description":"MagicPortals – portal shaders","pack_format":%d,"min_format":%d,"max_format":%d},
                     "overlays":{"entries":[{"directory":"%s","formats":{"min_inclusive":%d,"max_inclusive":%d},"min_format":%d,"max_format":%d}]}}
                    """).formatted(PACK_FORMAT, PACK_FORMAT, PACK_FORMAT, OVERLAY, PACK_FORMAT, PACK_FORMAT, PACK_FORMAT, PACK_FORMAT).getBytes(StandardCharsets.UTF_8));
            try (var logo = plugin.getResource("pack/pack.png")) { if (logo != null) entry(out, "pack.png", logo.readAllBytes()); }
            try (var files = Files.walk(baked)) {
                for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = baked.relativize(p).toString().replace('\\', '/');
                    if (name.startsWith("assets/")) entry(out, OVERLAY + "/" + name, Files.readAllBytes(p));
                }
            }
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        loadZip(zip);
        return zip;
    }

    // ---------- custom sounds ----------

    /** Folder for custom sounds: plugins/MagicPortals/sounds. */
    public Path soundFolder() { return plugin.getDataFolder().toPath().resolve("sounds"); }

    /** Valid name of a sound file or folder inside the sounds folder (Minecraft resource location rules). */
    private static final Pattern SOUND_NAME = Pattern.compile("[a-z0-9_./-]+");

    /**
     * The sound events made from the sounds folder: every .ogg file becomes "magicportals:&lt;path without .ogg&gt;",
     * every folder with .ogg files "magicportals:&lt;folder&gt;" playing one of its files at random.
     */
    public Map<String, List<String>> soundEvents() throws IOException {
        var events = new TreeMap<String, List<String>>();
        Path root = soundFolder();
        if (!Files.isDirectory(root)) return events;
        try (var files = Files.walk(root)) {
            for (Path p : files.filter(f -> Files.isRegularFile(f) && f.toString().toLowerCase(Locale.ROOT).endsWith(".ogg")).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - 4);
                if (!SOUND_NAME.matcher(name).matches()) {
                    plugin.getLogger().warning("Sound " + rel + " skipped: only lowercase letters, digits, _ - . / are allowed");
                    continue;
                }
                events.computeIfAbsent(name, k -> new ArrayList<>()).add(name);
                int slash = name.lastIndexOf('/');
                if (slash > 0) events.computeIfAbsent(name.substring(0, slash), k -> new ArrayList<>()).add(name);
            }
        }
        return events;
    }

    /** Copies the custom sounds into the pack and writes assets/magicportals/sounds.json. */
    private void bundleSounds(Path baked) throws IOException {
        var events = soundEvents();
        if (events.isEmpty()) return;
        Path target = baked.resolve("assets/magicportals/sounds");
        var json = new StringBuilder("{");
        var copied = new HashSet<String>();
        for (var event : events.entrySet()) {
            if (json.length() > 1) json.append(',');
            json.append("\n  \"").append(event.getKey()).append("\": {\"sounds\": [");
            for (int i = 0; i < event.getValue().size(); i++) {
                String file = event.getValue().get(i);
                Path source = soundFolder().resolve(file + ".ogg");
                if (copied.add(file)) {
                    Path t = target.resolve(file + ".ogg");
                    Files.createDirectories(t.getParent());
                    Files.copy(source, t, StandardCopyOption.REPLACE_EXISTING);
                }
                // long sounds (music, loops) are streamed instead of loaded into memory at once
                boolean stream = Files.size(source) > 300_000;
                json.append(i > 0 ? ", " : "").append("{\"name\": \"magicportals:").append(file).append('"').append(stream ? ", \"stream\": true" : "").append('}');
            }
            json.append("]}");
        }
        json.append("\n}\n");
        Files.writeString(baked.resolve("assets/magicportals/sounds.json"), json, StandardCharsets.UTF_8);
        plugin.getLogger().info("Custom sounds: " + events.size() + " sound event(s) from " + copied.size() + " file(s)");
    }

    private static void entry(ZipOutputStream out, String name, byte[] data) throws IOException {
        var e = new ZipEntry(name);
        e.setTime(0);
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    private void loadZip(Path zip) throws IOException {
        byte[] data = Files.readAllBytes(zip);
        try { zipSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        zipData = data;
    }

    // ---------- delivery ----------

    /** Hands the finished pack to the provider. Runs off the main thread; returns the console command to
     *  run afterwards on the main thread, or null. */
    public String deliver(Path baked) throws IOException {
        Path plugins = plugin.getDataFolder().toPath().getParent();
        switch (provider()) {
            case "oraxen" -> {
                Path uploads = plugins.resolve("Oraxen/pack/uploads");
                Files.createDirectories(uploads);
                Files.copy(plugin.getDataFolder().toPath().resolve("MagicPortals-pack.zip"), uploads.resolve("MagicPortals.zip"), StandardCopyOption.REPLACE_EXISTING);
                return "oraxen reload pack";
            }
            case "itemsadder" -> {
                Path target = plugins.resolve("ItemsAdder/contents/magicportals/resourcepack");
                if (Files.exists(target)) try (var old = Files.walk(target)) { old.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                try (var files = Files.walk(baked)) {
                    for (Path p : files.filter(Files::isRegularFile).toList()) {
                        String name = baked.relativize(p).toString().replace('\\', '/');
                        if (!name.startsWith("assets/")) continue;
                        Path t = target.resolve(name);
                        Files.createDirectories(t.getParent());
                        Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return "iazip";
            }
            case "standalone" -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    startWebServer();
                    for (Player p : Bukkit.getOnlinePlayers()) send(p);
                });
                return null;
            }
            default -> { return null; }
        }
    }

    // ---------- standalone: built-in web server ----------

    public void startWebServer() {
        if (!enabled() || !provider().equals("standalone")) { stopWebServer(); return; }
        Path zip = plugin.getDataFolder().toPath().resolve("MagicPortals-pack.zip");
        try { if (zipData == null && Files.exists(zip)) loadZip(zip); } catch (IOException e) { plugin.getLogger().warning(e.getMessage()); }
        if (webServer != null) return;
        int port = config().getInt("standalone.port", 8165);
        try {
            webServer = HttpServer.create(new InetSocketAddress(port), 0);
            webServer.createContext("/MagicPortals.zip", exchange -> {
                byte[] data = zipData;
                if (data == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (var out = exchange.getResponseBody()) { out.write(data); }
            });
            webServer.start();
            plugin.getLogger().info("Resource pack is served at " + url());
        } catch (IOException e) { plugin.getLogger().warning("Could not start the pack web server on port " + port + ": " + e.getMessage()); }
    }

    public void stopWebServer() { if (webServer != null) { webServer.stop(0); webServer = null; } }

    private String url() {
        String configured = config().getString("standalone.url", "");
        if (!configured.isBlank()) return configured;
        String ip = Bukkit.getIp();
        return "http://" + (ip == null || ip.isBlank() ? "localhost" : ip) + ":" + config().getInt("standalone.port", 8165) + "/MagicPortals.zip";
    }

    /** Sends the pack to a player (standalone provider only). */
    public void send(Player p) {
        if (!enabled() || !provider().equals("standalone") || zipSha1 == null || webServer == null) return;
        var info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url() + "?" + zipSha1.substring(0, 8)), zipSha1);
        var request = ResourcePackRequest.resourcePackRequest().packs(info).replace(false)
                .required(config().getBoolean("standalone.required", false));
        String prompt = config().getString("standalone.prompt", "");
        if (!prompt.isBlank()) request.prompt(Component.text(prompt));
        p.sendResourcePacks(request.build());
    }
}
