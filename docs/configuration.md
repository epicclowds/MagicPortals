# Configuration

`plugins/MagicPortals/config.yml` – one file per server. `/mp reload` applies changes.

```yaml
server: lobby
language: en

exchange:
  type: mysql
  folder: ../portal-exchange
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    username: magicportals
    password: ''
    table-prefix: magicportals_
    options: useSSL=false&allowPublicKeyRetrieval=true

fallback-blocks: false

portals:
  survival:
    name: Survival
    style: gate
    x: 26
    y: 60
    z: -30
    frame:
      outer: STONE_BRICKS
      inner: POLISHED_BLACKSTONE_BRICKS

resource-pack:
  enabled: true
  provider: auto
  client-jar: ""
  ultimateui: auto
  standalone:
    port: 8165
    url: ""
    required: false
    prompt: "This server uses a resource pack for its portals."
```

## General

| Key | Default | Description |
|---|---|---|
| `server` | – | **Required.** Name of this server exactly as listed under `[servers]` in `velocity.toml`. |
| `language` | `en` | Language of in-game messages: `en` or `de`. |
| `fallback` | `none` | Default for all portals (`none`, `nether`, `end`, `world`), see `fallback` under portals. The old `fallback-blocks: true` still works and means `world`. For players without the resource pack (or with Iris/OptiFine shader packs): the server sends the destination's blocks behind the portal as client-side blocks. Costs bandwidth; leave off if everyone uses the pack. |
| `wand-model` | `true` | The Portal Wand (`/mp wand`) uses its own animated model from the MagicPortals pack – a staff with a glowing violet-cyan crystal. Works with Oraxen, ItemsAdder and the built-in web server, no extra plugin needed. `false`: plain golden pickaxe, e.g. if a server does not get the pack. |

## `exchange`

How the servers share data. **All servers must use the same type and the same database or folder.**

| Key | Default | Description |
|---|---|---|
| `type` | `folder` | `mysql` (MySQL/MariaDB) or `folder` (shared directory). |
| `folder` | `../portal-exchange` | Shared directory, relative to the server directory. Only for `folder`. |
| `mysql.host` / `port` | `localhost` / `3306` | Database server. |
| `mysql.database` | – | Database name. |
| `mysql.username` / `password` | – | Credentials. Keep the config private. |
| `mysql.table-prefix` | `magicportals_` | Prefix of the tables MagicPortals creates. |
| `mysql.options` | `useSSL=false&allowPublicKeyRetrieval=true` | Extra JDBC URL options. |

What is stored: a heartbeat per server (every 5 s), arrivals while a player switches servers (deleted on pickup), the portal snapshots (gzipped, only written when they change) and the scene numbers assigned by `/mp update`. Timestamps come from the database clock, so servers with different clocks are fine.

## `portals`

One entry per portal on this server. The key is the portal's **name**; classic configs use the target server as name.

| Key | Default | Description |
|---|---|---|
| `name` | target, capitalised | Name used in messages and, with `label: true`, floating above the portal. |
| `label` | `false` | `true` shows the name (and an "offline" note while the target is down) floating above the portal. |
| `sound` | `false` | `true` plays the style's ambient sounds (crackling fire, thunder, humming …) to players nearby. Sound category *Ambient/Environment*, so players can turn it down in their settings. |
| `volume` | `1.0` | Volume of those sounds, `0` – `4` (above 1 they also carry further). |
| `particles` | `1.0` | Amount of particles, `0` (none) – `5`. |
| `effects` | `false` | `true` turns on the style's effect on nearby players (see [Styles](#styles)): burning, frozen screen, darkness, camera jolt. Only visual, no damage. |
| `fallback` | global `fallback` | What players **without the shader** see (shader packs like Iris/Complementary, or no server pack): `none` (default), `nether` – the opening filled with nether portal blocks, `end` – filled with end gateway blocks (starfield), `world` – the destination as client-side blocks behind the portal. The blocks only exist on the clients; players with the shader see the real view drawn over them. |
| `maintenance` | `false` | Closed for maintenance: nobody can walk through (admins can by sneaking). Switch with `/mp maintenance <portal|all> [on|off]`. |
| `maintenance-look` | `ring,text` | Any of `ring` (a ring of glowing letters turning around the portal), `text` (a sign "⚠ MAINTENANCE ⚠" with a line below), `bars` (iron grille with yellow-black barrier tape), or `all`. |
| `maintenance-text` | "This portal is temporarily closed." | Line under the sign; `none` for just the title. |
| `maintenance-push` | `true` | Players who come close are flung back with sparks and a sound. |
| `style` | `gate` | The look, see [Styles](#styles): `gate`, `rift`, `inferno`, `storm`, `void`, `frost`. |
| `x`, `y`, `z` | auto | Position of the portal (see below). If missing, MagicPortals places the portal on flat ground near the world spawn and writes the coordinates back. |
| `facing` | `south` | Direction the opening faces, i.e. the side players walk in from: `south`, `north`, `east` or `west`. `/mp set` fills this in for you. |
| `target` | the name | Server the portal leads to. Can be left out when the name is the server name. |
| `link` | – | Connection ID. Two portals with the same ID (on two servers, each targeting the other) form a pair. Without an ID the portal pairs with "the portal back to me". |
| `world` | main world | World the portal stands in, e.g. `world_nether` or `world_the_end`. `/mp set` fills this in with the world you are in. |
| `frame.outer` | `STONE_BRICKS` | Outer wall blocks (style `gate`). Any block material. |
| `frame.inner` | `POLISHED_BLACKSTONE_BRICKS` | Ring around the opening (style `gate`). |
| `frame` | – | Set to `none` for no wall at all (you build the frame yourself). The Portal Wand sets this automatically. |
| `width`, `height` | `3`, `3` | Size of the opening in blocks (1–7). For rifts it scales the crack and the walk-through area. |
| `inside-view` | `false` | `true`: cameras (spectators, freecam, replays) that fly through the opening see the destination all around (add `barriers: true` to keep normal players out of the space behind). `false`: the portal is flat from behind and nothing is placed behind it. |
| `barriers` | `false` | Only with `inside-view: true`: `true` places invisible barriers in the 3 blocks behind the opening so players cannot walk in there. Off by default – the space behind the portal stays walkable. |

**Position and orientation.** `facing` is the side players come from: with `facing: south` they approach from the south and walk north through the opening; the portal is flat (or shows a nebula, for rifts) from the other side.
`x y z` is the bottom centre block of the opening (for even widths the left one of the two middle blocks, seen from the front); `y` is the block players stand in (one above the floor). The portal plane is the front face of that block row, flush with the frame. The easiest way to get it right is `/mp set <target>`: with a wand selection the opening faces the side you are standing on, without one the portal is placed 3 blocks in the direction you look, its opening facing you.

What gets built:

- **gate:** a wall of block displays reaching 2 blocks around the opening (amethyst rim, glowing glass edge). Invisible barriers in the wall stop players from walking through it. With `frame: none` nothing is built.
- **rift, inferno, storm, void, frost:** no wall. A few invisible light blocks make the surroundings glow.
- **both:** a hidden marker entity for the shader and, with `label: true`, a floating name; with `inside-view: true` (and `barriers: true`) also invisible barriers in the 3 blocks right behind the opening. Everything is removed again when the plugin stops; the world itself is not changed otherwise.

**Counterparts.** A portal from A to B shows what is in front of the portal from B to A, and players arriving from A spawn in front of that portal. So every portal needs a counterpart on its target server.

## Styles

Change a portal's look at any time with `/mp edit <server> style <name>` (or `/mp style`). The shapes and edge effects are drawn by the shader; particles and sounds come from the server. All styles scale with `width`/`height`.

| Style | Shape | Effects | Seen from behind | Good for |
|---|---|---|---|---|
| `gate` | Rectangle in a stone wall (or your own frame) | Glowing cyan inner edge, sparks | Flat | Spawn, hubs |
| `rift` | Tall jagged crack | Cyan / magenta / gold edge, lightning arcs, sparkles, particles pulled in | Starry nebula | Anything magical |
| `inferno` | A giant fire: one big flame with two smaller tongues, swaying and flickering | Fire licking off the outline, embers, heat shimmer in the opening, smoke and lava drips. Effect: players right in front of it burn (only visually) | Wall of fire | Nether, fire dungeons |
| `storm` | Eye of the storm: round opening in a ring of swirling thunderclouds | Clouds lit by lightning from inside, electric rim, arcs, a bolt striking through the opening; every few seconds a (harmless) lightning strike hits the ground in front of it. Effect: the camera of players next to a strike jolts | Dark thunderclouds | Boss chambers, arenas |
| `void` | Round vortex | Swirling purple rim and dark halo, the view twisted towards the edge, specks spiralling in. Effect: darkness pulses over the screen of players nearby | Black spiral | The End, void worlds |
| `frost` | A big ice crystal rising from the ground, with smaller shards at its foot | You look through the ice (blue tint, frosted edges, slight refraction), glitter, cold mist, snowflakes. Effect: the screen freezes over like in powder snow – never long enough to hurt | Cracked ice | Winter areas, ice dungeons |

Sounds are off by default (`sound: false`). The lightning strikes of `storm` are only particles, a short flash of light (client-side) and – with sound on – thunder for nearby players; they do no damage, start no fires and are not heard across the whole world like real lightning.

## Custom sounds

With `sound: true` every style plays its own sounds. Each sound **slot** can be replaced – by any Minecraft sound, a sound from another resource pack (Oraxen, ItemsAdder, …) or your own `.ogg` files.

| Slot | Plays | Built-in sound |
|---|---|---|
| `ambient` | often, the main background sound | fire (inferno), portal whoosh (void), chimes (frost) |
| `hum` | every 4–5 s | beacon hum (gate, rift, storm, void), blaze (inferno) |
| `accent` | now and then | amethyst (rift, frost), lava (inferno), enderman (void) |
| `crackle` | short, frequent | small lightning (rift, storm), lava pop (inferno), snow steps (frost) |
| `strike` | storm: lightning hits the ground | lightning impact |
| `thunder` | storm: with every strike | thunder |
| `enter` | to the player walking through | – (silent unless set) |

```yaml
portals:
  boss:
    sound: true
    sounds:
      strike: magicportals:storm      # own files, see below
      thunder: minecraft:entity.warden.sonic_boom
      hum: none                       # silent
      enter: magicportals:whoosh
```

In game: `/mp edit boss sound.strike magicportals:storm`, `… sound.hum none`, `… sound.hum default`. A custom sound plays at the portal's `volume` and normal pitch.

**Your own files.** Put `.ogg` files (Ogg Vorbis) into `plugins/MagicPortals/sounds/` on the **resource-pack server** and run `/mp update` – they are added to the pack with a generated `sounds.json`:

```
sounds/whoosh.ogg            ->  magicportals:whoosh
sounds/storm/thunder1.ogg    ->  magicportals:storm/thunder1
sounds/storm/thunder2.ogg    ->  magicportals:storm/thunder2
                                 magicportals:storm   (one of the files in storm/ at random)
```

- File and folder names: lowercase letters, digits, `_ - .` only.
- Use **mono** files for sounds that should come from the portal; stereo files are heard at the same volume everywhere.
- Files above ~300 KB (music, long loops) are streamed automatically.

## View

The `view` section of a portal changes how the destination looks **through this portal** – it is baked into the scene, so run `/mp update` after changing it. All keys are optional and can also be set with `/mp edit <server> <key> <value>`.

```yaml
portals:
  lobby:
    view:
      time: night          # day, sunset, night
      fog: 2               # 0 = crystal clear, 1 = light haze far away (default), up to 8 = very dense
      fog-color: "#3060ff" # hex or a name (blue, red, green, purple, orange, white, …); none = sky colour
      tint: "#ff4020"      # colour filter over the whole view; none = off
      tint-strength: 0.5   # 0 – 1
      weather: snow        # none, rain, snow, ash, embers
      blur: 1              # depth of field: 0 = all sharp, 1 = default, 2 = strong
```

| Key | Default | Effect |
|---|---|---|
| `time` | `day` | `sunset`: orange sky and warm light. `night`: dark blue sky with stars; torches, lanterns, lava and other light sources glow (block light is baked into the scene).  In worlds with a ceiling (Nether) the view switches to a **cave look** by itself: rock ceiling, dark red distance instead of sky, glowing lava. In the **End** it switches to the End look: dark purple sky with faint streaks, black void below the islands. |
| `fog` | `1` | `1`: no fog, only a light haze on distant land. `0`: crystal clear. Above `1` the destination fades into fog earlier (`8`: after a few blocks). |
| `fog-color` | sky | Colour of the fog and the sky behind it – blue, red, green fog … |
| `tint` / `tint-strength` | off / `0.5` | Colour filter over the view. |
| `weather` | `none` | Animated particles in the destination with real depth: `rain`, `snow`, `ash` (grey flakes, Nether), `embers` (rising sparks). |
| `blur` | `1` | Depth of field like a camera: nearby blocks stay sharp, everything further away gets increasingly out of focus. `0` turns it off, `2` doubles it. |

## `resource-pack`

Enable this on **exactly one** server.

| Key | Default | Description |
|---|---|---|
| `enabled` | `false` | This server bakes the portal views and delivers the pack (`/mp update`). |
| `provider` | `auto` | `oraxen`, `itemsadder`, `standalone`, `none` or `auto` (Oraxen → ItemsAdder → standalone). |
| `client-jar` | `""` | Path to a Minecraft client jar of the server version. Empty: downloaded from Mojang once into `plugins/MagicPortals/cache/`. |
| `ultimateui` | `auto` | Ultimate UI also replaces the outline shader. `auto` detects it and merges its HUD code into the MagicPortals shader. `true`/`false` to force. |
| `standalone.port` | `8165` | Port of the built-in web server. |
| `standalone.url` | `""` | Public URL of the pack. Empty: `http://<server-ip or localhost>:<port>/MagicPortals.zip`. Set this when players connect from other machines. |
| `standalone.required` | `false` | Kick players who decline the pack. |
| `standalone.prompt` | text | Message shown in the pack prompt. |

Details in [Resource packs](resource-packs.md).

## Files

| Path | Content |
|---|---|
| `plugins/MagicPortals/config.yml` | This configuration |
| `plugins/MagicPortals/MagicPortals-pack.zip` | The last built resource pack (pack server) |
| `plugins/MagicPortals/bake/` | Working folder of `/mp update` |
| `plugins/MagicPortals/cache/client-<version>.jar` | Downloaded Minecraft client (pack server) |
