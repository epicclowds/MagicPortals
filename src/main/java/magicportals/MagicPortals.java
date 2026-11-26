package magicportals;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import magicportals.bake.SceneBaker;
import magicportals.exchange.Exchange;
import magicportals.exchange.MySqlExchange;
import magicportals.pack.ResourcePack;
import magicportals.portal.Facing;
import magicportals.portal.Portal;
import magicportals.portal.Style;
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
        createSoundFolder();
        pack = new ResourcePack(this);
        setup = new SetupAssistant(this);
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

    interface Task<T> { T run(Exchange exchange) throws Exception; }

    <T> void runAsync(Task<T> task) { async(task, null); }
    <T> void runAsync(Task<T> task, Consumer<T> then) { async(task, then); }
    org.bukkit.inventory.ItemStack wandItem() { return wand(); }

    private SetupAssistant setup;
    private void setupOnJoin(Player player) { setup.onJoin(player); }

    /** Exchange key of the "please rebuild the pack" request (set by the setup assistant on any server). */
    static final String UPDATE_REQUEST = "network@update";
    private boolean updateScheduled;

    /** Rebuilds the pack soon: here if this is the pack server, else via a request the pack server picks up. */
    void requestUpdate() {
        if (pack.enabled()) scheduleUpdate();
        else runAsync(ex -> { ex.saveSnapshot(UPDATE_REQUEST, "request", Long.toString(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8)); return null; });
    }

    /** Runs /mp update in 15 s – time for the new portals to store their snapshots and far terrain. */
    private void scheduleUpdate() {
        if (updateScheduled) return;
        updateScheduled = true;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            updateScheduled = false;
            if (updating) { scheduleUpdate(); return; }
            update(Bukkit.getConsoleSender());
        }, 300);
    }

    /** Runs the task asynchronously and hands the result back on the main thread. */
    private int farMinutes;

    /** Captures the far terrain of a portal and stores it (kind "far"), every 5 minutes and on (re)load. */
    private void saveFar(Portal p) {
        String key = p.ownKey(server);
        p.captureFar().thenAccept(far -> async(ex -> { ex.saveSnapshot(key, Exchange.FAR, far.toBytes()); return null; }, null))
                .exceptionally(e -> { getLogger().warning("Far terrain of " + key + ": " + e); return null; });
    }

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

    /** Creates plugins/MagicPortals/sounds with a short explanation on first start. */
    private void createSoundFolder() {
        Path folder = getDataFolder().toPath().resolve("sounds");
        try {
            if (Files.isDirectory(folder)) return;
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("README.txt"), """
                    Custom portal sounds (only needed on the server with resource-pack.enabled: true)

                    Put .ogg files (Ogg Vorbis, mono for sounds that come from the portal) into this folder.
                    Lowercase letters, digits, _ - . only. Subfolders are fine.

                      storm/thunder1.ogg  ->  magicportals:storm/thunder1
                      storm/thunder2.ogg  ->  magicportals:storm/thunder2
                                              magicportals:storm  (one of the files in storm/ at random)

                    Then run /mp update and use them, e.g.  /mp edit lobby sound.strike magicportals:storm
                    Slots: ambient, hum, accent, crackle, strike, thunder, enter. Sounds must be on (sound: true).
                    """);
        } catch (IOException e) { getLogger().warning("Could not create " + folder + ": " + e.getMessage()); }
    }

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
        var viewData = new LinkedHashMap<String, byte[]>();
        if (section != null) for (String id : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(id);
            if (p == null) continue;
            // "target" (server) defaults to the portal name: classic configs keyed by target server keep working
            String target = p.getString("target", id), link = p.getString("link");
            if (link != null && (link.isBlank() || link.equalsIgnoreCase("none"))) link = null;
            viewData.put(Portal.key(target, server, link), viewSettings(p).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int[] pos;
            if (p.contains("x") && p.contains("y") && p.contains("z")) pos = new int[]{p.getInt("x"), p.getInt("y"), p.getInt("z")};
            else {
                pos = findSpot();
                p.set("x", pos[0]); p.set("y", pos[1]); p.set("z", pos[2]);
                saveConfig();
                getLogger().info("Portal to " + target + " placed automatically at " + pos[0] + " " + pos[1] + " " + pos[2]);
            }
            String name = p.getString("name", id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1));
            Style style = Style.parse(p.getString("style", "gate"));
            if (style == null) { getLogger().warning("Unknown style for portal " + target + ": " + p.getString("style") + " – using gate"); style = Style.GATE; }
            boolean noFrame = "none".equalsIgnoreCase(p.getString("frame"));
            World portalWorld = p.contains("world") ? Bukkit.getWorld(p.getString("world")) : world;
            if (portalWorld == null) { getLogger().warning("Portal to " + target + ": world " + p.getString("world") + " not found – skipped"); continue; }
            Portal portal = new Portal(this, portalWorld, id, target, link, name, pos[0], pos[1], pos[2],
                    Facing.parse(p.getString("facing", "south")), p.getInt("width", 3), p.getInt("height", 3),
                    noFrame ? null : material(p.getString("frame.outer"), Material.STONE_BRICKS),
                    noFrame ? null : material(p.getString("frame.inner"), Material.POLISHED_BLACKSTONE_BRICKS),
                    style, p.getBoolean("inside-view", false), p.getBoolean("barriers", false), p.getBoolean("label", false),
                    p.getBoolean("sound", false), (float) p.getDouble("volume", 1.0), p.getDouble("particles", 1.0), p.getBoolean("effects", false),
                    messages.get("offline.sign"));
            var sounds = p.getConfigurationSection("sounds");
            if (sounds != null) for (String slot : Portal.SOUND_SLOTS) {
                String s = sounds.getString(slot);
                if (s != null && !s.isBlank()) portal.customSounds.put(slot, s.trim().toLowerCase(Locale.ROOT));
            }
            portals.add(portal);
        }
        // the view settings belong to the scene this portal shows, i.e. the snapshot "<target>@<server>"
        async(ex -> { for (var e : viewData.entrySet()) ex.saveSnapshot(e.getKey(), Exchange.VIEW, e.getValue()); return null; }, null);
        scenesVersion = -1;
        for (Portal p : portals) {
            p.build();
            save(p.captureAll(server));
            saveFar(p);
            getLogger().info("Portal " + p.id + ": " + server + " -> " + p.target + (p.link == null ? "" : " #" + p.link) + " at " + p.x + " " + p.y + " " + p.z + " (" + p.style.id + ")");
        }
        sync();
    }

    /** Settings of the view through a portal (section "view"). */
    static final List<String> VIEW_KEYS = List.of("time", "fog", "fog-color", "tint", "tint-strength", "weather", "blur");

    /** The view settings of a portal as text ("time=night;fog=1.5;…"), read by the scene baker. */
    private static String viewSettings(ConfigurationSection portal) {
        var view = portal.getConfigurationSection("view");
        if (view == null) return "";
        var parts = new ArrayList<String>();
        for (String key : VIEW_KEYS) if (view.contains(key)) parts.add(key + "=" + view.get(key));
        return String.join(";", parts);
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

    private record SyncResult(Set<String> online, long scenesVersion, Map<String, Integer> scenes, boolean updateRequested,
                              Map<String, byte[]> remoteViews, Map<String, Long> remoteVersions) { }

    /** Every 5 seconds: own heartbeat, who is online, new scene numbers, optionally the other sides' views. */
    private void sync() {
        if (syncing) return;
        syncing = true;
        boolean fallback = getConfig().getBoolean("fallback-blocks", false);
        boolean packServer = pack.enabled();
        long knownScenes = scenesVersion;
        var knownRemote = new HashMap<String, Long>();
        for (Portal p : portals) knownRemote.put(p.sceneKey(server), p.remoteVersion());
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
                    String key = e.getKey();
                    long v = ex.snapshotVersion(key, Exchange.FRONT);
                    if (v != 0 && v != e.getValue()) {
                        byte[] data = ex.loadSnapshot(key, Exchange.FRONT);
                        if (data != null) { views.put(e.getKey(), data); versions.put(e.getKey(), v); }
                    }
                }
                // a setup on another server asks the pack server to rebuild the pack
                boolean requested = packServer && ex.snapshotVersion(UPDATE_REQUEST, "request") != 0;
                if (requested) ex.deleteSnapshots(UPDATE_REQUEST);
                result = new SyncResult(online, version, scenes, requested, views, versions);
            } catch (IOException e) { getLogger().warning("Sync: " + e.getMessage()); }
            var done = result;
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                syncing = false;
                if (done == null || ex != exchange) return;
                for (Portal p : portals) {
                    p.setReachable(done.online().contains(p.target));
                    onlineServers = done.online();
                    byte[] view = done.remoteViews().get(p.sceneKey(server));
                    if (view != null) p.setRemoteView(view, done.remoteVersions().get(p.sceneKey(server)));
                }
                if (done.updateRequested()) { getLogger().info("Pack rebuild requested by a portal setup"); scheduleUpdate(); }
                if (done.scenes() != null) {
                    scenesVersion = done.scenesVersion();
                    for (Portal p : portals) p.setScene(done.scenes().get(p.sceneKey(server)));
                }
            });
        });
    }

    private void tick() {
        if (tick % 100 == 0) sync();
        if (tick % 1200 == 600) for (Portal p : portals) p.startCapture();
        if (tick == 900 && ++farMinutes % 5 == 0) for (Portal p : portals) saveFar(p);
        for (Portal p : portals) { var done = p.captureStep(server); if (done != null) save(done); }
        if (tick % 2 == 0 && getConfig().getBoolean("fallback-blocks", false))
            for (Player player : Bukkit.getOnlinePlayers()) for (Portal p : portals) p.updateFallback(player, tick);
        if (tick % 4 == 0) for (Portal p : portals) p.particles();
        tick = (tick + 1) % 1200;
    }

    // ---------- walking through and arriving ----------

    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;
        for (Portal p : portals) if (p.walkedThrough(e.getFrom(), e.getTo())) { travel(e.getPlayer(), p); return; }
    }

    private void travel(Player player, Portal portal) {
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(player.getUniqueId(), 0L) > now) return;
        cooldown.put(player.getUniqueId(), now + 5000);
        if (!portal.reachable()) { player.sendMessage(error("offline.message", portal.name)); return; }
        portal.playEnter(player);
        UUID id = player.getUniqueId();
        String self = server;
        player.playSound(player.getLocation(), "minecraft:block.portal.travel", .35f, 1.25f);
        // Store the arrival first, then connect, so the target server finds it when the player joins.
        // origin "<server>#<link>": the target server places the player at the portal with the same link
        String origin = portal.link == null ? self : self + "#" + portal.link;
        async(ex -> { ex.setArrival(portal.target, id, origin); return true; }, ok -> {
            if (!player.isOnline()) return;
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(portal.target); }
            catch (IOException e) { return; }
            player.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && Bukkit.getPlayer(id) == player) player.sendMessage(error("connect.failed"));
            }, 100);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        pack.send(player);
        UUID id = player.getUniqueId();
        String self = server;
        async(ex -> Optional.ofNullable(ex.takeArrival(self, id)), origin -> {
            if (!player.isOnline()) return;
            if (origin.isEmpty()) { setupOnJoin(player); return; }
            Portal portal = counterpart(origin.get());
            if (portal == null) {
                // linked portal not set up on this server yet: the player stays at the spawn
                if (origin.get().contains("#")) player.sendMessage(text("link.missing", origin.get().substring(origin.get().indexOf('#') + 1)));
                setupOnJoin(player);
                return;
            }
            Location arrival = portal.arrival();
            player.teleport(arrival);
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 70, 0, false, false, false));
            player.playSound(arrival, "minecraft:block.amethyst_block.chime", .7f, 1.1f);
            player.playSound(arrival, "minecraft:block.portal.trigger", .25f, 1.6f);
            setupOnJoin(player);
        });
    }

    /** The portal on this server that is the counterpart of a portal on origin ("<server>" or "<server>#<link>"). */
    private Portal counterpart(String origin) {
        int hash = origin.indexOf('#');
        String from = hash < 0 ? origin : origin.substring(0, hash), link = hash < 0 ? null : origin.substring(hash + 1);
        for (Portal p : portals) if (p.target.equals(from) && Objects.equals(p.link, link)) return p;
        if (link != null) return null;
        // classic pairing by server name
        for (Portal p : portals) if (p.target.equals(from)) return p;
        return portals.isEmpty() ? null : portals.getFirst();
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        cooldown.remove(e.getPlayer().getUniqueId());
        for (Portal p : portals) p.reset(e.getPlayer());
    }

    @EventHandler public void onTeleport(PlayerTeleportEvent e) {
        for (Portal p : portals) if (e.getTo().getWorld() != p.world()) p.reset(e.getPlayer());
    }

    // ---------- portal wand ----------

    private final Map<UUID, Location[]> selections = new HashMap<>();
    private NamespacedKey wandKey() { return new NamespacedKey(this, "wand"); }

    /** The selection tool: a glowing golden pickaxe tagged with magicportals:wand. */
    private org.bukkit.inventory.ItemStack wand() {
        var item = new org.bukkit.inventory.ItemStack(Material.GOLDEN_PICKAXE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Portal Wand", NamedTextColor.LIGHT_PURPLE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(messages.get("wand.lore1"), NamedTextColor.GRAY), Component.text(messages.get("wand.lore2"), NamedTextColor.GRAY)));
            meta.setEnchantmentGlintOverride(true);
            // own model from the MagicPortals resource pack (a staff with a glowing crystal); off: plain golden pickaxe
            if (getConfig().getBoolean("wand-model", true)) meta.setItemModel(new org.bukkit.NamespacedKey("magicportals", "wand"));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(wandKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isWand(org.bukkit.inventory.ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey());
    }

    @EventHandler public void onWandClick(PlayerInteractEvent e) {
        if (!isWand(e.getItem()) || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!e.getPlayer().hasPermission("magicportals.admin")) return;
        boolean left = e.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
        if (!left && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Location block = e.getClickedBlock().getLocation();
        Location[] sel = selections.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new Location[2]);
        sel[left ? 0 : 1] = block;
        e.getPlayer().sendMessage(text(left ? "wand.pos1" : "wand.pos2", block.getBlockX(), block.getBlockY(), block.getBlockZ()));
        if (sel[0] != null && sel[1] != null) { showSelection(e.getPlayer(), sel[0], sel[1]); setup.selectionChanged(e.getPlayer()); }
    }

    @EventHandler(ignoreCancelled = true) public void onWandBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    /** Servers seen in the network (heartbeats) plus the targets of this server's portals. */
    Set<String> knownServers() {
        var known = new TreeSet<>(onlineServers);
        for (Portal p : portals) known.add(p.target);
        known.remove(server);
        return known;
    }

    private Set<String> onlineServers = Set.of();

    /**
     * Creates or moves a portal: into the opening marked with the wand (facing the player's side), otherwise
     * 3 blocks in front of the player. id = name in the config, target = server, link = connection ID shared
     * with the counterpart (null = classic pairing). Returns false and tells the player why if it fails.
     */
    boolean createPortal(Player player, String id, String target, String link, String display) {
        if (!id.matches("[A-Za-z0-9_-]{1,32}") || target.equals(server) || !target.matches("[A-Za-z0-9_-]+")) {
            player.sendMessage(error("set.invalid", target.equals(server) ? target : id)); return false;
        }
        if (link != null && !link.matches("[A-Za-z0-9_-]{1,32}")) { player.sendMessage(error("link.invalid", link)); return false; }
        for (Portal p : portals)
            if (!p.id.equals(id) && p.target.equals(target) && Objects.equals(p.link, link)) {
                player.sendMessage(error(link == null ? "set.duplicate" : "link.taken", target, link == null ? p.id : link)); return false;
            }
        String path = "portals." + id;
        Location[] sel = selections.get(player.getUniqueId());
        if (sel != null && sel[0] != null && sel[1] != null) {
            // opening marked with the wand: exactly these blocks, no frame (built by hand), facing the side the player stands on
            Location a = sel[0], b = sel[1];
            Placement pl = a.getWorld() == b.getWorld() && a.getWorld() == player.getWorld() ? placement(a, b, player.getLocation()) : null;
            if (pl == null) { player.sendMessage(error("wand.flat")); return false; }
            if (pl.width > Portal.MAX_SIZE || pl.height > Portal.MAX_SIZE) { player.sendMessage(error("wand.size", Portal.MAX_SIZE)); return false; }
            getConfig().set(path + ".x", pl.x);
            getConfig().set(path + ".y", pl.y);
            getConfig().set(path + ".z", pl.z);
            getConfig().set(path + ".facing", pl.facing.name().toLowerCase(Locale.ROOT));
            getConfig().set(path + ".width", pl.width);
            getConfig().set(path + ".height", pl.height);
            getConfig().set(path + ".frame", "none");
            selections.remove(player.getUniqueId());
            player.sendMessage(text("set.selection", id, pl.width, pl.height, facingName(pl.facing)));
        } else {
            // 3 blocks in the look direction, the opening facing the player
            Location l = player.getLocation();
            var look = l.getDirection();
            Facing facing = Facing.towards(-look.getX(), -look.getZ());
            getConfig().set(path + ".x", l.getBlockX() - 3 * facing.zx);
            getConfig().set(path + ".y", l.getBlockY());
            getConfig().set(path + ".z", l.getBlockZ() - 3 * facing.zz);
            getConfig().set(path + ".facing", facing.name().toLowerCase(Locale.ROOT));
            player.sendMessage(text("set.done", id, facingName(facing)));
        }
        // the portal stands in the world the player is in (overworld, nether, end, any other world)
        getConfig().set(path + ".world", player.getWorld().equals(Bukkit.getWorlds().getFirst()) ? null : player.getWorld().getName());
        getConfig().set(path + ".target", id.equals(target) ? null : target);
        getConfig().set(path + ".link", link);
        if (!getConfig().contains(path + ".style")) getConfig().set(path + ".style", "gate");
        if (display != null) getConfig().set(path + ".name", display);
        saveConfig();
        load();
        return true;
    }

    /** Sets a single option of a portal in the config (used by the setup assistant) without reloading. */
    void setPortalOption(String id, String key, Object value) { getConfig().set("portals." + id + "." + key, value); }
    void saveAndReload() { saveConfig(); load(); }
    String serverName() { return server; }
    Messages messages() { return messages; }
    Map<UUID, Location[]> selections() { return selections; }
    List<Portal> portals() { return portals; }
    ResourcePack pack() { return pack; }
    Exchange exchange() { return exchange; }
    Component textOf(String key, Object... values) { return text(key, values); }
    Component errorOf(String key, Object... values) { return error(key, values); }

    /** Sends a player to another server of the network. */
    void connect(Player player, String target) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(target); }
        catch (IOException e) { return; }
        player.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
    }

    /** A portal placed into a marked opening: anchor cell (config x/y/z), size and facing. */
    private record Placement(int x, int y, int z, int width, int height, Facing facing) { }

    /**
     * The opening between two corner blocks lies in a plane of constant z (opening faces south or north)
     * or constant x (east or west). It faces the side the player stands on. Null if the corners do not lie
     * in one plane.
     */
    private static Placement placement(Location a, Location b, Location player) {
        int minX = Math.min(a.getBlockX(), b.getBlockX()), maxX = Math.max(a.getBlockX(), b.getBlockX());
        int minY = Math.min(a.getBlockY(), b.getBlockY()), maxY = Math.max(a.getBlockY(), b.getBlockY());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()), maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        int h = maxY - minY + 1;
        double dx = player.getX() - (minX + maxX + 1) / 2.0, dz = player.getZ() - (minZ + maxZ + 1) / 2.0;
        boolean zPlane = minZ == maxZ, xPlane = minX == maxX;
        if (zPlane && xPlane) { zPlane = Math.abs(dz) >= Math.abs(dx); xPlane = !zPlane; }   // 1 block wide
        if (zPlane) {
            int w = maxX - minX + 1, half = (w - 1) / 2;
            return dz > 0 ? new Placement(minX + half, minY, minZ, w, h, Facing.SOUTH) : new Placement(maxX - half, minY, minZ, w, h, Facing.NORTH);
        }
        if (xPlane) {
            int w = maxZ - minZ + 1, half = (w - 1) / 2;
            return dx > 0 ? new Placement(minX, minY, maxZ - half, w, h, Facing.EAST) : new Placement(minX, minY, minZ + half, w, h, Facing.WEST);
        }
        return null;
    }

    private String facingName(Facing facing) { return messages.get("facing." + facing.name().toLowerCase(Locale.ROOT)); }

    /** Outlines the marked opening with particles for a few seconds and reports its size. */
    private void showSelection(Player player, Location a, Location b) {
        int minX = Math.min(a.getBlockX(), b.getBlockX()), maxX = Math.max(a.getBlockX(), b.getBlockX()) + 1;
        int minY = Math.min(a.getBlockY(), b.getBlockY()), maxY = Math.max(a.getBlockY(), b.getBlockY()) + 1;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()), maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + 1;
        boolean zPlane = maxZ - minZ == 1, xPlane = maxX - minX == 1;
        if (!zPlane && !xPlane) { player.sendMessage(error("wand.flat")); return; }
        boolean alongX = zPlane;   // a single block column counts as a z plane here
        // the outline runs through the middle of the marked blocks
        double u0 = alongX ? minX : minZ, u1 = alongX ? maxX : maxZ, fixed = alongX ? minZ + .5 : minX + .5;
        player.sendMessage(text("wand.ready", (int) (u1 - u0), maxY - minY));
        var dust = new Particle.DustOptions(Color.fromRGB(255, 77, 242), 1f);
        for (int t = 0; t < 6; t++) Bukkit.getScheduler().runTaskLater(this, () -> {
            for (double s = 0; s <= 1.001; s += .05) {
                double pu = u0 + (u1 - u0) * s, py = minY + (maxY - minY) * s;
                for (double[] p : new double[][]{{pu, minY}, {pu, maxY}, {u0, py}, {u1, py}})
                    if (alongX) player.spawnParticle(Particle.DUST, p[0], p[1], fixed, 1, 0, 0, 0, 0, dust);
                    else player.spawnParticle(Particle.DUST, fixed, p[1], p[0], 1, 0, 0, 0, 0, dust);
            }
        }, t * 10L);
    }

    // ---------- commands ----------

    private Component text(String key, Object... values) {
        // confirmations ("….done") in green, everything else in the normal text colour
        return key.endsWith(".done") ? Chat.success(messages.get(key), values) : Chat.info(messages.get(key), values);
    }
    private Component error(String key, Object... values) { return Chat.error(messages.get(key), values); }

    /** Maps the old German sub-commands of Dorfportal to the English ones. */
    private static String subCommand(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "liste" -> "list";
            case "setzen" -> "set";
            case "entfernen" -> "remove";
            case "stil" -> "style";
            case "bearbeiten" -> "edit";
            case "aktualisieren" -> "update";
            case "neuladen" -> "reload";
            case "einrichten" -> "setup";
            default -> input.toLowerCase(Locale.ROOT);
        };
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : subCommand(args[0]);
        switch (sub) {
            case "list" -> {
                var lines = new ArrayList<Component>();
                if (portals.isEmpty()) lines.add(Component.text(messages.get("list.none").trim(), Chat.TEXT));
                for (Portal p : portals) {
                    // ● name → target #id          (green = target online)
                    //     x y z [world] · facing · style · scene
                    Component head = Component.text("● ", p.reachable() ? Chat.SUCCESS : Chat.ERROR)
                            .append(Chat.bold(p.id, NamedTextColor.WHITE));
                    if (!(p.id.equals(p.target) && p.link == null)) head = head.append(Component.text("  → ", NamedTextColor.DARK_GRAY)).append(Component.text(p.target, Chat.ACCENT));
                    if (p.link != null) head = head.append(Component.text("  #" + p.link, Chat.VALUE));
                    lines.add(head);
                    String where = p.x + " " + p.y + " " + p.z + (p.world() == world ? "" : " [" + p.world().getName() + "]");
                    lines.add(Component.text("    " + where + "  ·  " + facingName(p.facing) + "  ·  " + messages.get("style." + p.style.id) + "  ·  ", Chat.TEXT)
                            .append(p.reachable() ? Component.text(messages.get("list.online"), Chat.SUCCESS) : Component.text(messages.get("list.offline"), Chat.ERROR))
                            .append(Component.text("  ·  " + (p.scene() == null ? messages.get("list.noscene") : messages.get("list.scene", p.scene())).trim(), Chat.TEXT)));
                }
                Chat.box(sender, "MagicPortals", Chat.format(messages.get("list.header"), new Object[]{server, exchangeName()}, Chat.TEXT, Chat.VALUE), lines);
            }
            case "set" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(error("only.ingame")); return true; }
                if (args.length < 2) { sender.sendMessage(error("set.usage")); return true; }
                // /mp set <name> <server> [id] [display name]   – linked portal
                // /mp set <server> [display name]               – classic: name = server, paired with "the portal back to me"
                boolean linked = args.length >= 3 && knownServers().contains(args[2]);
                String id = args[1], target = linked ? args[2] : args[1];
                String link = linked && args.length >= 4 ? args[3] : null;
                int nameFrom = linked ? 4 : 2;
                String display = args.length > nameFrom ? String.join(" ", Arrays.copyOfRange(args, nameFrom, args.length)) : null;
                if (!createPortal(player, id, target, link, display)) return true;
                if (link != null)
                    sender.sendMessage(text("set.linked", id, target, link, server));
                else sender.sendMessage(text("set.counterpart", target, server));
            }
            case "wand" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(error("only.ingame")); return true; }
                player.getInventory().addItem(wand());
                sender.sendMessage(text("wand.given"));
            }
            case "remove" -> {
                if (args.length < 2 || !getConfig().contains("portals." + args[1])) { sender.sendMessage(error("remove.unknown")); return true; }
                ConfigurationSection before = getConfig().getConfigurationSection("portals." + args[1]);
                String key = Portal.key(server, before == null ? args[1] : before.getString("target", args[1]), before == null ? null : before.getString("link"));
                getConfig().set("portals." + args[1], null);
                saveConfig();
                load();
                async(ex -> { ex.deleteSnapshots(key); return null; }, null);
                sender.sendMessage(text("remove.done", args[1]));
            }
            case "style" -> {
                Style parsed = args.length >= 3 ? Style.parse(args[2]) : null;
                String style = parsed == null ? "" : parsed.id;
                if (args.length < 3 || !getConfig().contains("portals." + args[1]) || style.isEmpty()) { sender.sendMessage(error("style.usage")); return true; }
                getConfig().set("portals." + args[1] + ".style", style);
                saveConfig();
                load();
                sender.sendMessage(text("style.done", args[1], style));
            }
            case "setup" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(error("only.ingame")); return true; }
                setup.command(player, Arrays.copyOfRange(args, 1, args.length));
            }
            case "edit" -> edit(sender, args);
            case "reload" -> { load(); pack.startWebServer(); sender.sendMessage(text("reload.done", portals.size())); }
            case "update" -> update(sender);
            default -> {
                var lines = new ArrayList<Component>();
                for (String help : List.of("help.setup", "help.list", "help.wand", "help.set", "help.edit", "help.remove", "help.style", "help.update", "help.reload")) {
                    // "/mp command – description": command clickable (fills the chat line), description below in grey
                    String line = messages.get(help);
                    int dash = line.indexOf(" – ");
                    String command = (dash < 0 ? line : line.substring(0, dash)).replace("/magicportals ", "/mp ");
                    String base = command.contains(" <") ? command.substring(0, command.indexOf(" <")) : command.contains(" [") ? command.substring(0, command.indexOf(" [")) : command;
                    lines.add(Component.text(command, Chat.ACCENT).clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(base + " "))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(base, Chat.TEXT))));
                    if (dash >= 0) lines.add(Component.text("   " + line.substring(dash + 3), Chat.TEXT));
                }
                Chat.box(sender, "MagicPortals", Component.text(messages.get("help.title"), Chat.TEXT), lines);
            }
        }
        return true;
    }

    /** Settings that /mp edit can change, with their possible values (for tab completion). */
    private static final Map<String, List<String>> EDITABLE = new LinkedHashMap<>();
    static {
        EDITABLE.put("name", List.of());
        EDITABLE.put("target", List.of());
        EDITABLE.put("link", List.of("none"));
        EDITABLE.put("style", Style.ids());
        EDITABLE.put("facing", List.of("south", "north", "east", "west"));
        EDITABLE.put("width", List.of("1", "2", "3", "4", "5", "6", "7"));
        EDITABLE.put("height", List.of("1", "2", "3", "4", "5", "6", "7"));
        EDITABLE.put("inside-view", List.of("true", "false"));
        EDITABLE.put("barriers", List.of("true", "false"));
        EDITABLE.put("label", List.of("true", "false"));
        EDITABLE.put("sound", List.of("true", "false"));
        EDITABLE.put("volume", List.of("0.25", "0.5", "1", "1.5", "2"));
        EDITABLE.put("particles", List.of("0", "0.5", "1", "2", "3", "5"));
        EDITABLE.put("effects", List.of("true", "false"));
        EDITABLE.put("frame", List.of("none", "wall"));
        // the view through the portal (needs /mp update)
        EDITABLE.put("time", List.of("day", "sunset", "night"));
        EDITABLE.put("fog", List.of("0", "0.5", "1", "2", "4", "8"));
        EDITABLE.put("fog-color", List.of("none", "blue", "red", "green", "purple", "orange", "white", "#3050ff"));
        EDITABLE.put("tint", List.of("none", "blue", "red", "green", "purple", "orange", "yellow", "#ff6040"));
        EDITABLE.put("tint-strength", List.of("0.25", "0.5", "0.75", "1"));
        EDITABLE.put("weather", List.of("none", "rain", "snow", "ash", "embers"));
        EDITABLE.put("blur", List.of("0", "0.5", "1", "1.5", "2"));
        // custom sounds per slot, see Portal.SOUND_SLOTS
        for (String slot : Portal.SOUND_SLOTS) EDITABLE.put("sound." + slot, List.of("default", "none"));
    }

    private static final Map<String, String> COLOR_NAMES = Map.ofEntries(
            Map.entry("blue", "#3060ff"), Map.entry("blau", "#3060ff"), Map.entry("red", "#ff3020"), Map.entry("rot", "#ff3020"),
            Map.entry("green", "#30e060"), Map.entry("grün", "#30e060"), Map.entry("purple", "#a040ff"), Map.entry("lila", "#a040ff"),
            Map.entry("orange", "#ff8020"), Map.entry("yellow", "#ffe040"), Map.entry("gelb", "#ffe040"), Map.entry("white", "#ffffff"),
            Map.entry("weiß", "#ffffff"), Map.entry("pink", "#ff60c0"), Map.entry("cyan", "#40e0ff"), Map.entry("grey", "#808080"),
            Map.entry("gray", "#808080"), Map.entry("grau", "#808080"), Map.entry("black", "#101010"), Map.entry("schwarz", "#101010"));

    /** Custom sounds from the sounds folder as "magicportals:<name>" (only on the resource-pack server). */
    private List<String> customSoundNames() {
        try { return pack.soundEvents().keySet().stream().map(n -> "magicportals:" + n).toList(); }
        catch (IOException e) { return List.of(); }
    }

    /**
     * A sound key for /mp edit sound.<slot>: "default", "none", "namespace:path", or a bare name – a file from
     * the sounds folder becomes "magicportals:<name>", anything else "minecraft:<name>". Null if invalid.
     */
    private String soundKey(String value) {
        if (List.of("default", "reset", "standard").contains(value)) return "default";
        if (List.of("none", "off", "aus", "silent").contains(value)) return "none";
        if (!value.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        if (value.contains(":")) return value;
        return customSoundNames().contains("magicportals:" + value) ? "magicportals:" + value : "minecraft:" + value;
    }

    /** A colour as "#rrggbb" from a name or hex code; "none" to remove; null if invalid. */
    private static String color(String value) {
        if (List.of("none", "off", "aus", "default").contains(value)) return "none";
        String hex = COLOR_NAMES.getOrDefault(value, value.startsWith("#") ? value : "#" + value);
        return hex.matches("#[0-9a-f]{6}") ? hex : null;
    }

    /** /mp edit <server> [setting] [value]: shows or changes a setting of an existing portal. */
    private void edit(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(error("edit.usage")); return; }
        String path = "portals." + args[1];
        if (!getConfig().isConfigurationSection(path)) { sender.sendMessage(error("remove.unknown")); return; }
        Portal portal = portals.stream().filter(p -> p.id.equals(args[1])).findFirst().orElse(null);
        if (args.length < 4) {
            // show the current settings
            var c = getConfig();
            var lines = new ArrayList<Component>();
            for (String key : EDITABLE.keySet()) {
                if (key.startsWith("sound.")) continue;   // shown below, only the ones that are set
                Object value = switch (key) {
                    case "name" -> portal != null ? portal.name : c.getString(path + ".name", args[1]);
                    case "target" -> c.getString(path + ".target", args[1]);
                    case "link" -> c.getString(path + ".link", "none");
                    case "style" -> c.getString(path + ".style", "gate");
                    case "facing" -> c.getString(path + ".facing", "south");
                    case "width" -> c.getInt(path + ".width", 3);
                    case "height" -> c.getInt(path + ".height", 3);
                    case "inside-view" -> c.getBoolean(path + ".inside-view", false);
                    case "barriers" -> c.getBoolean(path + ".barriers", false);
                    case "label" -> c.getBoolean(path + ".label", false);
                    case "sound" -> c.getBoolean(path + ".sound", false);
                    case "volume" -> c.getDouble(path + ".volume", 1.0);
                    case "particles" -> c.getDouble(path + ".particles", 1.0);
                    case "effects" -> c.getBoolean(path + ".effects", false);
                    case "time" -> c.getString(path + ".view.time", "day");
                    case "fog" -> c.getDouble(path + ".view.fog", 1.0);
                    case "fog-color" -> c.getString(path + ".view.fog-color", "none");
                    case "tint" -> c.getString(path + ".view.tint", "none");
                    case "tint-strength" -> c.getDouble(path + ".view.tint-strength", 0.5);
                    case "weather" -> c.getString(path + ".view.weather", "none");
                    case "blur" -> c.getDouble(path + ".view.blur", 1.0);
                    default -> "none".equalsIgnoreCase(c.getString(path + ".frame")) ? "none" : "wall";
                };
                if (key.equals("time") || key.equals("sound")) lines.add(Component.empty());   // groups: portal · options · view
                // click a line to put "/mp edit <portal> <setting> " into the chat
                lines.add(Component.text(key + "  ", Chat.TEXT).append(Component.text(String.valueOf(value), Chat.VALUE))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/mp edit " + args[1] + " " + key + " "))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("/mp edit " + args[1] + " " + key + " …", Chat.TEXT))));
            }
            for (String slot : Portal.SOUND_SLOTS) {
                String s = c.getString(path + ".sounds." + slot);
                if (s != null) lines.add(Component.text("sound." + slot + "  ", Chat.TEXT).append(Component.text(s, Chat.VALUE)));
            }
            lines.add(Component.empty());
            lines.add(Component.text(messages.get("edit.hint", args[1]), Chat.TEXT));
            Chat.box(sender, "MagicPortals", Chat.format(messages.get("edit.header"), new Object[]{args[1]}, Chat.TEXT, Chat.VALUE), lines);
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT), raw = String.join(" ", Arrays.copyOfRange(args, 3, args.length)), value = raw.toLowerCase(Locale.ROOT);
        if (key.equals("insideview") || key.equals("inside")) key = "inside-view";
        if (!EDITABLE.containsKey(key)) { sender.sendMessage(error("edit.unknown", key, String.join(", ", EDITABLE.keySet()))); return; }
        if (key.startsWith("sound.")) {
            // custom sound for one slot: a sound key, a file from the sounds folder, "none" (silent) or "default"
            String sound = soundKey(value);
            if (sound == null) { sender.sendMessage(error("edit.invalid", raw, key, "default, none, magicportals:<name>, minecraft:<sound>")); return; }
            if (sound.equals("default")) getConfig().set(path + ".sounds." + key.substring(6), null);
            else getConfig().set(path + ".sounds." + key.substring(6), sound);
            saveConfig();
            load();
            sender.sendMessage(text("edit.done", args[1], key, sound));
            if (sound.startsWith("magicportals:")) sender.sendMessage(text("edit.sound"));
            return;
        }
        Object stored;
        switch (key) {
            case "name" -> stored = raw;
            case "target" -> stored = raw.matches("[A-Za-z0-9_-]+") && !raw.equals(server) ? raw : null;
            case "link" -> stored = value.equals("none") ? "none" : raw.matches("[A-Za-z0-9_-]{1,32}") ? raw : null;
            case "style" -> { Style s = Style.parse(value); stored = s == null ? null : s.id; }
            case "time", "weather" -> stored = EDITABLE.get(key).contains(value) ? value : null;
            case "fog-color", "tint" -> stored = color(value);
            case "volume", "particles", "fog", "tint-strength", "blur" -> {
                double v;
                try { v = Double.parseDouble(value.replace(',', '.')); } catch (NumberFormatException e) { v = -1; }
                double max = switch (key) { case "volume" -> 4; case "fog" -> 8; case "tint-strength" -> 1; case "blur" -> 2; default -> 5; };
                stored = v >= 0 && v <= max ? v : null;
            }
            case "facing" -> stored = EDITABLE.get("facing").contains(value) ? value : null;
            case "width", "height" -> {
                int n;
                try { n = Integer.parseInt(value); } catch (NumberFormatException e) { n = 0; }
                stored = n >= 1 && n <= Portal.MAX_SIZE ? n : null;
            }
            case "inside-view", "barriers", "label", "sound", "effects" -> stored = switch (value) { case "true", "on", "yes", "an", "ja" -> true; case "false", "off", "no", "aus", "nein" -> false; default -> null; };
            default -> stored = value.equals("none") ? "none" : value.equals("wall") ? "wall" : null;
        }
        if (stored == null) { sender.sendMessage(error("edit.invalid", raw, key, String.join(", ", EDITABLE.get(key)))); return; }
        boolean view = VIEW_KEYS.contains(key);
        if (key.equals("frame") && stored.equals("wall")) getConfig().set(path + ".frame", null);   // default wall
        else if (view) getConfig().set(path + ".view." + key, "none".equals(stored) ? null : stored);
        else if (key.equals("link") || key.equals("target")) {
            // the portal gets a new exchange key: remove what it stored under the old one
            String oldKey = Portal.key(server, getConfig().getString(path + ".target", args[1]), getConfig().getString(path + ".link"));
            if (key.equals("link")) getConfig().set(path + ".link", "none".equals(stored) ? null : stored);
            else getConfig().set(path + ".target", stored.equals(args[1]) ? null : stored);
            async(ex -> { ex.deleteSnapshots(oldKey); return null; }, null);
        }
        else getConfig().set(path + "." + key, stored);
        saveConfig();
        load();
        sender.sendMessage(text("edit.done", args[1], key, String.valueOf(stored)));
        // the views through both portals are baked with the portal's size and direction
        if (view || key.equals("facing") || key.equals("width") || key.equals("height")) sender.sendMessage(text("edit.update"));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = List.of();
        if (args.length == 1) options = List.of("setup", "list", "wand", "set", "edit", "remove", "style", "update", "reload");
        else {
            String sub = subCommand(args[0]);
            if (args.length == 2 && (sub.equals("remove") || sub.equals("style") || sub.equals("edit") || sub.equals("set")))
                options = portals.stream().map(p -> p.id).toList();
            else if (args.length == 2 && sub.equals("setup")) options = setup.completions();
            else if (args.length == 3 && sub.equals("set")) options = List.copyOf(knownServers());
            else if (args.length == 3 && sub.equals("style")) options = Style.ids();
            else if (args.length == 3 && sub.equals("edit")) options = List.copyOf(EDITABLE.keySet());
            else if (args.length == 4 && sub.equals("edit")) {
                options = new ArrayList<>(EDITABLE.getOrDefault(args[2].toLowerCase(Locale.ROOT), List.of()));
                if (args[2].toLowerCase(Locale.ROOT).startsWith("sound.")) options.addAll(customSoundNames());
            }
        }
        return options.stream().filter(s -> s.startsWith(input)).toList();
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
