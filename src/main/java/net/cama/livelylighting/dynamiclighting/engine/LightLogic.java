package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.compat.valkyrienskies.IVSCompat;
import net.cama.livelylighting.data.LivelyLightingData;
import net.cama.livelylighting.data.ModTags;
import net.cama.livelylighting.dynamiclighting.sound.SoundData;
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

    private static final java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> LUMINOUS =
            state -> state.getLightEmission() > 0;

    public static void runLightLogic(ServerLevel level, LivelyConfig config, LightGameState gameState, IVSCompat vsCompat, boolean useVs) {
        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, Integer> worldCurrentLights = gameState.levelLights.computeIfAbsent(dimension, k -> new HashMap<>());
        Set<BlockPos> worldPlayerLights = gameState.playerLights.computeIfAbsent(dimension, k -> new HashSet<>());
        Map<BlockPos, Integer> worldDesiredLights = new HashMap<>();
        Set<BlockPos> worldDesiredPlayerLights = new HashSet<>();
        Map<BlockPos, Integer> worldDesiredCarry = new HashMap<>();

        boolean smoothing = config.smoothing;
        boolean smoothingAllEntities = config.smoothing_all_entities;
        boolean clusterGrowing = config.experimental.cluster_growing;
        double mergeDistance = config.experimental.cluster_merge_distance;
        int maxRadius = config.experimental.max_influence_radius;
        int decayRate = Math.max(1, config.trail_decay_rate);
        int fadeInRate = Math.max(1, config.fade_in_rate);
        int maxSources = config.max_light_sources;

        Map<BlockPos, LightCluster> worldClusters = new HashMap<>();
        // Exact-position sources, keyed by their block position: ultra-smoothing
        // interpolation blocks and shipyard projections. These are pre-validated
        // single-block lights and must never be grid-merged or position-averaged
        // like regular clusters.
        Map<BlockPos, LightCluster> worldUltraClusters = new HashMap<>();
        int sourceCount = 0;

        // The radius is capped at 192: Valkyrien Skies mixes into
        // Level.getEntities and silently returns an EMPTY list for "too big"
        // boxes — VS 2.4.9 rejects volume > 1e8 (~464 blocks cubed; verified in
        // BugFixUtil.isCollisionBoxTooBig bytecode), older builds reject > 1000
        // per axis. That killed all entity lights at higher view distances.
        // 192 (~5.7e7 volume) clears both limits with margin, and light from an
        // entity beyond 192 blocks is imperceptible anyway.
        int viewDistance = Math.min(level.getServer().getPlayerList().getViewDistance() * 16, 192);

        Set<Integer> processedIds = new HashSet<>();

        for (Player player : level.players()) {
            if (sourceCount >= maxSources) break;
            if (player.isSpectator()) continue;

            processEntity(player, level, worldClusters, worldUltraClusters, processedIds, config, useVs, gameState, vsCompat);
            sourceCount++;
        }

        if (sourceCount < maxSources) {
            // Query the entity section index around each player instead of scanning
            // every loaded entity against every player. processedIds dedupes
            // entities that fall inside multiple players' ranges.
            outer:
            for (Player player : level.players()) {
                AABB searchBox = player.getBoundingBox().inflate(viewDistance);
                for (Entity entity : level.getEntities(player, searchBox)) {
                    if (sourceCount >= maxSources) break outer;
                    if (entity instanceof Player) continue;
                    if (!shouldCheckEntity(entity, config)) continue;

                    if (processEntity(entity, level, worldClusters, worldUltraClusters, processedIds, config, useVs, gameState, vsCompat)) {
                        sourceCount++;
                    }
                }
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
            Set<Long> scannedShips = new HashSet<>();
            for (Player player : level.players()) {
                AABB searchBox = player.getBoundingBox().inflate(viewDistance);
                for (Object ship : vsCompat.getShipsIntersecting(level, searchBox)) {
                    long shipId = vsCompat.getShipId(ship);
                    if (!scannedShips.add(shipId)) continue;

                    Map<BlockPos, Integer> emitters = gameState.shipEmitterCache.get(shipId);
                    long lastScan = gameState.shipEmitterScanTime.getOrDefault(shipId, Long.MIN_VALUE);
                    if (emitters == null || level.getGameTime() - lastScan >= SHIP_EMITTER_RESCAN_INTERVAL) {
                        emitters = vsCompat.getShipLightEmitters(level, ship);
                        gameState.shipEmitterCache.put(shipId, emitters);
                        gameState.shipEmitterScanTime.put(shipId, level.getGameTime());
                    }

                    for (Map.Entry<BlockPos, Integer> emitter : emitters.entrySet()) {
                        if (sourceCount >= maxSources) break;
                        sourceCount++;

                        BlockPos shipPos = emitter.getKey();
                        if (ownPlacedLights.contains(shipPos)) continue;
                        int emission = emitter.getValue();
                        double[] worldPos = vsCompat.transformShipToWorld(ship,
                                shipPos.getX() + 0.5, shipPos.getY() + 0.5, shipPos.getZ() + 0.5);

                        int gridX = (int) Math.floor(worldPos[0] / mergeDistance);
                        int gridY = (int) Math.floor(worldPos[1] / mergeDistance);
                        int gridZ = (int) Math.floor(worldPos[2] / mergeDistance);
                        BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);

                        // Carry equals the emission so an already-lit lamp never
                        // re-runs the ignition fade as the ship moves.
                        worldClusters.computeIfAbsent(gridPos, k -> new LightCluster())
                                     .add(worldPos[0], worldPos[1], worldPos[2], emission, false, emission);
                    }

                    // World → ship: genuine world light blocks near the ship light
                    // its hull as it sails past. World lamps are static, so their
                    // positions are cached per chunk — shared across ships and
                    // unaffected by ship movement — and only re-projected through
                    // the ship's current transform each tick.
                    projectWorldEmittersOntoShip(level, ship, gameState, ownPlacedLights, worldUltraClusters, vsCompat);
                }
            }
        }
        
        // Cleanup logic now scoped to the current dimension
        if (level.getGameTime() % 100 == 0) {
            Map<Integer, Map<String, LightData>> dimensionState = gameState.entityLitState.get(dimension);
            if (dimensionState != null) {
                dimensionState.keySet().removeIf(id -> level.getEntity(id) == null);
            }

            Map<Integer, Map<String, Integer>> dimEstablished = gameState.sourceEstablished.get(dimension);
            if (dimEstablished != null) {
                dimEstablished.keySet().removeIf(id -> level.getEntity(id) == null);
            }
            
            if (useVs) {
                Set<Long> loadedShipIds = vsCompat.getLoadedShipIds(level);
                gameState.shipEmitterCache.keySet().removeIf(id -> !loadedShipIds.contains(id));
                gameState.shipEmitterScanTime.keySet().removeIf(id -> !loadedShipIds.contains(id));

                // Drop world-emitter chunk scans no ship has refreshed recently,
                // so the cache doesn't accumulate along a ship's whole voyage.
                Map<Long, LightGameState.EmitterScan> dimEmitterChunks = gameState.worldEmitterCache.get(dimension);
                if (dimEmitterChunks != null) {
                    long now = level.getGameTime();
                    dimEmitterChunks.values().removeIf(scan -> now - scan.time > SHIP_EMITTER_RESCAN_INTERVAL * 5);
                }
            }
            
            // Cleanup per-entity caches, scoped to this dimension only
            Map<Integer, Long> dimSoundTimes = gameState.lastSoundTime.get(dimension);
            if (dimSoundTimes != null) {
                dimSoundTimes.keySet().removeIf(id -> level.getEntity(id) == null);
            }
            Map<Integer, Map<String, BlockPos>> dimSourcePos = gameState.lastSourcePos.get(dimension);
            if (dimSourcePos != null) {
                dimSourcePos.keySet().removeIf(id -> level.getEntity(id) == null);
            }
            Map<Integer, Vec3> dimLastPos = gameState.lastEntityPos.get(dimension);
            if (dimLastPos != null) {
                dimLastPos.keySet().removeIf(id -> level.getEntity(id) == null);
            }
            Map<Integer, Vec3> dimSmoothedVel = gameState.smoothedVelocity.get(dimension);
            if (dimSmoothedVel != null) {
                dimSmoothedVel.keySet().removeIf(id -> level.getEntity(id) == null);
            }
        }

        // 2. Calculate Desired Light Field
        LightPropagator.calculateLightField(level, worldClusters, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, smoothing, clusterGrowing, maxRadius);
        LightPropagator.calculateLightField(level, worldUltraClusters, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, smoothing, clusterGrowing, maxRadius);

        // 3. Apply Changes. Ship-projected lights live in the world like any other
        // source, so the normal fade/removal path cleans them up when the ship
        // moves away or unloads.
        LightPropagator.applyChanges(level, worldCurrentLights, worldPlayerLights, worldDesiredLights, worldDesiredPlayerLights, worldDesiredCarry, smoothing, smoothingAllEntities, decayRate, fadeInRate);
    }

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<BlockPos, LightCluster> worldUltraClusters, Set<Integer> processedIds, LivelyConfig config, boolean useVs, LightGameState gameState, IVSCompat vsCompat) {
        if (!processedIds.add(entity.getId())) return false;

        LivelyLightingData data = LivelyLightingData.get(level);
        if (data.isEntityDisabled(entity.getUUID())) return false;

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
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, lightData, "forced", gameState, vsCompat, velocity);
            return true;
        }

        // If cluster growing is enabled, we check all slots and add them as separate sources to the cluster
        if (config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            boolean foundLight = false;
            
            // Main Hand
            LightData main = LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, main, "mainhand", gameState, vsCompat, velocity);
            if (main.level > 0) foundLight = true;

            // Off Hand
            LightData off = LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, off, "offhand", gameState, vsCompat, velocity);
            if (off.level > 0) foundLight = true;

            // Armor
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                    LightData armor = LightCalculator.getItemLightLevel(living.getItemBySlot(slot), entity, level, config);
                    processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, armor, slot.getName(), gameState, vsCompat, velocity);
                    if (armor.level > 0) foundLight = true;
                }
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
                processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, blockLight, "carried_block", gameState, vsCompat, velocity);
                if (blockLight.level > 0) foundLight = true;
            }

            // Check other sources (fire, glow effect, etc)
            LightData entityLight = LightCalculator.getEntityLightLevel(entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, entityLight, "body", gameState, vsCompat, velocity);
            if (entityLight.level > 0) foundLight = true;
            
            return foundLight;
        } else {
            // Standard behavior: get best light level and process once
            LightData lightData = LightCalculator.getBestLightLevel(entity, level, config);
            LightData lastData = getEntityLitState(level, entity.getId(), "combined", gameState);
            
            if (lightData.level > 0 || (lastData != null && lastData.level > 0)) {
                processLightData(entity, level, worldClusters, worldUltraClusters, config, useVs, lightData, "combined", gameState, vsCompat, velocity);
                return lightData.level > 0;
            }
            return false;
        }
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
    
    private static void processLightData(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<BlockPos, LightCluster> worldUltraClusters, LivelyConfig config, boolean useVs, LightData lightData, String sourceId, LightGameState gameState, IVSCompat vsCompat, Vec3 velocity) {
        LivelyLightingData worldData = LivelyLightingData.get(level);
        int lightLevel = lightData.level;
        List<ParticleType<?>> particles = lightData.particles;
        List<SoundData> sounds = lightData.sounds;
        List<SoundData> extinguishSounds = lightData.extinguishSounds;
        
        LightData lastData = getEntityLitState(level, entity.getId(), sourceId, gameState);
        boolean wasLit = lastData != null && lastData.level > 0;
        boolean isLit = lightLevel > 0;
        
        boolean isNew = isLit && !wasLit;
        
        Vec3 handPos = entity.getEyePosition().add(entity.getViewVector(1.0f).scale(0.5)).add(entity.getViewVector(1.0f).cross(new Vec3(0, 1, 0)).scale(0.3));
        
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
        } else if (isNew) {
            if (!wasLit) { // Only play sounds/particles if we weren't lit before
                if (!(entity instanceof Creeper) && !(entity instanceof PrimedTnt)) {
                     Map<Integer, Long> soundTimes = gameState.lastSoundTime.computeIfAbsent(level.dimension(), k -> new HashMap<>());
                     long currentTime = level.getGameTime();
                     long lastTime = soundTimes.getOrDefault(entity.getId(), 0L);

                     if (currentTime - lastTime >= 10) { // 10 tick cooldown (0.5s)
                         soundTimes.put(entity.getId(), currentTime);

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
            // Valkyrien Skies. Two mirrored cases:
            // - An entity that lives at shipyard coordinates (item frames and other
            //   hanging entities mounted on ship blocks): the normal world path
            //   below already lights the ship around it, so project its light OUT
            //   into the static world at the ship-transformed position.
            // - An ordinary world entity (players and mobs on or near ships have
            //   true world coordinates): anchor a light block IN the shipyard so
            //   the ship's own blocks light up; see addShipyardProjection.
            if (useVs) {
                Object managingShip = vsCompat.getShipManagingPos(level, entity.blockPosition());
                if (managingShip != null) {
                    double[] worldEye = vsCompat.transformShipToWorld(managingShip,
                            entity.getX(), entity.getEyeY(), entity.getZ());
                    addWorldProjection(level, new Vec3(worldEye[0], worldEye[1], worldEye[2]),
                            lightLevel, entity instanceof Player, worldUltraClusters);
                } else {
                    addShipyardProjection(level, entity, lightLevel, worldUltraClusters, vsCompat);
                }
            }

            if (smoothingActive && config.experimental.ultra_smoothing) {
                // Ultra smoothing: no anchor block at all — the entity is treated as
                // a continuous point light and every nearby block gets its exact
                // interpolated level. Predictive lead, anchor caching and the
                // distance penalty are all bypassed; the interpolation IS the anchor.
                addUltraSources(level, entity, lightLevel, worldUltraClusters);
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
                    double mergeDistance = config.experimental.cluster_merge_distance;
                    int gridX = (int) Math.floor(lightX / mergeDistance);
                    int gridY = (int) Math.floor(lightY / mergeDistance);
                    int gridZ = (int) Math.floor(lightZ / mergeDistance);
                    BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);

                    worldClusters.computeIfAbsent(gridPos, k -> new LightCluster()).add(lightX, lightY, lightZ, lightLevel, entity instanceof Player, carryLevel);
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
    private static void addShipyardProjection(ServerLevel level, Entity entity, int lightLevel, Map<BlockPos, LightCluster> exactClusters, IVSCompat vsCompat) {
        AABB reach = entity.getBoundingBox().inflate(lightLevel);
        List<Object> ships = vsCompat.getShipsIntersecting(level, reach);
        if (ships.isEmpty()) return;

        Vec3 eye = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        boolean isPlayer = entity instanceof Player;

        for (Object ship : ships) {
            projectIntoShipyard(level, ship, eye, lightLevel, isPlayer, exactClusters, vsCompat);
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
    private static void projectIntoShipyard(ServerLevel level, Object ship, Vec3 worldSource, int lightLevel, boolean isPlayer, Map<BlockPos, LightCluster> exactClusters, IVSCompat vsCompat) {
        int[] bounds = vsCompat.getShipBlockBounds(ship);
        if (bounds == null) return;

        double[] shipSource = vsCompat.transformWorldToShip(ship, worldSource.x, worldSource.y, worldSource.z);
        Vec3 shipSourceVec = new Vec3(shipSource[0], shipSource[1], shipSource[2]);

        // Search around the closest in-bounds point to the transformed source —
        // for a source on deck that's roughly its own position, for one away
        // from the ship it's the nearest hull surface.
        BlockPos searchCenter = new BlockPos(
                (int) Math.floor(Math.max(bounds[0], Math.min(bounds[3], shipSource[0]))),
                (int) Math.floor(Math.max(bounds[1], Math.min(bounds[4], shipSource[1]))),
                (int) Math.floor(Math.max(bounds[2], Math.min(bounds[5], shipSource[2]))));

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = searchCenter.offset(dx, dy, dz);
                    if (!LightPropagator.isValidLightSpot(level, pos)) continue;
                    if (!isAttachedToShipStructure(level, pos)) continue;
                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(
                pos -> pos.distToCenterSqr(shipSource[0], shipSource[1], shipSource[2])));

        for (BlockPos pos : candidates) {
            if (!LightPropagator.hasLightPath(level,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), shipSourceVec)) {
                continue;
            }

            double dist = Math.sqrt(pos.distToCenterSqr(shipSource[0], shipSource[1], shipSource[2]));
            int dimmed = lightLevel - (int) Math.floor(Math.max(0, dist - 1.0));
            if (dimmed > 0) {
                exactClusters.computeIfAbsent(pos, k -> new LightCluster())
                             .add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dimmed, isPlayer, dimmed);
            }
            break;
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
    private static void addWorldProjection(ServerLevel level, Vec3 worldEye, int lightLevel, boolean isPlayer, Map<BlockPos, LightCluster> exactClusters) {
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
                exactClusters.computeIfAbsent(pos, k -> new LightCluster())
                             .add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, dimmed, isPlayer, dimmed);
            }
            break;
        }
    }

    /**
     * Projects the genuine world light blocks within reach of a ship into its
     * shipyard, so a lamp post lights a passing ship's hull. Emitters come from a
     * per-chunk cache: world lamps are static, so the cache needs no invalidation
     * on ship movement and is shared by every ship. Each chunk section is
     * pre-filtered with a palette-level check (maybeHas), so terrain without any
     * luminous block state is skipped without reading a single block.
     */
    private static void projectWorldEmittersOntoShip(ServerLevel level, Object ship, LightGameState gameState, Set<BlockPos> ownPlacedLights, Map<BlockPos, LightCluster> exactClusters, IVSCompat vsCompat) {
        double[] worldBounds = vsCompat.getShipWorldBounds(ship);
        if (worldBounds == null) return;

        double minX = worldBounds[0] - WORLD_EMITTER_LIGHT_REACH;
        double minZ = worldBounds[2] - WORLD_EMITTER_LIGHT_REACH;
        double maxX = worldBounds[3] + WORLD_EMITTER_LIGHT_REACH;
        double maxZ = worldBounds[5] + WORLD_EMITTER_LIGHT_REACH;

        Map<Long, LightGameState.EmitterScan> chunkCache = gameState.worldEmitterCache
                .computeIfAbsent(level.dimension(), k -> new HashMap<>());
        long now = level.getGameTime();

        record NearbyEmitter(BlockPos pos, int emission, int effective) {}
        List<NearbyEmitter> nearby = new ArrayList<>();

        int minChunkX = ((int) Math.floor(minX)) >> 4;
        int maxChunkX = ((int) Math.floor(maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(maxZ)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long chunkKey = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
                LightGameState.EmitterScan scan = chunkCache.get(chunkKey);
                if (scan == null || now - scan.time >= SHIP_EMITTER_RESCAN_INTERVAL) {
                    Map<BlockPos, Integer> found = scanChunkForWorldEmitters(level, chunkX, chunkZ, ownPlacedLights);
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

                    nearby.add(new NearbyEmitter(pos, emission, effective));
                }
            }
        }

        if (nearby.isEmpty()) return;
        nearby.sort((a, b) -> b.effective - a.effective);

        int count = Math.min(nearby.size(), MAX_WORLD_EMITTERS_PER_SHIP);
        for (int i = 0; i < count; i++) {
            NearbyEmitter emitter = nearby.get(i);
            Vec3 source = new Vec3(emitter.pos.getX() + 0.5, emitter.pos.getY() + 0.5, emitter.pos.getZ() + 0.5);
            projectIntoShipyard(level, ship, source, emitter.emission, false, exactClusters, vsCompat);
        }
    }

    /**
     * All genuine light-emitting blocks of one world chunk (full height — the
     * result is cached and shared by ships at any altitude), excluding blocks the
     * mod placed itself (their records are the player-placed vs. mod-placed
     * distinction). Returns null when the chunk isn't loaded, so nothing gets
     * cached for it.
     */
    private static Map<BlockPos, Integer> scanChunkForWorldEmitters(ServerLevel level, int chunkX, int chunkZ, Set<BlockPos> ownPlacedLights) {
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
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
                        if (state.is(net.minecraft.world.level.block.Blocks.LIGHT) && ownPlacedLights.contains(pos)) continue;

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
            if (!level.isLoaded(neighbor)) continue;
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(neighbor);
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
    private static void addUltraSources(ServerLevel level, Entity entity, int lightLevel, Map<BlockPos, LightCluster> ultraClusters) {
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
            ultraClusters.computeIfAbsent(candidate.pos, k -> new LightCluster())
                         .add(candidate.pos.getX() + 0.5, candidate.pos.getY() + 0.5, candidate.pos.getZ() + 0.5,
                              candidate.level, isPlayer, candidate.level);
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
