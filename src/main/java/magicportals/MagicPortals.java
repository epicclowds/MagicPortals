package magicportals;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import magicportals.bake.SceneBaker;
import magicportals.exchange.Exchange;
import magicportals.exchange.MySqlExchange;
import magicportals.pack.ResourcePack;
import magicportals.portal.Portal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * MagicPortals – walk-through portals between the servers of a Velocity network, with a live 3D view
 * of the destination drawn by a resource-pack shader.
 * <p>
 * Every server lists its portals in config.yml under "portals" (key = target server). A portal from A to B
 * shows what lies in front of the portal from B back to A, so every portal needs a counterpart.
 * Heartbeats, arrivals, snapshots and scene numbers are shared through a folder or a MySQL database
 * ("exchange"); all access to it happens off the main thread. "/magicportals update" bakes the scenes
 * and delivers the resource pack through Oraxen, ItemsAdder or the built-in web server.
 */
public final class MagicPortals extends JavaPlugin implements Listener, TabExecutor {
    private String server;
    private volatile Exchange exchange;
    private World world;
    private Messages messages = new Messages("en");
    private ResourcePack pack;
    private final List<Portal> portals = new ArrayList<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private long scenesVersion = -1;
    private int tick;
    private volatile boolean updating, syncing;
    private final long startedAt = System.currentTimeMillis();

    @Override public void onEnable() {
        migrateOldConfig();
        saveDefaultConfig();
        pack = new ResourcePack(this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getPluginManager().registerEvents(this, this);
        var command = getCommand("magicportals");
        if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        load();
        pack.startWebServer();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
    }

    @Override public void onDisable() {
        for (Portal p : portals) p.tearDown();
        portals.clear();
        if (pack != null) pack.stopWebServer();
        var ex = exchange;
        if (ex != null) {
            try { ex.signOff(server); } catch (IOException e) { getLogger().warning(e.getMessage()); }
            ex.close();
        }
    }

    // ---------- work off the main thread ----------

    private interface Task<T> { T run(Exchange exchange) throws Exception; }

    /** Runs the task asynchronously and hands the result back on the main thread. */
    private <T> void async(Task<T> task, Consumer<T> then) {
        var ex = exchange;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            T result;
            try { result = task.run(ex); }
            catch (Exception e) { getLogger().warning("Exchange: " + e.getMessage()); return; }
            if (then != null && isEnabled()) Bukkit.getScheduler().runTask(this, () -> then.accept(result));
        });
    }

    private void save(Portal.Capture capture) {
        async(ex -> { saveCapture(ex, capture); return null; }, null);
    }

    private static void saveCapture(Exchange ex, Portal.Capture capture) throws IOException {
        ex.saveSnapshot(capture.key(), Exchange.FRONT, capture.front().toBytes());
        ex.saveSnapshot(capture.key(), Exchange.BACK, capture.back().toBytes());
    }

    private String exchangeName() { return exchange instanceof MySqlExchange ? "MySQL" : "folder"; }

    // ---------- configuration ----------

    /** Takes over the config of the predecessor plugin "Dorfportal" (German keys) once. */
    private void migrateOldConfig() {
        Path current = getDataFolder().toPath().resolve("config.yml");
        Path old = getDataFolder().toPath().getParent().resolve("Dorfportal/config.yml");
        try {
            if (!Files.exists(current) && Files.exists(old)) {
                Files.createDirectories(current.getParent());
                Files.copy(old, current);
                getLogger().info("Took over plugins/Dorfportal/config.yml");
            }
            if (!Files.exists(current)) return;
            var c = YamlConfiguration.loadConfiguration(current.toFile());
            if (convertGermanKeys(c)) {
                c.save(current.toFile());
                getLogger().info("config.yml converted to the MagicPortals format.");
            }
        } catch (IOException e) { getLogger().warning("Could not convert the old config: " + e.getMessage()); }
    }

    /** Converts the German keys of Dorfportal (tore, austausch, stil, ...) into the MagicPortals format. */
    private static boolean convertGermanKeys(FileConfiguration c) {
        boolean changed = false;
        if (c.contains("tor.x") && !c.contains("tore") && !c.contains("portals")) {   // very first version
            String target = c.getString("ziel", "lobby".equals(c.getString("server")) ? "smp" : "lobby");
            for (String k : List.of("x", "y", "z")) c.set("tore." + target + "." + k, c.getInt("tor." + k));
            c.set("tor", null); c.set("ziel", null); c.set("karten", null); c.set("diagnose", null);
        }
        if (c.contains("austausch")) {
            if (c.isString("austausch")) { c.set("exchange.type", "folder"); c.set("exchange.folder", c.getString("austausch")); }
            else {
                var a = c.getConfigurationSection("austausch");
                c.set("exchange.type", "datei".equals(a.getString("art", "datei")) ? "folder" : "mysql");
                c.set("exchange.folder", a.getString("ordner", "../portal-exchange"));
                var m = a.getConfigurationSection("mysql");
                if (m != null) {
                    c.set("exchange.mysql.host", m.getString("host")); c.set("exchange.mysql.port", m.getInt("port", 3306));
                    c.set("exchange.mysql.database", m.getString("datenbank")); c.set("exchange.mysql.username", m.getString("benutzer"));
                    c.set("exchange.mysql.password", m.getString("passwort")); c.set("exchange.mysql.table-prefix", m.getString("tabellen-praefix", "dorfportal_"));
                }
            }
            c.set("austausch", null); changed = true;
        }
        if (c.contains("scheinbloecke")) { c.set("fallback-blocks", c.getBoolean("scheinbloecke")); c.set("scheinbloecke", null); changed = true; }
        if (c.contains("tore")) {
            var gates = c.getConfigurationSection("tore");
            for (String target : gates.getKeys(false)) {
                var g = gates.getConfigurationSection(target);
                String p = "portals." + target + ".";
                if (g.contains("name")) c.set(p + "name", g.getString("name"));
                c.set(p + "style", "riss".equalsIgnoreCase(g.getString("stil", "tor")) ? "rift" : "gate");
                for (String k : List.of("x", "y", "z")) if (g.contains(k)) c.set(p + k, g.getInt(k));
                if (g.contains("rahmen.aussen")) c.set(p + "frame.outer", g.getString("rahmen.aussen"));
                if (g.contains("rahmen.innen")) c.set(p + "frame.inner", g.getString("rahmen.innen"));
            }
            c.set("tore", null); changed = true;
        }
        if (c.contains("backen")) {
            c.set("resource-pack.enabled", true);
            c.set("resource-pack.provider", "auto");
            c.set("resource-pack.client-jar", c.getString("backen.client-jar", ""));
            c.set("backen", null); changed = true;
        }
        if (changed && !c.contains("language")) c.set("language", "de");
        return changed;
    }

    private void load() {
        for (Portal p : portals) p.tearDown();
        portals.clear();
        reloadConfig();
        var c = getConfig();
        messages = new Messages(c.getString("language", "en"));
        server = c.getString("server", "").trim();
        if (server.isEmpty()) throw new IllegalStateException("config.yml: set 'server' to the name of this server in velocity.toml");
        var old = exchange;
        try { exchange = Exchange.fromConfig(c, getLogger()); }
        catch (IOException e) { throw new IllegalStateException("Exchange not reachable: " + e.getMessage(), e); }
        if (old != null) old.close();
        getLogger().info("Exchange: " + exchangeName());
        world = Bukkit.getWorlds().getFirst();

        var section = c.getConfigurationSection("portals");
        if (section != null) for (String target : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(target);
            if (p == null) continue;
            int[] pos;
            if (p.contains("x") && p.contains("y") && p.contains("z")) pos = new int[]{p.getInt("x"), p.getInt("y"), p.getInt("z")};
            else {
                pos = findSpot();
                p.set("x", pos[0]); p.set("y", pos[1]); p.set("z", pos[2]);
                saveConfig();
                getLogger().info("Portal to " + target + " placed automatically at " + pos[0] + " " + pos[1] + " " + pos[2]);
            }
            String name = p.getString("name", target.substring(0, 1).toUpperCase(Locale.ROOT) + target.substring(1));
            String style = p.getString("style", "gate").toLowerCase(Locale.ROOT);
            portals.add(new Portal(this, world, target, name, pos[0], pos[1], pos[2],
                    material(p.getString("frame.outer"), Material.STONE_BRICKS),
                    material(p.getString("frame.inner"), Material.POLISHED_BLACKSTONE_BRICKS),
                    style.equals("rift"), messages.get("offline.sign")));
        }
        scenesVersion = -1;
        for (Portal p : portals) {
            p.build();
            save(p.captureAll(server));
            getLogger().info("Portal " + server + " -> " + p.target + " at " + p.x + " " + p.y + " " + p.z + (p.rift ? " (rift)" : " (gate)"));
        }
        sync();
    }

    private static Material material(String text, Material fallback) {
        Material m = text == null ? null : Material.matchMaterial(text);
        return m != null && m.isBlock() ? m : fallback;
    }

    /** Finds flat, solid ground near the world spawn that is not too close to another portal. */
    private int[] findSpot() {
        Location spawn = world.getSpawnLocation();
        for (int radius = 0; radius <= 64; radius += 4) for (int a = -radius; a <= radius; a += 4)
            for (int b = -radius; b <= radius; b += 4) {
                if (Math.max(Math.abs(a), Math.abs(b)) != radius) continue;
                int px = spawn.getBlockX() + a, pz = spawn.getBlockZ() + b;
                if (portals.stream().anyMatch(p -> p.near(px, pz, 12))) continue;
                int low = 9999, high = -9999;
                boolean ok = true;
                for (int i = -3; i <= 3 && ok; i++) for (int j = -3; j <= 4; j++) {
                    int h = world.getHighestBlockYAt(px + i, pz + j, HeightMap.MOTION_BLOCKING);
                    var m = world.getBlockAt(px + i, h, pz + j).getType();
                    if (!m.isSolid() || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.CACTUS || m == Material.MAGMA_BLOCK) { ok = false; break; }
                    low = Math.min(low, h); high = Math.max(high, h);
                }
                if (ok && high - low <= 1) return new int[]{px, high + 1, pz};
            }
        // no flat ground (e.g. ocean spawn): build a small wooden platform
        int px = spawn.getBlockX() + 8 * portals.size(), pz = spawn.getBlockZ(), high = world.getSeaLevel();
        for (int i = -3; i <= 3; i++) for (int j = -3; j <= 5; j++) high = Math.max(high, world.getHighestBlockYAt(px + i, pz + j));
        for (int i = -3; i <= 3; i++) for (int j = -3; j <= 5; j++) world.getBlockAt(px + i, high + 1, pz + j).setType(Material.SPRUCE_PLANKS, false);
        return new int[]{px, high + 2, pz};
    }

    // ---------- sync with the other servers ----------

    private record SyncResult(Set<String> online, long scenesVersion, Map<String, Integer> scenes,
                              Map<String, byte[]> remoteViews, Map<String, Long> remoteVersions) { }

    /** Every 5 seconds: own heartbeat, who is online, new scene numbers, optionally the other sides' views. */
    private void sync() {
        if (syncing) return;
        syncing = true;
        boolean fallback = getConfig().getBoolean("fallback-blocks", false);
        long knownScenes = scenesVersion;
        var knownRemote = new HashMap<String, Long>();
        for (Portal p : portals) knownRemote.put(p.target, p.remoteVersion());
        String self = server;
        var ex = exchange;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            SyncResult result = null;
            try {
                ex.heartbeat(self);
                Set<String> online = ex.online();
                long version = ex.scenesVersion();
                Map<String, Integer> scenes = version != knownScenes ? ex.scenes() : null;
                var views = new HashMap<String, byte[]>();
                var versions = new HashMap<String, Long>();
                if (fallback) for (var e : knownRemote.entrySet()) {
                    String key = Portal.key(e.getKey(), self);
                    long v = ex.snapshotVersion(key, Exchange.FRONT);
                    if (v != 0 && v != e.getValue()) {
                        byte[] data = ex.loadSnapshot(key, Exchange.FRONT);
                        if (data != null) { views.put(e.getKey(), data); versions.put(e.getKey(), v); }
                    }
                }
                result = new SyncResult(online, version, scenes, views, versions);
            } catch (IOException e) { getLogger().warning("Sync: " + e.getMessage()); }
            var done = result;
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                syncing = false;
                if (done == null || ex != exchange) return;
                for (Portal p : portals) {
                    p.setReachable(done.online().contains(p.target));
                    byte[] view = done.remoteViews().get(p.target);
                    if (view != null) p.setRemoteView(view, done.remoteVersions().get(p.target));
                }
                if (done.scenes() != null) {
                    scenesVersion = done.scenesVersion();
                    for (Portal p : portals) p.setScene(done.scenes().get(Portal.key(p.target, server)));
                }
            });
        });
    }

    private void tick() {
        if (tick % 100 == 0) sync();
        if (tick % 1200 == 600) for (Portal p : portals) p.startCapture();
        for (Portal p : portals) { var done = p.captureStep(server); if (done != null) save(done); }
        if (tick % 2 == 0 && getConfig().getBoolean("fallback-blocks", false))
            for (Player player : world.getPlayers()) for (Portal p : portals) p.updateFallback(player, tick);
        if (tick % 4 == 0) for (Portal p : portals) p.particles();
        tick = (tick + 1) % 1200;
    }

    // ---------- walking through and arriving ----------

    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getWorld() != world || e.getTo().getWorld() != world) return;
        for (Portal p : portals) if (p.walkedThrough(e.getFrom(), e.getTo())) { travel(e.getPlayer(), p); return; }
    }

    private void travel(Player player, Portal portal) {
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(player.getUniqueId(), 0L) > now) return;
        cooldown.put(player.getUniqueId(), now + 5000);
        if (!portal.reachable()) { player.sendMessage(Component.text(messages.get("offline.message", portal.name))); return; }
        UUID id = player.getUniqueId();
        String self = server;
        player.playSound(player.getLocation(), "minecraft:block.portal.travel", .35f, 1.25f);
        // Store the arrival first, then connect, so the target server finds it when the player joins.
        async(ex -> { ex.setArrival(portal.target, id, self); return true; }, ok -> {
            if (!player.isOnline()) return;
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(portal.target); }
            catch (IOException e) { return; }
            player.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && Bukkit.getPlayer(id) == player) player.sendMessage(Component.text(messages.get("connect.failed")));
            }, 100);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        pack.send(player);
        UUID id = player.getUniqueId();
        String self = server;
        async(ex -> Optional.ofNullable(ex.takeArrival(self, id)), origin -> {
            if (origin.isEmpty() || !player.isOnline()) return;
            Portal portal = portals.stream().filter(p -> p.target.equals(origin.get())).findFirst().orElse(portals.isEmpty() ? null : portals.getFirst());
            if (portal == null) return;
            Location arrival = portal.arrival();
            player.teleport(arrival);
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 70, 0, false, false, false));
            player.playSound(arrival, "minecraft:block.amethyst_block.chime", .7f, 1.1f);
            player.playSound(arrival, "minecraft:block.portal.trigger", .25f, 1.6f);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        cooldown.remove(e.getPlayer().getUniqueId());
        for (Portal p : portals) p.reset(e.getPlayer());
    }

    @EventHandler public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() != world) for (Portal p : portals) p.reset(e.getPlayer());
    }

    // ---------- commands ----------

    private Component text(String key, Object... values) { return Component.text(messages.get(key, values)); }
    private Component error(String key, Object... values) { return Component.text(messages.get(key, values), NamedTextColor.RED); }

    /** Maps the old German sub-commands of Dorfportal to the English ones. */
    private static String subCommand(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "liste" -> "list";
            case "setzen" -> "set";
            case "entfernen" -> "remove";
            case "stil" -> "style";
            case "aktualisieren" -> "update";
            case "neuladen" -> "reload";
            default -> input.toLowerCase(Locale.ROOT);
        };
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : subCommand(args[0]);
        switch (sub) {
            case "list" -> {
                sender.sendMessage(text("list.header", server, exchangeName()));
                if (portals.isEmpty()) sender.sendMessage(text("list.none"));
                for (Portal p : portals) sender.sendMessage(Component.text("  → " + p.target + "  " + p.x + " " + p.y + " " + p.z + "  " + messages.get(p.rift ? "style.rift" : "style.gate") + "  ")
                        .append(p.reachable() ? Component.text(messages.get("list.online"), NamedTextColor.GREEN) : Component.text(messages.get("list.offline"), NamedTextColor.RED))
                        .append(Component.text(p.scene() == null ? messages.get("list.noscene") : messages.get("list.scene", p.scene()), NamedTextColor.GRAY)));
            }
            case "set" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(error("only.ingame")); return true; }
                if (args.length < 2) { sender.sendMessage(error("set.usage")); return true; }
                String target = args[1];
                if (target.equals(server) || !target.matches("[A-Za-z0-9_-]+")) { sender.sendMessage(error("set.invalid", target)); return true; }
                Location l = player.getLocation();
                String path = "portals." + target;
                getConfig().set(path + ".x", l.getBlockX());
                getConfig().set(path + ".y", l.getBlockY());
                getConfig().set(path + ".z", l.getBlockZ() - 3);
                if (!getConfig().contains(path + ".style")) getConfig().set(path + ".style", "gate");
                if (args.length >= 3) getConfig().set(path + ".name", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                saveConfig();
                load();
                sender.sendMessage(text("set.done", target));
                sender.sendMessage(text("set.counterpart", target, server));
            }
            case "remove" -> {
                if (args.length < 2 || !getConfig().contains("portals." + args[1])) { sender.sendMessage(error("remove.unknown")); return true; }
                getConfig().set("portals." + args[1], null);
                saveConfig();
                String key = Portal.key(server, args[1]);
                load();
                async(ex -> { ex.deleteSnapshots(key); return null; }, null);
                sender.sendMessage(text("remove.done", args[1]));
            }
            case "style" -> {
                String style = args.length >= 3 ? switch (args[2].toLowerCase(Locale.ROOT)) { case "riss", "rift" -> "rift"; case "tor", "gate" -> "gate"; default -> ""; } : "";
                if (args.length < 3 || !getConfig().contains("portals." + args[1]) || style.isEmpty()) { sender.sendMessage(error("style.usage")); return true; }
                getConfig().set("portals." + args[1] + ".style", style);
                saveConfig();
                load();
                sender.sendMessage(text("style.done", args[1], style));
            }
            case "reload" -> { load(); pack.startWebServer(); sender.sendMessage(text("reload.done", portals.size())); }
            case "update" -> update(sender);
            default -> {
                for (String help : List.of("help.list", "help.set", "help.remove", "help.style", "help.update", "help.reload")) sender.sendMessage(text(help));
            }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return List.of("list", "set", "remove", "style", "update", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        String sub = subCommand(args[0]);
        if (args.length == 2 && (sub.equals("remove") || sub.equals("style"))) return portals.stream().map(p -> p.target).toList();
        if (args.length == 3 && sub.equals("style")) return List.of("gate", "rift");
        return List.of();
    }

    /** Bakes all scenes and delivers the resource pack. Only on the server with resource-pack.enabled. */
    private void update(CommandSender sender) {
        if (!pack.enabled()) { sender.sendMessage(error("update.disabled")); return; }
        if (updating) { sender.sendMessage(error("update.running")); return; }
        // Capture this server's portals right now; other servers capture every minute.
        var own = new ArrayList<Portal.Capture>();
        for (Portal p : portals) own.add(p.captureAll(server));
        updating = true;
        sender.sendMessage(text("update.start"));
        Path work = getDataFolder().toPath().resolve("bake");
        String provider = pack.provider();
        var ex = exchange;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                for (var capture : own) saveCapture(ex, capture);
                Path jar = pack.clientJar(version -> Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(text("update.jar", version))));
                Path output = work.resolve("output");
                if (Files.exists(output)) try (var old = Files.walk(output)) { old.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                var log = new ArrayList<String>();
                Map<String, Integer> numbers = SceneBaker.bake(ex.exportForBaking(work.resolve("input")), jar, output, log::add);
                for (String line : log) getLogger().info(line);
                ex.setScenes(numbers);
                pack.build(output);
                String afterwards = pack.deliver(output);
                // Right after a server start Oraxen is still building its own pack; a reload during that
                // time is lost. So reload at the earliest 60 s after start.
                long delay = "oraxen".equals(provider) ? Math.max(1, (60_000 - (System.currentTimeMillis() - startedAt)) / 50) : 1;
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(text("update.done", numbers.size(), provider));
                    if (delay > 20) sender.sendMessage(text("update.wait", provider));
                    scenesVersion = -1;
                    sync();
                });
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    updating = false;
                    if (afterwards != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), afterwards);
                }, delay);
            } catch (Exception e) {
                getLogger().warning("Update failed: " + e);
                Bukkit.getScheduler().runTask(this, () -> { updating = false; sender.sendMessage(error("update.failed", String.valueOf(e.getMessage()))); });
            }
        });
    }
}
