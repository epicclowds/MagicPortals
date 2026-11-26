package magicportals;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/* Builds the MagicPortals resource pack and delivers it.
 *
 * Providers:
 *   oraxen      MagicPortals.zip goes into plugins/Oraxen/pack/uploads, then "oraxen reload pack"
 *   itemsadder  files go into plugins/ItemsAdder/contents/magicportals/resourcepack, then "iazip"
 *   standalone  a small built-in web server hosts the zip and MagicPortals sends it to joining players
 *   none        only build plugins/MagicPortals/MagicPortals-pack.zip, deliver it yourself
 *   auto        oraxen if installed, else itemsadder if installed, else standalone */
final class Paket {
    static final int PACK_FORMAT = 97;                 // Minecraft 26.3
    static final String OVERLAY = "magicportals_26_3";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("magicportals:pack".getBytes(StandardCharsets.UTF_8));
    private static final List<String> SHADER = List.of(
            "assets/minecraft/shaders/core/rendertype_outline.vsh",
            "assets/minecraft/shaders/core/rendertype_outline.fsh",
            "assets/minecraft/shaders/include/magicportals_marker.glsl",
            "assets/minecraft/shaders/post/magicportals.fsh",
            "assets/minecraft/shaders/post/magicportals_scrub.fsh");

    private final MagicPortals plugin;
    private HttpServer webserver;
    private volatile byte[] zipDaten;
    private volatile String zipSha1;

    Paket(MagicPortals plugin) { this.plugin = plugin; }

    ConfigurationSection config() {
        var c = plugin.getConfig().getConfigurationSection("resource-pack");
        return c != null ? c : plugin.getConfig().createSection("resource-pack");
    }

    boolean aktiv() { return config().getBoolean("enabled", false); }

    String anbieter() {
        String a = config().getString("provider", "auto").toLowerCase(Locale.ROOT);
        if (!a.equals("auto")) return a;
        var pm = Bukkit.getPluginManager();
        if (pm.getPlugin("Oraxen") != null) return "oraxen";
        if (pm.getPlugin("ItemsAdder") != null) return "itemsadder";
        return "standalone";
    }

    boolean ultimateUi() {
        String u = config().getString("ultimateui", "auto").toLowerCase(Locale.ROOT);
        if (u.equals("true")) return true;
        if (u.equals("false")) return false;
        return Bukkit.getPluginManager().getPlugin("UltimateUI") != null;
    }

    // ---------- Minecraft client jar (block models and textures for baking) ----------

    /* Path to the client jar; downloads it from Mojang into plugins/MagicPortals/cache once if needed. */
    Path clientJar(java.util.function.Consumer<String> info) throws IOException, InterruptedException {
        String eigen = config().getString("client-jar", "");
        if (!eigen.isBlank()) {
            Path p = Path.of(eigen);
            if (!Files.isRegularFile(p)) throw new IOException("client-jar not found: " + p);
            return p;
        }
        String version = Bukkit.getMinecraftVersion();
        Path ziel = plugin.getDataFolder().toPath().resolve("cache").resolve("client-" + version + ".jar");
        if (Files.isRegularFile(ziel)) return ziel;
        info.accept(version);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        String liste = http.send(HttpRequest.newBuilder(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        var eintrag = Pattern.compile("\\{\"id\": ?\"" + Pattern.quote(version) + "\"[^}]*?\"url\": ?\"([^\"]+)\"").matcher(liste);
        if (!eintrag.find()) throw new IOException("Minecraft " + version + " not found in Mojang's version list. Set resource-pack.client-jar manually.");
        String details = http.send(HttpRequest.newBuilder(URI.create(eintrag.group(1))).build(), HttpResponse.BodyHandlers.ofString()).body();
        var client = Pattern.compile("\"client\": ?\\{[^}]*?\"url\": ?\"([^\"]+)\"").matcher(details);
        if (!client.find()) throw new IOException("No client download for Minecraft " + version);
        Files.createDirectories(ziel.getParent());
        Path tmp = ziel.resolveSibling(ziel.getFileName() + ".part");
        http.send(HttpRequest.newBuilder(URI.create(client.group(1))).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        Files.move(tmp, ziel, StandardCopyOption.REPLACE_EXISTING);
        return ziel;
    }

    // ---------- building ----------

    /* Copies the shaders into the baked folder and packs everything into MagicPortals-pack.zip. */
    Path bauen(Path gebacken) throws IOException {
        boolean uui = ultimateUi();
        for (String datei : SHADER) {
            String text;
            try (var in = plugin.getResource("pack/" + datei)) {
                if (in == null) throw new IOException("Missing shader in plugin jar: " + datei);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (datei.endsWith("rendertype_outline.vsh")) {
                text = text.replace("//@ULTIMATEUI_INCLUDE", uui ? "#include <minecraft:hud.glsl>" : "")
                           .replace("//@ULTIMATEUI_HUD", uui ? "if (make_hud()) {\n        return;\n    }" : "");
            }
            Path ziel = gebacken.resolve(datei);
            Files.createDirectories(ziel.getParent());
            Files.writeString(ziel, text, StandardCharsets.UTF_8);
        }
        Path zip = plugin.getDataFolder().toPath().resolve("MagicPortals-pack.zip");
        Path tmp = zip.resolveSibling("MagicPortals-pack.zip.part");
        try (var out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            eintrag(out, "pack.mcmeta", ("""
                    {"pack":{"description":"MagicPortals – portal shaders","pack_format":%d,"min_format":%d,"max_format":%d},
                     "overlays":{"entries":[{"directory":"%s","formats":{"min_inclusive":%d,"max_inclusive":%d},"min_format":%d,"max_format":%d}]}}
                    """).formatted(PACK_FORMAT, PACK_FORMAT, PACK_FORMAT, OVERLAY, PACK_FORMAT, PACK_FORMAT, PACK_FORMAT, PACK_FORMAT).getBytes(StandardCharsets.UTF_8));
            try (var logo = plugin.getResource("pack/pack.png")) { if (logo != null) eintrag(out, "pack.png", logo.readAllBytes()); }
            try (var dateien = Files.walk(gebacken)) {
                for (Path p : dateien.filter(Files::isRegularFile).sorted().toList()) {
                    String name = gebacken.relativize(p).toString().replace('\\', '/');
                    if (!name.startsWith("assets/")) continue;
                    eintrag(out, OVERLAY + "/" + name, Files.readAllBytes(p));
                }
            }
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        zipLaden(zip);
        return zip;
    }

    private static void eintrag(ZipOutputStream out, String name, byte[] daten) throws IOException {
        var e = new ZipEntry(name);
        e.setTime(0);
        out.putNextEntry(e);
        out.write(daten);
        out.closeEntry();
    }

    private void zipLaden(Path zip) throws IOException {
        byte[] daten = Files.readAllBytes(zip);
        try { zipSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(daten)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        zipDaten = daten;
    }

    // ---------- delivery ----------

    /* Hands the finished pack to the provider. Runs off the main thread; returns the command to run
     * afterwards on the main thread (or null). */
    String ausliefern(Path zip, Path gebacken) throws IOException {
        switch (anbieter()) {
            case "oraxen" -> {
                Path uploads = plugin.getDataFolder().toPath().getParent().resolve("Oraxen/pack/uploads");
                Files.createDirectories(uploads);
                Files.copy(zip, uploads.resolve("MagicPortals.zip"), StandardCopyOption.REPLACE_EXISTING);
                return "oraxen reload pack";
            }
            case "itemsadder" -> {
                Path ziel = plugin.getDataFolder().toPath().getParent().resolve("ItemsAdder/contents/magicportals/resourcepack");
                if (Files.exists(ziel)) try (var alt = Files.walk(ziel)) { alt.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                try (var dateien = Files.walk(gebacken)) {
                    for (Path p : dateien.filter(Files::isRegularFile).toList()) {
                        String name = gebacken.relativize(p).toString().replace('\\', '/');
                        if (!name.startsWith("assets/")) continue;
                        Path z = ziel.resolve(name);
                        Files.createDirectories(z.getParent());
                        Files.copy(p, z, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return "iazip";
            }
            case "standalone" -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    webserverStarten();
                    for (Player p : Bukkit.getOnlinePlayers()) senden(p);
                });
                return null;
            }
            default -> { return null; }
        }
    }

    // ---------- standalone: built-in web server ----------

    void webserverStarten() {
        if (!aktiv() || !anbieter().equals("standalone")) { webserverStoppen(); return; }
        Path zip = plugin.getDataFolder().toPath().resolve("MagicPortals-pack.zip");
        try { if (zipDaten == null && Files.exists(zip)) zipLaden(zip); } catch (IOException e) { plugin.getLogger().warning(e.getMessage()); }
        if (webserver != null) return;
        int port = config().getInt("standalone.port", 8165);
        try {
            webserver = HttpServer.create(new InetSocketAddress(port), 0);
            webserver.createContext("/MagicPortals.zip", austausch -> {
                byte[] daten = zipDaten;
                if (daten == null) { austausch.sendResponseHeaders(404, -1); austausch.close(); return; }
                austausch.getResponseHeaders().add("Content-Type", "application/zip");
                austausch.sendResponseHeaders(200, daten.length);
                try (var out = austausch.getResponseBody()) { out.write(daten); }
            });
            webserver.start();
            plugin.getLogger().info("Resource pack is served at " + url());
        } catch (IOException e) { plugin.getLogger().warning("Could not start the pack web server on port " + port + ": " + e.getMessage()); }
    }

    void webserverStoppen() { if (webserver != null) { webserver.stop(0); webserver = null; } }

    private String url() {
        String eigen = config().getString("standalone.url", "");
        if (!eigen.isBlank()) return eigen;
        String ip = Bukkit.getIp();
        return "http://" + (ip == null || ip.isBlank() ? "localhost" : ip) + ":" + config().getInt("standalone.port", 8165) + "/MagicPortals.zip";
    }

    /* Sends the pack to a player (standalone only). */
    void senden(Player p) {
        if (!aktiv() || !anbieter().equals("standalone") || zipSha1 == null || webserver == null) return;
        var info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url() + "?" + zipSha1.substring(0, 8)), zipSha1);
        var anfrage = ResourcePackRequest.resourcePackRequest().packs(info).replace(false)
                .required(config().getBoolean("standalone.required", false));
        String hinweis = config().getString("standalone.prompt", "");
        if (!hinweis.isBlank()) anfrage.prompt(Component.text(hinweis));
        p.sendResourcePacks(anfrage.build());
    }
}
