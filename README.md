# Lively Lighting

Dynamic lighting for Forge 1.20.1 that knows Valkyrien Skies, Create contraptions and Colorful Lighting. Held torches, burning
mobs, glowing items and the lamps of moving ships and contraptions light the world; the world lights the ships and contraptions
that pass; the light has its source's colour where Colorful Lighting is installed. Since 0.5.0 the light can be shown two ways,
with the same definitions and the same configuration keys whichever is used:

- **The server module** places `minecraft:light` blocks where light should be. Every client, modded or not, sees it. It is the
  original mod, and the costly way: each block a light moves to is a block change on the server and a light update and a chunk
  rebuild on every client.
- **The client module** draws the same light on the client, with no block placed anywhere. With Colorful Lighting 2.8.0 it draws
  in colour through Colorful Lighting's own light sampling (entities, block entities, particles and chunk meshes), and where
  Embeddium runs Colorful Lighting's shaders without an Oculus shader pack it draws in the shaders themselves, so a moving light
  rebuilds no mesh at all; under a shader pack the light goes through the packed light the pack decodes, like every dynamic
  lighting mod. Without Colorful Lighting it draws white light through the vanilla and Embeddium light lookups.

`lighting_mode` in `livelylighting.json` says who shows the light (read by the server, the integrated one in singleplayer too):
`server_blocks` (the server places blocks for everyone; a client with the mod draws nothing), `client` (the server places nothing;
clients with the mod draw, clients without see no dynamic light) or `hybrid` (default: the server places blocks only where a
player whose client has no Lively Lighting can see them, and clients with the mod draw their own). The server tells every client
the mode on login; a server without 0.5.0 counts as `server_blocks`.

## Light shapes, colour and definitions

A definition (item, entity or block) may give its light a `shape`: `point` (all round, vanilla's light) or `spot` (a cone: a
headlight on a ship or contraption, a flashlight in hand, pointing where its holder looks or where the block faces), with
`cone_degrees` (the full outer angle, default 40; the inner, fully lit angle is 60 % of it), `range` (blocks; default the level
for a point, 32 for a spot) and `color` (`#RRGGBB`; default Colorful Lighting's colour for the item or block, white without
it). The server module places every shape as a point light of its level; the client module draws the shape. `block_definitions/`
is new: `block_id` (or `regex:`), `shape`, `cone_degrees`, `range`, `color`, `direction` (`facing` for the block's facing
property, else a fixed direction such as `north`), `lit_property` (a boolean block property that must be true, such as `lit`)
and `light_level` (default the block's emission). The default set makes `minecraft:end_rod` a headlight: a 30-degree cone of
range 32 along its rod. A block in `#livelylighting:headlights` is a headlight with the default cone when it has no definition;
an item in `#livelylighting:flashlights` a flashlight.

## The client module's keys (`client`, read by the client only)

*   **`renderer`**: `auto` (Colorful Lighting's shader path where it can run, else the lightmap path), `shader`, `lightmap`, `off`. Default `auto`.
*   **`falloff`**: `diamond` (a level lost per block of Manhattan distance, vanilla's look) or `round` (per block of straight distance). Default `diamond`.
*   **`lightmap_rebuilds_per_tick`** (12), **`lightmap_max_reach`** (15), **`lightmap_max_moving_lamps`** (32): the lightmap path's budgets.
*   **`shader_max_lights`** (16, at most 64), **`shader_max_distance`** (64): the lights the shaders get per frame, nearest the camera first; every terrain vertex in view evaluates each of them.
*   **`spot_beams`** (false): reserved for a faint beam drawn in the air.

Overrides for a run: `-Dlivelylighting.mode=server_blocks|client|hybrid` (the server), `-Dlivelylighting.clientRenderer=...` and
`-Dlivelylighting.clientMode=client` (a client draws even where the server places blocks).

## Moving structures

With `vs_support`, a Valkyrien Skies ship's lamps light the world as it moves (the brightest `max_lamps_per_ship` first), world
lamps and other ships' lamps light the ship, an entity on a ship lights the world; a Create contraption's lamps do the same
(Create is optional). In the client module the lamps follow the ship's render pose and the contraption's render transform every
frame; a headlight block on either is a spot light along its facing.

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
*   **`max_lamps_per_ship`** (Integer): With `vs_support`: at most this many of a ship's own lamps are projected into the world as it moves, the brightest first, then the nearest a player (chosen again with the ship's lamp scan every three seconds); the rest give the world no light. Each projected lamp is a light block moving with the ship, a block change and a light update on every client per cell it crosses. `0` = no cap. Default: `32`.
*   **`small_ship_updates_per_tick`** (Integer): With `vs_support`: when more small ships than this need their light changed in the same engine run (a torch carried past hundreds of one-block ships), only this many are updated, the ones whose light is most wrong and nearest a player first, each in one step; the others keep the light they have until their turn. Lights in the world and on larger ships are never deferred. `0` = no limit. Default: `6`.
*   **`small_ship_max_blocks`** (Integer): What counts as a small ship for the setting above: the volume of the box its blocks fill. Default: `8`.
*   **`ship_fire_lights`** (Boolean): With `vs_support`: a ship on fire (Camafy's ship fire) gives light like a burning entity, at up to four places on it, lighting the world round it and its own blocks. Default: `true`.
*   **`clean_copied_ship_lights`** (Boolean): With `vs_support`: the first time in a session a ship comes near a player, light blocks in and round it that the mod has no record of and no player placed by hand are taken out: copies of the mod's own lights that a VMod schematic paste or a ship assembly took along, which would otherwise shine in the copy for good. `-Dlivelylighting.keepShipLights=true` skips it for a run. Default: `true`.
*   **`light_source_colors`** (Boolean): Clients are told what each of the mod's light blocks stands for (the holder and the slot its light comes from, a lamp, a burning ship), so Colorful Lighting gives the light its source's colour wherever it is: a fast holder's trail, a light fading out after it was put away, lamps merged into one light, the dim edge of a lamp's light on a ship. Needs the mod on the client too (it then answers Colorful Lighting's block light colour API); clients without it keep Colorful Lighting's own guess. `-Dlivelylighting.noLightSourceColors=true` on a client ignores what it is told, for an A/B. Default: `true`.
*   **`curios_lighting`** (Boolean): With Curios installed, an item with a light level worn in a curio slot (a torch or lantern on a belt) lights its wearer like one in the off hand; the light takes the colour Colorful Lighting gives that item. Lively Lighting also puts the torches and lanterns in Curios' `belt` tag, so a pack with a belt slot (Spyglass Improvements adds one) can hang them there. Default: `true`.

### Experimental Settings

*   **`experimental.smoothing`** (Boolean): Enables smooth light movement and trails. Default: `true`.
*   **`experimental.smoothing_all_entities`** (Boolean): Applies smoothing to all entities, not just players. Can be laggy. Default: `false`.
*   **`experimental.cluster_growing`** (Boolean): Merges nearby light sources into a larger/brighter cluster. Default: `true`.
*   **`experimental.cluster_merge_distance`** (Double): Distance to merge lights. Default: `6.0`.
*   **`experimental.max_influence_radius`** (Integer): Max radius of a light cluster. Default: `3`.
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
