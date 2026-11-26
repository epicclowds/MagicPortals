package magicportals;

import java.util.Map;

/* In-game messages in English and German ("language: en|de" in config.yml).
 * Placeholders {0}, {1}, ... are replaced in order. */
final class Messages {
    private static final Map<String, String[]> TEXTS = Map.ofEntries(
            Map.entry("offline.sign", new String[]{"offline", "gerade offline"}),
            Map.entry("offline.message", new String[]{"{0} is offline right now. Try again in a moment.", "{0} ist gerade offline. Versuche es gleich noch einmal."}),
            Map.entry("connect.failed", new String[]{"The connection did not complete. Step back and walk through the portal again.", "Verbindung nicht abgeschlossen. Geh einen Schritt zurück und erneut ins Tor."}),
            Map.entry("list.header", new String[]{"Portals on {0} (exchange: {1}):", "Tore auf {0} (Austausch: {1}):"}),
            Map.entry("list.none", new String[]{"  none", "  keine"}),
            Map.entry("list.online", new String[]{"online", "online"}),
            Map.entry("list.offline", new String[]{"offline", "offline"}),
            Map.entry("list.noscene", new String[]{"  no shader scene yet", "  ohne Shader-Szene"}),
            Map.entry("list.scene", new String[]{"  scene {0}", "  Szene {0}"}),
            Map.entry("style.gate", new String[]{"gate", "Tor"}),
            Map.entry("style.rift", new String[]{"rift", "Riss"}),
            Map.entry("style.inferno", new String[]{"inferno", "Inferno"}),
            Map.entry("style.storm", new String[]{"storm", "Sturm"}),
            Map.entry("style.void", new String[]{"void", "Leere"}),
            Map.entry("style.frost", new String[]{"frost", "Frost"}),
            Map.entry("only.ingame", new String[]{"Only possible in game.", "Nur im Spiel möglich."}),
            Map.entry("set.usage", new String[]{"/magicportals set <target server> [display name]", "/magicportals set <Zielserver> [Anzeigename]"}),
            Map.entry("set.invalid", new String[]{"Invalid target server: {0}", "Ungültiger Zielserver: {0}"}),
            Map.entry("set.done", new String[]{"The portal to {0} now stands 3 blocks in front of you, its opening facing you ({1}).", "Das Tor nach {0} steht jetzt 3 Blöcke vor dir, die Öffnung zeigt zu dir ({1})."}),
            Map.entry("set.counterpart", new String[]{"{0} needs a portal back to {1}. Then run /magicportals update.", "Auf {0} braucht es ein Tor zurück nach {1}, danach /magicportals update."}),
            Map.entry("remove.unknown", new String[]{"There is no portal to that server. See /magicportals list", "Kein Tor zu diesem Server. Siehe /magicportals list"}),
            Map.entry("remove.done", new String[]{"Portal to {0} removed. /magicportals update also removes its scene from the pack.", "Tor nach {0} entfernt. Mit /magicportals update verschwindet auch seine Szene aus dem Paket."}),
            Map.entry("style.usage", new String[]{"/magicportals style <target server> <gate|rift|inferno|storm|void|frost>", "/magicportals style <Zielserver> <gate|rift|inferno|storm|void|frost>"}),
            Map.entry("style.done", new String[]{"The portal to {0} now uses the style ''{1}''.", "Das Tor nach {0} hat jetzt den Stil ''{1}''."}),
            Map.entry("edit.usage", new String[]{"/magicportals edit <target server> [setting] [value]", "/magicportals edit <Zielserver> [Einstellung] [Wert]"}),
            Map.entry("edit.header", new String[]{"Portal to {0}:", "Tor nach {0}:"}),
            Map.entry("edit.hint", new String[]{"Change with /mp edit {0} <setting> <value>", "Ändern mit /mp edit {0} <Einstellung> <Wert>"}),
            Map.entry("edit.unknown", new String[]{"Unknown setting {0}. Possible: {1}", "Unbekannte Einstellung {0}. Möglich: {1}"}),
            Map.entry("edit.invalid", new String[]{"Invalid value {0} for {1}. Possible: {2}", "Ungültiger Wert {0} für {1}. Möglich: {2}"}),
            Map.entry("edit.done", new String[]{"Portal to {0}: {1} is now {2}.", "Tor nach {0}: {1} ist jetzt {2}."}),
            Map.entry("edit.update", new String[]{"Run /mp update on the resource-pack server so the views match again (the counterpart should have the same width).", "Führe /mp update auf dem Ressourcenpaket-Server aus, damit die Ansichten wieder passen (das Gegenstück sollte gleich breit sein)."}),
            Map.entry("reload.done", new String[]{"Config reloaded, {0} portal(s).", "Config neu geladen, {0} Tor(e)."}),
            Map.entry("update.disabled", new String[]{"This server does not build the resource pack (resource-pack.enabled is false). Run the command on that server.", "Dieser Server baut das Ressourcenpaket nicht (resource-pack.enabled ist false). Führe den Befehl auf diesem Server aus."}),
            Map.entry("update.running", new String[]{"An update is already running.", "Läuft schon."}),
            Map.entry("update.start", new String[]{"Baking portal scenes …", "Backe Portalszenen …"}),
            Map.entry("update.jar", new String[]{"Downloading the Minecraft {0} client from Mojang (needed once for block textures) …", "Lade den Minecraft-{0}-Client von Mojang (einmalig, für die Blocktexturen) …"}),
            Map.entry("update.done", new String[]{"{0} scene(s) baked, pack delivered via {1}.", "{0} Szene(n) gebacken, Paket über {1} ausgeliefert."}),
            Map.entry("update.wait", new String[]{"{0} is still starting up, reloading in a moment …", "{0} startet noch, lade gleich neu …"}),
            Map.entry("update.failed", new String[]{"Update failed: {0}", "Aktualisieren fehlgeschlagen: {0}"}),
            Map.entry("set.selection", new String[]{"The portal to {0} now fills your marked opening ({1}x{2}, facing {3} – the side you stand on; no frame, build your own).", "Das Tor nach {0} füllt jetzt deine markierte Öffnung ({1}x{2}, zeigt nach {3} – zu deiner Seite; ohne Rahmen, bau deinen eigenen)."}),
            Map.entry("wand.given", new String[]{"Portal Wand: left-click one corner of the opening, right-click the other, then stand on the side players come from and run /mp set <server>.", "Portal Wand: Linksklick auf eine Ecke der Öffnung, Rechtsklick auf die andere, dann auf die Seite stellen, von der man hineingeht, und /mp set <Server>."}),
            Map.entry("wand.lore1", new String[]{"Left-click: first corner of the opening", "Linksklick: erste Ecke der Öffnung"}),
            Map.entry("wand.lore2", new String[]{"Right-click: second corner", "Rechtsklick: zweite Ecke"}),
            Map.entry("wand.pos1", new String[]{"Corner 1: {0} {1} {2}", "Ecke 1: {0} {1} {2}"}),
            Map.entry("wand.pos2", new String[]{"Corner 2: {0} {1} {2}", "Ecke 2: {0} {1} {2}"}),
            Map.entry("wand.ready", new String[]{"Opening {0}x{1} marked. Now: /mp set <server> [name]", "Öffnung {0}x{1} markiert. Jetzt: /mp set <Server> [Name]"}),
            Map.entry("wand.flat", new String[]{"Both corners must lie in the same wall plane (same x or same z).", "Beide Ecken müssen in derselben Wandebene liegen (gleiches x oder gleiches z)."}),
            Map.entry("wand.size", new String[]{"The opening may be at most {0}x{0} blocks.", "Die Öffnung darf höchstens {0}x{0} Blöcke groß sein."}),
            Map.entry("facing.south", new String[]{"south", "Süden"}),
            Map.entry("facing.west", new String[]{"west", "Westen"}),
            Map.entry("facing.north", new String[]{"north", "Norden"}),
            Map.entry("facing.east", new String[]{"east", "Osten"}),
            Map.entry("help.wand", new String[]{"/magicportals wand – tool to mark a portal opening of any size", "/magicportals wand – Werkzeug, um eine Toröffnung beliebiger Größe zu markieren"}),
            Map.entry("help.list", new String[]{"/magicportals list – portals on this server", "/magicportals list – Tore dieses Servers"}),
            Map.entry("help.set", new String[]{"/magicportals set <server> [name] – portal in the marked opening, or 3 blocks in front of you", "/magicportals set <Server> [Name] – Tor in der markierten Öffnung oder 3 Blöcke vor dir"}),
            Map.entry("help.remove", new String[]{"/magicportals remove <server>", "/magicportals remove <Server>"}),
            Map.entry("help.edit", new String[]{"/magicportals edit <server> [setting] [value] – show or change name, style, facing, size, inside-view, barriers, label, sound, volume, frame", "/magicportals edit <Server> [Einstellung] [Wert] – Name, Stil, Richtung, Größe, inside-view, barriers, label, sound, volume, frame anzeigen oder ändern"}),
            Map.entry("help.style", new String[]{"/magicportals style <server> <gate|rift|inferno|storm|void|frost> – look of the portal", "/magicportals style <Server> <gate|rift|inferno|storm|void|frost> – Aussehen des Tores"}),
            Map.entry("help.update", new String[]{"/magicportals update – bake scenes, build and deliver the resource pack", "/magicportals update – Szenen backen, Paket bauen und ausliefern"}),
            Map.entry("help.reload", new String[]{"/magicportals reload – reload config.yml", "/magicportals reload – config.yml neu einlesen"})
    );

    private final int language;

    Messages(String code) { language = "de".equalsIgnoreCase(code) ? 1 : 0; }

    String get(String key, Object... values) {
        String[] t = TEXTS.get(key);
        String text = t == null ? key : t[language];
        for (int i = 0; i < values.length; i++) text = text.replace("{" + i + "}", String.valueOf(values[i]));
        return text.replace("''", "'");
    }
}
