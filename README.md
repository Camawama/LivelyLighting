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
*   **`frames_on_ships_are_headlights`** (true): a lit item in an item frame on a Valkyrien Skies ship is a headlight along the frame's facing, turned with the ship.
*   **`lit_carriers`** (true): an entity carrying a light that asks to be lit by it (Camafy's `LightSource.glow`: a kart under its headlights), and whoever rides it, is drawn at least that bright, with nothing round it lit.
*   **`spot_holder_glow`** (6, 0 to 15, 0 for off): whoever holds a spot light (a flashlight) is drawn at least this bright in its colour, with nothing round them lit. The beam itself never lights its holder: it starts at them and points away. Needs `lit_carriers` and Colorful Lighting 2.10.3.
*   **`map_refresh`** (true): Xaero's minimap and World Map redraw the chunks a dynamic light left or moved in. They read the world's light (`world_light`) but redraw a chunk only when told of a change, and kept the light they had drawn.
*   **`cluster_merging`** (true): with `experimental.cluster_growing`, point lights of entities, dropped items and other mods closer than `experimental.cluster_merge_distance` to one another, with nothing solid on the line between them, add one larger light over their middle. It is their light added up by how close they are: two torches side by side give a light three levels brighter than one torch, four give six more, and from half the merge distance outward each light counts less, down to nothing where the group parts, so the merged light grows as lights come together and is gone, not at its brightest, when they part. A long row or ring of lights is cut into merged lights that each lie among their own members. Up to 15 plus `experimental.max_influence_radius` (24 at most); with Colorful Lighting 2.10.1 a strength over 15 is a core of full light and a longer reach. It eases in and out as lights join and part, and eases out when this key or `experimental.cluster_growing` is switched off in a running game. Spot lights never merge. Off: every light is drawn on its own.
*   **`cluster_lamps`** (true): lamps on ships and contraptions, a burning ship's fire among them, join a merged light that has a carried light in it and count as the carried lights do. Lamps alone never merge. Off: lamps stay out of merged lights.
*   **`max_merged_lights`** (12, 1 to 16): merged lights drawn at once, the nearest the camera first. A nearer group takes the place of the farthest merged light, which eases out first. Colorful Lighting 2.10.2 keeps sixteen slabs for lights that reach beyond 15 blocks; an older one keeps four, and merged lights beyond those are drawn without walls stopping them.
*   **`hold_new_lights`** (true): on the shader renderer a new light stays dark until Colorful Lighting has worked out where its light may go, six ticks at most, and is then let up over two. Off: a new light shows at once and can shine through a wall for a tick or two.
*   **`light_path_update_ticks`** (1, 1 to 10): on the shader renderer with `voxel` transmission, the fewest ticks between two updates of where one moving light's light may go (Colorful Lighting 2.10.4). At 1 a light's shadows and the colour it takes behind stained glass follow it every tick; at more they follow that many ticks apart, while the light itself still moves every frame. Higher values spare the background thread that works the paths out when many lights move at once (64 moving lights: 95% of a core at 1, 34% at 4, 15% at 10) without a change in the frame rate on a processor with cores to spare.
*   **`world_light`** (true): the world's own light on this client answers the drawn dynamic light too, for mods that read how bright the player's place is (Stellar View's light pollution). The meshes and Colorful Lighting's samples don't read it.

With Embeddium these keys are also a **Lively Lighting page in the video settings**; a change there is written to `livelylighting.json` and takes effect at once.
*   **`lightmap_propagate`** (true): on the lightmap path a point light is a lamp Colorful Lighting's engine propagates: stopped by walls, tinted by glass, read by shader packs as block light.

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

## Configuration

The configuration is file-based, in `config/livelylighting/`.

## Configuration Structure

The configuration is split into three parts:
1.  **`livelylighting.json`**: The main configuration file for global settings.
2.  **`item_definitions/`**: A directory containing individual JSON files for each custom item.
3.  **`entity_definitions/`**: A directory containing individual JSON files for each custom entity.

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
*   **`enable_particles`** (Boolean): Master toggle for particle effects. Default: `true`.
*   **`enable_sounds`** (Boolean): Master toggle for sound effects. Default: `true`.
*   **`auto_detect_block_light`** (Boolean): If enabled, the mod automatically detects items that are blocks and emit light (e.g., modded lamps) and assigns them a dynamic light level. Default: `true`.
*   **`auto_detect_blacklist`** (List<String>): A list of item IDs to exclude from auto-detection (e.g., `minecraft:light`).
*   **`vs_support`** (Boolean): Experimental Valkyrien Skies support. Default: `false`.
*   **`lamps_scale_with_ships`** (Boolean): With `vs_support`: a ship's lamps reach as far as the ship's Valkyrien Skies scale says (see "Moving structures"). Read by the server for its light blocks and by each client for the light it draws. Default: `true`.
*   **`max_lamps_per_ship`** (Integer): With `vs_support`: at most this many of a ship's own lamps are projected into the world as it moves, the brightest first, then the nearest a player (chosen again with the ship's lamp scan every three seconds); the rest give the world no light. Each projected lamp is a light block moving with the ship, a block change and a light update on every client per cell it crosses. `0` = no cap. Default: `32`.
*   **`small_ship_updates_per_tick`** (Integer): With `vs_support`: when more small ships than this need their light changed in the same engine run (a torch carried past hundreds of one-block ships), only this many are updated, the ones whose light is most wrong and nearest a player first, each in one step; the others keep the light they have until their turn. Lights in the world and on larger ships are never deferred. `0` = no limit. Default: `6`.
*   **`small_ship_max_blocks`** (Integer): What counts as a small ship for the setting above: the volume of the box its blocks fill. Default: `8`.
*   **`ship_fire_lights`** (Boolean): With `vs_support`: a ship on fire (Camafy's ship fire) gives light like a burning entity, at up to four places on it, lighting the world round it and its own blocks. Default: `true`.
*   **`clean_copied_ship_lights`** (Boolean): With `vs_support`: the first time in a session a ship comes near a player, light blocks in and round it that the mod has no record of and no player placed by hand are taken out: copies of the mod's own lights that a VMod schematic paste or a ship assembly took along, which would otherwise shine in the copy for good. `-Dlivelylighting.keepShipLights=true` skips it for a run. Default: `true`.
*   **`light_source_colors`** (Boolean): Clients are told what each of the mod's light blocks stands for (the holder and the slot its light comes from, a lamp, a burning ship), so Colorful Lighting gives the light its source's colour wherever it is: a fast holder's trail, a light fading out after it was put away, lamps merged into one light, the dim edge of a lamp's light on a ship. Needs the mod on the client too (it then answers Colorful Lighting's block light colour API); clients without it keep Colorful Lighting's own guess. `-Dlivelylighting.noLightSourceColors=true` on a client ignores what it is told, for an A/B. Default: `true`.
*   **`gameplay_light`** (Boolean): Dynamic light counts as the world's own light on the server: mob spawning, snow and ice melting, crops and anything else that asks how bright a place is see the carried, worn and burning lights and other mods' lights, as they saw the light blocks. Each light counts with its source's level, losing a level per block. Idle in `server_blocks` mode. Default: `true`.
*   **`gameplay_light_occlusion`** (Boolean): With `gameplay_light`: that light stops at walls and goes round corners as a placed lamp's does (a level a block, more through leaves and water, none through an opaque block), and a spot light counts inside its cone only, dimming along its beam, where a straight line from it is clear. A ship's blocks stop nothing. Off: a straight count through everything, every light a point light, as 0.6.0. Default: `true`.
*   **`curios_lighting`** (Boolean): With Curios installed, an item with a light level worn in a curio slot (a torch or lantern on a belt) lights its wearer like one in the off hand; the light takes the colour Colorful Lighting gives that item. Lively Lighting also puts the torches and lanterns in Curios' `belt` tag, so a pack with a belt slot (Spyglass Improvements adds one) can hang them there. Default: `true`.

### Experimental Settings

*   **`experimental.smoothing`** (Boolean): Enables smooth light movement and trails. Default: `true`.
*   **`experimental.smoothing_all_entities`** (Boolean): Applies smoothing to all entities, not just players. Can be laggy. Default: `false`.
*   **`experimental.cluster_growing`** (Boolean): Lights near each other add a larger, brighter merged light: the server's light blocks, the server's gameplay light and, on the client, the drawn lights (`client.cluster_merging`), all by one rule. Every light keeps its own light; the merged light lies over the group. Default: `false`.
*   **`experimental.cluster_merge_distance`** (Double): How far apart two lights can be and still merge, with a clear line between them. Up to half of it a light counts in full, beyond it not at all. A ship's lamps are grouped by cells of this size in the ship's own grid. Default: `6.0`.
*   **`experimental.max_influence_radius`** (Integer): Max radius of a light cluster; on the client a merged light's strength goes up to 15 plus this (24 at most). Default: `3`.
*   **`experimental.trail_decay_rate`** (Integer): Speed of light trail decay. Default: `2`.
*   **`experimental.fade_in_rate`** (Integer): Speed of light fade-in. Default: `5`.
*   **`experimental.light_source_spacing`** (Double): Minimum distance between light updates (0.0 = every block).

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
  "activate_sounds": "minecraft:block.fire.ambient:1.0:1.0",
  "deactivate_sounds": "minecraft:block.fire.extinguish"
}
```

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

## Commands

*   `/ll reload`: Reloads all config files and definitions.
*   `/ll purge`: Removes all light blocks from the world.
*   `/ll toggle [entity]`: Toggles dynamic lighting for a specific entity.
*   `/ll sound <on|off>`: Toggles dynamic lighting sounds for yourself (client-side preference).
*   `/ll add <entity> <level>`: Forces an entity to emit light.
*   `/ll remove <entity>`: Removes forced light from an entity.
*   `/ll config <option> <value>`: Modifies main config options in-game.
