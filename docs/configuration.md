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
| `fallback-blocks` | `false` | For players without the resource pack (or with Iris/OptiFine shader packs): the server sends the destination's blocks behind the portal as client-side blocks. Costs bandwidth; leave off if everyone uses the pack. |

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

One entry per portal on this server. The key is the **target server**.

| Key | Default | Description |
|---|---|---|
| `name` | target, capitalised | Label floating above the portal and used in messages. |
| `style` | `gate` | `gate` – opening in a thick stone wall. `rift` – floating dimensional crack. |
| `x`, `y`, `z` | auto | Position of the portal (see below). If missing, MagicPortals places the portal on flat ground near the world spawn and writes the coordinates back. |
| `facing` | `south` | Direction the opening faces, i.e. the side players walk in from: `south`, `north`, `east` or `west`. `/mp set` fills this in for you. |
| `frame.outer` | `STONE_BRICKS` | Outer wall blocks (style `gate`). Any block material. |
| `frame.inner` | `POLISHED_BLACKSTONE_BRICKS` | Ring around the opening (style `gate`). |
| `frame` | – | Set to `none` for no wall at all (you build the frame yourself). The Portal Wand sets this automatically. |
| `width`, `height` | `3`, `3` | Size of the opening in blocks (1–7). For rifts it scales the crack and the walk-through area. |
| `inside-view` | `false` | `true`: cameras (spectators, freecam, replays) that fly through the opening see the destination all around (add `barriers: true` to keep normal players out of the space behind). `false`: the portal is flat from behind and nothing is placed behind it. |
| `barriers` | `false` | Only with `inside-view: true`: `true` places invisible barriers in the 3 blocks behind the opening so players cannot walk in there. Off by default – the space behind the portal stays walkable. |

**Position and orientation.** `facing` is the side players come from: with `facing: south` they approach from the south and walk north through the opening; the portal is flat (or shows a nebula, for rifts) from the other side.
`x y z` is the bottom centre block of the opening (for even widths the left one of the two middle blocks, seen from the front); `y` is the block players stand in (one above the floor). The portal plane is the back face of that block row. The easiest way to get it right is `/mp set <target>`: with a wand selection the opening faces the side you are standing on, without one the portal is placed 3 blocks in the direction you look, its opening facing you.

What gets built:

- **gate:** a wall of block displays reaching 2 blocks around the opening (amethyst rim, glowing glass edge). Invisible barriers in the wall stop players from walking through it. With `frame: none` nothing is built.
- **rift:** no wall. A few invisible light blocks make the surroundings glow.
- **both:** a hidden marker entity for the shader and a floating label; with `inside-view: true` (and `barriers: true`) also invisible barriers in the 3 blocks right behind the opening. Everything is removed again when the plugin stops; the world itself is not changed otherwise.

**Counterparts.** A portal from A to B shows what is in front of the portal from B to A, and players arriving from A spawn in front of that portal. So every portal needs a counterpart on its target server.

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
