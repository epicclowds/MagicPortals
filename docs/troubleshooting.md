# Troubleshooting

**The portal opening shows the normal world behind it.**
- Run `/mp list`. If it says *no shader scene yet*, run `/mp update` on the resource-pack server.
- The player has to load the new pack: rejoin or `F3`+`T`.
- Shader packs (Iris/OptiFine) disable resource-pack shaders. Turn the shader pack off, or enable `fallback-blocks`.
- Check the client log (`logs/latest.log`) for `Failed to load post chain` or `Couldn't compile`. If another pack overrides `rendertype_outline.vsh`, `rendertype_outline.fsh` or `post_effect/entity_outline.json`, see [Resource packs → Conflicts](resource-packs.md#conflicts-with-other-packs).

**Players get kicked to the main menu after accepting the pack / menus of other plugins break.**
A shader in the pack failed to compile and the client rejected the whole pack. Check the client log for `ShaderCompileException` and open an issue with the message. Make sure the server runs Minecraft 26.3.

**"… is offline right now."**
The target server has not sent a heartbeat for 16 seconds. Check that it runs MagicPortals with the same `exchange` settings and that `server:` matches the name in `velocity.toml`.

**Walking through does nothing / "The connection did not complete".**
- Velocity must have `bungee-plugin-message-channel = true` and the target name in `[servers]`.
- The portal target in the config must match the Velocity server name exactly.

**I arrive at the spawn instead of in front of the portal.**
The arrival is fetched from the exchange when you join. If the database is slow or unreachable, it may be missed. Check the server log for `Exchange:` warnings. The target server also needs a portal back to the server you came from.

**`/mp update` says the client jar could not be found / downloaded.**
The server needs internet access to Mojang once. Alternatively copy a Minecraft client jar of the same version onto the server and set `resource-pack.client-jar` to its path.

**The view through the portal is outdated.**
Views are snapshots. Every server re-records its portals every minute; run `/mp update` to put the latest state into the pack.

**Something looks wrong in the destination (missing or odd blocks).**
Blocks with special renderers (chests, signs, banners, beds, heads) and some multi-part blocks are simplified or left out. Water is drawn as a translucent surface. Please open an issue with a screenshot if a common block looks broken.

**Players without the pack / with shader packs.**
`fallback-blocks: true` makes the server send real blocks of the destination behind the opening, only to players looking through it. Works without any pack, but costs bandwidth and does not show the rift effect.
