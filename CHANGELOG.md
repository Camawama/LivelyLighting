# Changelog

## 0.6.1 — Unreleased

### Added

- **Item frames on ships are headlights** (`client.frames_on_ships_are_headlights`, on): a lit item in a frame on a Valkyrien Skies
  ship shines as a cone along the frame's facing, turned with the ship.

### Fixed

- An item definition for an item registered after the definitions were read (a KubeJS item) never applied: the id is kept and
  matched when the item is first looked up.

### Changed

- With Colorful Lighting 2.9.1 a round light is round again (the field's levels are the analytic falloff wherever the way through the
  blocks is straight) and a moving light slides between ticks instead of stepping; a spot light lights everything within two blocks of
  itself, so a kart's body under its headlights no longer flickers (`DynamicLights.lightAt` agrees).

## 0.6.0 — Unreleased

### Added

- **Light that spreads like Minecraft's** (2026-11-09, with Colorful Lighting 2.9.0). On the shader path a dynamic light is propagated through
  the blocks round the camera the way a placed lamp's light is: a level lost per block along the path, stopped by walls, tinted by stained glass,
  round corners, with the blocks of Valkyrien Skies ships included (`client.transmission` `voxel`, on). 0.5.1 marched a straight line from each
  vertex to each light, which drew hard shadows with jagged edges, black behind every wall and no light through a ship's glass.
- **Colours for every light.** A definition without a `color` takes the colour Colorful Lighting gives its source: a held glowstone lights the
  ground orange as the placed block does, a burning mob's light is fire's, a ship's or contraption's lamp its block's (Colorful Lighting 2.9.0 for
  lamps). Before, every such light was white.
- **Dynamic light is the world's light on the server** (`gameplay_light`, on): mob spawning, snow and ice melting, crops and anything else that asks
  how bright a place is see the carried, worn and burning lights and other mods' lights, as they saw the light blocks; a point light of each
  source's level losing a level per block (walls don't stop it). Idle in `server_blocks` mode. Camafy's `DynamicLights.lightAt` is answered on
  the server from the same lights, and the client's raw brightness (`getMaxLocalRawBrightness`) from what it draws.
- **A client on a server without the mod draws for itself** (`client.draw_without_server`, on): a vanilla server or another pack says nothing
  about a mode, and the client draws the light it can see; a server with an older Lively Lighting places blocks and is never drawn over. The mod
  works on the server alone, on the client alone, or both.
- **A Lively Lighting page in Embeddium's video settings** with the client keys: renderer, falloff, light through blocks, propagated lightmap
  lights, spot beams and their opacity, the shader and lightmap budgets, drawing without a server.
- `client.spot_beam_opacity` (0.06): the beam's opacity at the light (a fixed 0.22 before, far too solid).
- `client.world_light` (on): the client's own block light layer and raw brightness answer the drawn dynamic light, for mods that read
  how bright the player's place is.

### Fixed

- A light put away vanished at once instead of fading out: the fading light's reach took the wanted level (0), so it reached one block. The
  reach follows the faded level now, and other mods' lights that stop being given fade out where they were (they vanished at once too).
- Other mods' lights fade with `smoothing`, as players' do (they needed `smoothing_all_entities`).

## 0.5.1 — Unreleased

### Added

- **Other mods' lights** (2026-11-09). Camafy 0.1.84's `light.DynamicLights`: a mod registers a provider and its point lights and spot cones
  are drawn with the mod's own (faded the same, in their colour); `DynamicLights.lightAt` is answered from what the client draws. MKW
  Mobility's karts get headlights this way. Camafy 0.1.84 is the floor now.
- **Spot beams** (`client.spot_beams`): a faint additive cone in the air along a spot light, brightest at the light and gone at its range.
- **LambDynamicLights item files** (`assets/<ns>/dynamiclights/item/*.json`) are read on the client at every resource reload as definitions
  of the lowest priority, so a pack's existing light sources light here without conversion.
- **Light through blocks** (`client.transmission`: `voxel`, on by default): with Colorful Lighting 2.8.1 the shader path marches each light through
  the blocks round the camera: a wall stops it (a torch behind a wall lights neither the room beyond nor the ground through the floor) and
  stained glass tints it, as Colorful Lighting's filters tinted the block mode's light. `off` lets light through everything, as other dynamic
  lighting mods do.
- **Propagated point lights on the lightmap path** (`client.lightmap_propagate`, on): with Colorful Lighting 2.8.1 a point light is a lamp its
  engine propagates, stopped by walls, tinted by glass, read by shader packs as block light.

## 0.5.0 — Unreleased

### Added

- **The client module** (2026-11-09). Dynamic light drawn on the client with no light block placed: the same sources, definitions and
  configuration keys as the server module, read by the shared `LightCalculator`. With Colorful Lighting 2.8.0 the lights are handed to
  its dynamic light provider API and drawn in colour through its light sampling, or in its Embeddium and Flywheel shaders where no
  shader pack replaces them (no mesh rebuilt when a light moves); without it, white light through the vanilla and Embeddium light
  lookups with a rebuild budget. `lighting_mode` (`server_blocks`, `client`, `hybrid` default) decides who shows the light; the
  server tells each client in a `Mode` message (channel version 3), with the forced and switched-off entities of `/ll add` and
  `/ll toggle`. In `hybrid` the server places light blocks only where a player whose client lacks the mod can see them.
- **Light shapes.** `shape` (`point`, `spot`), `cone_degrees`, `range` and `color` on item, entity and block definitions; spot lights
  are headlights on ships and contraptions and flashlights in hand. `block_definitions/` (default: `minecraft:end_rod` is a
  headlight), the tags `#livelylighting:headlights` and `#livelylighting:flashlights`. `client.falloff` picks vanilla's diamond or a
  round light.
- **Create contraptions** are moving structures like ships: their lamps light the world as they move (client module).
- `/ll config` sets string options too (`lighting_mode`, `client.renderer`, `client.falloff`).

### Changed

- `LightCalculator` takes any `Level` and is thread-safe (the client and the integrated server share it in singleplayer); entity
  definitions are full definitions (shape, colour), not bare levels.
- The description no longer says "fully server-sided".

## 0.4.4 — Unreleased

### Added

- **Curio slots light up** (2026-11-06). With Curios installed, an item with a light level worn in any curio slot (a torch or lantern on a belt) lights its wearer like one in the off hand: the server takes the brightest worn curio into the entity's light (`LightCalculator`; a `curios` source of its own on the cluster path), clients are told the light comes from the curios (`LightSources.SLOT_CURIOS`, the token a fold of the worn items' ids both sides compute alike) and colour it from the first worn curio Colorful Lighting knows a colour for (`LightSourceColors.curioColour`). Curios is reached by reflection (`compat.CuriosLights`); nothing changes without it. Config `curios_lighting` (on). The mod also puts the torch, soul torch, lantern and soul lantern in Curios' `curios:belt` tag, so a pack with a belt slot (Spyglass Improvements adds one to players) can hang a lantern there. Pack harness `ll_curios` (the main pack).

### Fixed

- **Camafy is required on both sides** (2026-10-25). Its `mods.toml` entry was `side="SERVER"`, and Forge never checks a server-side dependency on a client, so a singleplayer install without Camafy loaded and then failed once the light engine ran in its integrated server (`LightManager`, `LightLogic`, `LightPropagator` use Camafy's ship classes unguarded). Now `side="BOTH"`: Forge names the missing Camafy at startup. Clients still don't need Lively Lighting to join a server that runs it.
- **A burning entity's light white** (reported 2026-10-17: when an entity first catches fire its light is white, and only turns fire-coloured once it starts moving). Not reproduced as such: in the pack harness a pig and the player set alight, still and moving, had fire's colour from the first tick their light had any (`ll6_ignite`). What does make a burning entity's light white: a light merged from several sources (with `cluster_growing`, each of an entity's slots is a source of its own, and with ultra smoothing they share the eye's cells) took the brightest of each channel of every source at full strength, so the plain white glow of an enchanted item (`enchanted_items_glow`, level 6) held by a burning mob or player won every channel over the fire (level 15), and a torch's near-white did the same; the light stayed white as long as the item was held and the fire burned. Each source now carries its own light level (the channel `livelylighting:light_sources` at version 2; the kind's upper bits), and the client colours a merged light by the light each source gives (its colour at its level, the brightest of each channel, brought back to full for the brightest source): fire over an enchanted sword's glow is fire, slightly warmed; a torch (14) beside a soul lantern (10) is the torch's colour. An older client without the new channel version keeps Colorful Lighting's own guess.
- **A ship with many lamps cost clients their frames as it moved** (reported 2026-10-17: many light sources on one Valkyrien Skies ship generate a lot of frame drops for clients as it moves; a hard cap for dynamic light sources on a single ship in the config was suggested).
  - Cause: a ship's lamps are projected into the world as the mod's light blocks, merged into one light per `cluster_merge_distance` cell; with `cluster_growing` on (the pack's config) a merged cluster grows by the span of its members, and a cluster of lamps 5 blocks apart put a blob of up to a hundred level-15 light blocks round its centroid, every one of them moved (a block change on the server, a light update and a chunk rebuild on every client) each time the ship crossed a cell.
  - Fix: a cluster made of a ship's lamps alone never grows (one light block per merged cluster at the lamps' level; growth is for lights spread over a span, several holders' torches), and new option **`max_lamps_per_ship`** (default `32`): at most that many of a ship's lamps are projected, the brightest first, then the nearest a player (chosen again with the ship's lamp scan every three seconds); the rest give the world no light, the ship's own blocks stay lit by its lamps as ever. `-Dlivelylighting.shipLampsGrow=true` and `-Dlivelylighting.maxLampsPerShip=<n>` flip both live (the pack harness's A/B).
  - Measured (pack harness `ll6_shiplamps`: a 33x33 plank deck hovering with 64 sea lanterns in a grid, the pack's config (cluster growing, ultra smoothing), cruising 15 blocks east at 3 m/s and back, watched from the ground, the switches flipped live in one session): as before (clusters grown) the server wrote 3,725 light blocks a second (1,649 placed, 1,647 removed, the rest re-levelled), the client ran 705 block-light section updates a second, frames p95 21.1 ms / p99 98 ms, 22 of 10 s over 50 ms, server ticks mean 21 ms, p95 69 ms, 18 over 50 ms; not grown: 121 writes/s, 259 section updates/s, frames p95 14.8 ms / p99 48 ms, ticks mean 17 ms, p95 20 ms; capped at 16: 61 writes/s, 191/s, p95 10.9 ms / p99 17.7 ms. The deck's light blocks in the world: 21 for the 64 lamps (merged per 6-block cell), 10 under the cap of 16. Standing still every variant costs nothing (0.4 writes/s).
- **Held automatic emitters were white** (the Colorful Lighting compat didn't work with its automatic emitters: held, they always looked white, while placed they had the proper colour). Colorful Lighting's side: the colour it gave a held or dropped light item took only an `emitters.json` colour (a block it had sampled from its texture, or an entry marked `auto`, came out white); it now gives the colour the placed block emits (Colorful Lighting `DynamicLightsCompat.resolveStackColor`, `Config.getLightColor(Block)`); nothing changed here, the client half already asks Colorful Lighting for the item's colour.

### Tests

- Pack harness `ll6_held` (held automatic emitters' colour against placed), `ll6_shiplamps` (64 lamps on a 33x33 deck cruising at 3 m/s: frames, light updates, block writes, the cap).

## 0.4.3 — Unreleased

### Fixed

- **Coloured light turning white** with Colorful Lighting (2026-10-12): a dynamic light source moving too fast lost the colour of its trail; a held dynamic light source switched for an empty hand faded out white; two Valkyrien Skies ships with blue light brought next to each other clustered their light together and it turned white; and a ship nearing a red lamp lit up white at the edge of the lamp's reach before it turned red.
  - Cause: Colorful Lighting worked out the colour of each of the mod's light blocks itself, every time the block's light was spread: from the coloured entity within 8 blocks of it, or, near a ship, from the light it had stored round the block's mirror across the ship's boundary (a lamp within 3 blocks there, or a cell bright enough to tell a hue). A trail cell fading a few ticks behind a fast holder is more than 8 blocks from it; a light fading after its item was put away has a holder that holds nothing; a light merged from several lamps stands where none of them is; the dim edge of a world lamp's light on a ship has nothing bright enough round its mirror. All of them fell back to white.
  - Fix: every light now carries what it stands for (`LightSources`): the entity, the slot its light comes from (or the hands and armour together) and what is in it; a lamp's block, in the world or in a ship's shipyard; a burning ship. A merged light carries every one of its sources (at most four). The server tells each client with the mod what the lights round it stand for (channel `livelylighting:light_sources`, optional on both sides; `LightSourceSync`): each light once, again when its sources change or when the player starts watching its chunk again, nothing when nothing changed. A light fading out keeps the sources it was lit with. The client half (`client.LightSourceColors`, with Colorful Lighting) works out the colour Colorful Lighting gives each source (an item's colour, an entity's, fire's, or the colour a lamp's light leaves it with, a filter beside it included; several sources per channel, as light adds up) and answers Colorful Lighting's block light colour API with it. An entity's light waits up to a second for this client's copy of the entity to hold what the server's did.
  - New server option `light_source_colors` (default `true`). Clients without the mod keep Colorful Lighting's own guess; `-Dlivelylighting.noLightSourceColors=true` on a client ignores what it is told (an A/B). Needs a Colorful Lighting with the source colour helpers (2026-10-12); with an older one the client half says so in the log once and stands down.
  - Measured (pack harness, the pack's config: ultra smoothing and cluster growing on; light blocks read on the client with the colour Colorful Lighting stored in their cell, "white" = under half the saturation of a soul lantern's #36d9e6; the client switch flipped for the A/B in otherwise identical sessions): a soul lantern carried at 2.5 blocks a tick, 46 of 250 trail cells white -> 0 of 210; at 5 blocks a tick 214 of 408 -> 0 of 298; put away, 6 of 8 fading cells white -> 0 of 6; a stone ship nearing a glowstone in red glass, the first light on it at 15 blocks white (#333333) -> red. Two beacon ships in blue glass brought together a block at a time and carried together at 3 m/s (112 merged light blocks at the end), and two soul-lantern ships, stayed coloured both ways in these runs (the merged light lands near a lamp there); the merged light now takes its lamps' colour wherever it lands. The main pack, with the fix: 0 white cells in every phase.
- **A click when something catches fire** (the sound for an entity catching fire should be the flame sound alone, without the little flint-and-steel-like click that played with it). A burning entity's light played the fire charge's whoosh and flint and steel's click together; it now plays only the whoosh (`LightCalculator.FLAME_SOUNDS`).
- **Ignition sounds and particles over and over** (standing on a burning ship in creative, the ship tried to set the player alight over and over, and the mod's sound and particles played every time). A creative player's fire lasts one tick (vanilla), and a burning ship set it alight again every few ticks, so the player's light went out and came back each time and its flame sound and particles played every half second. A source lit again within a second of going out is now the same light flickering, not a new ignition: no sound or particles again (`IGNITION_REARM_TICKS`). Camafy 0.1.37 no longer sets such a player alight at all.

### Tests

- Gametests `LightSourceTests`: a held light names its holder and what it holds, a light put away keeps its sources while it fades, a light merged from two holders names both.
- Pack harness `llcol_trail`, `llcol_ships`, `llcol_fire`.

## 0.4.2 — Unreleased

The client-performance update, driven by profiling (client block-light propagation was ~14% of the render thread with ~100 moving sources): far sources now update less and freeze in place between refreshes, world-join ignition is budgeted, and config files upgrade themselves.

### Added

- **`/ll purge all`** — besides the light blocks the mod has a record of, removes every `minecraft:light` block in the loaded chunks within view distance of each player and in every loaded Valkyrien Skies ship, recorded or not, and logs each unrecorded one it removed. For light blocks the mod lost track of (see Fixed); a light block placed on purpose in that area goes too, hence the log. Both purges now say what they did (blocks removed, records kept for unloaded chunks) and also clear the shipyard anchor cache, the chunk-sweep queue and the pending equipment updates.
- **Burning ships give light** (`ship_fire_lights`, default `true`; needs `vs_support`; Camafy 0.1.34 or later, now the minimum) — a Valkyrien Skies ship on fire (Camafy's ship fire) is a dynamic light like a burning entity (level 15): one light at each burning part, the largest four, or on its top blocks spread over it when it burns all over. Each lights the world round the ship at its world position, following it as it moves and turns, and its own deck through an anchor in its shipyard, the way a held torch lights a ship. Every light block goes through the ordinary plan and records, so it fades out within half a second of the fire going out or the ship being deleted, is swept when its chunk next loads and purged when the server stops. The lights come on 15 ticks after the ship catches, so a client with Colorful Lighting already knows the ship burns: Lively Lighting's client half (only with Colorful Lighting and Camafy on the client) answers Colorful Lighting's block light colour API with fire's colour for a light block in or beside a ship drawn burning (`-Dlivelylighting.noShipFireColor=true` turns that off). Needs a Colorful Lighting that asks its colour API first for such blocks (2026-10-04): the raft's light then reads #ff8844 like a burning pig's, where it was pure red.
- **`light_blocks_are_lamps`** (default `false`) — whether `minecraft:light` blocks count as lamps that light passing ships and that a ship projects into the world. Off, only genuinely luminous blocks do.
- **Distance-based LOD** (`distance_lod`, default `true`) — entities farther than `lod_start_distance` (default 32) blocks from every player refresh their lights less often, one extra tick of interval per `lod_distance_step` (16) blocks, capped at `lod_max_interval` (8). Between refreshes the light is *frozen* exactly in place rather than skipped, so a skipped run sends zero block updates to clients — and block updates are the entire client-side cost of a dynamic light. Near lights are untouched and stay perfectly smooth; players, TNT, creepers, and shipyard-resident sources always update at full rate. Trails and fades are unaffected — an abandoned anchor still fades out normally on the entity's next refresh.
- **`light_cull_distance`** (default `0` = disabled) — optional hard cull: entity light sources farther than this from every player are dropped entirely (fading out through the normal path, re-igniting on approach). Set it well above the LOD distances or lights will visibly pop at night.
- **No more world-join freeze** (`max_ignitions_per_tick`, default `10`, `0` = unlimited) — spawning into a world full of dynamic light sources used to ignite every one of them in the same tick: one giant block-update burst and a single-frame light-propagation spike on the client. Ignitions (unlit → lit transitions) are now budgeted per engine run, so the backlog drains as a quick cascade over a few ticks instead. Already-lit sources are completely unaffected — this only shapes bursts; players, TNT and creepers are never budgeted, so your own light and explosion flashes stay instant.
- **Config files now upgrade themselves.** On load the file's structure is compared against the current schema: options added since the file was written are filled in at their defaults and written back, stale keys from removed options are dropped — all existing settings are kept, so deleting the config after updating the mod is never needed again. A config file that fails to parse no longer risks crashing the load; it's set aside as `livelylighting.json.broken` and a fresh default config is generated.

### Fixed

- **A held light among very many small Valkyrien Skies ships** (2026-10-04: passing an area with a great many single-block Valkyrien Skies ships, the smoothing got bad and the game lagged and stuttered). Every light near a ship keeps a light block in that ship's shipyard and re-levels or moves it as the light moves: a block change on the server and, on every client, a light update that spreads over about 26 light sections of that ship's chunks. A torch walked past 182 one-block ships was 433 such changes a second (11,560 light-section updates a second on the client, about 0.67 ms of frame time each change), and the number grows with every ship in reach.
  - **Small ships take turns** (`small_ship_updates_per_tick`, default `6`; `small_ship_max_blocks`, default `8`; `0` = off). When more small ships than the budget need their light changed in one engine run, the ones whose light is most wrong and nearest a player are updated, each brought fully up to date in that one step, and the rest keep exactly the light they have until their turn (not one block change). With no more than the budget changing at once nothing is deferred and nothing differs; lights in the world (the player's own) and larger ships are never deferred. Standing still, every ship gets its exact light: four seconds after stopping, each of 150 ships had the same light level as with the old engine.
  - **The anchor search reads 27 cells instead of 343 for a one-block ship** (only cells within one of the ship's block bounds can touch it): the same anchors found. With both, Lively Lighting's share of the server thread in the walk below went from 5.0% to 2.7%.
  - **Measured** (pack harness, new `perf_llships`: static one-block ships two blocks apart at night, a torch walked down a lane through them, 182 in the torch's reach; the same session switched live with `-Dlivelylighting.noShipBudget` / `-Dlivelylighting.fullAnchorSearch`): old engine 433 shipyard light writes a second, 92 fps (frame mean 10.8 ms, 99th percentile 27.4), 15 server ticks over 50 ms in 15 s; now 136 writes a second, 113 fps (8.8 ms, 22.5), 1 tick over 50 ms; without a torch 130 fps. At three blocks apart (86 in reach): 195 writes a second to 108. Away from ships the torch writes 16.8 light blocks a second before and after. No frame over 50 ms was seen in either; the reported pile was probably denser, and the old cost has no ceiling while the new one stays near 136 writes a second however many ships there are.
  - Gametest `anImmediateLightTakesItsLevelInOneStep` (11 of 11 pass).
- **Light blocks left shining in ships** (2026-10-10: single-block Valkyrien Skies ships still showed up lit, and purging didn't fix it; and an old world whose ships stayed full bright at night). Three ways a light block outlived its light:
  - **A light whose change failed was dropped.** When the plan dims or removes a light and the write fails (its chunk unloading with a ship that moves away, or not writable), the light block stayed with its record but nothing tracked it any more, until a chunk-load sweep that looked only once. It now stays tracked at the level it has and is tried again every run (`LightPropagator.applyChanges`); its record is dropped instead when the ship whose shipyard held it is gone.
  - **A five-second record audit** (`LightManager.auditRecords`): a recorded light the engine doesn't track, in a loaded chunk, goes. The chunk-load sweep alone missed records whose chunk wasn't ready at that moment (the reported saves kept 22 such records through every session: "kept 22 record(s) of blocks in unloaded chunks").
  - **Copies of the mod's lights in ships** (`clean_copied_ship_lights`, default `true`; needs `vs_support`): a VMod schematic of a ship takes the light blocks the mod projected into that ship along, and Valkyrien Skies assembling a world area takes the mod's world lights along; the copies have no record, and nothing ever took them out (78 ships of the reported world held them at a join). The first time in a session a ship comes near a player, light blocks in and round it (8 blocks) that the mod has no record of and that no player placed by hand (now recorded: `HandPlacedLights` in the save) are taken out, logged once per ship; the mod puts back whatever light it wants there at once. `-Dlivelylighting.keepShipLights=true` skips it (the pack harness's `ship_light`, whose rafts carry light blocks on purpose). A light block placed by hand on a ship before this version, or in a world area later assembled into a ship, is taken for a copy.
  - `/ll purge all` also clears the chunks round a ship's blocks (8 blocks): Valkyrien Skies centres a ship on a chunk corner, and the lights projected onto it stand in the next chunk as often as not.
  - Gametests `aLightWhoseRemovalFailedStaysTracked`, `anOrphanedRecordedLightIsAudited`.
- **Server crash: `NullPointerException ... Heightmap ... Map.get(Object) is null` in `LevelChunk.setBlockState`, from `LightPropagator.placeLight`** (the crashes of 2026-10-01: a gasoline pool exploding, a ship forced through lava). Valkyrien Skies 2.4.11 empties a ship's old shipyard chunks' heightmaps when the ship changes dimension (`clearChunk`, from `moveTerrainAcrossDimensions`, e.g. VS Genesis carrying a ship into space) and never primes them again; the chunks stay loaded until they unload, and every block write there throws. A light projected onto that ship went on being placed into its old chunks. The light engine now writes only into a loaded chunk with all four heightmaps a block write updates (`LightPropagator.writableChunk`), for placing, re-placing, fading, removing, both purges and the ship projection alike; a light block it can't remove keeps its record for a later sweep, and a cleared chunk's record is dropped. As a last line of defence every write goes through `trySetBlock`: anything it throws is logged (at most once a minute, with the position and the chunk's status) and the position is dropped from that pass's plan instead of taking the server down. Gametests `ChunkGuardTests` (3).
- **A light block that kept coming back** (as reported: breaking it in creative, or `/ll purge`, made it vanish for a moment and return). A light block the mod placed can lose its record: a crash before the records are saved (chunks save as they unload, the records only at autosave), or Valkyrien Skies copying it to a new position when a ship is assembled or disassembled. Such a block was then treated as a real lamp: one on a ship was projected into the world every tick, so the projected light came back the tick after it was broken or purged, and the purge never touched the unrecorded block behind it. Light blocks are no longer lamps by default (`light_blocks_are_lamps`), `/ll purge all` removes unrecorded ones, and the records are written to disk once a minute while they change, so a crash loses at most a minute of them.
- **A source's state outlived its owner.** The five-second sweep dropped an entity's lit state only when no entity with that id was in the level; now also when it is removed (dying, discarded, picked up), a spectator, or switched off with `/ll toggle`. A switched-off entity is forgotten at once and never frozen in place by the distance LOD (a far, switched-off mob's light used to be held at its level between refreshes).
- **The light item is never a light source**, whatever the config's `auto_detect_blacklist` says (it lists `minecraft:light` by default; a hand-edited list could drop it): holding it to see and break light blocks would otherwise light the player and re-place every light block they broke near them.
- **No dynamic lights on a server that reports a view distance of 0** (a game-test server, some server wrappers): every entity was culled; vanilla's default of 10 chunks stands in.
- Gametests (`gradlew runGameTestServer`, new): a torch-holding zombie's light goes when it is discarded, killed, sent to another dimension or switched off, a dropped torch's when it is picked up, the light item lights nothing, and a purge (and `purge all`, an unrecorded block too) leaves nothing behind. The client-only dev mods (Cubes Without Borders, Embeddium) stay off the classpath of server runs, which refused to load them.

- **Lag spikes near Valkyrien Skies ships** (found in spark profiles, which showed the server waiting on chunks while lights were placed). The light engine reads only chunks that are already loaded now (`getChunkNow`): checking a spot for a light, whether it touches the ship's structure, and scanning a chunk for world lamps used to go through calls that, on the server, wait for a chunk that is still being loaded - and a ship's shipyard chunks are loaded and unloaded by VS as players come and go. A spot in a chunk that isn't loaded yet is simply not used this time.
- **Less work for every light near a ship.** Finding where a held light's glow goes on a ship (up to 343 candidate spots, each with a line-of-sight check) was done again every tick for every light. The spot found is now kept for a second (`ANCHOR_REUSE_TICKS`, 20 ticks) per ship, light cell and brightness, and reused while it is still a valid spot; the cache is pruned with the other anchor bookkeeping.

## 0.4.1

Full Valkyrien Skies light propagation, experimental ultra smoothing, and a round of smoothing fixes.

### Added

- **Full Valkyrien Skies light propagation** (`vs_support`) — light now flows every direction between the static world and ships:
  - **Held lights illuminate ships.** Approaching or standing on a ship while holding a light source anchors a light block in the shipyard (where ship blocks take their light from), dimmed by distance so the hull and deck brighten naturally as you get closer. The anchor must be attached to the ship — face-adjacent to a genuine ship block, with light blocks not counting so anchors can never chain outward off each other (any AABB growth is bounded to one block beyond real structure) — and must have line-of-sight to the holder in shipyard space, so a sealed hull won't light up inside from a torch outside.
  - **Ship lamps illuminate the world.** Each nearby ship's own light-emitting blocks (lanterns, glowstone, etc.) are scanned (cached, rescanned every 3 s) and projected into the static world as dynamic light sources — as the ship moves and rotates, its lamps genuinely appear to illuminate the world around it.
  - **World lamps illuminate passing ships.** A lamp post, glowstone, or any luminous block in the static world lights up a ship's hull as it sails past, dimmed by distance and respecting the ship's own geometry. Performant by design: world lamps are static, so they're cached per chunk (shared by all ships, unaffected by ship movement, refreshed every 3 s) with a palette-level pre-filter that skips terrain sections containing no luminous block type without reading a single block; only the cached lamp positions are re-projected through the ship's current transform each tick, capped at the 16 strongest lamps per ship.
  - **Ship-to-ship lighting.** Each nearby ship's lamps are collected at their current world positions and fed into every other ship's projection, competing in the same distance ranking and per-ship cap as static world lamps — two ships passing at night light each other's hulls.
  - **Item frames on ships.** Item frames mounted on ship blocks live at shipyard coordinates, so their light is additionally projected out to the ship-transformed world position — a framed lantern on a sailing ship lights the world around it, exactly like a placed lamp.
  - All projected lights live in the ordinary world light lifecycle (fade-out, save records, chunk-load sweep), and the scans distinguish mod-placed light blocks from player-placed ones via the save records — so projections can never echo, cascade endlessly between ships, or leave orphans, while manually placed light blocks on ships still project like any other lamp.
- **`experimental.ultra_smoothing`** (default `false`) — sub-block light interpolation. The source is treated as a continuous point light at the entity's eye: every nearby block gets `ceil(level − manhattanDistance(eye, blockCenter))`, so light levels crossfade exactly with your position. Standing centered on a block gives one full-level light; straddling a boundary lights both blocks at full; every surrounding cell steps ±1 exactly when your position dictates, including vertical motion (jumping interpolates). Crossfades can never dim — a block only drops a level the same tick its successor gains one. Redundant blocks (already receiving their exact level by propagation) are trimmed to limit block-update churn, but this mode still updates more blocks than normal smoothing. Requires `smoothing`; bypasses predictive anchoring and the fade rates entirely while active (trails on departure still apply). Highly experimental.

### Changed

- **Ignition fade-in now follows the source, not the block.** Each source tracks an *established* level that ramps up at `fade_in_rate` while it stays lit — igniting a torch fades in smoothly even mid-sprint, and movement can never restart or compound the fade. Once established, a moving source stays at full brightness.
- **No more flashing when changing direction.** The predictive anchor lead now uses an exponentially smoothed velocity, so turning swings the lead over a few ticks instead of snapping the light block sideways.
- **Fade-out is synced with fade-in.** A block being vacated never fades below the level the incoming light will provide once its own fade-in completes, so handoffs can't dip dark and pulse back up; it's removed the moment the live light actually covers it.
- **Smoothing off now means fully instant.** With `smoothing` disabled (or for non-players with `smoothing_all_entities` disabled), lights place, update, and vanish immediately — no fade-in, fade-out, or trails anywhere in the pipeline.

### Fixed

- **Light trails are back.** Fast-moving sources no longer dim toward darkness: the fade-in throttle used to re-apply at every new anchor block, so any source moving more blocks per tick than `fade_in_rate` lost brightness until it (and its trail) all but vanished. An established source now carries its light level with it while moving, and the blocks it leaves behind fade out at `trail_decay_rate` — visible whenever you move faster than the decay (lower `trail_decay_rate` for longer trails).
- Two light-holding entities near each other no longer cancel each other's light out. Merged clusters place their light at the average of the sources' positions, which could land inside a wall between them — placement failed and both lights vanished. The cluster now snaps to the nearest valid spot.
- Light blocks the mod didn't place (manually placed ones, e.g. Colorful Lighting wildcard emitters) are never adopted, overwritten, or deleted anymore. Previously a dynamic light could anchor onto one, change its level while you stood nearby, and remove it entirely when you walked away.
- **Valkyrien Skies support reworked from scratch.** The old implementation placed light blocks inside the shipyard with no safety rails; a moving ship made the transformed position drift every tick (leaving a new block each tick that never cleaned up), and blocks placed at the shipyard edge grew the ship's bounds, which grew the placement region — the runaway "expanding ship" bug. Shipyard placement is now validated (see above) and every placed block goes through the normal world lifecycle, so orphaned or stacking ship lights are structurally impossible. Stale shipyard light blocks from older versions are cleaned automatically by the ghost-light sweep when the ship loads.
- Spectral arrows (and other projectiles/items) no longer lose their light when stuck in or against a wall — the suffocation check only applies to living entities now; a genuinely buried source is instead handled by the new line-of-sight rule.
- Light blocks can no longer be placed through walls. Anchor spots (and ultra-smoothing blocks) must now have a clear light path back to the source, using the light engine's own transparency rule so glass and other light-passing blocks don't block it. An enclosed entity's light stays inside its enclosure.

## 0.4.0

The smoothness update: dynamic lights now hand off between blocks with the minimum possible visual change, plus major stability and performance work.

### Added

- **Predictive light anchoring** — the light block leads slightly in your direction of travel (up to 1 block, derived from movement history), so light no longer visibly trails behind when sprinting. Vertical motion is ignored so jumping doesn't bounce the light.
- **Ghost light cleanup** — every light block the mod places is now recorded in world save data. If a chunk unloads mid-fade or the server crashes, the orphaned light block is automatically removed the next time its chunk loads. `/ll purge` also clears these records.
- **`light_update_interval` config** (default `1`) — run the light engine every N ticks. Movement handoffs only shift light by one level per block, so `2` still looks smooth on busy servers.
- **Instant equipment response** — swapping a light source in or out of your hands/armor forces an immediate light update, even with a raised update interval.
- **Persistent sound preferences** — `/ll sound on|off` is now saved with the world and survives restarts.
- Dynamic light items now emit light from item frames.

### Changed

- **Reworked the smoothing algorithm.** Moving light sources now use an "invisible handoff": the new light block spawns at the brightness that spot already receives from the old block, and the old block is removed the moment the new one covers it. Every affected block changes by exactly ±1 light level per block moved — the minimum possible with Minecraft's integer lighting — eliminating the pulsing trail and flicker when moving fast. Fade-in/fade-out still applies where it should: igniting, extinguishing, and light left behind corners.
- **Smoothing is now enabled by default** and moved out of the experimental config section (`smoothing`, `smoothing_all_entities`, `fade_in_rate`, `trail_decay_rate` are now top-level options; defaults for the rates are now `2`). Old config files are migrated automatically and existing values are preserved.
- Fade rates no longer affect movement smoothness at all — they only control ignition, extinguish, and corner fades, so lower values are now purely cosmetic polish.
- The light level no longer flickers between two values while moving near the anchor block (distance penalty now has a 1-block dead-band).
- License changed to MIT.

### Fixed

- Flickering and jarring light transitions when moving quickly with a light source.
- Light blocks left behind permanently ("ghost lights") after chunk unloads or server crashes.
- Moving through an area where another entity ignited no longer makes your light dip dark and fade back in.
- Per-entity caches are now tracked per dimension — a player in the Nether no longer has their light state wrongly purged by the Overworld's cleanup (this could break `light_source_spacing` outside the Overworld).
- The ignition sound cooldown is now the intended 0.5 seconds.

### Performance

- Entity scanning now queries the entity index around each player instead of checking every loaded entity against every player each tick — a large win on servers with many entities.
  - The query radius is capped at 192 blocks: Valkyrien Skies rejects oversized entity queries (returning an empty list, which would disable all entity lights at high view distances). Light beyond that range is imperceptible. Note that VS support itself remains experimental and disabled by default (`vs_support`).
- Removed a per-source flood-fill that could never place any blocks (only runs for overgrown clusters now).
- Removed a per-position cluster scan from the fade logic; trail blocks are now removed the first tick they become redundant instead of ticking down individually, generating roughly one block update per block moved (also fewer client chunk rebuilds, especially with Sodium/Embeddium renderers).

### Config migration notes

- `experimental.smoothing`, `experimental.smoothing_all_entities`, `experimental.fade_in_rate`, and `experimental.trail_decay_rate` move to the top level automatically on first load; your existing values are kept. Note: configs written by older versions always contain these keys, so the old default (`smoothing: false`) carries over — delete the keys or set `smoothing: true` to get the new default behavior.
- `cluster_growing`, `cluster_merge_distance`, `max_influence_radius`, and `light_source_spacing` remain experimental, as does Valkyrien Skies support (`vs_support`, off by default).

## 0.3.1

- Reworked smoothing logic and trail fading.
- Zombies can occasionally spawn holding torches.
- In-wall check for light placement and sound/particle spam prevention.
- Removed the old datapack reference; config fixes.
