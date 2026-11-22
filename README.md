# Lively Lighting

Dynamic lighting for Forge 1.20.1 that knows Valkyrien Skies, Create contraptions and Colorful Lighting. Held torches, burning
mobs, glowing items and the lamps of moving ships and contraptions light the world; the world lights the ships and contraptions
that pass; the light has its source's colour where Colorful Lighting is installed. Since 0.5.0 the light can be shown two ways,
with the same definitions and the same configuration keys whichever is used:

- **The server module** places `minecraft:light` blocks where light should be. Every client, modded or not, sees it. It is the
  original mod, and the costly way: each block a light moves to is a block change on the server and a light update and a chunk
  rebuild on every client.
- **The client module** draws the same light on the client, with no block placed anywhere. With Colorful Lighting 2.9.0 it draws
  in colour through Colorful Lighting's own light sampling (entities, block entities, particles and chunk meshes), and where
  Embeddium runs Colorful Lighting's shaders without an Oculus shader pack it draws in the shaders themselves, so a moving light
  rebuilds no mesh at all. There the light spreads through the blocks round the camera the way a placed lamp's light does: a
  level lost per block along the path, stopped by walls, tinted by stained glass, round corners, the blocks of Valkyrien Skies
  ships included; under a shader pack the light goes through the packed light the pack decodes, like every dynamic lighting mod.
  Without Colorful Lighting it draws white light through the vanilla and Embeddium light lookups.

**Requires:** Minecraft 1.20.1, Forge 47.4+, Camafy 0.1.89+ (everywhere the mod is). **Optional:** Colorful Lighting (the client
module's colours, its spread through the blocks and, with 2.14.0, the UV list), Embeddium (the shader renderer), Valkyrien Skies and
Create (lamps on ships and contraptions), Curios (lights worn in a slot).

## Where the mod is needed

Either side alone, or both. **Server only:** every client sees light blocks (`lighting_mode` `server_blocks`, or `hybrid` for clients without
the mod). **Client only** (a vanilla server, another pack): the client draws the light it can see, its own and other players' held items,
burning mobs, ships' lamps (`client.draw_without_server`); a server with an older Lively Lighting places blocks and is never drawn over.
**Both:** the client draws its own light and the server lights the clients without the mod (`hybrid`), and the server counts the dynamic
light as the world's own for mob spawning, snow and ice melting and whatever else asks how bright a place is (`gameplay_light`). Camafy is
needed wherever the mod is; Colorful Lighting is optional on the client and gives the light its colours and its spread through the blocks.

`lighting_mode` in `livelylighting.json` says who shows the light (read by the server, the integrated one in singleplayer too):
`server_blocks` (the server places blocks for everyone; a client with the mod draws nothing), `client` (the server places nothing;
clients with the mod draw, clients without see no dynamic light) or `hybrid` (default: the server places blocks only where a
player whose client has no Lively Lighting can see them, and clients with the mod draw their own). The server tells every client
the mode on login; a server without 0.5.0 counts as `server_blocks`.

Only the server decides whether light blocks are placed. On a dedicated server the mode comes from the server's own file, from
`-Dlivelylighting.mode` on the server's JVM or from an operator's `/ll config lighting_mode ...`; the mod's channel carries
nothing from a client to the server, so a client's own `lighting_mode` is never read there and no player can make the server
place blocks. In `hybrid` the server places blocks for the players whose clients lack the mod: a server that must never place
any runs `client` (and can require the mod of its clients with Camafy's `[clientMods] required`). In singleplayer and on a LAN
world the host's game is the server, and its file is the one read.

## Light shapes, colour and definitions

A definition (item, entity or block) may give its light a `shape`: `point` (all round, vanilla's light) or `spot` (a cone: a
headlight on a ship or contraption, a flashlight in hand, pointing where its holder looks or where the block faces), with
`cone_degrees` (the full outer angle, default 40; the inner, fully lit angle is 60 % of it), `range` (blocks; default the level
for a point, 32 for a spot) and `color` (`#RRGGBB`, a dye's name such as `light_blue`, or `uv` for a blacklight; default
Colorful Lighting's colour for the item or block, white without it). The server module places every shape as a point light of its level; the client module draws the shape. `block_definitions/`
is new: `block_id` (or `regex:`), `shape`, `cone_degrees`, `range`, `color`, `direction` (`facing` for the block's facing
property, else a fixed direction such as `north`), `lit_property` (a boolean block property that must be true, such as `lit`)
and `light_level` (default the block's emission). The default set makes `minecraft:end_rod` a headlight: a 30-degree cone of
range 32 along its rod. A block in `#livelylighting:headlights` is a headlight with the default cone when it has no definition;
an item in `#livelylighting:flashlights` a flashlight. An item definition with `"nbt": {"on": true}` applies only to stacks whose
tag holds those values (a flashlight a script switches on and off by its NBT); one without `nbt` applies to every stack of the item.

One item can shine in a colour per stack: an item definition with `"color_nbt": "color"` reads its light's colour from that key
of the stack's tag, as text (`"#00FFAA"`, `"red"`, `"uv"`) or as a number (`0xRRGGBB`). A stack without the key, or with a value
that is no colour, keeps the definition's own `color`. `/give @s minecraft:spyglass{color:"red"}` and `{color:"uv"}` are then a
red flashlight and a blacklight of the same item. A blacklight (`uv`, `blacklight` or `ultraviolet`) needs Colorful Lighting
2.11.0 or later on the client: its light is a dim violet that makes saturated colours glow. With an older Colorful Lighting, and
as the server module's light blocks, it is a plain violet light.

A spot definition can zoom (0.11.0): `"zoom": {"nbt": "zoom", "steps": 8, "wide": [60, 30], "tight": [12, 90]}` lets each stack set its cone
and reach by a step in its tag (the key `nbt` names, 0 at the wide end to `steps` at the tight one; each end is `[degrees, blocks]` or
`{"cone_degrees": d, "range": r}`), the cone narrowing and the beam reaching farther step by step as the head of a zoom flashlight slides.
Sneaking with such an item in either hand, the mouse wheel narrows (up) or widens (down) the beam one step a notch instead of scrolling the
hotbar; the server sets the step on the stack, so every client draws the same cone, and the holder sees the setting on the action bar and
in the tooltip. A stack without the tag keeps the definition's own `cone_degrees` and `range`; its first notch starts from the step nearest
them. `beam_zoom` (common, true) switches the gesture off on either side.

To try a flashlight, give any item a definition with a shape: a file `config/livelylighting/item_definitions/spyglass_flashlight.json` with
`{"item_id": "minecraft:spyglass", "light_level": 14, "shape": "spot", "cone_degrees": 35, "range": 32, "color": "#FFF2CC"}` makes a held
spyglass a flashlight pointing where you look (`/ll reload` reads it in). Headlights on blocks: a definition in `block_definitions/` with
`"shape": "spot"` and `"direction": "facing"`, or the block in `#livelylighting:headlights`; the default set makes an end rod one. MKW
Mobility's karts carry headlights of their own through Camafy's dynamic light API. `client.spot_beams` draws the beam in the air.

## The client module's keys (`client`, read by the client only)

*   **`renderer`**: `auto` (Colorful Lighting's shader path where it can run, else the lightmap path), `shader`, `lightmap`, `off`. Default `auto`.
*   **`falloff`**: `diamond` (a level lost per block of Manhattan distance, vanilla's look) or `round` (per block of straight distance). Default `diamond`.
*   **`lightmap_rebuilds_per_tick`** (12), **`lightmap_max_reach`** (15), **`lightmap_max_moving_lamps`** (32): the lightmap path's budgets.
*   **`shader_max_lights`** (16, at most 64), **`shader_max_distance`** (64): the lights the shaders get per frame, nearest the camera first; every terrain vertex in view evaluates each of them.
*   **`spot_beams`** (false), **`spot_beam_opacity`** (0.06): a faint additive cone in the air along every spot light, and how visible it is at the light.
*   **`transmission`** (`voxel`, `off`): how dynamic light passes through blocks on the shader path (Colorful Lighting 2.9.0): `voxel` propagates each light through a 96-block cube of the blocks round the camera as a placed lamp's light spreads, stopped by walls, tinted by stained glass, round corners, ships' blocks included; `off` lets it through everything, as other dynamic lighting mods do.
*   **`draw_without_server`** (true): on a server without Lively Lighting this client draws the light it can see.
*   **`scaled_lamp_max_reach`** (24, 15 to 48): with `lamps_scale_with_ships`, the furthest in blocks an enlarged ship's lamp reaches on the shader renderer (a full-size lamp reaches 15). A lamp reaching past 15 blocks takes one of the 16 light paths of that reach, past 24 one of 4 (Colorful Lighting's slab classes); the lights beyond those shine through walls.
*   **`frames_on_ships_are_headlights`** (true): a lit item in a glow item frame on a Valkyrien Skies ship is a headlight along the frame's facing, turned with the ship. A plain item frame keeps its item's own round light, so a ship can store torches and lanterns in frames without casting beams; **`plain_frames_are_headlights`** (false) makes plain frames beams too (0.10.2).
*   **`frame_lights_off_when_powered`** (true, common): a redstone signal into the block an item frame hangs on switches the frame's light off (0.10.2); see "Headlights you can switch".
*   **`beam_zoom`** (true, common): a spot light whose definition has a `zoom` (the pack's flashlight) is narrowed or widened by the mouse wheel while its holder sneaks (0.11.0); off, the wheel always scrolls the hotbar and the server takes no step.
*   **The world's lamps on ships** (0.9.0, `client.world_lamps_per_ship`, 8, 0 for off): with `vs_support`, the shader renderer and Colorful Lighting 2.13.0 or newer, the lamps of the world light the Valkyrien Skies ships beside them, the ones that reach a ship brightest first, in their own colour and with the ship's blocks in their way. They are drawn on ships' blocks alone; the world is left as it is. A block of a ship or a contraption that Colorful Lighting makes absorb light (an end gateway) takes light there too instead of giving it.
*   **Lights that absorb** (0.8.0): a definition's `"color": "absorb"` (or a stack's `color_nbt` tag saying `absorb`) makes its light take light away instead of giving it, with the definition's level, shape, cone and range: an item that darkens the room round its holder, a beam that cuts a dark cone. `"absorb:#00FFFF"` takes only those channels. Client module only, with Colorful Lighting 2.12.0 or newer; for the server module and for anything that asks how bright a place is it is no light at all.
*   **`headlight_spill`** (10, 0 to 15, 0 for off) and **`headlight_spill_reach`** (4.0 blocks): what such a headlight spills round itself, and how far. The beam points away from the ship, so without the spill nothing showed where it came from: the frame and its item are drawn lit, and the ship's blocks beside it take a dim light of this level.
*   **`lit_carriers`** (true): an entity carrying a light that asks to be lit by it (Camafy's `LightSource.glow`: a kart under its headlights), and whoever rides it, is drawn at least that bright, with nothing round it lit.
*   **`spot_holder_glow`** (6, 0 to 15, 0 for off): whoever holds a spot light (a flashlight) is drawn at least this bright in its colour, with nothing round them lit. The beam itself never lights its holder: it starts at them and points away. Needs `lit_carriers` and Colorful Lighting 2.10.3.
*   **`map_refresh`** (true): Xaero's minimap and World Map redraw the chunks a dynamic light left or moved in. They read the world's light (`world_light`) but redraw a chunk only when told of a change, and kept the light they had drawn.
*   **`cluster_merging`** (true): with `experimental.cluster_growing`, point lights of entities, dropped items and other mods closer than `experimental.cluster_merge_distance` to one another, with nothing solid on the line between them, add one larger light over their middle. It is their light added up by how close they are: two torches side by side give a light three levels brighter than one torch, four give six more, and from half the merge distance outward each light counts less, down to nothing where the group parts, so the merged light grows as lights come together and is gone, not at its brightest, when they part. A long row or ring of lights is cut into merged lights that each lie among their own members. Up to 15 plus `experimental.max_influence_radius` (24 at most); with Colorful Lighting 2.10.1 a strength over 15 is a core of full light and a longer reach. It eases in and out as lights join and part, and eases out when this key or `experimental.cluster_growing` is switched off in a running game. Spot lights never merge. Off: every light is drawn on its own.
*   **`cluster_lamps`** (true): lamps on ships and contraptions, a burning ship's fire among them, join a merged light that has a carried light in it and count as the carried lights do. Lamps alone never merge. Off: lamps stay out of merged lights.
*   **`max_merged_lights`** (12, 1 to 16): merged lights drawn at once, the nearest the camera first. A nearer group takes the place of the farthest merged light, which eases out first. Colorful Lighting 2.10.2 keeps sixteen slabs for lights that reach beyond 15 blocks; an older one keeps four, and merged lights beyond those are drawn without walls stopping them.
*   **`hold_new_lights`** (true): on the shader renderer a new light stays dark until Colorful Lighting has worked out where its light may go, six ticks at most, and is then let up over two. Off: a new light shows at once and can shine through a wall for a tick or two.
*   **`light_path_update_ticks`** (1, 1 to 10): on the shader renderer with `voxel` transmission, the fewest ticks between two updates of where one moving light's light may go (Colorful Lighting 2.10.4). At 1 a light's shadows and the colour it takes behind stained glass follow it every tick; at more they follow that many ticks apart, while the light itself still moves every frame. Higher values spare the background thread that works the paths out when many lights move at once (64 moving lights: 95% of a core at 1, 34% at 4, 15% at 10) without a change in the frame rate on a processor with cores to spare.
*   **`world_light`** (true): the world's own light on this client answers the drawn dynamic light too, for mods that read how bright the player's place is (Stellar View's light pollution). The meshes and Colorful Lighting's samples don't read it.

*   **`lightmap_propagate`** (true): on the lightmap path a point light is a lamp Colorful Lighting's engine propagates: stopped by walls, tinted by glass, read by shader packs as block light.
*   **`uv_reactive_entities`** (true): the entities of `uv_definitions/` glow under a blacklight (see "UV Definitions").

With Embeddium these keys are also a **Lively Lighting page in the video settings**; a change there is written to `livelylighting.json` and takes effect at once.

Overrides for a run: `-Dlivelylighting.mode=server_blocks|client|hybrid` (the server), `-Dlivelylighting.clientRenderer=...`, `-Dlivelylighting.clientTransmission=off` (the shader path's transmission march off, for an A/B), `-Dlivelylighting.lightPathUpdateTicks=N` (`light_path_update_ticks` for a run), `-Dlivelylighting.lampsScaleWithShips=false` (`lamps_scale_with_ships` for a run) and
`-Dlivelylighting.clientMode=client` (a client draws even where the server places blocks).

## Other mods' lights

A mod registers a `DynamicLightProvider` with Camafy's `light.DynamicLights` (0.1.84) and its lights, point or spot, are drawn with this
mod's own: faded the same, in their colour, through the same renderers. `DynamicLights.lightAt(level, pos)` tells any mod how much dynamic
light reaches a place. MKW Mobility's karts carry headlights this way. `client.spot_beams` draws a faint cone in the air along every spot
light. Resource packs' LambDynamicLights item files (`assets/<ns>/dynamiclights/item/*.json`) are read on the client too, as definitions of
the lowest priority.

## Moving structures

With `vs_support`, a Valkyrien Skies ship's lamps light the world as it moves (the brightest `max_lamps_per_ship` first), world
lamps and other ships' lamps light the ship, an entity on a ship lights the world; a Create contraption's lamps do the same
(Create is optional). In the client module the lamps follow the ship's render pose and the contraption's render transform every
frame; a headlight block on either is a spot light along its facing.

A ship built or set to another size carries lamps of that size (`lamps_scale_with_ships`, 0.6.8; the client module needs
Colorful Lighting 2.10.6 for it): at half size a lamp reaches half as far, at twice the size twice as far, up to
`client.scaled_lamp_max_reach` blocks, and is as bright at the lamp as ever. A headlight's range is scaled and its cone is
kept, to 48 blocks at most. The server's light blocks take the level times the scale, rounded, from 1 to 15: a light block
can't reach past 15 blocks, so in `server_blocks` mode a shrunk ship's lamps shrink and an enlarged ship's stay as they are.
In the client module a lit item in an item frame on a ship is scaled the same way. A ship's fires and a Create contraption's
lamps aren't scaled, and where Colorful Lighting propagates the light without its shaders the light loses a level a block
at every size.

### Headlights you can switch

A glow item frame holding a light on a ship is a headlight. Power the block the frame hangs on and its light goes out; take
the power away and it shines again (`frame_lights_off_when_powered`, 0.10.2). Any block works, and the signal is read the way
vanilla reads it for that block, so whatever powers a block powers the switch. It works on ships (Camafy's ship redstone powers
ship blocks across the ship's edge as on land), on land, in the client renderer and for the server's light blocks, and needs no
extra packets. Plain frames' round lights follow the same rule.

*   **A light switch:** a lever on the block the frame hangs on (on its top or on a side face). Lever on: dark. Lever off: lit.
*   **Night-only headlights:** a daylight sensor on top of that block. By day the sensor powers the block and the headlight is
    off; at night the sensor is quiet and the headlight shines. No inverter needed.
*   **One switch for a whole row:** a row of headlights on one wall with redstone dust behind it, a lever or a button at the
    helm feeding the dust. A pulse from a button switches them off for the button's press; a lever holds them.

## Configuration

The configuration is file-based, in `config/livelylighting/`.

## Configuration Structure

The configuration is split into these parts:
1.  **`livelylighting.json`**: The main configuration file for global settings.
2.  **`item_definitions/`**: A directory containing individual JSON files for each custom item.
3.  **`entity_definitions/`**: A directory containing individual JSON files for each custom entity.
4.  **`block_definitions/`**: Blocks with a light shape of their own, such as headlights (see "Light shapes, colour and definitions").
5.  **`uv_definitions/`**: What glows under ultraviolet whatever its own colours (see "UV Definitions").

This structure allows for easy management of custom light sources without cluttering a single file.

---

## Main Config (`livelylighting.json`)

### General Settings

*   **`enable`** (Boolean): Master toggle for the entire mod. Default: `true`.
*   **`max_light_sources`** (Integer): Maximum number of dynamic lights allowed per dimension. Default: `500`.
*   **`enchanted_items_glow`** (Boolean): If enabled, enchanted items emit light level 6. Default: `true`.
*   **`fire_aspect_glow`** (Boolean): If enabled, items with Fire Aspect emit light level 9. Default: `true`.
*   **`enable_entity_lights`** (Boolean): Master toggle for entity-based lighting (e.g., Blazes). Default: `true`.
*   **`creeper_lighting`** (Boolean): If enabled, Creepers emit light while swelling. Default: `true`.
*   **`tnt_lighting`** (Boolean): If enabled, ignited TNT flashes light. Default: `true`.
*   **`burning_entity_lighting`** (Boolean): If enabled, entities on fire emit light level 15. Default: `true`.
*   **`glowing_effect_lighting`** (Boolean): If enabled, entities with the Glowing effect emit light level 6. Default: `true`.
*   **`enable_particles`** (Boolean): Master toggle for the particles of a light being taken out or going out (a definition's `particles`, the smoke of a torch put out by water or rain). Default: `true`.
*   **`enable_sounds`** (Boolean): Master toggle for the sounds of a light being taken out or going out. Default: `true`. In `server_blocks` mode the server plays these effects; in `client` and `hybrid` each client with Lively Lighting plays them for the lights it draws, at the holder's hand or belt (0.10.1), and the server plays them only for the players it lights. A light first seen already shining (someone walking into view with a torch) plays nothing, nor does one lit again within a second of going out (switching back and forth between slots); one ignition per holder every half second at most.
*   **`smoothing`** (Boolean): Smooth light movement and trails. Default: `true`.
*   **`smoothing_all_entities`** (Boolean): Smoothing for all entities, not just players. Can be laggy. Default: `false`.
*   **`trail_decay_rate`** (Integer): How fast a moving light's trail fades. Default: `2`.
*   **`fade_in_rate`** (Integer): How fast a new light fades in. Default: `2`.
*   **`auto_detect_block_light`** (Boolean): If enabled, the mod automatically detects items that are blocks and emit light (e.g., modded lamps) and assigns them a dynamic light level: the light the block gives when it is placed without power, so a lamp that needs redstone is dark in the hand. Default: `true`.
*   **`auto_detect_blacklist`** (List<String>): A list of item IDs to exclude from auto-detection (e.g., `minecraft:light`).
*   **`vs_support`** (Boolean): Valkyrien Skies support (ships' lamps, lights on ships). Default: `false`.
*   **`light_blocks_are_lamps`** (Boolean): With `vs_support`: `minecraft:light` blocks on ships and in the world count as lamps that light ships and the world round a ship. Off, only truly luminous blocks do, so a light block of the mod's own left behind by a crash can't shine for good. Default: `false`.
*   **`lamps_scale_with_ships`** (Boolean): With `vs_support`: a ship's lamps reach as far as the ship's Valkyrien Skies scale says (see "Moving structures"). Read by the server for its light blocks and by each client for the light it draws. Default: `true`.
*   **`max_lamps_per_ship`** (Integer): With `vs_support`: at most this many of a ship's own lamps are projected into the world as it moves, the brightest first, then the nearest a player (chosen again with the ship's lamp scan every three seconds); the rest give the world no light. Each projected lamp is a light block moving with the ship, a block change and a light update on every client per cell it crosses. `0` = no cap. Default: `32`.
*   **`small_ship_updates_per_tick`** (Integer): With `vs_support`: when more small ships than this need their light changed in the same engine run (a torch carried past hundreds of one-block ships), only this many are updated, the ones whose light is most wrong and nearest a player first, each in one step; the others keep the light they have until their turn. Lights in the world and on larger ships are never deferred. `0` = no limit. Default: `6`.
*   **`small_ship_max_blocks`** (Integer): What counts as a small ship for the setting above: the volume of the box its blocks fill. Default: `8`.
*   **`ship_fire_lights`** (Boolean): With `vs_support`: a ship on fire (Camafy's ship fire) gives light like a burning entity, at up to four places on it, lighting the world round it and its own blocks. Default: `true`.
*   **`clean_copied_ship_lights`** (Boolean): With `vs_support`: the first time in a session a ship comes near a player, light blocks in and round it that the mod has no record of and no player placed by hand are taken out: copies of the mod's own lights that a VMod schematic paste or a ship assembly took along, which would otherwise shine in the copy for good. `-Dlivelylighting.keepShipLights=true` skips it for a run. Default: `true`.
*   **`light_source_colors`** (Boolean): Clients are told what each of the mod's light blocks stands for (the holder and the slot its light comes from, a lamp, a burning ship), so Colorful Lighting gives the light its source's colour wherever it is: a fast holder's trail, a light fading out after it was put away, lamps merged into one light, the dim edge of a lamp's light on a ship. Needs the mod on the client too (it then answers Colorful Lighting's block light colour API); clients without it keep Colorful Lighting's own guess. `-Dlivelylighting.noLightSourceColors=true` on a client ignores what it is told, for an A/B. Default: `true`.
*   **`gameplay_light`** (Boolean): Dynamic light counts as the world's own light on the server: mob spawning, snow and ice melting, crops and anything else that asks how bright a place is see the carried, worn and burning lights and other mods' lights, as they saw the light blocks. Each light counts with its source's level, losing a level per block. Idle in `server_blocks` mode. Default: `true`.
*   **`gameplay_light_occlusion`** (Boolean): With `gameplay_light`: that light stops at walls and goes round corners as a placed lamp's does (a level a block, more through leaves and water, none through an opaque block), and a spot light counts inside its cone only, dimming along its beam, where a straight line from it is clear. A ship's blocks stop nothing. Off: a straight count through everything, every light a point light, as 0.6.0. Default: `true`.
*   **`gameplay_light_max_sources`** (Integer): With `gameplay_light`: at most this many lights are counted per world. The players' own lights always count; the others (burning and glowing entities, dropped items, item frames, other mods' lights) fill what is left, the nearest to a player first, and the rest give the world no light until they are among the nearest. `0` = no cap. Default: `256`.
*   **`curios_lighting`** (Boolean): With Curios installed, an item with a light level worn in a curio slot (a lantern on a belt) lights its wearer like one in the off hand; the light takes the colour Colorful Lighting gives that item. Default: `true`.

**Lanterns on the belt (0.10.1).** With Curios, Lively Lighting gives players a `belt` slot and puts the item tag `#livelylighting:belt_lanterns` in Curios' `belt` tag. The tag holds vanilla's lantern and soul lantern and, each optional, the hanging lanterns of SecurityCraft, Biomancy, Caverns & Chasms, Deep Aether, The Endergetic Expansion, Metal Works and The Undergarden, and Spelunkery's salt lamp; a pack adds its own lanterns with a tag file of the same name (`data/livelylighting/tags/items/belt_lanterns.json`, `"replace": false`) or KubeJS. Torches no longer go on the belt. A lantern on the belt is drawn hanging at the hip on the side away from the main hand, chain up, at half size and as bright as its own light (its own hanging model; an item without a hanging state is drawn as its item); one with a Curios renderer of its own keeps it. Taking out a lantern without a sound of its own clinks its chain.

### Performance Settings (the server module)

*   **`light_update_interval`** (Integer): Run the light engine every N ticks (1 = every tick); 2 still looks smooth on a busy server. Default: `1`.
*   **`unlit_entity_check_interval`** (Integer): Look for new light sources among unlit mobs only every N runs (a mob's light can start up to N-1 ticks late; TNT and creepers are never delayed). Default: `2`.
*   **`max_ignitions_per_tick`** (Integer): At most this many lights may switch on per run, so arriving in a place with dozens of lights doesn't place all their blocks in one tick (0 = no limit; players, TNT and creepers never wait). Default: `10`.
*   **`distance_lod`** (Boolean), **`lod_start_distance`** (32), **`lod_distance_step`** (16), **`lod_max_interval`** (8): a lit mob farther than the start distance from every player updates its light less often, one run more per step, up to the cap; its light block stays put meanwhile. Players, TNT, creepers and lights on ships always run at full rate. Default: `true`.
*   **`light_cull_distance`** (Integer): Mob lights farther than this from every player are dropped and come back on approach (0 = off; keep it well above the LOD distances). Default: `0`.

### Experimental Settings

*   **`experimental.ultra_smoothing`** (Boolean): Treats a light as a point between blocks, so its light crossfades exactly as it moves (more block updates than plain smoothing; needs `smoothing`). Default: `false`.
*   **`experimental.cluster_growing`** (Boolean): Lights near each other add a larger, brighter merged light: the server's light blocks, the server's gameplay light and, on the client, the drawn lights (`client.cluster_merging`), all by one rule. Every light keeps its own light; the merged light lies over the group. Default: `false`.
*   **`experimental.cluster_merge_distance`** (Double): How far apart two lights can be and still merge, with a clear line between them. Up to half of it a light counts in full, beyond it not at all. A ship's lamps are grouped by cells of this size in the ship's own grid. Default: `6.0`.
*   **`experimental.max_influence_radius`** (Integer): Max radius of a light cluster; on the client a merged light's strength goes up to 15 plus this (24 at most). Default: `3`.
*   **`experimental.light_source_spacing`** (Double): Minimum distance between light updates (0.0 = every block). Default: `0.0`.

Files from before 0.4.0 kept `smoothing`, `smoothing_all_entities`, `trail_decay_rate` and `fade_in_rate` under `experimental`; they are moved to the top level on load.

---

## Item Definitions (`item_definitions/*.json`)

Each file in this directory defines a single item's dynamic lighting properties.

**Example (`minecraft_torch.json`):**
```json
{
  "item_id": "minecraft:torch",
  "light_level": 14,
  "water_sensitive": true,
  "particles": "minecraft:flame,minecraft:smoke",
  "activate_sounds": "minecraft:item.firecharge.use:0.15:1.7",
  "deactivate_sounds": "minecraft:block.fire.extinguish:0.35:1.8"
}
```

The default files (0.10.1): torches whoosh softly with a little flame and smoke, and hiss when water or rain puts them out; lanterns clink on their chain with no flame; campfires crackle; a lava bucket pops; glowstone, sea lanterns and shroomlights sparkle; blaze rods, magma cream and jack o'lanterns give a small flame; a beacon chimes once; the conduit, the ender chest, the enchanting table, end rods, nether stars and end crystals give particles only. A default file nobody edited is rewritten with these on load; an edited one is left as it is.

*   **`item_id`**: The registry name of the item. Supports regex (e.g., `regex:.*torch`). **Note:** While powerful, complex regex patterns can impact performance. Prefer simple patterns or exact IDs when possible.
*   **`light_level`**: The light level (0-15).
*   **`water_sensitive`**: If `true`, the light extinguishes in water or rain.
*   **`particles`**: Comma-separated list of particle IDs to spawn. Use `null` to disable. Max 3.
*   **`activate_sounds`**: Comma-separated list of sounds to play when the item is equipped/activated. Max 3.
    *   Format: `sound_id` or `sound_id:volume` or `sound_id:volume:pitch`.
*   **`deactivate_sounds`**: Comma-separated list of sounds to play when the item is unequipped or extinguished. Max 3.

---

## Entity Definitions (`entity_definitions/*.json`)

Each file in this directory defines a single entity type's dynamic lighting properties.

**Example (`minecraft_blaze.json`):**
```json
{
  "entity_id": "minecraft:blaze",
  "light_level": 15
}
```

*   **`entity_id`**: The registry name of the entity. Supports regex. **Note:** While powerful, complex regex patterns can impact performance. Prefer simple patterns or exact IDs when possible.
*   **`light_level`**: The light level (0-15).

---

## UV Definitions (`uv_definitions/*.json`)

A blacklight (a light whose colour is `uv`) makes saturated colours glow. Some things glow under ultraviolet whatever
their colours: a scorpion is brown and glows blue-green. Each file here names entity types and the glow they have.
Drawn by Colorful Lighting 2.14.0 or newer, on the client, where a blacklight block or an ultraviolet dynamic light
reaches; the list is the client's own.

**Example (`arachnids.json`):**
```json
{
  "entity_ids": ["regex:.*:(.*_)?scorpion(_.*)?", "regex:.*:(.*_)?spider", "alexsmobs:centipede_head"],
  "glow_color": "#3DFFD2",
  "strength": 0.9
}
```

*   **`entity_ids`** (or one **`entity_id`**): registry names, `regex:` patterns over registry names, or `#` entity type tags (`"#mypack:fluorescent"`).
*   **`glow_color`**: The colour of the glow: `#RRGGBB` or a dye's name.
*   **`strength`**: How strong, 0 to 4 (default 1).
*   **`min_brightness`**, **`max_brightness`** (optional, 0 to 1): only the part of the texture that bright glows. `"min_brightness": 0.4` leaves shadows and dark clothes dark.
*   **`hue`**, **`hue_range`** (optional): only the part of the texture near a hue glows. `hue` is a colour (`"#FFD000"`) or degrees round the colour wheel; `hue_range` is degrees either side (default 30). With these the markings of a body can glow and the rest stay dark.
*   **`textures`** (optional): textures that glow whoever draws them (`"mymod:textures/entity/layer.png"`: a layer of a mob, a block entity's texture). A listed entity's own body texture needs no naming; what it wears or holds does not glow.

The files written when the folder is missing or empty: arachnids, jellyfish, crustaceans, fungi, bones, frogs and the
mammals found to glow under ultraviolet. A file can be deleted or edited; the defaults come back only into an empty folder.
`/ll reload` and a resource reload (F3 + T) read the folder again; `client.uv_reactive_entities` switches the glow off.

---

## Commands

*   `/ll reload`: Reloads all config files and definitions.
*   `/ll purge`: Removes all light blocks from the world.
*   `/ll toggle [entity]`: Toggles dynamic lighting for a specific entity.
*   `/ll sound <on|off>`: Switches the sounds of your own lights on or off, for everyone who hears them (kept by the server and told to the clients that play the sounds themselves).
*   `/ll add <entity> <level>`: Forces an entity to emit light.
*   `/ll remove <entity>`: Removes forced light from an entity.
*   `/ll config <option> <value>`: Modifies main config options in-game.

`/ll toggle` for yourself and `/ll sound` are everyone's. The others ask for operator level 2, or the permission
`livelylighting.command.livelylighting` from a permissions mod (LuckPerms, through Camafy's `Permissions`, 0.1.89).
