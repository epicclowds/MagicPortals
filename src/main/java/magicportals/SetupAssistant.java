package magicportals;

import java.nio.charset.StandardCharsets;
import java.util.*;
import magicportals.portal.Portal;
import magicportals.portal.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * /mp setup – a guided, clickable chat assistant that creates a linked portal pair across two servers:
 * mark the opening, pick the target server, name and connection ID, look and options, then travel to the
 * target server and mark the counterpart there. The state travels with the player through the exchange,
 * so the assistant continues on the other server. At the end the resource pack is rebuilt automatically.
 */
final class SetupAssistant {
    /** Steps in order; "travel" and "counter" happen after the first portal exists. */
    private static final List<String> STEPS = List.of("mark", "target", "name", "style", "travel", "counter");
    static final String KIND = "setup";

    private final MagicPortals plugin;
    private final Map<UUID, State> running = new HashMap<>();

    SetupAssistant(MagicPortals plugin) { this.plugin = plugin; }

    /** Setup state of one player; stored in the exchange as "key=value;…" while travelling. */
    static final class State {
        String step = "mark", origin, target, name, link, style = "gate";
        boolean sound, effects;

        String toText() {
            return "step=" + step + ";origin=" + origin + ";target=" + target + ";name=" + name + ";link=" + link
                    + ";style=" + style + ";sound=" + sound + ";effects=" + effects;
        }

        static State parse(String text) {
            var s = new State();
            for (String part : text.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq), v = part.substring(eq + 1);
                if (v.equals("null")) v = null;
                switch (k) {
                    case "step" -> s.step = v;
                    case "origin" -> s.origin = v;
                    case "target" -> s.target = v;
                    case "name" -> s.name = v;
                    case "link" -> s.link = v;
                    case "style" -> s.style = v == null ? "gate" : v;
                    case "sound" -> s.sound = Boolean.parseBoolean(v);
                    case "effects" -> s.effects = Boolean.parseBoolean(v);
                    default -> { }
                }
            }
            return s;
        }
    }

    static String key(UUID player) { return "setup@" + player; }

    // ---------- chat building blocks ----------

    private String msg(String key, Object... values) { return plugin.messages().get(key, values); }

    private Component button(String label, String command, NamedTextColor color, boolean run) {
        return Component.text(label, color, TextDecoration.BOLD)
                .clickEvent(run ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    private Component run(String label, String command) { return button(label, command, NamedTextColor.GREEN, true); }

    private Component footer(boolean back) {
        Component c = Component.text("  ");
        if (back) c = c.append(button(msg("setup.btn.back"), "/mp setup back", NamedTextColor.YELLOW, true)).append(Component.text("  "));
        return c.append(button(msg("setup.btn.cancel"), "/mp setup cancel", NamedTextColor.RED, true));
    }

    private void title(Player p) {
        p.sendMessage(Component.empty());
        p.sendMessage(Component.text("✦ " + msg("setup.title") + " ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
    }

    // ---------- commands ----------

    void command(Player p, String[] args) {
        String action = args.length == 0 ? "start" : args[0].toLowerCase(Locale.ROOT);
        State s = running.get(p.getUniqueId());
        if (action.equals("start") || action.equals("show")) {
            if (s == null) {
                s = new State();
                s.origin = plugin.serverName();
                running.put(p.getUniqueId(), s);
                plugin.selections().remove(p.getUniqueId());
                p.getInventory().addItem(plugin.wandItem());
            }
            show(p, s);
            return;
        }
        if (action.equals("cancel")) {
            running.remove(p.getUniqueId());
            String key = key(p.getUniqueId());
            plugin.runAsync(ex -> { ex.deleteSnapshots(key); return null; });
            p.sendMessage(Component.text(msg("setup.cancelled"), NamedTextColor.GRAY));
            return;
        }
        if (s == null) { p.sendMessage(Component.text(msg("setup.none"), NamedTextColor.RED)); return; }
        switch (action) {
            case "back" -> {
                int i = STEPS.indexOf(s.step);
                if (i > 0 && i <= STEPS.indexOf("style")) s.step = STEPS.get(i - 1);
                show(p, s);
            }
            case "target" -> {
                if (args.length < 2 || !plugin.knownServers().contains(args[1])) { show(p, s); return; }
                s.target = args[1];
                s.name = null; s.link = null;
                s.step = "name";
                show(p, s);
            }
            case "name" -> {
                if (args.length < 3 || !args[1].matches("[A-Za-z0-9_-]{1,32}") || !args[2].matches("[A-Za-z0-9_-]{1,32}")) {
                    p.sendMessage(Component.text(msg("setup.badname"), NamedTextColor.RED));
                    return;
                }
                s.name = args[1]; s.link = args[2];
                s.step = "style";
                show(p, s);
            }
            case "style" -> {
                Style style = args.length >= 2 ? Style.parse(args[1]) : null;
                if (style != null) s.style = style.id;
                show(p, s);
            }
            case "sound" -> { s.sound = !s.sound; show(p, s); }
            case "effects" -> { s.effects = !s.effects; show(p, s); }
            case "next" -> createFirst(p, s);
            case "go" -> plugin.connect(p, s.target);
            case "create" -> createCounterpart(p, s);
            default -> show(p, s);
        }
    }

    /** Called when the player marked both corners with the wand. */
    void selectionChanged(Player p) {
        State s = running.get(p.getUniqueId());
        if (s == null) return;
        if (s.step.equals("mark")) { s.step = "target"; show(p, s); }
        else if (s.step.equals("counter")) show(p, s);
    }

    /** After joining this server: continue a setup that was started on another server. */
    void onJoin(Player p) {
        UUID id = p.getUniqueId();
        String key = key(id);
        plugin.runAsync(ex -> {
            byte[] data = ex.loadSnapshot(key, KIND);
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        }, text -> {
            if (text == null || !p.isOnline()) return;
            State s = State.parse(text);
            if (plugin.serverName().equals(s.target) && (s.step.equals("travel") || s.step.equals("counter"))) {
                s.step = "counter";
                running.put(id, s);
                plugin.selections().remove(id);
                p.getInventory().addItem(plugin.wandItem());
                // give the server a moment so the message is not lost in the join messages
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) show(p, s); }, 30);
            } else if (s.target != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    p.sendMessage(Component.text(msg("setup.elsewhere", s.target), NamedTextColor.YELLOW)
                            .append(Component.text("  ")).append(run(msg("setup.btn.go", s.target), "/mp setup go")));
                    running.put(id, s);
                }, 30);
            }
        });
    }

    // ---------- steps ----------

    private void show(Player p, State s) {
        title(p);
        switch (s.step) {
            case "mark" -> {
                p.sendMessage(Component.text(msg("setup.step1"), NamedTextColor.WHITE));
                p.sendMessage(footer(false));
            }
            case "target" -> {
                p.sendMessage(Component.text(msg("setup.step2"), NamedTextColor.WHITE));
                var servers = plugin.knownServers();
                if (servers.isEmpty()) {
                    p.sendMessage(Component.text(msg("setup.noservers") + " ", NamedTextColor.GRAY).append(run(msg("setup.btn.retry"), "/mp setup show")));
                } else {
                    Component line = Component.text("  ");
                    for (String server : servers) line = line.append(run("[" + server + "]", "/mp setup target " + server)).append(Component.text("  "));
                    p.sendMessage(line);
                }
                p.sendMessage(footer(true));
            }
            case "name" -> {
                if (s.name == null) s.name = uniqueName(s.target);
                if (s.link == null) s.link = uniqueLink(s.origin + "-" + s.target);
                p.sendMessage(Component.text(msg("setup.step3", s.target), NamedTextColor.WHITE));
                p.sendMessage(Component.text("  " + msg("setup.suggestion", s.name, s.link), NamedTextColor.AQUA));
                String cmd = "/mp setup name " + s.name + " " + s.link;
                p.sendMessage(Component.text("  ").append(run(msg("setup.btn.accept"), cmd)).append(Component.text("  "))
                        .append(button(msg("setup.btn.edit"), cmd, NamedTextColor.AQUA, false)));
                p.sendMessage(footer(true));
            }
            case "style" -> {
                p.sendMessage(Component.text(msg("setup.step4"), NamedTextColor.WHITE));
                Component styles = Component.text("  ");
                for (String id : Style.ids()) {
                    boolean chosen = id.equals(s.style);
                    styles = styles.append(button((chosen ? "● " : "○ ") + msg("style." + id), "/mp setup style " + id,
                            chosen ? NamedTextColor.GOLD : NamedTextColor.GRAY, true)).append(Component.text("  "));
                }
                p.sendMessage(styles);
                p.sendMessage(Component.text("  ")
                        .append(button(msg("setup.btn.sound", msg(s.sound ? "setup.on" : "setup.off")), "/mp setup sound", s.sound ? NamedTextColor.GREEN : NamedTextColor.GRAY, true))
                        .append(Component.text("  "))
                        .append(button(msg("setup.btn.effects", msg(s.effects ? "setup.on" : "setup.off")), "/mp setup effects", s.effects ? NamedTextColor.GREEN : NamedTextColor.GRAY, true)));
                p.sendMessage(Component.text("  ").append(run(msg("setup.btn.next"), "/mp setup next")));
                p.sendMessage(footer(true));
            }
            case "travel" -> {
                p.sendMessage(Component.text(msg("setup.created", s.name, s.target), NamedTextColor.WHITE));
                p.sendMessage(Component.text("  ").append(run(msg("setup.btn.go", s.target), "/mp setup go")));
                p.sendMessage(footer(false));
            }
            case "counter" -> {
                p.sendMessage(Component.text(msg("setup.arrived", s.target, s.origin, s.link), NamedTextColor.WHITE));
                if (hasSelection(p)) p.sendMessage(Component.text("  " + msg("setup.ready") + " ", NamedTextColor.AQUA).append(run(msg("setup.btn.create"), "/mp setup create")));
                p.sendMessage(footer(false));
            }
            default -> p.sendMessage(footer(false));
        }
    }

    private boolean hasSelection(Player p) {
        var sel = plugin.selections().get(p.getUniqueId());
        return sel != null && sel[0] != null && sel[1] != null;
    }


    /** Creates the first portal (on the origin server) and sends the player on to the target server. */
    private void createFirst(Player p, State s) {
        if (!hasSelection(p)) { s.step = "mark"; show(p, s); return; }
        if (!plugin.createPortal(p, s.name, s.target, s.link, null)) return;
        applyOptions(s, s.name);
        s.step = "travel";
        String key = key(p.getUniqueId()), text = s.toText();
        plugin.runAsync(ex -> { ex.saveSnapshot(key, KIND, text.getBytes(StandardCharsets.UTF_8)); return null; });
        show(p, s);
    }

    /** Creates the counterpart on the target server, finishes the setup and rebuilds the pack. */
    private void createCounterpart(Player p, State s) {
        if (!s.step.equals("counter")) { show(p, s); return; }
        if (!hasSelection(p)) { show(p, s); return; }
        String name = uniqueName(s.origin);
        if (!plugin.createPortal(p, name, s.origin, s.link, null)) return;
        applyOptions(s, name);
        running.remove(p.getUniqueId());
        String key = key(p.getUniqueId());
        plugin.runAsync(ex -> { ex.deleteSnapshots(key); return null; });
        plugin.requestUpdate();
        title(p);
        p.sendMessage(Component.text(msg("setup.done", s.origin, s.target, s.link), NamedTextColor.GREEN));
    }

    private void applyOptions(State s, String portal) {
        plugin.setPortalOption(portal, "style", s.style);
        plugin.setPortalOption(portal, "sound", s.sound ? true : null);
        plugin.setPortalOption(portal, "effects", s.effects ? true : null);
        plugin.saveAndReload();
    }

    /** A portal name not used on this server yet: base, base-2, base-3, … */
    private String uniqueName(String base) {
        Set<String> used = new HashSet<>();
        for (Portal p : plugin.portals()) used.add(p.id);
        if (!used.contains(base)) return base;
        for (int i = 2; ; i++) if (!used.contains(base + "-" + i)) return base + "-" + i;
    }

    /** A connection ID not used on this server yet. */
    private String uniqueLink(String base) {
        Set<String> used = new HashSet<>();
        for (Portal p : plugin.portals()) if (p.link != null) used.add(p.link);
        if (!used.contains(base)) return base;
        for (int i = 2; ; i++) if (!used.contains(base + "-" + i)) return base + "-" + i;
    }

    List<String> completions() { return List.of("start", "cancel", "back", "next", "go", "create"); }
}
