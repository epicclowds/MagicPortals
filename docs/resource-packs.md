# Resource packs

The portal view is drawn by shaders inside a resource pack. `/mp update` on the resource-pack server

1. stores fresh snapshots of its own portals,
2. collects the snapshots of all servers from the exchange,
3. bakes them into three textures (voxels, block palette, texture atlas) plus a small shader include,
4. adds the portal shaders and builds `plugins/MagicPortals/MagicPortals-pack.zip`,
5. hands it to the configured provider.

Players have to reload the pack afterwards (rejoin or `F3`+`T`).

## Providers

### Oraxen

`provider: oraxen` (or `auto` with Oraxen installed)

The zip is copied to `plugins/Oraxen/pack/uploads/MagicPortals.zip`, then MagicPortals runs `oraxen reload pack`. Oraxen merges it into its own pack and sends it to players as usual.

Right after a server start Oraxen is still building its pack and would ignore a reload; MagicPortals therefore waits until the server has been up for 60 seconds before reloading.

### ItemsAdder

`provider: itemsadder` (or `auto` with ItemsAdder installed, Oraxen not)

The files are copied to `plugins/ItemsAdder/contents/magicportals/resourcepack/assets/minecraft/…`, then MagicPortals runs `iazip`.

> The ItemsAdder integration places the files and triggers `iazip`, but has not been tested on a live ItemsAdder server yet. Please report problems.

### Standalone (no pack plugin)

`provider: standalone` (or `auto` with neither installed)

MagicPortals starts a tiny web server and sends the pack to every player who joins this server (and to everyone online after `/mp update`).

```yaml
resource-pack:
  enabled: true
  provider: standalone
  standalone:
    port: 8165
    url: "http://play.example.com:8165/MagicPortals.zip"
    required: false
    prompt: "This server uses a resource pack for its portals."
```

- Open the port in your firewall, or put the pack behind your own web server/CDN and set `url` to that address.
- Without `url`, the address is built from `server-ip` in `server.properties` (or `localhost`) – fine for testing on one machine, not for real players.
- Players normally join through this server first (e.g. the lobby). The client keeps the pack when Velocity moves them on.

### None

`provider: none` only builds `MagicPortals-pack.zip`. Merge it into your own pack however you like. The zip contains everything inside the overlay folder `magicportals_26_3/`.

## What the pack contains

```
pack.mcmeta, pack.png
magicportals_26_3/assets/minecraft/
  shaders/core/rendertype_outline.vsh|fsh     marker → camera data
  shaders/include/magicportals_marker.glsl
  shaders/include/magicportals_scene.glsl     generated: scene sizes, count
  shaders/post/magicportals.fsh               the portal renderer
  shaders/post/magicportals_scrub.fsh
  post_effect/entity_outline.json             hooks the shaders into the outline pass
  textures/effect/magicportals/voxel.png      generated: all scenes
  textures/effect/magicportals/palette.png    generated: block shapes, tints, faces
  textures/effect/magicportals/atlas.png      generated: block textures used by the scenes
```

The generated textures contain Minecraft block textures taken from the client jar on your server; they are built locally and never part of this repository.

## Conflicts with other packs

MagicPortals replaces three vanilla files: `shaders/core/rendertype_outline.vsh`, `rendertype_outline.fsh` and `post_effect/entity_outline.json`. If another pack also replaces them, only one can win.

- **Ultimate UI** replaces `rendertype_outline.vsh` for its HUD. MagicPortals detects Ultimate UI (`ultimateui: auto`) and puts Ultimate UI's HUD hook into its own version of the shader, so both keep working. In testing with Oraxen, `MagicPortals.zip` was imported after Ultimate UI's upload and its merged shader won. If in your setup the Ultimate UI version ends up in the final pack instead (check `rendertype_outline.vsh` inside Oraxen's `pack.zip` for `magicportals_marker`), the portals stay empty.
- **Other plugins/packs** touching these files: make sure the MagicPortals files take priority, and merge the other pack's changes into them if you need both.

## Shader packs

Iris and OptiFine shader packs switch off core shaders from resource packs. Players with a shader pack enabled see an empty opening. Use `fallback-blocks: true` if you want them to see something – see [Troubleshooting](troubleshooting.md).
