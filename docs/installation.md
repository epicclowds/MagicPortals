# Installation

This guide sets up a small network from scratch: a **Velocity** proxy, a **lobby** and a **survival** server, with one portal pair between them. Adapt names and ports to your network.

```
          players
             │  :25565
      ┌──────▼──────┐
      │   Velocity  │
      └──┬───────┬──┘
   :25566│       │:25567
   ┌─────▼──┐ ┌──▼───────┐
   │ lobby  │ │ survival │   ← both run MagicPortals
   └────────┘ └──────────┘
          ╲      ╱
       MySQL / shared folder   ← "exchange"
```

## 1. Velocity

MagicPortals moves players with the standard plugin message `Connect`, so the backend servers have to sit behind Velocity.

`velocity.toml` (only the relevant parts):

```toml
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:25566"
survival = "127.0.0.1:25567"
try = ["lobby"]

[advanced]
bungee-plugin-message-channel = true   # default, must stay true
```

The names under `[servers]` are the names you use in MagicPortals (`server:` and portal targets).

## 2. Paper servers

For every backend server:

- `server.properties`: `online-mode=false` and a unique `server-port`
- `config/paper-global.yml`:
  ```yaml
  proxies:
    velocity:
      enabled: true
      online-mode: true
      secret: '<contents of forwarding.secret>'
  ```
- Put `MagicPortals.jar` into `plugins/` and start the server once. This creates `plugins/MagicPortals/config.yml`.

## 3. The exchange

All servers must be able to see each other's heartbeats, arrivals and portal snapshots.

**MySQL / MariaDB** – recommended, works across machines and in cloud systems:

```yaml
exchange:
  type: mysql
  mysql:
    host: 10.0.0.5
    port: 3306
    database: minecraft
    username: magicportals
    password: 'secret'
    table-prefix: magicportals_
```

MagicPortals creates its tables itself (`magicportals_server`, `_ankunft`, `_aufnahme`, `_szene`, `_stand`). The MySQL driver ships with Paper, nothing to install. A dedicated database user with rights only on these tables is a good idea.

**Shared folder** – only if every server runs on the same machine:

```yaml
exchange:
  type: folder
  folder: ../portal-exchange   # relative to the server directory, same folder for all servers
```

## 4. Name each server

In every server's `config.yml`:

```yaml
server: lobby   # exactly as in velocity.toml
language: en    # or de
```

## 5. Choose the resource-pack server

Exactly **one** server builds and ships the resource pack – usually the lobby, or whichever server already handles your pack. On that server:

```yaml
resource-pack:
  enabled: true
  provider: auto
```

`auto` uses Oraxen if it is installed, otherwise ItemsAdder, otherwise the built-in web server. See [Resource packs](resource-packs.md) for details, ports and public URLs.

The client keeps the pack when Velocity moves a player to another server, so the other servers do not need to send it.

Restart all servers.

## 6. Create the first portal pair

1. Join the lobby, walk to where the portal should be and **look north**.
2. `/mp set survival Survival` – the portal appears 3 blocks in front of you.
3. Join the survival server (e.g. `/server survival`), go to the spot for the way back, look north.
4. `/mp set lobby Lobby`
5. Optional: `/mp style lobby rift` for the dimensional rift look.
6. Back on the resource-pack server: `/mp update`.
   The first update downloads the Minecraft client jar from Mojang (about 40 MB, once) – it contains the block textures and models used for baking.
7. Rejoin or press `F3`+`T`. Look through the portal, then walk through it.

`/mp list` shows every portal with its online state and scene number. A portal shows *no shader scene yet* until the next `/mp update`.

## Adding more servers later

1. Set up the new Paper server like above (Velocity entry, forwarding secret, MagicPortals with the same `exchange`).
2. Give it a portal back, e.g. leave `portals: { lobby: { name: Lobby } }` without coordinates – it is placed near the spawn on first start – or use `/mp set lobby` in game.
3. On the lobby: `/mp set <new server>`.
4. `/mp update` on the resource-pack server.

## Updating from "Dorfportal"

MagicPortals is the successor of the German-only *Dorfportal* plugin. On the first start it copies `plugins/Dorfportal/config.yml`, converts the German keys (`tore`, `austausch`, `stil`, …) and keeps your MySQL table prefix, so existing snapshots stay valid. Remove the old `Dorfportal.jar` and run `/mp update` once.
