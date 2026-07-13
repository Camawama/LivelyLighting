# Changelog

## 0.4.0 — Unreleased

The smoothness update: dynamic lights now hand off between blocks with the minimum possible visual change, plus major stability and performance work.

### Added

- **`experimental.ultra_smoothing`** (default `false`) — sub-block light interpolation. The source is treated as a continuous point light at the entity's eye: every nearby block gets `ceil(level − manhattanDistance(eye, blockCenter))`, so light levels crossfade exactly with your position. Standing centered on a block gives one full-level light; straddling a boundary lights both blocks at full; every surrounding cell steps ±1 exactly when your position dictates, including vertical motion (jumping interpolates). Crossfades can never dim — a block only drops a level the same tick its successor gains one. Redundant blocks (already receiving their exact level by propagation) are trimmed to limit block-update churn, but this mode still updates more blocks than normal smoothing. Requires `smoothing`; bypasses predictive anchoring and the fade rates entirely while active (trails on departure still apply). Highly experimental.
- **Predictive light anchoring** — the light block leads slightly in your direction of travel (up to 1 block, derived from movement history), so light no longer visibly trails behind when sprinting. Vertical motion is ignored so jumping doesn't bounce the light.
- **Ghost light cleanup** — every light block the mod places is now recorded in world save data. If a chunk unloads mid-fade or the server crashes, the orphaned light block is automatically removed the next time its chunk loads. `/ll purge` also clears these records.
- **`light_update_interval` config** (default `1`) — run the light engine every N ticks. Movement handoffs only shift light by one level per block, so `2` still looks smooth on busy servers.
- **Instant equipment response** — swapping a light source in or out of your hands/armor forces an immediate light update, even with a raised update interval.
- **Persistent sound preferences** — `/ll sound on|off` is now saved with the world and survives restarts.
- Dynamic light items now emit light from item frames.

### Changed

- **Ignition fade-in now follows the source, not the block.** Each source tracks an *established* level that ramps up at `fade_in_rate` while it stays lit — igniting a torch fades in smoothly even mid-sprint, and movement can never restart or compound the fade. Once established, a moving source stays at full brightness.
- **No more flashing when changing direction.** The predictive anchor lead now uses an exponentially smoothed velocity, so turning swings the lead over a few ticks instead of snapping the light block sideways.
- **Fade-out is synced with fade-in.** A block being vacated never fades below the level the incoming light will provide once its own fade-in completes, so handoffs can't dip dark and pulse back up; it's removed the moment the live light actually covers it.
- **Smoothing off now means fully instant.** With `smoothing` disabled (or for non-players with `smoothing_all_entities` disabled), lights place, update, and vanish immediately — no fade-in, fade-out, or trails anywhere in the pipeline.
- **Reworked the smoothing algorithm.** Moving light sources now use an "invisible handoff": the new light block spawns at the brightness that spot already receives from the old block, and the old block is removed the moment the new one covers it. Every affected block changes by exactly ±1 light level per block moved — the minimum possible with Minecraft's integer lighting — eliminating the pulsing trail and flicker when moving fast. Fade-in/fade-out still applies where it should: igniting, extinguishing, and light left behind corners.
- **Smoothing is now enabled by default** and moved out of the experimental config section (`smoothing`, `smoothing_all_entities`, `fade_in_rate`, `trail_decay_rate` are now top-level options; defaults for the rates are now `2`). Old config files are migrated automatically and existing values are preserved.
- Fade rates no longer affect movement smoothness at all — they only control ignition, extinguish, and corner fades, so lower values are now purely cosmetic polish.
- The light level no longer flickers between two values while moving near the anchor block (distance penalty now has a 1-block dead-band).
- License changed to MIT.

### Fixed

- **Held lights now illuminate Valkyrien Skies ships (both directions).** Approaching or standing on a ship while holding a light source anchors a light block in the shipyard (where ship blocks take their light from), dimmed by distance so the hull and deck brighten naturally as you get closer. Unlike the old implementation this is safe: the anchor must be attached to the ship — face-adjacent to a genuine ship block, with light blocks not counting so anchors can never chain outward off each other (any AABB growth is bounded to one block beyond real structure); it must have line-of-sight to the holder in shipyard space (a sealed hull won't light up inside from a torch outside); and it lives in the ordinary world light lifecycle — fade-out, save records and the chunk-load sweep all apply, so orphaned or stacking ship lights are structurally impossible. Combined with emitter projection below, world ↔ ship light now flows both ways. The ship-lamp scan distinguishes mod-placed light blocks from player-placed ones via the save records, so a held light's own shipyard anchor never echoes back into the world as a "ship lamp" — while manually placed light blocks on ships still project like any other lamp.
- **Valkyrien Skies support reworked from scratch.** The old implementation placed light blocks inside the shipyard; a moving ship made the transformed position drift every tick (leaving a new block each tick that never cleaned up), and blocks placed at the shipyard edge grew the ship's bounds, which grew the placement region — the runaway "expanding ship" bug. The mod now **never places a block in the shipyard**. Instead, each nearby ship's own light-emitting blocks (lanterns, glowstone, etc.) are scanned (cached, rescanned every 3 s) and projected into the static world as dynamic light sources — as the ship moves and rotates, its lamps genuinely appear to illuminate the world around it. Entities holding lights while standing on ships are handled as normal world sources. Stale shipyard light blocks from older versions are cleaned automatically by the ghost-light sweep when the ship loads.
- Spectral arrows (and other projectiles/items) no longer lose their light when stuck in or against a wall — the suffocation check only applies to living entities now; a genuinely buried source is instead handled by the new line-of-sight rule.
- Light blocks can no longer be placed through walls. Anchor spots (and ultra-smoothing blocks) must now have a clear light path back to the source, using the light engine's own transparency rule so glass and other light-passing blocks don't block it. An enclosed entity's light stays inside its enclosure.
- **Light trails are back.** Fast-moving sources no longer dim toward darkness: the fade-in throttle used to re-apply at every new anchor block, so any source moving more blocks per tick than `fade_in_rate` lost brightness until it (and its trail) all but vanished. An established source now carries its light level with it while moving, and the blocks it leaves behind fade out at `trail_decay_rate` — visible whenever you move faster than the decay (lower `trail_decay_rate` for longer trails).
- Two light-holding entities near each other no longer cancel each other's light out. Merged clusters place their light at the average of the sources' positions, which could land inside a wall between them — placement failed and both lights vanished. The cluster now snaps to the nearest valid spot.
- Light blocks the mod didn't place (manually placed ones, e.g. Colorful Lighting wildcard emitters) are never adopted, overwritten, or deleted anymore. Previously a dynamic light could anchor onto one, change its level while you stood nearby, and remove it entirely when you walked away.
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
