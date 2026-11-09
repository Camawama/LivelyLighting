package net.cama.livelylighting.dynamiclighting.engine;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.data.LivelyLightingData;
import net.cama.livelylighting.data.ModTags;
import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class LightLogic {

    // How often (in ticks) a ship's blocks are rescanned for light emitters.
    // Between scans the cached emitter list is re-projected every tick, so ship
    // motion stays perfectly current; only placing/breaking a lamp on the ship
    // waits at most this long to be noticed. World emitter chunks use the same
    // interval.
    private static final int SHIP_EMITTER_RESCAN_INTERVAL = 60;

    // How far a world light block's light can plausibly reach a ship.
    private static final int WORLD_EMITTER_LIGHT_REACH = 15;

    // At most this many world lamps light a single ship, brightest-effective
    // first, so a ship docked in a heavily lit harbor stays bounded.
    private static final int MAX_WORLD_EMITTERS_PER_SHIP = 16;

    /**
     * How long (ticks) a light's anchor in a ship's shipyard is reused before it is searched for again. Finding one
     * reads up to seven blocks for each of 343 cells and raycasts the best of them; done for every lamp near every
     * ship each tick it cost about a tenth of the server's tick with a few dozen ships around. Where the anchor goes
     * depends only on which shipyard cell the light falls in and on the ship's blocks there, so it is kept while the
     * light stays in that cell (a ship moving the lamp into another cell searches again at once) and the anchor spot
     * stays valid, and searched afresh at least this often for blocks changed nearby.
     */
    private static final int ANCHOR_REUSE_TICKS = 20;

    /** A light's anchor in a ship's shipyard: the ship, the shipyard cell the light falls in, its level and kind. */
    private record AnchorKey(long shipId, long sourceCell, int lightLevel, boolean isPlayer) {}

    /** Where that light was anchored (null: nowhere), and when it was searched. */
    private record CachedAnchor(BlockPos anchor, long time) {}

    private static final Map<ResourceKey<Level>, Map<AnchorKey, CachedAnchor>> ANCHORS = new HashMap<>();

    /**
     * A source lit again within this many ticks of going out is the same light flickering, not a new ignition: no
     * ignition sound or particles again. A creative player on a burning ship was set alight every few ticks by the
     * ship's fire and put out by vanilla between (an invulnerable player's fire lasts one tick), and the mod's flame
     * sound and particles played every half second (the owner, 2026-10-12).
     */
    private static final int IGNITION_REARM_TICKS = 20;

    /** Forgets every cached shipyard anchor (a purge). */
    public static void clearCaches() {
        ANCHORS.clear();
        FIRE_SEEN.clear();
    }

    /**
     * Whether a light source's owner is gone as far as this level is concerned: no entity with
     * that id here (left the dimension, unloaded, despawned), one that is removed (dying,
     * discarded, picked up), a spectator, or one switched off with /ll toggle. Such an entity's
     * lit state and anchors are dropped, so nothing can freeze its light in place or put it back.
     */
    private static boolean sourceGone(ServerLevel level, LivelyLightingData data, int entityId) {
        Entity entity = level.getEntity(entityId);
        if (entity == null || entity.isRemoved()) return true;
        if (entity instanceof Player player && player.isSpectator()) return true;
        return data.isEntityDisabled(entity.getUUID());
    }

    /** Drops everything the engine remembers about one entity's sources; its light then fades as unwanted. */
    static void forgetEntity(ServerLevel level, int entityId, LightGameState gameState) {
        ResourceKey<Level> dimension = level.dimension();
        Map<Integer, Map<String, LightData>> lit = gameState.entityLitState.get(dimension);
        if (lit != null) lit.remove(entityId);
        Map<Integer, Map<String, Integer>> established = gameState.sourceEstablished.get(dimension);
        if (established != null) established.remove(entityId);
        Map<Integer, Map<String, BlockPos>> anchors = gameState.lastSourcePos.get(dimension);
        if (anchors != null) anchors.remove(entityId);
    }

    private static final java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> LUMINOUS =
            state -> state.getLightEmission() > 0;

    /**
     * Counters for tests and the pack harness: {runs with a small ship wanting a change, small ships that wanted one,
     * ships brought up to date on a turn, ships held waiting}.
     */
    private static final long[] SHIP_BUDGET_STATS = new long[4];
    /** {ships whose lamps were capped at a scan, lamps left out} (the harness). */
    private static final long[] SHIP_LAMP_CAP_STATS = new long[2];

    public static long[] shipLampCapStats() {
        return SHIP_LAMP_CAP_STATS.clone();
    }

    public static long[] shipBudgetStats() {
        return SHIP_BUDGET_STATS.clone();
    }

    /**
     * Small ships take turns when more of them need their lights changed in this run than
     * {@code small_ship_updates_per_tick}: every changed light block in a shipyard is a block change, a light update
     * and a rebuilt chunk on each client, and a torch carried past hundreds of one-block ships asked for hundreds a
     * second.
     *
     * Works on the finished plan: the lights wanted this run against the ones placed, shipyard positions grouped by
     * their ship. A small ship (its blocks fill a box of at most {@code small_ship_max_blocks}) whose lights would
     * change is a candidate; its error is the largest level difference among its positions. With no more candidates
     * than the budget nothing is touched (the plan is applied as ever, fades included). Otherwise the candidates with
     * the highest score (error, weighed by the distance to the nearest player: 1 within 8 blocks, 8 / distance beyond;
     * plus a tenth for every run already waited, so none starves) take the turns, and their lights go to the wanted
     * state in one step (the returned positions: {@code applyChanges}' immediate set). For every other candidate the
     * plan is rewritten to what is placed now, so not one of its blocks changes this run.
     *
     * Lights in the world and on larger ships are never touched here.
     */
    private static Set<BlockPos> budgetSmallShips(ServerLevel level, LivelyConfig config, LightGameState gameState,
                                                  Map<BlockPos, Integer> current, Set<BlockPos> currentPlayer,
                                                  Map<BlockPos, Integer> desired, Set<BlockPos> desiredPlayer) {
        Map<Long, Integer> waited = gameState.shipLightWait.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        int budget = config.small_ship_updates_per_tick;
        if (budget <= 0 || Boolean.getBoolean("livelylighting.noShipBudget")) {
            waited.clear();
            return Set.of();
        }

        // the planned and the placed lights that lie in a shipyard, by ship (one ship lookup per chunk)
        Map<Long, ShipRef> chunkShips = new HashMap<>();
        Map<Long, ShipRef> ships = new HashMap<>();
        Map<Long, List<BlockPos>> byShip = new HashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            for (BlockPos pos : (pass == 0 ? current : desired).keySet()) {
                if (pass == 1 && current.containsKey(pos)) continue;
                if (!Ships.inShipyard(level, pos)) continue;
                long chunkKey = net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
                ShipRef ship = chunkShips.get(chunkKey);
                if (ship == null && !chunkShips.containsKey(chunkKey)) {
                    ship = Ships.managing(level, pos);
                    chunkShips.put(chunkKey, ship);
                }
                if (ship == null) continue;
                ships.putIfAbsent(ship.id(), ship);
                byShip.computeIfAbsent(ship.id(), k -> new ArrayList<>()).add(pos);
            }
        }

        // the small ships whose lights would change this run
        int maxBlocks = Math.max(1, config.small_ship_max_blocks);
        List<long[]> candidates = new ArrayList<>(); // {ship id, error}
        for (Map.Entry<Long, List<BlockPos>> entry : byShip.entrySet()) {
            BoundingBox blocks = ships.get(entry.getKey()).shipyardBlocks();
            if (blocks == null) continue;
            if ((long) blocks.getXSpan() * blocks.getYSpan() * blocks.getZSpan() > maxBlocks) continue;
            int error = 0;
            for (BlockPos pos : entry.getValue()) {
                error = Math.max(error, Math.abs(desired.getOrDefault(pos, 0) - current.getOrDefault(pos, 0)));
            }
            if (error > 0) candidates.add(new long[]{entry.getKey(), error});
        }
        if (candidates.isEmpty()) {
            waited.clear();
            return Set.of();
        }
        SHIP_BUDGET_STATS[0]++;
        SHIP_BUDGET_STATS[1] += candidates.size();
        if (candidates.size() <= budget) {
            waited.clear();
            return Set.of();
        }

        Map<Long, Double> score = new HashMap<>();
        List<? extends Player> players = level.players();
        for (long[] candidate : candidates) {
            AABB box = ships.get(candidate[0]).worldAabb();
            double nearest = Double.MAX_VALUE;
            if (box != null) {
                for (Player player : players) {
                    if (player.isSpectator()) continue;
                    double dx = Math.max(0, Math.max(box.minX - player.getX(), player.getX() - box.maxX));
                    double dy = Math.max(0, Math.max(box.minY - player.getEyeY(), player.getEyeY() - box.maxY));
                    double dz = Math.max(0, Math.max(box.minZ - player.getZ(), player.getZ() - box.maxZ));
                    nearest = Math.min(nearest, Math.max(dx, Math.max(dy, dz)));
                }
            }
            double weight = nearest <= 8.0 ? 1.0 : 8.0 / nearest;
            score.put(candidate[0], candidate[1] * weight + 0.1 * waited.getOrDefault(candidate[0], 0));
        }
        // the highest score first; the ship id settles ties, so the order never depends on map iteration
        candidates.sort((a, b) -> {
            int byScore = Double.compare(score.get(b[0]), score.get(a[0]));
            return byScore != 0 ? byScore : Long.compare(a[0], b[0]);
        });

        Set<BlockPos> immediate = new HashSet<>();
        Map<Long, Integer> stillWaiting = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            long id = candidates.get(i)[0];
            List<BlockPos> positions = byShip.get(id);
            if (i < budget) {
                immediate.addAll(positions);
                continue;
            }
            stillWaiting.put(id, waited.getOrDefault(id, 0) + 1);
            for (BlockPos pos : positions) {
                int placed = current.getOrDefault(pos, 0);
                if (placed > 0) {
                    desired.put(pos, placed);
                    if (currentPlayer.contains(pos)) desiredPlayer.add(pos);
                    else desiredPlayer.remove(pos);
                } else {
                    desired.remove(pos);
                    desiredPlayer.remove(pos);
                }
            }
        }
        SHIP_BUDGET_STATS[2] += Math.min(budget, candidates.size());
        SHIP_BUDGET_STATS[3] += stillWaiting.size();
        waited.clear();
        waited.putAll(stillWaiting);
        return immediate;
    }

    // A ship lamp's world-space position this tick, collected in pass 1 of the VS
    // section and fed to every OTHER ship's projection for ship-to-ship lighting.
    // sourceKind/sourceValue: what its light stands for (LightSources), for its colour.
    private record ShipWorldEmitter(long shipId, double x, double y, double z, int emission, int sourceKind, long sourceValue) {}

    /**
     * @param vsAvailable Valkyrien Skies is installed and reachable through Camafy
     * @param useVs       ship lighting is enabled ({@code vsAvailable} and the vs_support config)
     */
    public static void runLightLogic(ServerLevel level, LivelyConfig config, LightGameState gameState, boolean vsAvailable, boolean useVs) {
        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, Integer> worldCurrentLights = gameState.levelLights.computeIfAbsent(dimension, k -> new HashMap<>());
        Set<BlockPos> worldPlayerLights = gameState.playerLights.computeIfAbsent(dimension, k -> new HashSet<>());
        Map<BlockPos, Integer> worldDesiredLights = new HashMap<>();
        Set<BlockPos> worldDesiredPlayerLights = new HashSet<>();
        Map<BlockPos, Integer> worldDesiredCarry = new HashMap<>();
        // what each planned light stands for (LightSources), told to clients for its colour
        Map<BlockPos, long[]> worldDesiredSources = new HashMap<>();

        boolean smoothing = config.smoothing;
        boolean smoothingAllEntities = config.smoothing_all_entities;
        boolean clusterGrowing = config.experimental.cluster_growing;
        double mergeDistance = config.experimental.cluster_merge_distance;
        int maxRadius = config.experimental.max_influence_radius;
        int decayRate = Math.max(1, config.trail_decay_rate);
        int fadeInRate = Math.max(1, config.fade_in_rate);
        int maxSources = config.max_light_sources;

        // Carried lights (entities, other mods' lights), one per block they are placed in. Until 0.6.4 these were merged by
        // a grid of the merge distance: two holders merged or not by which side of a cell border they stood on, and their
        // light jumped as one crossed it. Lights near each other now add a merged light by LightClusters' rule below.
        Map<BlockPos, LightCluster> worldClusters = new HashMap<>();
        // A ship's lamps and fire, one light per cell of the merge distance in the ship's own (shipyard) grid, so the lamps
        // of a cell stay the same ones however the ship moves (the world's grid regrouped them as it moved).
        Map<BlockPos, LightCluster> lampClusters = new HashMap<>();
        double lampGrid = mergeDistance >= 1.0 ? mergeDistance : 1.0;
        // Exact-position sources, keyed by their block position: ultra-smoothing
        // interpolation blocks and shipyard projections. These are pre-validated
        // single-block lights and must never be grid-merged or position-averaged
        // like regular clusters.
        Map<BlockPos, LightCluster> worldUltraClusters = new HashMap<>();
        int sourceCount = 0;

        // The radius is capped at 192. The VS ship search boxes below still go
        // through queries that Valkyrien Skies rejects when "too big" — VS 2.4.9
        // rejects volume > 1e8 (~464 blocks cubed; verified in
        // BugFixUtil.isCollisionBoxTooBig bytecode), older builds reject > 1000
        // per axis; that once killed all entity lights at higher view distances.
        // 192 (~5.7e7 volume) clears both limits with margin, bounds the entity
        // range test, and light from a source beyond 192 blocks is imperceptible
        // anyway. (The entity scan itself no longer uses Level.getEntities.)
        // A server that never set a view distance (a game-test server, some wrappers) reports 0,
        // which would cull every entity: vanilla's default of 10 chunks stands in for it.
        int viewChunks = level.getServer().getPlayerList().getViewDistance();
        if (viewChunks <= 0) viewChunks = 10;
        int viewDistance = Math.min(viewChunks * 16, 192);

        Set<Integer> processedIds = new HashSet<>();

        // the players the block engine lights for (lighting_mode: all of them, or only those whose client has no
        // Lively Lighting); a source is placed only where one of them can see it
        List<? extends Player> litFor = net.cama.livelylighting.source.LightModes.litFor(level);

        for (Player player : level.players()) {
            if (sourceCount >= maxSources) break;
            if (player.isSpectator()) continue;
            if (!litFor.contains(player) && !net.cama.livelylighting.source.LightModes.anyNear(player, litFor, viewDistance)) continue;

            processEntity(player, level, worldClusters, worldUltraClusters, processedIds, config, useVs, gameState);
            sourceCount++;
        }

        if (sourceCount < maxSources) {
            // Single pass over the loaded-entity list instead of a per-player
            // sectioned AABB query: the box query walked every entity section in
            // a ~384-block cube per player each tick and ran through VS's
            // Level.getEntities mixin. A Chebyshev range test against each
            // player reproduces the old inflated-box semantics.
            List<? extends Player> players = litFor;
            int unlitInterval = Math.max(1, config.unlit_entity_check_interval);
            long runIndex = level.getGameTime() / Math.max(1, config.light_update_interval);

            boolean lodEnabled = config.distance_lod;
            int lodStart = config.lod_start_distance;
            int lodStep = Math.max(1, config.lod_distance_step);
            int lodMax = Math.max(1, config.lod_max_interval);
            int cullLimit = viewDistance;
            if (config.light_cull_distance > 0) {
                cullLimit = Math.min(cullLimit, config.light_cull_distance);
            }
            // Once a player at most this close is found, scanning further players
            // can't change the outcome: the entity is within the cull limit and
            // already at the full update rate.
            double earlyOut = lodEnabled ? Math.min(lodStart, cullLimit) : cullLimit;

            // Budgeted ignition: how many unlit->lit transitions this run may
            // still perform. Players were processed above without touching this,
            // so a joining player's own light is always immediate.
            int ignitionBudget = config.max_ignitions_per_tick > 0
                    ? config.max_ignitions_per_tick : Integer.MAX_VALUE;

            for (Entity entity : level.getAllEntities()) {
                if (sourceCount >= maxSources) break;
                if (entity instanceof Player) continue;

                // Chebyshev distance to the nearest player: reproduces the old
                // inflated-box inclusion test exactly at distance == cullLimit,
                // and doubles as the LOD metric below.
                double minDist = Double.MAX_VALUE;
                for (Player player : players) {
                    double cheb = Math.max(Math.abs(entity.getX() - player.getX()),
                                  Math.max(Math.abs(entity.getY() - player.getY()),
                                           Math.abs(entity.getZ() - player.getZ())));
                    if (cheb < minDist) minDist = cheb;
                    if (minDist <= earlyOut) break;
                }
                boolean shipResident = false;
                if (minDist > cullLimit && vsAvailable) {
                    // Shipyard-resident entities (item frames and other hanging
                    // entities mounted on ship blocks) have raw coordinates in
                    // the far-away shipyard region and can never pass the
                    // world-space test above; the old sectioned AABB query
                    // included them via VS's Level.getEntities mixin. Range-test
                    // their ship-transformed world position instead. Gated on
                    // vsAvailable (not useVs) to match the mixin's old behavior of
                    // feeding these in even with vs_support disabled.
                    ShipRef ship = Ships.managing(level, entity.blockPosition());
                    if (ship != null) {
                        shipResident = true;
                        Vec3 worldPos = ship.toWorld(entity.position());
                        if (worldPos != null) {
                            for (Player player : players) {
                                double cheb = Math.max(Math.abs(worldPos.x - player.getX()),
                                              Math.max(Math.abs(worldPos.y - player.getY()),
                                                       Math.abs(worldPos.z - player.getZ())));
                                if (cheb < minDist) minDist = cheb;
                            }
                        }
                    }
                }
                if (minDist > cullLimit) continue;
                if (!shouldCheckEntity(entity, config)) continue;

                // Distance LOD: refresh interval grows with distance from the
                // nearest player. Ship-resident sources are exempt — their
                // world-side projection is recomputed from scratch every run
                // (never cached in lastSourcePos), so freezing them would let
                // that projection fade and pulse between refreshes.
                int lodInterval = 1;
                if (lodEnabled && !shipResident && minDist > lodStart) {
                    lodInterval = Math.min(lodMax, 1 + (int) ((minDist - lodStart) / lodStep));
                }

                // TNT and creepers are exempt from every throttle so their flash
                // cadence stays exact. Staggering by run index (not raw game
                // time) keeps the stagger cycling when light_update_interval
                // shares a factor with it.
                boolean throttleExempt = entity instanceof PrimedTnt || entity instanceof Creeper;

                boolean wasUnlit = !hasLitState(level, entity.getId(), gameState);
                if (wasUnlit) {
                    // Unlit entities are only re-checked for NEW light every
                    // checkInterval runs, staggered by id — ignition can merely
                    // start up to interval-1 runs late, existing lights are
                    // untouched. The LOD interval folds in here so far-away
                    // unlit mobs are polled at the LOD rate too.
                    int checkInterval = Math.max(unlitInterval, lodInterval);
                    if (checkInterval > 1 && !throttleExempt
                            && (entity.getId() + runIndex) % checkInterval != 0) {
                        continue;
                    }
                    // Out of ignition budget this run: defer before doing any
                    // item/equipment scan work. The entity stays unlit and gets
                    // its turn as the backlog drains over the next few runs —
                    // this is what turns the world-join "every source ignites
                    // in one tick" freeze into a brief cascade.
                    if (ignitionBudget <= 0 && !throttleExempt) {
                        continue;
                    }
                } else if (lodInterval > 1 && !throttleExempt
                        && (entity.getId() + runIndex) % lodInterval != 0) {
                    // LOD-skipped run for a lit entity: freeze its light exactly
                    // where it is instead of recomputing it. Desired == current
                    // means applyChanges touches nothing — zero block updates
                    // reach the client, which is the entire point of the LOD.
                    // The anchor catches up on the entity's next refresh run and
                    // the abandoned spot fades out as a normal trail.
                    if (freezeEntityLights(level, entity, gameState, worldUltraClusters)) {
                        sourceCount++;
                        continue;
                    }
                    // Nothing to freeze (ultra-smoothing sources keep no anchor
                    // cache): process normally rather than letting the light
                    // fade out mid-skip.
                }

                if (processEntity(entity, level, worldClusters, worldUltraClusters, processedIds, config, useVs, gameState)) {
                    sourceCount++;
                    if (wasUnlit) {
                        ignitionBudget--;
                    }
                }
            }
        }

        // Other mods' lights through Camafy's DynamicLights (kart headlights): placed as point lights of their level where a
        // player the engine lights for can see them (a spot's cone is the client module's; here it is a point light)
        if (net.camacraft.camafy.light.DynamicLights.hasProviders() && !litFor.isEmpty()) {
            List<net.camacraft.camafy.light.LightSource> apiLights = new ArrayList<>();
            net.camacraft.camafy.light.DynamicLights.collect(level, 1.0f, apiLights::add);
            for (net.camacraft.camafy.light.LightSource src : apiLights) {
                if (sourceCount >= maxSources) break;
                int lvl = Math.min(15, Math.round(src.level()));
                if (lvl <= 0) continue;
                Vec3 at = src.pos();
                boolean seen = false;
                for (Player p : litFor) {
                    if (Math.max(Math.abs(p.getX() - at.x), Math.max(Math.abs(p.getY() - at.y), Math.abs(p.getZ() - at.z))) <= viewDistance) { seen = true; break; }
                }
                if (!seen) continue;
                sourceCount++;
                worldClusters.computeIfAbsent(BlockPos.containing(at), k -> new LightCluster()).add(at.x, at.y, at.z, lvl, false, lvl);
            }
        }

        // Valkyrien Skies: project each nearby ship's own light-emitting blocks
        // into the world as dynamic light sources. As the ship moves and rotates,
        // the world light blocks follow, so the ship's lamps appear to illuminate
        // the world around the ship. Entities standing on ships already have true
        // world coordinates and were handled above like any other entity — nothing
        // is ever placed inside the shipyard.
        if (useVs) {
            // The mod's own shipyard-projected light blocks emit light too and must
            // never count as ship lamps, or a held torch echoes back into the world.
            // The scan itself already excludes recorded blocks (the authoritative
            // filter — a stale cache entry could otherwise outlive its record after
            // the anchor fades); this projection-time check is defense in depth.
            Set<BlockPos> ownPlacedLights = LivelyLightingData.get(level).getPlacedLights(dimension);

            // Pass 1: gather every ship near a player once, refresh its emitter
            // cache, project its lamps into the world, and remember each lamp's
            // world position this tick for pass 2's ship-to-ship lighting.
            Map<Long, ShipRef> nearbyShips = new HashMap<>();
            // only the players the block engine lights for (lighting_mode): a client that draws its own light needs no
            // block for a ship's lamp, and the ships it alone sees are left to it
            for (Player player : litFor) {
                AABB searchBox = player.getBoundingBox().inflate(viewDistance);
                for (ShipRef ship : Ships.intersecting(level, searchBox)) {
                    nearbyShips.putIfAbsent(ship.id(), ship);
                }
            }

            Map<Long, Map<BlockPos, Integer>> shipEmitterCache = gameState.shipEmitterCache
                    .computeIfAbsent(dimension, k -> new HashMap<>());
            Map<Long, Long> shipEmitterScanTime = gameState.shipEmitterScanTime
                    .computeIfAbsent(dimension, k -> new HashMap<>());

            List<ShipWorldEmitter> shipWorldEmitters = new ArrayList<>();
            // -Dlivelylighting.maxLampsPerShip=<n> overrides the config for a run (an A/B)
            int lampCap = Integer.getInteger("livelylighting.maxLampsPerShip", config.max_lamps_per_ship);
            for (Map.Entry<Long, ShipRef> shipEntry : nearbyShips.entrySet()) {
                long shipId = shipEntry.getKey();
                ShipRef ship = shipEntry.getValue();
                if (config.clean_copied_ship_lights && !Boolean.getBoolean("livelylighting.keepShipLights")) LightManager.checkShipLights(level, ship, gameState);

                Map<BlockPos, Integer> emitters = shipEmitterCache.get(shipId);
                long lastScan = shipEmitterScanTime.getOrDefault(shipId, Long.MIN_VALUE);
                if (emitters == null || level.getGameTime() - lastScan >= SHIP_EMITTER_RESCAN_INTERVAL) {
                    emitters = capShipLamps(level, ship, scanShipForEmitters(ship, ownPlacedLights, config.light_blocks_are_lamps), lampCap);
                    shipEmitterCache.put(shipId, emitters);
                    shipEmitterScanTime.put(shipId, level.getGameTime());
                }

                for (Map.Entry<BlockPos, Integer> emitter : emitters.entrySet()) {
                    if (sourceCount >= maxSources) break;
                    sourceCount++;

                    BlockPos shipPos = emitter.getKey();
                    if (ownPlacedLights.contains(shipPos)) continue;
                    int emission = emitter.getValue();
                    Vec3 worldPos = ship.toWorld(Vec3.atCenterOf(shipPos));
                    if (worldPos == null) continue; // ship gone since the intersect query

                    int gridX = (int) Math.floor(shipPos.getX() / lampGrid);
                    int gridY = (int) Math.floor(shipPos.getY() / lampGrid);
                    int gridZ = (int) Math.floor(shipPos.getZ() / lampGrid);
                    BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);

                    // Carry equals the emission so an already-lit lamp never
                    // re-runs the ignition fade as the ship moves.
                    LightCluster lampCluster = lampClusters.computeIfAbsent(gridPos, k -> new LightCluster());
                    lampCluster.add(worldPos.x, worldPos.y, worldPos.z, emission, false, emission);
                    lampCluster.lamp().source(LightSources.SHIP_LAMP, shipPos.asLong());

                    shipWorldEmitters.add(new ShipWorldEmitter(shipId, worldPos.x, worldPos.y, worldPos.z, emission,
                            LightSources.SHIP_LAMP, shipPos.asLong()));
                }

                // a ship on fire (Camafy's ship fire) is a light like a burning entity (ship_fire_lights)
                if (config.ship_fire_lights && sourceCount < maxSources) {
                    sourceCount += addShipFireLights(level, ship, shipId, lampGrid, maxSources - sourceCount,
                            lampClusters, worldUltraClusters, shipWorldEmitters);
                }
            }

            // Pass 2: light each ship from the static world's lamps and from the
            // other ships' lamps collected above — ship-to-ship lighting is just
            // the world→ship projection fed with another ship's emitters.
            for (Map.Entry<Long, ShipRef> shipEntry : nearbyShips.entrySet()) {
                projectWorldEmittersOntoShip(level, shipEntry.getValue(), shipEntry.getKey(), shipWorldEmitters, gameState, ownPlacedLights, config.light_blocks_are_lamps, worldUltraClusters);
            }
        }
        
        // Cleanup logic now scoped to the current dimension
        if (level.getGameTime() % 100 == 0) {
            Map<AnchorKey, CachedAnchor> anchors = ANCHORS.get(dimension);
            if (anchors != null) {
                long now = level.getGameTime();
                anchors.values().removeIf(cached -> now - cached.time() >= ANCHOR_REUSE_TICKS);
            }
            Map<Long, Long> fireSeen = FIRE_SEEN.get(dimension);
            if (fireSeen != null) {
                // ships out of reach of every player, or put out while out of reach
                fireSeen.keySet().removeIf(id -> !net.camacraft.camafy.compat.vs.ShipFire.burning(level, id));
            }
            // The sanity sweep, every five seconds: a source whose owner is gone, removed, a
            // spectator or switched off keeps no state here, so its light can neither be frozen
            // in place by the LOD path nor put back when someone breaks the block.
            LivelyLightingData sweepData = LivelyLightingData.get(level);
            Map<Integer, Map<String, LightData>> dimensionState = gameState.entityLitState.get(dimension);
            if (dimensionState != null) {
                dimensionState.keySet().removeIf(id -> sourceGone(level, sweepData, id));
            }

            Map<Integer, Map<String, Integer>> dimEstablished = gameState.sourceEstablished.get(dimension);
            if (dimEstablished != null) {
                dimEstablished.keySet().removeIf(id -> sourceGone(level, sweepData, id));
            }
            
            if (useVs) {
                // Ships loaded in this level only, so prune only this
                // dimension's ship caches: pruning a shared map against them
                // evicted every other dimension's ships on each pass.
                LongSet loadedShipIds = Ships.loadedIds(level);
                Map<Long, Map<BlockPos, Integer>> dimShipEmitters = gameState.shipEmitterCache.get(dimension);
                if (dimShipEmitters != null) {
                    dimShipEmitters.keySet().removeIf(id -> !loadedShipIds.contains((long) id));
                }
                Map<Long, Long> dimShipScanTimes = gameState.shipEmitterScanTime.get(dimension);
                if (dimShipScanTimes != null) {
                    dimShipScanTimes.keySet().removeIf(id -> !loadedShipIds.contains((long) id));
                }

                // Drop world-emitter chunk scans no ship has refreshed recently,
                // so the cache doesn't accumulate along a ship's whole voyage.
                Map<Long, LightGameState.EmitterScan> dimEmitterChunks = gameState.worldEmitterCache.get(dimension);
                if (dimEmitterChunks != null) {
                    long now = level.getGameTime();
                    dimEmitterChunks.values().removeIf(scan -> now - scan.time > SHIP_EMITTER_RESCAN_INTERVAL * 5);
                }
            }
            
            // Cleanup per-entity caches, scoped to this dimension only
            Map<Integer, Map<String, Long>> dimLitTimes = gameState.lastLitTime.get(dimension);
            if (dimLitTimes != null) {
                long now = level.getGameTime();
                for (Map<String, Long> times : dimLitTimes.values()) times.values().removeIf(t -> now - t > IGNITION_REARM_TICKS);
                dimLitTimes.entrySet().removeIf(e -> e.getValue().isEmpty() || sourceGone(level, sweepData, e.getKey()));
            }
            Map<Integer, Long> dimSoundTimes = gameState.lastSoundTime.get(dimension);
            if (dimSoundTimes != null) {
                dimSoundTimes.keySet().removeIf(id -> sourceGone(level, sweepData, id));
            }
            Map<Integer, Map<String, BlockPos>> dimSourcePos = gameState.lastSourcePos.get(dimension);
            if (dimSourcePos != null) {
                dimSourcePos.keySet().removeIf(id -> sourceGone(level, sweepData, id));
            }
            Map<Integer, Vec3> dimLastPos = gameState.lastEntityPos.get(dimension);
            if (dimLastPos != null) {
                dimLastPos.keySet().removeIf(id -> sourceGone(level, sweepData, id));
            }
            Map<Integer, Vec3> dimSmoothedVel = gameState.smoothedVelocity.get(dimension);
            if (dimSmoothedVel != null) {
                dimSmoothedVel.keySet().removeIf(id -> sourceGone(level, sweepData, id));
            }
        }

        // 2. Calculate Desired Light Field: every light on its own, and the merged lights of those near each other
        LightPropagator.calculateLightField(level, worldClusters, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, worldDesiredSources, smoothing, false, maxRadius);
        LightPropagator.calculateLightField(level, lampClusters, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, worldDesiredSources, smoothing, false, maxRadius);
        if (clusterGrowing && mergeDistance > 0) {
            Map<BlockPos, LightCluster> merged = mergedClusters(level, worldClusters, lampClusters, mergeDistance, maxRadius);
            if (!merged.isEmpty()) {
                LightPropagator.calculateLightField(level, merged, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, worldDesiredSources, smoothing, true, maxRadius);
            }
        }
        LightPropagator.calculateLightField(level, worldUltraClusters, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, worldDesiredSources, smoothing, clusterGrowing, maxRadius);

        // 3. Apply Changes. Ship-projected lights live in the world like any other
        // source, so the normal fade/removal path cleans them up when the ship
        // moves away or unloads.
        // (small ships take turns when too many of them would change at once: small_ship_updates_per_tick)
        Set<BlockPos> immediate = useVs
                ? budgetSmallShips(level, config, gameState, worldCurrentLights, worldPlayerLights, worldDesiredLights, worldDesiredPlayerLights)
                : Set.of();
        LightPropagator.applyChanges(level, worldCurrentLights, worldPlayerLights, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, smoothing, smoothingAllEntities, decayRate, fadeInRate, immediate);

        // 4. Tell clients what each placed light stands for, so Colorful Lighting gives it its source's colour
        // (light_source_colors). A fading light keeps what they were told while it was lit.
        LightSourceSync.afterRun(level, config, gameState, worldCurrentLights, worldDesiredSources);
    }

    /**
     * The merged lights of the lights near each other (0.6.4, {@code experimental.cluster_growing}): the rule the client
     * draws by and the gameplay light counts by ({@link net.cama.livelylighting.source.LightClusters}), over the carried
     * lights and, beside those, a ship's lamps. Two lights merge with a clear line between them; the merged light is their
     * light added up by how close they are, so it grows as they come together and is gone where they part. It fades in with
     * its newest member, and tells clients every member's source for its colour.
     */
    private static Map<BlockPos, LightCluster> mergedClusters(ServerLevel level, Map<BlockPos, LightCluster> carried, Map<BlockPos, LightCluster> lamps,
                                                             double mergeDistance, int maxRadius) {
        int total = carried.size() + lamps.size();
        if (carried.isEmpty() || total < 2) return Map.of();
        int n = Math.min(net.cama.livelylighting.source.LightClusters.MAX_LIGHTS, total);
        LightCluster[] members = new LightCluster[n];
        boolean[] lamp = new boolean[n];
        int k = 0;
        for (LightCluster c : carried.values()) {
            if (k >= n) break;
            if (c.count > 0 && c.maxLightLevel > 0) members[k++] = c;
        }
        int carriedCount = k;
        for (LightCluster c : lamps.values()) {
            if (k >= n) break;
            if (c.count > 0 && c.maxLightLevel > 0) {
                lamp[k] = true;
                members[k++] = c;
            }
        }
        final int count = k;
        if (carriedCount == 0 || count < 2) return Map.of();
        double[] x = new double[count], y = new double[count], z = new double[count];
        float[] levels = new float[count];
        Vec3[] at = new Vec3[count];
        for (int i = 0; i < count; i++) {
            x[i] = members[i].midX();
            y[i] = members[i].midY();
            z[i] = members[i].midZ();
            levels[i] = members[i].maxLightLevel;
            at[i] = new Vec3(x[i], y[i], z[i]);
        }
        // each pair's line is walked once a tick at most
        byte[] seen = new byte[count * count];
        net.cama.livelylighting.source.LightClusters.Sight sight = (i, j) -> {
            int key = i * count + j;
            if (seen[key] == 0) seen[key] = (byte) (net.cama.livelylighting.source.LightSight.clear(level, at[i], at[j], false) ? 1 : 2);
            return seen[key] == 1;
        };
        Map<BlockPos, LightCluster> out = new HashMap<>();
        int index = 0;
        for (net.cama.livelylighting.source.LightClusters.Merged m : net.cama.livelylighting.source.LightClusters.merge(x, y, z, levels, lamp, count,
                mergeDistance, net.cama.livelylighting.source.LightClusters.cap(maxRadius), sight, false)) {
            boolean player = false;
            int carry = Integer.MAX_VALUE;
            for (int i : m.members) {
                player |= members[i].isPlayer;
                carry = Math.min(carry, members[i].carryLevel);
            }
            int lvl = Math.max(1, Math.min(15, (int) Math.floor(m.level)));
            LightCluster cluster = new LightCluster();
            cluster.add(m.x, m.y, m.z, lvl, player, Math.min(lvl, carry));
            cluster.merged(m.level);
            for (int i : m.members) cluster.sources(members[i].sources());
            out.put(new BlockPos(index++, 0, 0), cluster);
        }
        return out;
    }

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<BlockPos, LightCluster> worldUltraClusters, Set<Integer> processedIds, LivelyConfig config, boolean useVs, LightGameState gameState) {
        if (!processedIds.add(entity.getId())) return false;

        LivelyLightingData data = LivelyLightingData.get(level);
        if (data.isEntityDisabled(entity.getUUID())) {
            // switched off with /ll toggle: nothing of its light may linger or be frozen in place
            forgetEntity(level, entity.getId(), gameState);
            return false;
        }

        // Velocity from position history: server-side getDeltaMovement is unreliable
        // for players, so derive it from where the entity was last time we ran.
        Vec3 eyePosition = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        Vec3 previousPos = gameState.lastEntityPos
                .computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .put(entity.getId(), eyePosition);
        Vec3 instantVelocity = previousPos != null ? eyePosition.subtract(previousPos) : Vec3.ZERO;

        // Exponentially smoothed: a direction change swings the predictive anchor
        // lead over a few updates instead of snapping it sideways, which flicked
        // the anchor to a different block and flashed the light when turning.
        Map<Integer, Vec3> dimSmoothed = gameState.smoothedVelocity
                .computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Vec3 previousSmoothed = dimSmoothed.get(entity.getId());
        Vec3 velocity = previousSmoothed == null
                ? instantVelocity
                : previousSmoothed.scale(0.5).add(instantVelocity.scale(0.5));
        dimSmoothed.put(entity.getId(), velocity);

        // Check for forced light level
        Integer forcedLevel = data.getForcedLevel(entity.getUUID());
        if (forcedLevel != null) {
            LightData lightData = new LightData(forcedLevel, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, lightData, "forced", gameState, velocity);
            return true;
        }

        // If cluster growing is enabled, we check all slots and add them as separate sources to the cluster
        if (config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            boolean foundLight = false;
            
            // Main Hand
            LightData main = LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, main, "mainhand", gameState, velocity);
            if (main.level > 0) foundLight = true;

            // Off Hand
            LightData off = LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, off, "offhand", gameState, velocity);
            if (off.level > 0) foundLight = true;

            // Armor
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                    LightData armor = LightCalculator.getItemLightLevel(living.getItemBySlot(slot), entity, level, config);
                    processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, armor, slot.getName(), gameState, velocity);
                    if (armor.level > 0) foundLight = true;
                }
            }
            
            // Curios (a torch or lantern on a belt): the brightest worn curio as one source
            if (config.curios_lighting && net.cama.livelylighting.compat.CuriosLights.loaded()) {
                LightData curios = new LightData(0, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
                for (net.minecraft.world.item.ItemStack worn : net.cama.livelylighting.compat.CuriosLights.worn(living)) {
                    LightData one = LightCalculator.getItemLightLevel(worn, entity, level, config);
                    if (one.level > curios.level) curios = one;
                }
                processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, curios, "curios", gameState, velocity);
                if (curios.level > 0) foundLight = true;
            }

            // Special Enderman check
            if (entity instanceof net.minecraft.world.entity.monster.EnderMan enderman) {
                net.minecraft.world.level.block.state.BlockState heldBlock = enderman.getCarriedBlock();
                LightData blockLight;
                if (heldBlock != null) {
                    net.minecraft.world.item.ItemStack blockStack = new net.minecraft.world.item.ItemStack(heldBlock.getBlock());
                    blockLight = LightCalculator.getItemLightLevel(blockStack, entity, level, config);
                } else {
                    blockLight = new LightData(0, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
                }
                processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, blockLight, "carried_block", gameState, velocity);
                if (blockLight.level > 0) foundLight = true;
            }

            // Check other sources (fire, glow effect, etc)
            LightData entityLight = LightCalculator.getEntityLightLevel(entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, entityLight, "body", gameState, velocity);
            if (entityLight.level > 0) foundLight = true;
            
            return foundLight;
        } else {
            // Standard behavior: get best light level and process once
            LightData lightData = LightCalculator.getBestLightLevel(entity, level, config);
            LightData lastData = getEntityLitState(level, entity.getId(), "combined", gameState);
            
            if (lightData.level > 0 || (lastData != null && lastData.level > 0)) {
                processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, lightData, "combined", gameState, velocity);
                return lightData.level > 0;
            }
            return false;
        }
    }
    
    // Whether any source on this entity was lit last run — such entities bypass
    // the unlit-entity throttle so active lights are never skipped.
    private static boolean hasLitState(ServerLevel level, int entityId, LightGameState gameState) {
        Map<Integer, Map<String, LightData>> dimensionState = gameState.entityLitState.get(level.dimension());
        return dimensionState != null && dimensionState.containsKey(entityId);
    }

    /**
     * Re-injects an LOD-skipped entity's cached anchors into the desired field at
     * exactly their currently applied level, holding the light in place for this
     * run. Because desired == current, applyChanges recognizes the block as
     * already correct and performs no setBlock at all — a frozen source costs the
     * server one map lookup and the client literally nothing. Carry equals the
     * level so the fade-in ramp can't re-trigger. Goes through the exact-position
     * cluster map since the anchor is a pre-validated single block that must not
     * be grid-merged or position-averaged.
     *
     * Returns false when there is nothing to freeze (no cached anchor, or its
     * block is already gone), in which case the caller should process the entity
     * normally so its light state can't silently rot.
     */
    private static boolean freezeEntityLights(ServerLevel level, Entity entity, LightGameState gameState, Map<BlockPos, LightCluster> exactClusters) {
        if (entity.isRemoved() || LivelyLightingData.get(level).isEntityDisabled(entity.getUUID())) {
            forgetEntity(level, entity.getId(), gameState);
            return false;
        }
        Map<Integer, Map<String, BlockPos>> dimSourcePos = gameState.lastSourcePos.get(level.dimension());
        if (dimSourcePos == null) return false;
        Map<String, BlockPos> anchors = dimSourcePos.get(entity.getId());
        if (anchors == null || anchors.isEmpty()) return false;
        Map<BlockPos, Integer> dimLights = gameState.levelLights.get(level.dimension());
        if (dimLights == null) return false;

        boolean frozen = false;
        boolean isPlayer = entity instanceof Player;
        for (Map.Entry<String, BlockPos> anchor : anchors.entrySet()) {
            BlockPos pos = anchor.getValue();
            Integer current = dimLights.get(pos);
            if (current == null || current <= 0) continue;
            LightCluster cluster = exactClusters.computeIfAbsent(pos, k -> new LightCluster());
            cluster.add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, current, isPlayer, current);
            int slot = LightSources.slotOf(anchor.getKey());
            cluster.source(LightSources.ENTITY, LightSources.entity(entity.getId(), slot, LightSources.token(entity, slot)));
            frozen = true;
        }
        return frozen;
    }

    private static LightData getEntityLitState(ServerLevel level, int entityId, String sourceId, LightGameState gameState) {
        Map<Integer, Map<String, LightData>> dimensionState = gameState.entityLitState.get(level.dimension());
        if (dimensionState == null) return null;
        Map<String, LightData> entityState = dimensionState.get(entityId);
        return entityState != null ? entityState.get(sourceId) : null;
    }
    
    private static void setEntityLitState(ServerLevel level, int entityId, String sourceId, LightData data, LightGameState gameState) {
        Map<Integer, Map<String, LightData>> dimState = gameState.entityLitState.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        
        if (data.level > 0) {
            dimState.computeIfAbsent(entityId, k -> new HashMap<>()).put(sourceId, data);
        } else {
            Map<String, LightData> entState = dimState.get(entityId);
            if (entState != null) {
                entState.remove(sourceId);
                if (entState.isEmpty()) {
                    dimState.remove(entityId);
                }
            }
        }
    }
    
    /**
     * Where ignition/extinguish sounds and particles spawn: half a block in front
     * of the eyes, offset toward the hand. Computed from xRot/yRot directly
     * rather than Entity.getViewVector — GravityChanger-style mods inject a
     * capability lookup into getRotationVector, which made the two getViewVector
     * calls here the hottest vanilla calls in the mod despite the result only
     * being needed on the rare ignition/extinguish tick.
     */
    private static Vec3 handPos(Entity entity) {
        float xRot = entity.getXRot() * ((float) Math.PI / 180F);
        float yRot = -entity.getYRot() * ((float) Math.PI / 180F);
        float cosY = net.minecraft.util.Mth.cos(yRot);
        float sinY = net.minecraft.util.Mth.sin(yRot);
        float cosX = net.minecraft.util.Mth.cos(xRot);
        float sinX = net.minecraft.util.Mth.sin(xRot);
        Vec3 view = new Vec3(sinY * cosX, -sinX, cosY * cosX);
        return new Vec3(entity.getX(), entity.getEyeY(), entity.getZ())
                .add(view.scale(0.5))
                .add(view.cross(new Vec3(0, 1, 0)).scale(0.3));
    }

    private static void processLightData(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<BlockPos, LightCluster> worldUltraClusters, LivelyConfig config, boolean useVs, LightData lightData, String sourceId, LightGameState gameState, Vec3 velocity) {
        LivelyLightingData worldData = LivelyLightingData.get(level);
        int lightLevel = lightData.level;
        List<ParticleType<?>> particles = lightData.particles;
        List<SoundData> sounds = lightData.sounds;
        List<SoundData> extinguishSounds = lightData.extinguishSounds;
        
        LightData lastData = getEntityLitState(level, entity.getId(), sourceId, gameState);
        boolean wasLit = lastData != null && lastData.level > 0;
        boolean isLit = lightLevel > 0;
        
        boolean isNew = isLit && !wasLit;

        // lit again soon after going out: the same light flickering, no ignition effects (IGNITION_REARM_TICKS)
        Map<Integer, Map<String, Long>> dimLitTimes = gameState.lastLitTime.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Map<String, Long> litTimes = dimLitTimes.get(entity.getId());
        Long lastLit = litTimes == null ? null : litTimes.get(sourceId);
        boolean flickering = lastLit != null && level.getGameTime() - lastLit <= IGNITION_REARM_TICKS;
        if (isLit) dimLitTimes.computeIfAbsent(entity.getId(), k -> new HashMap<>()).put(sourceId, level.getGameTime());

        if (wasLit && !isLit) {
            if (config.enable_sounds && !worldData.arePlayerSoundsDisabled(entity.getUUID())) {
                boolean playExtinguish = false;
                // Check if extinguished by water (feet or eyes)
                if (entity.isInWater()) {
                    playExtinguish = true;
                }
                // Check if extinguished by rain
                else if (level.isRainingAt(entity.blockPosition()) && level.canSeeSky(entity.blockPosition())) {
                    playExtinguish = true;
                }
                
                if (playExtinguish) {
                    Vec3 handPos = handPos(entity);
                    if (lastData != null && lastData.extinguishSounds != null && !lastData.extinguishSounds.isEmpty()) {
                        for (SoundData sd : lastData.extinguishSounds) {
                            level.playSound(null, handPos.x, handPos.y, handPos.z, sd.sound, SoundSource.NEUTRAL, sd.volume, sd.pitch);
                        }
                    } else {
                        // Fallback default
                        level.playSound(null, handPos.x, handPos.y, handPos.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 0.5f, 1.0f);
                    }
                    
                    if (config.enable_particles) {
                        level.sendParticles(ParticleTypes.SMOKE, handPos.x, handPos.y, handPos.z, 5, 0.1, 0.1, 0.1, 0.0);
                    }
                }
            }
        } else if (isNew && !flickering) {
            if (!wasLit) { // Only play sounds/particles if we weren't lit before
                if (!(entity instanceof Creeper) && !(entity instanceof PrimedTnt)) {
                     Map<Integer, Long> soundTimes = gameState.lastSoundTime.computeIfAbsent(level.dimension(), k -> new HashMap<>());
                     long currentTime = level.getGameTime();
                     long lastTime = soundTimes.getOrDefault(entity.getId(), 0L);

                     if (currentTime - lastTime >= 10) { // 10 tick cooldown (0.5s)
                         soundTimes.put(entity.getId(), currentTime);
                         Vec3 handPos = handPos(entity);

                         if (config.enable_sounds && !worldData.arePlayerSoundsDisabled(entity.getUUID())) {
                             if (sounds != null) {
                                 for (SoundData sd : sounds) {
                                     level.playSound(null, handPos.x, handPos.y, handPos.z, sd.sound, SoundSource.NEUTRAL, sd.volume, sd.pitch);
                                 }
                             }
                         }
                         if (config.enable_particles) {
                             if (particles != null) {
                                 for (ParticleType<?> particle : particles) {
                                     if (particle instanceof ParticleOptions) {
                                         level.sendParticles((ParticleOptions) particle, handPos.x, handPos.y, handPos.z, 3, 0.05, 0.05, 0.05, 0.02);
                                     }
                                 }
                             }
                         }
                     }
                }
            }
        }
        
        setEntityLitState(level, entity.getId(), sourceId, lightData, gameState);

        boolean smoothingActive = config.smoothing && (config.smoothing_all_entities || entity instanceof Player);

        if (lightLevel > 0) {
            // The source's established level ramps up at fade_in_rate while it stays
            // lit, independent of position — so igniting fades in smoothly even while
            // sprinting, and moving can never restart the fade (a per-block fade-in
            // compounds at speed > fade_in_rate and dims the light toward darkness).
            // Once established, a moving source stays at full brightness.
            // With smoothing off (or not applicable to this entity) the light is
            // always instant: no ramp, no fades.
            if (smoothingActive) {
                Map<String, Integer> established = gameState.sourceEstablished
                        .computeIfAbsent(level.dimension(), k -> new HashMap<>())
                        .computeIfAbsent(entity.getId(), k -> new HashMap<>());
                int establishedLevel = Math.min(lightLevel,
                        established.getOrDefault(sourceId, 0) + Math.max(1, config.fade_in_rate));
                established.put(sourceId, establishedLevel);
                lightLevel = establishedLevel;
            }
        } else {
            Map<Integer, Map<String, Integer>> dimEstablished = gameState.sourceEstablished.get(level.dimension());
            if (dimEstablished != null) {
                Map<String, Integer> established = dimEstablished.get(entity.getId());
                if (established != null) {
                    established.remove(sourceId);
                    if (established.isEmpty()) {
                        dimEstablished.remove(entity.getId());
                    }
                }
            }
        }

        if (lightLevel > 0) {
            // what this light stands for (LightSources): the entity, the slot it comes from and what is in it
            int slot = LightSources.slotOf(sourceId);
            long source = LightSources.entity(entity.getId(), slot, LightSources.token(entity, slot));

            // Valkyrien Skies. Two mirrored cases:
            // - An entity that lives at shipyard coordinates (item frames and other
            //   hanging entities mounted on ship blocks): the normal world path
            //   below already lights the ship around it, so project its light OUT
            //   into the static world at the ship-transformed position.
            // - An ordinary world entity (players and mobs on or near ships have
            //   true world coordinates): anchor a light block IN the shipyard so
            //   the ship's own blocks light up; see addShipyardProjection.
            if (useVs) {
                ShipRef managingShip = Ships.managing(level, entity.blockPosition());
                if (managingShip != null) {
                    Vec3 worldEye = managingShip.toWorld(new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()));
                    if (worldEye != null) {
                        addWorldProjection(level, worldEye, lightLevel, entity instanceof Player, worldUltraClusters, source);
                    }
                } else {
                    addShipyardProjection(level, entity, lightLevel, worldUltraClusters, source);
                }
            }

            if (smoothingActive && config.experimental.ultra_smoothing) {
                // Ultra smoothing: no anchor block at all — the entity is treated as
                // a continuous point light and every nearby block gets its exact
                // interpolated level. Predictive lead, anchor caching and the
                // distance penalty are all bypassed; the interpolation IS the anchor.
                addUltraSources(level, entity, lightLevel, worldUltraClusters, source);
                Map<Integer, Map<String, BlockPos>> dimSourcePos = gameState.lastSourcePos.get(level.dimension());
                if (dimSourcePos != null) {
                    Map<String, BlockPos> cache = dimSourcePos.get(entity.getId());
                    if (cache != null) {
                        cache.remove(sourceId);
                    }
                }
            } else {
                double lightX = entity.getX();
                double lightY = entity.getEyeY();
                double lightZ = entity.getZ();

                BlockPos eyePos = BlockPos.containing(lightX, lightY, lightZ);

                // Predictive anchoring: lead the anchor in the direction of horizontal
                // travel (~3 ticks ahead, at most 1 block) so the light doesn't visibly
                // trail behind at sprint speed. Vertical motion is ignored — leading a
                // jump would reintroduce the up/down anchor flapping. Falls back to the
                // eye block when the predicted spot can't host a light (e.g. wall ahead).
                Vec3 lead = new Vec3(velocity.x, 0, velocity.z).scale(3);
                double leadLength = lead.length();
                if (leadLength > 0.5) {
                    if (leadLength > 1.0) {
                        lead = lead.scale(1.0 / leadLength);
                    }
                    BlockPos predicted = BlockPos.containing(lightX + lead.x, lightY, lightZ + lead.z);
                    if (LightPropagator.isValidLightSpot(level, predicted)) {
                        eyePos = predicted;
                    }
                }

                // Calculate dynamic radius based on light level
                int searchRadius = Math.max(1, lightLevel / 2);

                // Use cached position if available and valid
                Map<String, BlockPos> sourcePosCache = gameState.lastSourcePos
                        .computeIfAbsent(level.dimension(), k -> new HashMap<>())
                        .computeIfAbsent(entity.getId(), k -> new HashMap<>());
                BlockPos lastPos = sourcePosCache.get(sourceId);
                BlockPos bestPos = null;

                // The level this source's light block reached last tick. Passed to the
                // cluster as its fade-in floor: an established source moving to a new
                // anchor keeps its brightness instead of re-running the ignition fade,
                // which used to dim fast movers to near-darkness (the ambient handoff
                // loses more per tick than fade_in_rate restores at high speeds).
                int carryLevel = 0;
                if (lastPos != null) {
                    Map<BlockPos, Integer> dimLights = gameState.levelLights.get(level.dimension());
                    if (dimLights != null) {
                        carryLevel = dimLights.getOrDefault(lastPos, 0);
                    }
                }

                double spacingSq = config.experimental.light_source_spacing * config.experimental.light_source_spacing;

                if (lastPos != null && LightPropagator.isValidLightSpot(level, lastPos)) {
                    double distSq = lastPos.distToCenterSqr(entity.getX(), entity.getEyeY(), entity.getZ());
                    if (distSq < spacingSq // Within spacing distance
                            && LightPropagator.hasLightPath(level,
                                    new Vec3(lastPos.getX() + 0.5, lastPos.getY() + 0.5, lastPos.getZ() + 0.5),
                                    new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()))) {
                        bestPos = lastPos;
                    }
                }

                if (bestPos == null) {
                    bestPos = LightPropagator.findValidLightPos(level, eyePos, entity.blockPosition(), searchRadius,
                            new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()));
                }

                if (bestPos != null) {
                    sourcePosCache.put(sourceId, bestPos);
                    
                    lightX = bestPos.getX() + 0.5;
                    lightY = bestPos.getY() + 0.5;
                    lightZ = bestPos.getZ() + 0.5;
                    
                    // Dead-band of one block: the entity's eye is normally within ~1 block
                    // of the anchor, and penalizing that distance made the level flap
                    // between N and N-1 as the eye crossed the boundary while moving.
                    // Only dim anchors that are genuinely far away (e.g. the in-wall case).
                    double dist = Math.sqrt(bestPos.distToCenterSqr(entity.getX(), entity.getEyeY(), entity.getZ()));
                    lightLevel = Math.max(0, lightLevel - (int) Math.floor(Math.max(0, dist - 1.0)));
                } else {
                    sourcePosCache.remove(sourceId);
                }

                if (lightLevel > 0) {
                    LightCluster cluster = worldClusters.computeIfAbsent(BlockPos.containing(lightX, lightY, lightZ), k -> new LightCluster());
                    cluster.add(lightX, lightY, lightZ, lightLevel, entity instanceof Player, carryLevel);
                    cluster.source(LightSources.ENTITY, source);
                }
            }
        } else {
            Map<Integer, Map<String, BlockPos>> dimSourcePos = gameState.lastSourcePos.get(level.dimension());
            if (dimSourcePos != null) {
                Map<String, BlockPos> cache = dimSourcePos.get(entity.getId());
                if (cache != null) {
                    cache.remove(sourceId);
                }
            }
        }
    }

    /**
     * Projects a held/entity light onto nearby Valkyrien Skies ships: the entity's
     * eye is transformed into shipyard space and a light block is anchored at the
     * nearest valid shipyard spot, dimmed by its distance to the transformed eye —
     * so a ship's hull and deck brighten naturally as a light source approaches.
     *
     * Safety rails (each one guards against the old runaway-expansion bug class):
     * the anchor must be ATTACHED to the ship — face-adjacent to a genuine ship
     * block (non-air and not a light block, so anchors can never chain outward
     * off each other) — keeping any AABB growth bounded to one block beyond real
     * structure; it must have a clear light path to the transformed eye in
     * shipyard space, so a closed hull never lights up inside from a torch
     * outside; and the block itself goes through the exact-position cluster map
     * into the ordinary world lifecycle (shipyard coordinates are plain positions
     * in the same dimension), so fade-out, persistence records and the chunk-load
     * sweep all apply unchanged. The ship's block bounds are only used to centre
     * the search on the hull surface nearest the holder — the air above a deck or
     * beside the hull sits at or just outside those bounds, never inside them.
     */
    private static void addShipyardProjection(ServerLevel level, Entity entity, int lightLevel, Map<BlockPos, LightCluster> exactClusters, long source) {
        AABB reach = entity.getBoundingBox().inflate(lightLevel);
        List<ShipRef> ships = Ships.intersecting(level, reach);
        if (ships.isEmpty()) return;

        Vec3 eye = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        boolean isPlayer = entity instanceof Player;

        for (ShipRef ship : ships) {
            projectIntoShipyard(level, ship, eye, lightLevel, isPlayer, exactClusters, LightSources.ENTITY, source);
        }
    }

    /**
     * Anchors one light block in a ship's shipyard for a light source at the given
     * world position — the shared core of both held-light projection and world
     * lamp projection. The source is transformed into shipyard space, the search
     * is centred on the hull surface nearest it, and the anchor must be attached
     * to genuine ship structure and have a clear shipyard-space light path back to
     * the source; the placed level is dimmed by the anchor's distance to the
     * transformed source.
     */
    private static void projectIntoShipyard(ServerLevel level, ShipRef ship, Vec3 worldSource, int lightLevel, boolean isPlayer, Map<BlockPos, LightCluster> exactClusters,
                                            int sourceKind, long sourceValue) {
        BoundingBox blocks = ship.shipyardBlocks();
        if (blocks == null) return;

        Vec3 shipSourceVec = ship.toShip(worldSource);
        if (shipSourceVec == null) return;
        double[] shipSource = {shipSourceVec.x, shipSourceVec.y, shipSourceVec.z};

        // the anchor found for this light in this cell lately, still a valid spot: reused (ANCHOR_REUSE_TICKS)
        Map<AnchorKey, CachedAnchor> anchors = ANCHORS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        AnchorKey key = new AnchorKey(ship.id(), BlockPos.containing(shipSourceVec).asLong(), lightLevel, isPlayer);
        long now = level.getGameTime();
        CachedAnchor cached = anchors.get(key);
        if (cached != null && now - cached.time() < ANCHOR_REUSE_TICKS
                && (cached.anchor() == null || LightPropagator.isValidLightSpot(level, cached.anchor()))) {
            if (cached.anchor() != null) addAnchor(cached.anchor(), shipSource, lightLevel, isPlayer, exactClusters, sourceKind, sourceValue);
            return;
        }

        // Search around the closest in-bounds point to the transformed source —
        // for a source on deck that's roughly its own position, for one away
        // from the ship it's the nearest hull surface. shipyardBlocks() is
        // inclusive; the upper clamp has always been the block edge past the
        // last block (VS's exclusive max), hence the + 1.
        BlockPos searchCenter = new BlockPos(
                (int) Math.floor(Math.max(blocks.minX(), Math.min(blocks.maxX() + 1, shipSource[0]))),
                (int) Math.floor(Math.max(blocks.minY(), Math.min(blocks.maxY() + 1, shipSource[1]))),
                (int) Math.floor(Math.max(blocks.minZ(), Math.min(blocks.maxZ() + 1, shipSource[2]))));

        // Only cells that can touch the ship: an anchor is face-adjacent to one of its blocks, and those all lie
        // in its block bounds, so nothing more than one cell outside them can qualify. For a one-block ship that
        // is 27 cells read instead of 343 (the same cells found, in the same order).
        boolean everyCell = Boolean.getBoolean("livelylighting.fullAnchorSearch");
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            int x = searchCenter.getX() + dx;
            if (!everyCell && (x < blocks.minX() - 1 || x > blocks.maxX() + 1)) continue;
            for (int dy = -3; dy <= 3; dy++) {
                int y = searchCenter.getY() + dy;
                if (!everyCell && (y < blocks.minY() - 1 || y > blocks.maxY() + 1)) continue;
                for (int dz = -3; dz <= 3; dz++) {
                    int z = searchCenter.getZ() + dz;
                    if (!everyCell && (z < blocks.minZ() - 1 || z > blocks.maxZ() + 1)) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!LightPropagator.isValidLightSpot(level, pos)) continue;
                    if (!isAttachedToShipStructure(level, pos)) continue;
                    candidates.add(pos);
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(
                pos -> pos.distToCenterSqr(shipSource[0], shipSource[1], shipSource[2])));

        BlockPos found = null;
        for (BlockPos pos : candidates) {
            if (!LightPropagator.hasLightPath(level,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), shipSourceVec)) {
                continue;
            }
            found = pos;
            addAnchor(pos, shipSource, lightLevel, isPlayer, exactClusters, sourceKind, sourceValue);
            break;
        }
        anchors.put(key, new CachedAnchor(found, now));
    }

    /** Light sources a burning ship gives at most. */
    private static final int SHIP_FIRE_LIGHTS = 4;
    /** A burning ship whose blocks span more than this along a horizontal shipyard axis gets two lights along it. */
    private static final int SHIP_FIRE_SPREAD_SPAN = 8;
    /**
     * Ticks a ship burns before its lights come on. Camafy tells clients which ships burn every 5 ticks, and Colorful
     * Lighting gives a light block its colour when the block arrives (fire's, from Lively Lighting's client half, only
     * once that client knows the ship burns); a light that came first stayed white.
     */
    private static final int SHIP_FIRE_LIGHT_DELAY = 15;
    /** Per dimension: burning ships and the game time Lively Lighting first saw each one burning. */
    private static final Map<ResourceKey<Level>, Map<Long, Long>> FIRE_SEEN = new HashMap<>();

    /**
     * A Valkyrien Skies ship on fire (Camafy's {@code ShipFire}) is a light source like a burning entity: a light of
     * a burning entity's level at each burning part (its block, nudged toward the ship's up so the cell above a deck is
     * the nearest anchor; the largest parts first, at most {@value #SHIP_FIRE_LIGHTS}), or, when the whole ship burns,
     * on its top blocks spread over its box. Each one goes the ways a ship's own lamp and a held torch over a ship go:
     * into the world at its world position (lighting the terrain round the ship, and other ships, following the ship as
     * it moves and turns) and anchored in the ship's own shipyard (lighting its blocks). Every light block comes from
     * the ordinary plan, so it is recorded, fades out within a few ticks once the fire is out or the ship is gone, and is
     * swept when its chunk next loads and purged when the server stops. Returns the light sources added.
     */
    private static int addShipFireLights(ServerLevel level, ShipRef ship, long shipId, double mergeDistance, int room,
                                         Map<BlockPos, LightCluster> lampClusters, Map<BlockPos, LightCluster> exactClusters,
                                         List<ShipWorldEmitter> shipWorldEmitters) {
        Map<Long, Long> seen = FIRE_SEEN.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        if (!net.camacraft.camafy.compat.vs.ShipFire.burning(level, shipId)) {
            seen.remove(shipId);
            return 0;
        }
        if (level.getGameTime() - seen.computeIfAbsent(shipId, k -> level.getGameTime()) < SHIP_FIRE_LIGHT_DELAY) return 0;
        int lightLevel = LightCalculator.BURNING_LIGHT_LEVEL;
        int added = 0;
        for (Vec3 shipyardPoint : shipFirePoints(level, ship, shipId)) {
            if (added >= room) break;
            Vec3 worldPos = ship.toWorld(shipyardPoint);
            if (worldPos == null) break; // ship gone
            BlockPos gridPos = new BlockPos((int) Math.floor(shipyardPoint.x / mergeDistance),
                    (int) Math.floor(shipyardPoint.y / mergeDistance), (int) Math.floor(shipyardPoint.z / mergeDistance));
            // carry equals the level, as for a ship's lamp: a fire moving with its ship never re-runs the ignition fade
            LightCluster fireCluster = lampClusters.computeIfAbsent(gridPos, k -> new LightCluster());
            fireCluster.add(worldPos.x, worldPos.y, worldPos.z, lightLevel, false, lightLevel);
            fireCluster.source(LightSources.FIRE, 0L);
            shipWorldEmitters.add(new ShipWorldEmitter(shipId, worldPos.x, worldPos.y, worldPos.z, lightLevel, LightSources.FIRE, 0L));
            projectIntoShipyard(level, ship, worldPos, lightLevel, false, exactClusters, LightSources.FIRE, 0L);
            added++;
        }
        return added;
    }

    /** Shipyard points a burning ship's lights come from (see {@link #addShipFireLights}). */
    private static List<Vec3> shipFirePoints(ServerLevel level, ShipRef ship, long shipId) {
        List<Vec3> points = new ArrayList<>();
        double[] parts = net.camacraft.camafy.compat.vs.ShipFire.burningParts(level, shipId);
        int count = parts.length / 5;
        // a ship set alight all at once, or whose parts have spread all over it, keeps its parts: it burns all over
        if (count > 0 && net.camacraft.camafy.compat.vs.ShipFire.burningShare(level, shipId) < 0.999F) {
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            // the largest parts first; the part's block settles ties, so the choice never flickers between equals
            Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> -parts[5 * i + 3])
                    .thenComparingDouble(i -> parts[5 * i]).thenComparingDouble(i -> parts[5 * i + 1])
                    .thenComparingDouble(i -> parts[5 * i + 2]));
            for (int k = 0; k < Math.min(SHIP_FIRE_LIGHTS, count); k++) {
                int i = order[k];
                points.add(new Vec3(parts[5 * i] + 0.5, parts[5 * i + 1] + 0.8, parts[5 * i + 2] + 0.5));
            }
            return points;
        }
        // burns all over: its top blocks at one or two places along each horizontal axis of its box
        BoundingBox box = ship.shipyardBlocks();
        if (box == null) return points;
        int nx = box.getXSpan() > SHIP_FIRE_SPREAD_SPAN ? 2 : 1;
        int nz = box.getZSpan() > SHIP_FIRE_SPREAD_SPAN ? 2 : 1;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int x = box.minX() + (int) ((i + 0.5) * box.getXSpan() / nx);
                int z = box.minZ() + (int) ((j + 0.5) * box.getZSpan() / nz);
                for (int y = box.maxY(); y >= box.minY(); y--) {
                    net.minecraft.world.level.block.state.BlockState state = LightPropagator.loadedState(level, new BlockPos(x, y, z));
                    if (state == null) break; // not loaded: nothing waits for a chunk
                    if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.LIGHT)) continue;
                    points.add(new Vec3(x + 0.5, y + 0.8, z + 0.5));
                    break;
                }
            }
        }
        if (points.isEmpty()) {
            points.add(new Vec3(box.minX() + box.getXSpan() / 2.0, box.maxY() + 0.8, box.minZ() + box.getZSpan() / 2.0));
        }
        return points;
    }

    /** The light at its anchor, dimmed by the anchor's distance to the light's exact shipyard position. */
    private static void addAnchor(BlockPos pos, double[] shipSource, int lightLevel, boolean isPlayer, Map<BlockPos, LightCluster> exactClusters,
                                  int sourceKind, long sourceValue) {
        double dist = Math.sqrt(pos.distToCenterSqr(shipSource[0], shipSource[1], shipSource[2]));
        int dimmed = lightLevel - (int) Math.floor(Math.max(0, dist - 1.0));
        if (dimmed > 0) {
            LightCluster cluster = exactClusters.computeIfAbsent(pos, k -> new LightCluster());
            cluster.add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dimmed, isPlayer, dimmed);
            cluster.source(sourceKind, sourceValue);
        }
    }

    /**
     * The ship→world mirror of {@link #addShipyardProjection}: places a world
     * light at the ship-transformed position of a shipyard-resident source (an
     * item frame mounted on a ship, for example), so its light appears to shine
     * off the ship into the static world. The world space there is ordinarily
     * empty air co-located with the ship's visuals; no attachment rule is needed
     * since world placement can't grow anything, but the light path check keeps
     * it from seeding light inside overlapping world terrain.
     */
    private static void addWorldProjection(ServerLevel level, Vec3 worldEye, int lightLevel, boolean isPlayer, Map<BlockPos, LightCluster> exactClusters, long source) {
        BlockPos center = BlockPos.containing(worldEye);

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!LightPropagator.isValidLightSpot(level, pos)) continue;
                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(
                pos -> pos.distToCenterSqr(worldEye.x, worldEye.y, worldEye.z)));

        for (BlockPos pos : candidates) {
            if (!LightPropagator.hasLightPath(level,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), worldEye)) {
                continue;
            }

            double dist = Math.sqrt(pos.distToCenterSqr(worldEye.x, worldEye.y, worldEye.z));
            int dimmed = lightLevel - (int) Math.floor(Math.max(0, dist - 1.0));
            if (dimmed > 0) {
                LightCluster cluster = exactClusters.computeIfAbsent(pos, k -> new LightCluster());
                cluster.add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dimmed, isPlayer, dimmed);
                cluster.source(LightSources.ENTITY, source);
            }
            break;
        }
    }

    /**
     * Projects the light sources within reach of a ship into its shipyard: the
     * genuine world light blocks nearby (so a lamp post lights a passing ship's
     * hull) and other ships' lamps at their current world positions (ship-to-ship
     * lighting). World emitters come from a per-chunk cache: world lamps are
     * static, so the cache needs no invalidation on ship movement and is shared
     * by every ship. Each chunk section is pre-filtered with a palette-level
     * check (maybeHas), so terrain without any luminous block state is skipped
     * without reading a single block. All sources compete in one ranking under
     * the same per-ship cap.
     */
    private static void projectWorldEmittersOntoShip(ServerLevel level, ShipRef ship, long shipId, List<ShipWorldEmitter> shipEmitters, LightGameState gameState, Set<BlockPos> ownPlacedLights, boolean lightBlocksAreLamps, Map<BlockPos, LightCluster> exactClusters) {
        AABB worldBox = ship.worldAabb();
        if (worldBox == null) return;
        double[] worldBounds = {worldBox.minX, worldBox.minY, worldBox.minZ, worldBox.maxX, worldBox.maxY, worldBox.maxZ};

        double minX = worldBounds[0] - WORLD_EMITTER_LIGHT_REACH;
        double minZ = worldBounds[2] - WORLD_EMITTER_LIGHT_REACH;
        double maxX = worldBounds[3] + WORLD_EMITTER_LIGHT_REACH;
        double maxZ = worldBounds[5] + WORLD_EMITTER_LIGHT_REACH;

        Map<Long, LightGameState.EmitterScan> chunkCache = gameState.worldEmitterCache
                .computeIfAbsent(level.dimension(), k -> new HashMap<>());
        long now = level.getGameTime();

        record NearbyEmitter(double x, double y, double z, int emission, int effective, int sourceKind, long sourceValue) {}
        List<NearbyEmitter> nearby = new ArrayList<>();

        // Other ships' lamps at their current world positions. Distance to this
        // ship's world box decides cutoff and ranking, exactly like world lamps.
        for (ShipWorldEmitter shipEmitter : shipEmitters) {
            if (shipEmitter.shipId() == shipId) continue;
            double sdx = Math.max(0, Math.max(worldBounds[0] - shipEmitter.x(), shipEmitter.x() - worldBounds[3]));
            double sdy = Math.max(0, Math.max(worldBounds[1] - shipEmitter.y(), shipEmitter.y() - worldBounds[4]));
            double sdz = Math.max(0, Math.max(worldBounds[2] - shipEmitter.z(), shipEmitter.z() - worldBounds[5]));
            double shipDist = sdx + sdy + sdz;
            int shipEffective = shipEmitter.emission() - (int) Math.floor(Math.max(0, shipDist - 1.0));
            if (shipEffective <= 0) continue;

            nearby.add(new NearbyEmitter(shipEmitter.x(), shipEmitter.y(), shipEmitter.z(), shipEmitter.emission(), shipEffective,
                    shipEmitter.sourceKind(), shipEmitter.sourceValue()));
        }

        int minChunkX = ((int) Math.floor(minX)) >> 4;
        int maxChunkX = ((int) Math.floor(maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(maxZ)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long chunkKey = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
                LightGameState.EmitterScan scan = chunkCache.get(chunkKey);
                if (scan == null || now - scan.time >= SHIP_EMITTER_RESCAN_INTERVAL) {
                    Map<BlockPos, Integer> found = scanChunkForWorldEmitters(level, chunkX, chunkZ, ownPlacedLights, lightBlocksAreLamps);
                    if (found != null) {
                        scan = new LightGameState.EmitterScan(now, found);
                        chunkCache.put(chunkKey, scan);
                    }
                }
                if (scan == null) continue;

                for (Map.Entry<BlockPos, Integer> entry : scan.emitters.entrySet()) {
                    BlockPos pos = entry.getKey();
                    int emission = entry.getValue();

                    // Distance from the lamp to the ship's world box decides both
                    // the cutoff and the ranking; the exact per-anchor dimming
                    // happens in projectIntoShipyard.
                    double dx = Math.max(0, Math.max(worldBounds[0] - (pos.getX() + 0.5), (pos.getX() + 0.5) - worldBounds[3]));
                    double dy = Math.max(0, Math.max(worldBounds[1] - (pos.getY() + 0.5), (pos.getY() + 0.5) - worldBounds[4]));
                    double dz = Math.max(0, Math.max(worldBounds[2] - (pos.getZ() + 0.5), (pos.getZ() + 0.5) - worldBounds[5]));
                    double dist = dx + dy + dz;
                    int effective = emission - (int) Math.floor(Math.max(0, dist - 1.0));
                    if (effective <= 0) continue;

                    nearby.add(new NearbyEmitter(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, emission, effective,
                            LightSources.WORLD_LAMP, pos.asLong()));
                }
            }
        }

        if (nearby.isEmpty()) return;
        nearby.sort((a, b) -> b.effective - a.effective);

        int count = Math.min(nearby.size(), MAX_WORLD_EMITTERS_PER_SHIP);
        for (int i = 0; i < count; i++) {
            NearbyEmitter emitter = nearby.get(i);
            projectIntoShipyard(level, ship, new Vec3(emitter.x, emitter.y, emitter.z), emitter.emission, false, exactClusters,
                    emitter.sourceKind, emitter.sourceValue);
        }
    }

    /**
     * At most {@code cap} of a ship's lamps (max_lamps_per_ship): the brightest first, then the nearest a player
     * (their world positions now); the rest are left out until the next scan. Every projected lamp is a light block
     * moving with the ship, so a ship carrying very many lamps cost clients their frames (2026-10-17).
     */
    private static Map<BlockPos, Integer> capShipLamps(ServerLevel level, ShipRef ship, Map<BlockPos, Integer> emitters, int cap) {
        if (cap <= 0 || emitters.size() <= cap) return emitters;
        List<? extends Player> players = level.players();
        record Ranked(BlockPos pos, int emission, double playerDistSq) {}
        List<Ranked> ranked = new ArrayList<>(emitters.size());
        for (Map.Entry<BlockPos, Integer> entry : emitters.entrySet()) {
            Vec3 world = ship.toWorld(Vec3.atCenterOf(entry.getKey()));
            double nearest = Double.MAX_VALUE;
            if (world != null) {
                for (Player player : players) nearest = Math.min(nearest, player.distanceToSqr(world));
            }
            ranked.add(new Ranked(entry.getKey(), entry.getValue(), nearest));
        }
        ranked.sort((a, b) -> a.emission != b.emission ? Integer.compare(b.emission, a.emission) : Double.compare(a.playerDistSq, b.playerDistSq));
        Map<BlockPos, Integer> kept = new LinkedHashMap<>();
        for (int i = 0; i < cap; i++) kept.put(ranked.get(i).pos, ranked.get(i).emission);
        SHIP_LAMP_CAP_STATS[0]++;
        SHIP_LAMP_CAP_STATS[1] += emitters.size() - cap;
        return kept;
    }

    /**
     * Every light-emitting block on a ship, in shipyard coordinates, mapped to its
     * light emission. Used to project the ship's lights into the world as dynamic
     * light sources; nothing is ever placed inside the shipyard. Camafy walks the
     * ship's block bounds section by section, skipping all-air sections and ones
     * whose palette holds no luminous state, and reads only loaded chunks.
     *
     * Light blocks Lively Lighting itself placed in the shipyard (held-light
     * projections) must never register as ship lamps — they'd echo the held
     * light back into the world. The placed-lights records are the exact
     * "placed by the mod vs. placed by a player" distinction: a mod block is
     * recorded for as long as it exists, while player-placed light blocks
     * (e.g. wildcard emitters) are absent and still count as real emitters.
     */
    private static Map<BlockPos, Integer> scanShipForEmitters(ShipRef ship, Set<BlockPos> ownPlacedLights, boolean lightBlocksAreLamps) {
        Map<BlockPos, Integer> emitters = new HashMap<>();
        ship.forEachBlock(LUMINOUS, (pos, state) -> {
            // A light block is a lamp only if the config says so, and never one the mod placed.
            // The records are the mod's memory of its own blocks, and a crash can lose them while
            // the blocks stay: such an orphan on a ship must not become a lamp that projects light
            // into the world for good, which no purge of the records can reach.
            if (state.is(net.minecraft.world.level.block.Blocks.LIGHT) && (!lightBlocksAreLamps || ownPlacedLights.contains(pos))) return;
            emitters.put(pos.immutable(), state.getLightEmission());
        });
        return emitters;
    }

    /**
     * All genuine light-emitting blocks of one world chunk (full height — the
     * result is cached and shared by ships at any altitude), excluding blocks the
     * mod placed itself (their records are the player-placed vs. mod-placed
     * distinction). Returns null when the chunk isn't loaded, so nothing gets
     * cached for it.
     */
    private static Map<BlockPos, Integer> scanChunkForWorldEmitters(ServerLevel level, int chunkX, int chunkZ, Set<BlockPos> ownPlacedLights, boolean lightBlocksAreLamps) {
        // loaded right now, not on its way in (getChunk(x, z, false) waits for one that is loading)
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) return null;

        Map<BlockPos, Integer> found = null;
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            net.minecraft.world.level.chunk.LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionMinY = level.getSectionYFromSectionIndex(sectionIndex) << 4;
            if (!section.maybeHas(LUMINOUS)) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        net.minecraft.world.level.block.state.BlockState state = section.getBlockState(x, y, z);
                        int emission = state.getLightEmission();
                        if (emission <= 0) continue;

                        BlockPos pos = new BlockPos(baseX + x, sectionMinY + y, baseZ + z);
                        if (state.is(net.minecraft.world.level.block.Blocks.LIGHT) && (!lightBlocksAreLamps || ownPlacedLights.contains(pos))) continue;

                        if (found == null) found = new HashMap<>();
                        found.put(pos, emission);
                    }
                }
            }
        }
        return found == null ? Collections.emptyMap() : found;
    }

    /**
     * Whether a shipyard position touches the ship's actual structure: at least
     * one face neighbor is a real block. Light blocks don't count, so a projected
     * anchor can never justify the next anchor and creep outward off the hull —
     * shipyard placement stays bounded to one block beyond genuine ship blocks.
     */
    private static boolean isAttachedToShipStructure(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            net.minecraft.world.level.block.state.BlockState state = LightPropagator.loadedState(level, neighbor);
            if (state == null) continue;
            if (!state.isAir() && !state.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ultra smoothing: sub-block light interpolation. The source is treated as a
     * continuous point light at the entity's eye; every block within manhattan
     * distance 2 of the eye block gets level ceil(L - continuousManhattan(eye,
     * blockCenter)). Standing centered on a block yields exactly one full-level
     * block (neighbors are redundant and trimmed); straddling a boundary puts the
     * full level on both straddled blocks, and every surrounding cell crosses its
     * ±1 boundary exactly when the entity's position dictates — the smoothest
     * field integer light can express, including vertical motion. ceil (rather
     * than round) guarantees crossfades never dim: a block only drops a level in
     * the same tick its successor gains one.
     *
     * Candidates already receiving their exact level from a brighter kept block
     * via open-air propagation are trimmed — the vanilla engine produces the same
     * value there, so placing them would only add block-update churn.
     */
    private static void addUltraSources(ServerLevel level, Entity entity, int lightLevel, Map<BlockPos, LightCluster> ultraClusters, long source) {
        double eyeX = entity.getX();
        double eyeY = entity.getEyeY();
        double eyeZ = entity.getZ();
        BlockPos basePos = BlockPos.containing(eyeX, eyeY, eyeZ);
        boolean isPlayer = entity instanceof Player;

        record Candidate(BlockPos pos, int level) {}
        List<Candidate> candidates = new ArrayList<>();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2) continue;

                    BlockPos pos = basePos.offset(dx, dy, dz);
                    double dist = Math.abs(eyeX - (pos.getX() + 0.5))
                                + Math.abs(eyeY - (pos.getY() + 0.5))
                                + Math.abs(eyeZ - (pos.getZ() + 0.5));
                    int posLevel = Math.min(15, (int) Math.ceil(lightLevel - dist));
                    if (posLevel <= 0) continue;
                    if (!LightPropagator.isValidLightSpot(level, pos)) continue;
                    // Standing against a wall must not seed light on its far side.
                    if (!LightPropagator.hasLightPath(level,
                            new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                            new Vec3(eyeX, eyeY, eyeZ))) continue;

                    candidates.add(new Candidate(pos, posLevel));
                }
            }
        }

        // Brightest first so the trim check only ever tests against kept blocks
        // that could actually cover the current one.
        candidates.sort((a, b) -> b.level - a.level);
        List<Candidate> kept = new ArrayList<>();

        outer:
        for (Candidate candidate : candidates) {
            for (Candidate keeper : kept) {
                int manhattan = Math.abs(keeper.pos.getX() - candidate.pos.getX())
                              + Math.abs(keeper.pos.getY() - candidate.pos.getY())
                              + Math.abs(keeper.pos.getZ() - candidate.pos.getZ());
                if (keeper.level - manhattan >= candidate.level) continue outer;
            }
            kept.add(candidate);

            // Keyed by exact block position; carry equals the level so applyChanges
            // grants it immediately — the interpolation replaces fade-in/out here.
            LightCluster cluster = ultraClusters.computeIfAbsent(candidate.pos, k -> new LightCluster());
            cluster.add(candidate.pos.getX() + 0.5, candidate.pos.getY() + 0.5, candidate.pos.getZ() + 0.5,
                        candidate.level, isPlayer, candidate.level);
            cluster.source(LightSources.ENTITY, source);
        }
    }

    private static boolean shouldCheckEntity(Entity entity, LivelyConfig config) {
        // Check forced entities first (handled in processEntity, but we need to return true here to reach it)
        if (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Creeper || entity instanceof PrimedTnt || entity instanceof ItemFrame) {
             // Always allow checking these, processEntity will check the saved data
        } else {
             // For other entities, check if they are forced
             if (entity.level() instanceof ServerLevel serverLevel) {
                 LivelyLightingData data = LivelyLightingData.get(serverLevel);
                 if (data.getForcedLevel(entity.getUUID()) != null) return true;
             }
        }

        if (config.enable_entity_lights) {
            return true;
        }
        return entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Creeper || entity instanceof PrimedTnt || entity instanceof ItemFrame
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_15)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_12)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_9)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_6)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_3);
    }
}
