package magicportals;

import java.io.*;
import java.nio.file.*;
import java.util.*;
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

/* MagicPortals – walk-through portals between the servers of a Velocity network, with a live 3D view
 * of the destination drawn by a resource-pack shader.
 *
 * Every server lists its portals in config.yml under "portals" (key = target server). A portal from A to B
 * shows what lies in front of the portal from B back to A, so every portal needs a counterpart.
 * Heartbeats, arrivals, snapshots and scene numbers are shared through a folder or a MySQL database
 * ("exchange"); all access to it happens off the main thread. "/magicportals update" bakes the scenes
 * and delivers the resource pack through Oraxen, ItemsAdder or the built-in web server. */
public final class MagicPortals extends JavaPlugin implements Listener, TabExecutor {
    private String lokal;
    private volatile Austausch austausch;
    private World welt;
    private Lang lang = new Lang("en");
    private Paket paket;
    private final List<Tor> tore = new ArrayList<>();
    private final Map<UUID, Long> sperre = new HashMap<>();
    private Map<String, Integer> szenen = Map.of();
    private long szenenStand = -1;
    private int tick;
    private volatile boolean backtGerade, abgleichLaeuft;
    private final long gestartet = System.currentTimeMillis();

    Lang lang() { return lang; }

    @Override public void onEnable() {
        alteConfigUebernehmen();
        saveDefaultConfig();
        paket = new Paket(this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getPluginManager().registerEvents(this, this);
        var befehl = getCommand("magicportals");
        if (befehl != null) { befehl.setExecutor(this); befehl.setTabCompleter(this); }
        laden();
        paket.webserverStarten();
        Bukkit.getScheduler().runTaskTimer(this, this::aktualisieren, 1, 1);
    }

    @Override public void onDisable() {
        for (Tor t : tore) t.abbauen();
        tore.clear();
        if (paket != null) paket.webserverStoppen();
        var a = austausch;
        if (a != null) {
            try { a.abmelden(lokal); } catch (IOException e) { getLogger().warning(e.getMessage()); }
            a.close();
        }
    }

    // ---------- helpers for work off the main thread ----------

    private interface Arbeit<T> { T tun(Austausch a) throws Exception; }

    /* Runs the work asynchronously and hands the result back on the main thread. */
    private <T> void spaeter(Arbeit<T> arbeit, java.util.function.Consumer<T> danach) {
        var a = austausch;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            T ergebnis;
            try { ergebnis = arbeit.tun(a); }
            catch (Exception e) { getLogger().warning("Exchange: " + e.getMessage()); return; }
            if (danach != null && isEnabled()) Bukkit.getScheduler().runTask(this, () -> danach.accept(ergebnis));
        });
    }

    private void speichern(Tor.Aufnahme aufnahme) {
        spaeter(a -> {
            a.aufnahmeSpeichern(aufnahme.schluessel(), "blick", aufnahme.vorne().alsBytes());
            a.aufnahmeSpeichern(aufnahme.schluessel(), "rueck", aufnahme.rueck().alsBytes());
            return null;
        }, null);
    }

    private String austauschName() { return austausch instanceof MysqlAustausch ? "MySQL" : "folder"; }

    // ---------- configuration ----------

    /* Takes over the config of the predecessor plugin "Dorfportal" (German keys) once. */
    private void alteConfigUebernehmen() {
        Path neu = getDataFolder().toPath().resolve("config.yml");
        Path alt = getDataFolder().toPath().getParent().resolve("Dorfportal/config.yml");
        try {
            if (!Files.exists(neu) && Files.exists(alt)) {
                Files.createDirectories(neu.getParent());
                Files.copy(alt, neu);
                getLogger().info("Took over plugins/Dorfportal/config.yml");
            }
            if (!Files.exists(neu)) return;
            var c = YamlConfiguration.loadConfiguration(neu.toFile());
            if (deutscheSchluesselUmstellen(c)) {
                c.save(neu.toFile());
                getLogger().info("config.yml converted to the MagicPortals format.");
            }
        } catch (IOException e) { getLogger().warning("Could not convert the old config: " + e.getMessage()); }
    }

    private static boolean deutscheSchluesselUmstellen(FileConfiguration c) {
        boolean geaendert = false;
        if (c.contains("tor.x") && !c.contains("tore") && !c.contains("portals")) {   // very first version
            String ziel = c.getString("ziel", "lobby".equals(c.getString("server")) ? "smp" : "lobby");
            for (String k : List.of("x", "y", "z")) c.set("tore." + ziel + "." + k, c.getInt("tor." + k));
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
            c.set("austausch", null); geaendert = true;
        }
        if (c.contains("scheinbloecke")) { c.set("fallback-blocks", c.getBoolean("scheinbloecke")); c.set("scheinbloecke", null); geaendert = true; }
        if (c.contains("tore")) {
            var t = c.getConfigurationSection("tore");
            for (String ziel : t.getKeys(false)) {
                var a = t.getConfigurationSection(ziel);
                String p = "portals." + ziel + ".";
                if (a.contains("name")) c.set(p + "name", a.getString("name"));
                c.set(p + "style", "riss".equalsIgnoreCase(a.getString("stil", "tor")) ? "rift" : "gate");
                for (String k : List.of("x", "y", "z")) if (a.contains(k)) c.set(p + k, a.getInt(k));
                if (a.contains("rahmen.aussen")) c.set(p + "frame.outer", a.getString("rahmen.aussen"));
                if (a.contains("rahmen.innen")) c.set(p + "frame.inner", a.getString("rahmen.innen"));
            }
            c.set("tore", null); geaendert = true;
        }
        if (c.contains("backen")) {
            c.set("resource-pack.enabled", true);
            c.set("resource-pack.provider", "auto");
            c.set("resource-pack.client-jar", c.getString("backen.client-jar", ""));
            c.set("backen", null); geaendert = true;
        }
        if (geaendert && !c.contains("language")) c.set("language", "de");
        return geaendert;
    }

    private void laden() {
        for (Tor t : tore) t.abbauen();
        tore.clear();
        reloadConfig();
        var c = getConfig();
        lang = new Lang(c.getString("language", "en"));
        lokal = c.getString("server", "").trim();
        if (lokal.isEmpty()) throw new IllegalStateException("config.yml: set 'server' to the name of this server in velocity.toml");
        var alt = austausch;
        try { austausch = Austausch.aus(c, getDataFolder().toPath(), getLogger()); }
        catch (IOException e) { throw new IllegalStateException("Exchange not reachable: " + e.getMessage(), e); }
        if (alt != null) alt.close();
        getLogger().info("Exchange: " + austauschName());
        welt = Bukkit.getWorlds().getFirst();

        var abschnitt = c.getConfigurationSection("portals");
        if (abschnitt != null) for (String ziel : abschnitt.getKeys(false)) {
            ConfigurationSection t = abschnitt.getConfigurationSection(ziel);
            if (t == null) continue;
            int[] lage;
            if (t.contains("x") && t.contains("y") && t.contains("z")) lage = new int[]{t.getInt("x"), t.getInt("y"), t.getInt("z")};
            else {
                lage = platzSuchen();
                t.set("x", lage[0]); t.set("y", lage[1]); t.set("z", lage[2]);
                saveConfig();
                getLogger().info("Portal to " + ziel + " placed automatically at " + lage[0] + " " + lage[1] + " " + lage[2]);
            }
            String name = t.getString("name", ziel.substring(0, 1).toUpperCase(Locale.ROOT) + ziel.substring(1));
            String stil = t.getString("style", "gate").toLowerCase(Locale.ROOT);
            tore.add(new Tor(this, welt, ziel, name, lage[0], lage[1], lage[2],
                    material(t.getString("frame.outer"), Material.STONE_BRICKS),
                    material(t.getString("frame.inner"), Material.POLISHED_BLACKSTONE_BRICKS),
                    stil.equals("rift") || stil.equals("riss")));
        }
        szenenStand = -1;
        for (Tor t : tore) {
            t.bauen();
            speichern(t.allesAufnehmen(lokal));
            getLogger().info("Portal " + lokal + " -> " + t.ziel + " at " + t.x + " " + t.y + " " + t.z + (t.riss ? " (rift)" : " (gate)"));
        }
        abgleichen();
    }

    private static Material material(String text, Material standard) {
        Material m = text == null ? null : Material.matchMaterial(text);
        return m != null && m.isBlock() ? m : standard;
    }

    private int[] platzSuchen() {
        Location spawn = welt.getSpawnLocation();
        for (int radius = 0; radius <= 64; radius += 4) for (int a = -radius; a <= radius; a += 4)
            for (int b = -radius; b <= radius; b += 4) {
                if (Math.max(Math.abs(a), Math.abs(b)) != radius) continue;
                int px = spawn.getBlockX() + a, pz = spawn.getBlockZ() + b;
                if (tore.stream().anyMatch(t -> t.nahe(px, pz, 12))) continue;
                int tief = 9999, hoch = -9999; boolean gut = true;
                for (int i = -3; i <= 3 && gut; i++) for (int j = -3; j <= 4; j++) {
                    int h = welt.getHighestBlockYAt(px + i, pz + j, HeightMap.MOTION_BLOCKING);
                    var m = welt.getBlockAt(px + i, h, pz + j).getType();
                    if (!m.isSolid() || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.CACTUS || m == Material.MAGMA_BLOCK) { gut = false; break; }
                    tief = Math.min(tief, h); hoch = Math.max(hoch, h);
                }
                if (gut && hoch - tief <= 1) return new int[]{px, hoch + 1, pz};
            }
        int px = spawn.getBlockX() + 8 * tore.size(), pz = spawn.getBlockZ(), hoch = welt.getSeaLevel();
        for (int i = -3; i <= 3; i++) for (int j = -3; j <= 5; j++) hoch = Math.max(hoch, welt.getHighestBlockYAt(px + i, pz + j));
        for (int i = -3; i <= 3; i++) for (int j = -3; j <= 5; j++) welt.getBlockAt(px + i, hoch + 1, pz + j).setType(Material.SPRUCE_PLANKS, false);
        return new int[]{px, hoch + 2, pz};
    }

    // ---------- sync with the other servers ----------

    private record Abgleich(Set<String> online, long szenenStand, Map<String, Integer> szenen, Map<String, byte[]> fern, Map<String, Long> fernStand) { }

    /* Every 5 seconds: own heartbeat, who is online, new scene numbers, optionally the other side's view. */
    private void abgleichen() {
        if (abgleichLaeuft) return;
        abgleichLaeuft = true;
        boolean schein = getConfig().getBoolean("fallback-blocks", false);
        long bekannterStand = szenenStand;
        var fernBekannt = new HashMap<String, Long>();
        for (Tor t : tore) fernBekannt.put(t.ziel, t.fernStand());
        String ich = lokal;
        var a0 = austausch;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Abgleich ergebnis = null;
            try {
                a0.lebenszeichen(ich);
                Set<String> online = a0.online();
                long stand = a0.szenenStand();
                Map<String, Integer> neu = stand != bekannterStand ? a0.szenen() : null;
                var fern = new HashMap<String, byte[]>(); var fernStand = new HashMap<String, Long>();
                if (schein) for (var e : fernBekannt.entrySet()) {
                    String s = Tor.schluessel(e.getKey(), ich);
                    long st = a0.aufnahmeStand(s, "blick");
                    if (st != 0 && st != e.getValue()) { byte[] d = a0.aufnahmeLaden(s, "blick"); if (d != null) { fern.put(e.getKey(), d); fernStand.put(e.getKey(), st); } }
                }
                ergebnis = new Abgleich(online, stand, neu, fern, fernStand);
            } catch (IOException e) { getLogger().warning("Sync: " + e.getMessage()); }
            var fertig = ergebnis;
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                abgleichLaeuft = false;
                if (fertig == null || a0 != austausch) return;
                for (Tor t : tore) {
                    t.erreichbarSetzen(fertig.online().contains(t.ziel));
                    byte[] d = fertig.fern().get(t.ziel);
                    if (d != null) t.fernSetzen(d, fertig.fernStand().get(t.ziel));
                }
                if (fertig.szenen() != null) {
                    szenen = fertig.szenen(); szenenStand = fertig.szenenStand();
                    for (Tor t : tore) t.markerSetzen(szenen.get(Tor.schluessel(t.ziel, lokal)));
                }
            });
        });
    }

    private void aktualisieren() {
        if (tick % 100 == 0) abgleichen();
        if (tick % 1200 == 600) for (Tor t : tore) t.aufnahmeStarten();
        for (Tor t : tore) { var fertig = t.aufnahmeSchritt(lokal); if (fertig != null) speichern(fertig); }
        if (tick % 2 == 0 && getConfig().getBoolean("fallback-blocks", false))
            for (Player p : welt.getPlayers()) for (Tor t : tore) t.blickAktualisieren(p, tick);
        if (tick % 4 == 0) for (Tor t : tore) t.partikel();
        tick = (tick + 1) % 1200;
    }

    // ---------- walking through and arriving ----------

    @EventHandler(ignoreCancelled = true) public void gehen(PlayerMoveEvent e) {
        if (e.getFrom().getWorld() != welt || e.getTo().getWorld() != welt) return;
        for (Tor t : tore) if (t.durchschritten(e.getFrom(), e.getTo())) { wechsel(e.getPlayer(), t); return; }
    }

    private void wechsel(Player p, Tor t) {
        long jetzt = System.currentTimeMillis();
        if (sperre.getOrDefault(p.getUniqueId(), 0L) > jetzt) return;
        sperre.put(p.getUniqueId(), jetzt + 5000);
        if (!t.erreichbar) { p.sendMessage(Component.text(lang.t("offline.message", t.name))); return; }
        UUID id = p.getUniqueId();
        String ich = lokal;
        p.playSound(p.getLocation(), "minecraft:block.portal.travel", .35f, 1.25f);
        // Store the arrival first, then connect, so the target server finds it when the player joins.
        spaeter(a -> { a.ankunftSetzen(t.ziel, id, ich); return true; }, ok -> {
            if (!p.isOnline()) return;
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(t.ziel); }
            catch (IOException e) { return; }
            p.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && Bukkit.getPlayer(id) == p) p.sendMessage(Component.text(lang.t("connect.failed")));
            }, 100);
        });
    }

    @EventHandler public void beitritt(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        paket.senden(p);
        UUID id = p.getUniqueId();
        String ich = lokal;
        spaeter(a -> Optional.ofNullable(a.ankunftHolen(ich, id)), herkunft -> {
            if (herkunft.isEmpty() || !p.isOnline()) return;
            Tor tor = tore.stream().filter(t -> t.ziel.equals(herkunft.get())).findFirst().orElse(tore.isEmpty() ? null : tore.getFirst());
            if (tor == null) return;
            Location ankunft = tor.ankunft();
            p.teleport(ankunft);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 70, 0, false, false, false));
            p.playSound(ankunft, "minecraft:block.amethyst_block.chime", .7f, 1.1f);
            p.playSound(ankunft, "minecraft:block.portal.trigger", .25f, 1.6f);
        });
    }

    @EventHandler public void verlassen(PlayerQuitEvent e) {
        sperre.remove(e.getPlayer().getUniqueId());
        for (Tor t : tore) t.zuruecksetzen(e.getPlayer());
    }

    @EventHandler public void teleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() != welt) for (Tor t : tore) t.zuruecksetzen(e.getPlayer());
    }

    // ---------- commands ----------

    private Component text(String schluessel, Object... werte) { return Component.text(lang.t(schluessel, werte)); }
    private Component fehler(String schluessel, Object... werte) { return Component.text(lang.t(schluessel, werte), NamedTextColor.RED); }

    private static String befehl(String eingabe) {
        return switch (eingabe.toLowerCase(Locale.ROOT)) {
            case "liste" -> "list";
            case "setzen" -> "set";
            case "entfernen" -> "remove";
            case "stil" -> "style";
            case "aktualisieren" -> "update";
            case "neuladen" -> "reload";
            default -> eingabe.toLowerCase(Locale.ROOT);
        };
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String was = args.length == 0 ? "help" : befehl(args[0]);
        switch (was) {
            case "list" -> {
                sender.sendMessage(text("list.header", lokal, austauschName()));
                if (tore.isEmpty()) sender.sendMessage(text("list.none"));
                for (Tor t : tore) sender.sendMessage(Component.text("  → " + t.ziel + "  " + t.x + " " + t.y + " " + t.z + "  " + lang.t(t.riss ? "style.rift" : "style.gate") + "  ")
                        .append(t.erreichbar ? Component.text(lang.t("list.online"), NamedTextColor.GREEN) : Component.text(lang.t("list.offline"), NamedTextColor.RED))
                        .append(Component.text(t.szene() == null ? lang.t("list.noscene") : lang.t("list.scene", t.szene()), NamedTextColor.GRAY)));
            }
            case "set" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage(fehler("only.ingame")); return true; }
                if (args.length < 2) { sender.sendMessage(fehler("set.usage")); return true; }
                String ziel = args[1];
                if (ziel.equals(lokal) || !ziel.matches("[A-Za-z0-9_-]+")) { sender.sendMessage(fehler("set.invalid", ziel)); return true; }
                Location l = p.getLocation();
                String pfad = "portals." + ziel;
                getConfig().set(pfad + ".x", l.getBlockX());
                getConfig().set(pfad + ".y", l.getBlockY());
                getConfig().set(pfad + ".z", l.getBlockZ() - 3);
                if (!getConfig().contains(pfad + ".style")) getConfig().set(pfad + ".style", "gate");
                if (args.length >= 3) getConfig().set(pfad + ".name", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                saveConfig();
                laden();
                sender.sendMessage(text("set.done", ziel));
                sender.sendMessage(text("set.counterpart", ziel, lokal));
            }
            case "remove" -> {
                if (args.length < 2 || !getConfig().contains("portals." + args[1])) { sender.sendMessage(fehler("remove.unknown")); return true; }
                getConfig().set("portals." + args[1], null);
                saveConfig();
                String schluessel = Tor.schluessel(lokal, args[1]);
                laden();
                spaeter(a -> { a.aufnahmeLoeschen(schluessel); return null; }, null);
                sender.sendMessage(text("remove.done", args[1]));
            }
            case "style" -> {
                String stil = args.length >= 3 ? switch (args[2].toLowerCase(Locale.ROOT)) { case "riss", "rift" -> "rift"; case "tor", "gate" -> "gate"; default -> ""; } : "";
                if (args.length < 3 || !getConfig().contains("portals." + args[1]) || stil.isEmpty()) { sender.sendMessage(fehler("style.usage")); return true; }
                getConfig().set("portals." + args[1] + ".style", stil);
                saveConfig();
                laden();
                sender.sendMessage(text("style.done", args[1], stil));
            }
            case "reload" -> { laden(); paket.webserverStarten(); sender.sendMessage(text("reload.done", tore.size())); }
            case "update" -> aktualisierenBefehl(sender);
            default -> {
                for (String h : List.of("help.list", "help.set", "help.remove", "help.style", "help.update", "help.reload")) sender.sendMessage(text(h));
            }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return List.of("list", "set", "remove", "style", "update", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        String was = befehl(args[0]);
        if (args.length == 2 && (was.equals("remove") || was.equals("style"))) return tore.stream().map(t -> t.ziel).toList();
        if (args.length == 3 && was.equals("style")) return List.of("gate", "rift");
        return List.of();
    }

    /* Bakes all scenes and delivers the resource pack. Only on the server with resource-pack.enabled. */
    private void aktualisierenBefehl(CommandSender sender) {
        if (!paket.aktiv()) { sender.sendMessage(fehler("update.disabled")); return; }
        if (backtGerade) { sender.sendMessage(fehler("update.running")); return; }
        // Capture this server's portals right now; other servers capture every minute.
        var eigene = new ArrayList<Tor.Aufnahme>();
        for (Tor t : tore) eigene.add(t.allesAufnehmen(lokal));
        backtGerade = true;
        sender.sendMessage(text("update.start"));
        Path arbeit = getDataFolder().toPath().resolve("bake");
        String anbieter = paket.anbieter();
        var a = austausch;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                for (var aufnahme : eigene) {
                    a.aufnahmeSpeichern(aufnahme.schluessel(), "blick", aufnahme.vorne().alsBytes());
                    a.aufnahmeSpeichern(aufnahme.schluessel(), "rueck", aufnahme.rueck().alsBytes());
                }
                Path jar = paket.clientJar(version -> Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(text("update.jar", version))));
                Path ausgabe = arbeit.resolve("output");
                if (Files.exists(ausgabe)) try (var alt = Files.walk(ausgabe)) { alt.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                var meldungen = new ArrayList<String>();
                Map<String, Integer> nummern = PortalBacken.backen(a.zumBacken(arbeit.resolve("input")), jar, ausgabe, meldungen::add);
                for (String m : meldungen) getLogger().info(m);
                a.szenenSetzen(nummern);
                Path zip = paket.bauen(ausgabe);
                String nachher = paket.ausliefern(zip, ausgabe);
                // Right after a server start Oraxen is still building its own pack; a reload during that
                // time is lost. So reload at the earliest 60 s after start.
                long warten = "oraxen".equals(anbieter) ? Math.max(1, (60_000 - (System.currentTimeMillis() - gestartet)) / 50) : 1;
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(text("update.done", nummern.size(), anbieter));
                    if (warten > 20) sender.sendMessage(text("update.wait", anbieter));
                    szenenStand = -1;
                    abgleichen();
                });
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    backtGerade = false;
                    if (nachher != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), nachher);
                }, warten);
            } catch (Exception ex) {
                getLogger().warning("Update failed: " + ex);
                Bukkit.getScheduler().runTask(this, () -> { backtGerade = false; sender.sendMessage(fehler("update.failed", String.valueOf(ex.getMessage()))); });
            }
        });
    }
}
