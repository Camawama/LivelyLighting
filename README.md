# Lively Lighting - Configuration Guide

Lively Lighting is a server-side dynamic lighting mod that is highly configurable. It uses a file-based configuration system located in `config/livelylighting/`.

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
*   **`small_ship_updates_per_tick`** (Integer): With `vs_support`: when more small ships than this need their light changed in the same engine run (a torch carried past hundreds of one-block ships), only this many are updated, the ones whose light is most wrong and nearest a player first, each in one step; the others keep the light they have until their turn. Lights in the world and on larger ships are never deferred. `0` = no limit. Default: `6`.
*   **`small_ship_max_blocks`** (Integer): What counts as a small ship for the setting above: the volume of the box its blocks fill. Default: `8`.
*   **`ship_fire_lights`** (Boolean): With `vs_support`: a ship on fire (Camafy's ship fire) gives light like a burning entity, at up to four places on it, lighting the world round it and its own blocks. Default: `true`.
*   **`clean_copied_ship_lights`** (Boolean): With `vs_support`: the first time in a session a ship comes near a player, light blocks in and round it that the mod has no record of and no player placed by hand are taken out: copies of the mod's own lights that a VMod schematic paste or a ship assembly took along, which would otherwise shine in the copy for good. `-Dlivelylighting.keepShipLights=true` skips it for a run. Default: `true`.
*   **`light_source_colors`** (Boolean): Clients are told what each of the mod's light blocks stands for (the holder and the slot its light comes from, a lamp, a burning ship), so Colorful Lighting gives the light its source's colour wherever it is: a fast holder's trail, a light fading out after it was put away, lamps merged into one light, the dim edge of a lamp's light on a ship. Needs the mod on the client too (it then answers Colorful Lighting's block light colour API); clients without it keep Colorful Lighting's own guess. `-Dlivelylighting.noLightSourceColors=true` on a client ignores what it is told, for an A/B. Default: `true`.

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
