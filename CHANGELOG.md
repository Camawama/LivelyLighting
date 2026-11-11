# Changelog

## 0.7.0 — Unreleased

### Added

- **One item, a light colour per stack.** An item definition with `"color_nbt": "color"` reads its light's colour from
  that key of the stack's tag, as text (`"#00FFAA"`, a dye's name such as `"red"` or `"light_blue"`, `"uv"`) or as a
  number (`0xRRGGBB`). A stack without the key, or with a value that is no colour, keeps the definition's own `color`.
  `/give @s kubejs:flashlight{color:"red"}` and `{color:"uv"}` are a red flashlight and a blacklight of the same item.
  Held items, dropped items, items in frames and on armor stands all follow it.
- **A definition's `color` takes names.** The sixteen dye names and `uv` (also `blacklight`, `ultraviolet`) beside
  `#RRGGBB`.
- **Blacklights** (the client module needs Colorful Lighting 2.11.0). A light whose colour is `uv` is handed to Colorful
  Lighting as a blacklight: a dim violet light that makes saturated blocks glow in their own colour, as a point or as a
  flashlight's beam. With an older Colorful Lighting, and as the server module's light blocks, it is a plain violet
  light. A blacklight is never merged with the lights near it (`client.cluster_merging`): a merged light has one colour,
  and a blacklight is not a colour.

## 0.6.8 — Unreleased

### Added

- **A lamp on a scaled ship reaches as far as its ship is scaled** (Valkyrien Skies; the client module needs Colorful
  Lighting 2.10.6). A lamp on a ship drawn at another size lit the same 15 blocks of the world as on a ship at its own
  size: a small model's lanterns flooded the room, a large ship's lit a patch of its own deck. The client module now
  stretches such a lamp's falloff by the ship's scale, as bright at the lamp either way: a glowstone on a ship at half its
  size reaches 7.5 blocks, at one and a half 22.5. A point lamp on an enlarged ship reaches at most
  `client.scaled_lamp_max_reach` blocks (24, 15 to 48), a beam at most 48: the volumes a light's paths are kept in. A lit
  item in a frame on the ship is one of its lamps here. The server module's light blocks lose a level per block whatever
  is asked, so there the block's level goes with the scale: a lamp on a ship at half its size is a block of level 8, and a
  ship larger than its own size gains nothing (level 15 is the most a block gives). `lamps_scale_with_ships` (true;
  `-Dlivelylighting.lampsScaleWithShips=false` for a run) is the switch, read by the server module from the server's file
  and by the client module from the client's. Not scaled: fire on a ship and Create's contraptions. Where Colorful Lighting
  propagates the lights through its light engine instead of its shaders, a lamp keeps a level per block at every size.
  Harness `ll_r9_scale`: against the same ship at its own size the lit floor reached 0.50 times as far at half size, 1.50
  at one and a half and 1.57 at twice the size (the 24-block limit gives 1.6), with the brightness beside the lamp 0.98,
  1.01 and 0.88 of the full-size ship's; with the switch off 1.04, 0.99 and 1.00; in `server_blocks` mode the block light
  at the lamp was 15, 8, 15 and 15.

### Documentation

- The README says who decides the lighting mode: `lighting_mode` is read by the server alone (in singleplayer and on a LAN
  world the host is the server), the mod's one channel only sends from the server to the clients, and the commands that
  change the mode need permission level 2. A client can only choose whether it draws its own lights.

## 0.6.7 — Unreleased

### Fixed

- **The colour behind a ship's stained glass moves with the ship** (Valkyrien Skies; needs Colorful Lighting 2.10.5). With
  a ship of coloured glass flown past a standing dynamic light, the tinted light behind it stood still and then leapt.
  Two causes. The volume the light is propagated through took a ship's blocks afresh only every ten ticks unless the ship
  carried lamps; a ship with blocks that tint light, within reach of a dynamic light, now has it rebuilt as it moves, a
  fiftieth of a block at a time and as often as `client.light_path_update_ticks` allows
  (`-Dlivelylighting.shipGlassRebuilds=false` for the way before). And a ship's block was written to whole cells of that
  volume, so the colour moved a block at a time however slowly the ship went; a block that tints light is now written by
  the share of each cell it takes up, exact for a ship along the world's axes and counted over 32 points a cell for a
  turned one (`-Dlivelylighting.shipBlockCoverage=false`, which also ends the rebuilds: which ships tint light is learnt
  from the shares). Blocks that stop light are whole cells as before: the shadow of a moving ship's hull from a standing
  light still moves a block at a time. Harness `ll_r9_shipglass`: flown at 2 blocks a second the red light behind the glass
  moved in every judged tick with a largest step of 2.4 times its mean (before: 10.2 times, standing for 7 ticks); at 0.6
  blocks a second 2.6 times (14.1), and turning as it went 4.2 times with no stand over 3 ticks (14.1, standing for 12).

## 0.6.6 — Unreleased

### Added

- **`client.light_path_update_ticks`** (1, 1 to 10; needs Colorful Lighting 2.10.4; "Light Path Update Interval" on the
  Embeddium page; `-Dlivelylighting.lightPathUpdateTicks` for a run). The fewest ticks between two updates of where one
  moving light's light may go, on the shader renderer with `voxel` transmission. At 1, as before, a light's shadows and the
  colour it takes behind stained glass follow it every tick. At more they follow that many ticks apart: at 4 a walking
  torch's shadows trail it by up to a block and the colour behind glass moves in steps. The light itself, its place and its
  falloff, still moves every frame, and a new light's first paths never wait. Higher values spare the background thread that
  works the paths out and the uploads of their textures when many lights move at once: with 64 lights moving, that thread
  took 95% of a core at 1, 34% at 4 and 15% at 10, and the uploads 2.6%, 0.9% and 0.4% of the render thread (harness
  `ll_stress`). The frame rate was the same within the measure's noise at every value, so this is a setting for a processor
  with few cores.

### Changed

- **How much dynamic light reaches a place is looked up by column from 24 lights on** (`-Dlivelylighting.lightIndexFrom`
  for another count). The game asks the client's raw brightness many times a frame (`client.world_light`), and every
  question went through every light: a tenth of the render thread with 256 lights. With 256 lights standing the frame rate
  went from 0.91 to 0.96 of the rate without any, with 256 moving from 0.83 to 0.90.

## 0.6.5 — Unreleased

### Changed

- **Whoever holds a beam is drawn a little lit, and never by the beam itself** (`client.spot_holder_glow`, 6; needs
  `client.lit_carriers` and Colorful Lighting 2.10.3). A flashlight's cone starts at its holder and points away, and its edge
  lit the body now and then: the holder was dark with lit moments while turning slowly. The holder's own beams are now left
  out of their light, and they are drawn at least block light 6 in the beam's colour, with nothing round them lit. A point
  light in the hand lights its holder as it always did.
- **A carrier's glow has its light's colour** (a kart under warm headlights; white before), and the lights that glow on an
  entity are left out of its own light, for whoever rides it too.

### Added

- **Xaero's maps drop the light a dynamic light left** (`client.map_refresh`, true; with `client.world_light`). The minimap and
  the World Map read the world's light and so show a held torch, but they redraw a chunk only when told of a change in it, and
  a light that moves on or goes out changes no block: its light stayed on the map. Every four ticks the chunks a light's reach
  left or entered are marked for a redraw the way the maps' own block updates mark them. Without Xaero's maps nothing is done.

### Fixed

- **A small ship's lamps no longer flicker.** A block of a turned or scaled ship was written into Colorful Lighting's block
  volume at its centre's cell and the cells of its eight corners, up to eight cells for one block: a one-block ship with a lamp
  on each face stood in a dark lump of its own cells that changed with every turn, and its lamps were shadowed by it in turns.
  A block now takes the cells whose centres lie in it, and a lamp its own cell alone
  (`-Dlivelylighting.shipBlocksCentreSampled=false` for the corners).
- **The block volume follows a ship with lamps as it moves.** The volume is rebuilt every ten ticks; in between a moving
  ship's lamps shone from among the cells its blocks had left. A ship with lamps within 56 blocks of the camera that has moved
  a quarter of a block or turned about three degrees has the next build asked for at once, every second tick at most
  (`-Dlivelylighting.shipLampRebuilds=false`; Colorful Lighting 2.10.3).

## 0.6.4 — Unreleased

### Changed

- **A merged light is its members' light added up by how close they are.** It was as strong as the brightest light plus the
  group's span, so it was at its brightest just before the group parted and dimmed as the lights came together. Now two torches
  side by side give a light three levels brighter than one (level 17 from two of 14), every doubling adds three, and from half
  `experimental.cluster_merge_distance` outward a light counts less, down to nothing at the merge distance: the merged light
  grows as lights close in and has faded to nothing where they part. The client's drawn lights, the server's light blocks and
  the gameplay light all use this one rule.
- **Lights merge only with a clear line between them.** Two torches on either side of a wall stay two lights. The line is
  looked at again every five ticks and when a light changes its block.
- **A long row or ring of lights no longer makes one light far from its members.** A group is cut while one of its lights is
  farther than the merge distance from the group's middle, so each merged light lies among the lights it stands for.
- **The server's light blocks merge by distance, not by a grid.** Carried lights were grouped by cells of the merge distance,
  so two holders merged or not by which side of a cell border they stood on and the light jumped as one crossed it. Every
  carried light now keeps its own light and the merged light is added over the group. A ship's lamps are grouped in the
  ship's own grid, so the same lamps stay together however the ship moves.
- **Up to sixteen merged lights on the client** (`client.max_merged_lights`, 12; six before). A nearer group takes the place
  of the farthest merged light, which eases out first. Needs Colorful Lighting 2.10.2 for walls to stop more than four of them.
- **Switching `client.cluster_merging` or `experimental.cluster_growing` off in a running game eases the merged lights out**
  over about three quarters of a second, where they went out in one frame.
- **Gameplay light stops at walls** (`gameplay_light_occlusion`, on): the server counted every light straight through
  everything, so a torch carried along a corridor kept mobs from spawning in the cave behind its wall. Each light now spreads
  the way a placed lamp's does, and a spot light counts inside its cone only, where a straight line from it is clear.

### Added

- **Lamps join merged lights** (`client.cluster_lamps`, on): lamps on ships and contraptions and a burning ship's fire join a
  group that has a carried light in it. Lamps alone never merge. Spot lights still never merge.
- **A new light waits for its walls** (`client.hold_new_lights`, on; shader renderer): a light taken out behind a wall lit the
  room beyond for a tick or two, until Colorful Lighting had worked out where its light may go. A new light now stays dark
  until then, six ticks at most, and is let up over two.

### Fixed

- **Lightmap renderer: a ship's lamp is tinted by the ship's stained glass.** The lamp's light now carries the tint of the
  ship's own blocks between it and the open side, as the shader renderer's block volume already did. A contraption's lamps
  are not tinted by the contraption's blocks on this renderer.
- A spot light's level is drawn between ticks like a point light's.
- The build no longer uses the `ResourceLocation` constructors Forge marks for removal.

## 0.6.3 — Unreleased

### Added

- **Lights near each other merge on the client** (`client.cluster_merging`, on; needs `experimental.cluster_growing`, the switch the
  server's light blocks merge by): point lights of entities, dropped items and other mods closer than
  `experimental.cluster_merge_distance` to one another add one larger light over their middle, as strong as the brightest plus the
  group's span, up to 15 plus `experimental.max_influence_radius` (24 at most). With Colorful Lighting 2.10.1 a strength over 15 is a
  core of full light and a reach that much longer; an older one draws a light of 15. The merged light eases in and out as lights
  join and part, follows its group every frame, takes the group's blended colour and stays dark until Colorful Lighting knows where
  its light may go, so it never shows through a wall first. Six merged lights at most, the nearest first. Spot lights, the lamps of
  ships and contraptions and a burning ship's lights stay on their own.

### Changed

- With `gameplay_light` and `experimental.cluster_growing` the server counts lights near each other as the merged light the client
  draws over them.

## 0.6.2 — Unreleased

### Added

- **A light's carrier drawn lit by it** (`client.lit_carriers`, on; Camafy 0.1.85's `LightSource.glow`): an entity that carries a light
  asking for it, and whoever rides it, is drawn at least that bright, with nothing round it lit. MKW Mobility 0.1.4's karts ask for it
  under their headlights, which point away from the kart.
- **Definitions keyed to NBT**: an item definition with `"nbt": {...}` applies only to stacks whose tag holds those values (a flashlight
  a script switches on and off), matched when the stack is looked up; a definition without `nbt` applies to every stack of the item.

### Changed

- With Colorful Lighting 2.10.0 the light is the smooth analytic light again at every point, moving with its source every frame, scaled
  by where the blocks let it go (per light: 1 in the open, less round a corner, 0 behind a wall): 2.9.x's field interpolated summed levels
  per cell, which stepped and flickered as a light moved. A spot light no longer lights two blocks round itself (`DynamicLights.lightAt`
  agrees): that put a blob of light on the ground round a kart and on a flashlight's holder.

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
