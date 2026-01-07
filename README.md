# Lively Lighting - Configuration Guide

Lively Lighting is a server-side dynamic lighting mod that is highly configurable via the `config/livelylighting.json` file. This file is generated when you first run the mod. You can edit it while the game is running and apply changes using the `/ll reload` command.

## General Settings

### `enable`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: Master toggle for the entire mod. If set to `false`, no dynamic lights will be calculated or placed.

### `max_light_sources`
*   **Type**: Integer
*   **Default**: `500`
*   **Range**: `0` - `10000`
*   **Description**: The maximum number of entities that can emit light simultaneously per dimension. This prevents server lag in scenarios with massive entity counts (e.g., mob farms). Players are prioritized over other entities.

### `enchanted_items_glow`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: If enabled, any enchanted item held by an entity will emit a faint glow (Light Level 6).

### `fire_aspect_glow`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: If enabled, items enchanted with Fire Aspect will emit a brighter glow (Light Level 9).

### `enable_entity_lights`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: Master toggle for entities emitting light based on their type (e.g., Blazes, Magma Cubes). If disabled, only held items will emit light.

### `creeper_lighting`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: If enabled, Creepers will emit light as they swell up before exploding. The light level increases with the swell progress.

### `tnt_lighting`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: If enabled, ignited TNT will flash light in sync with its visual blinking animation.

### `vs_support`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `false`
*   **Description**: Enables compatibility with Valkyrien Skies. This feature is experimental and may have issues. It allows light to propagate between the world and ships.

---

## Experimental Settings

These settings control the advanced lighting engine features.

### `experimental.mode`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `false`
*   **Description**: Enables the advanced lighting engine.
    *   **False (Standard Mode)**: Fast and stable. Places a single light block at the entity's position.
    *   **True (Experimental Mode)**: Enables Smoothing and Cluster Growing features.

### `experimental.smoothing`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: Requires `experimental.mode = true`.
    *   Calculates precise light levels based on the entity's exact position (sub-block precision).
    *   Enables "Trail Decay" (lights fade out slowly behind moving entities).
    *   Enables "Fade In" (lights ramp up brightness when first activated).

### `experimental.cluster_growing`
*   **Type**: Boolean (`true` / `false`)
*   **Default**: `true`
*   **Description**: Requires `experimental.mode = true`.
    *   If multiple light-emitting entities are close together, their light levels combine to create a larger radius of illumination.

### `experimental.cluster_merge_distance`
*   **Type**: Double
*   **Default**: `4.0`
*   **Description**: The distance (in blocks) within which entities are grouped into a single light cluster. Higher values mean entities that are further apart will still merge their light.

### `experimental.max_influence_radius`
*   **Type**: Integer
*   **Default**: `8`
*   **Range**: `1` - `32`
*   **Description**: The maximum radius (in blocks) that a cluster of entities can illuminate. Prevents massive lag if hundreds of entities are stacked in one spot.

### `experimental.trail_decay_rate`
*   **Type**: Integer
*   **Default**: `2`
*   **Range**: `1` - `15`
*   **Description**: Controls how fast the light trail fades out. Higher value = Faster fade out.

### `experimental.fade_in_rate`
*   **Type**: Integer
*   **Default**: `5`
*   **Range**: `1` - `15`
*   **Description**: Controls how fast a new light source reaches full brightness. Higher value = Faster fade in.

---

## Custom Lists

### `custom_items`
*   **Format**: `"item_id|light_level|water_sensitive|type"`
*   **Description**: A list of items that emit light when held or thrown.
    *   `item_id`: The registry name of the item (e.g., `minecraft:torch`). Supports regex if prefixed with `regex:`.
    *   `light_level`: The light level (0-15).
    *   `water_sensitive`: `true` if the item should stop emitting light in water/rain, `false` otherwise.
    *   `type`: (Optional) The effect type. Can be `FLAME` or `GLOW`. Defaults to `GLOW` unless the ID contains "torch", "fire", etc.
*   **Example**:
    ```json
    "minecraft:torch|14|true|FLAME",
    "minecraft:glowstone|15|false|GLOW"
    ```

### `custom_entities`
*   **Format**: `"entity_id|light_level"`
*   **Description**: A list of entity types that naturally emit light.
    *   `entity_id`: The registry name of the entity (e.g., `minecraft:blaze`). Supports regex.
    *   `light_level`: The light level (0-15).
*   **Example**:
    ```json
    "minecraft:blaze|15",
    "minecraft:spectral_arrow|8"
    ```

---

## Commands

All commands can be run using either `/livelylighting` or the alias `/ll`.

### General Commands

*   `/ll reload`
    *   **Description**: Reloads the `livelylighting.json` file and applies changes immediately without restarting the server.
    *   **Permission**: OP (Level 2)

*   `/ll purge`
    *   **Description**: Removes all light blocks placed by the mod from the world. Useful if light blocks get stuck or linger.
    *   **Permission**: OP (Level 2)

*   `/ll config <option> <value>`
    *   **Description**: Modifies a configuration option in-game. Automatically saves and reloads the config.
    *   **Examples**:
        *   `/ll config enable false`
        *   `/ll config experimental.mode true`
        *   `/ll config max_light_sources 100`
    *   **Note**: Cannot modify list-based options like `custom_items` or `custom_entities`.
    *   **Permission**: OP (Level 2)

### Entity Management Commands

*   `/ll toggle [entity]`
    *   **Description**: Toggles dynamic lighting for a specific entity.
    *   **Usage**:
        *   `/ll toggle`: Toggles lighting for yourself (the player running the command). No OP required.
        *   `/ll toggle @e[type=minecraft:zombie,limit=1]`: Toggles lighting for a specific target entity. Requires OP.
    *   **Persistence**: This setting is saved per-entity and persists across server restarts.

*   `/ll add <entity> <light_level>`
    *   **Description**: Forces a specific entity to emit light at the specified level (1-15).
    *   **Usage**: `/ll add @p 15` (Makes the nearest player glow with light level 15).
    *   **Persistence**: This setting is saved per-entity and persists across server restarts.
    *   **Permission**: OP (Level 2)

*   `/ll remove <entity>`
    *   **Description**: Removes the forced light level set by `/ll add`. The entity will revert to its default behavior (emitting light only if holding an item or configured in `custom_entities`).
    *   **Permission**: OP (Level 2)
