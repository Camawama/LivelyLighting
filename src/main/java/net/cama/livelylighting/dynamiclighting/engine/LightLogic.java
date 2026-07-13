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
    // waits at most this long to be noticed.
    private static final int SHIP_EMITTER_RESCAN_INTERVAL = 60;

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
        // Ultra smoothing sources, keyed by exact block position: each entry is one
        // block of the sub-block interpolation and must never be grid-merged or
        // position-averaged like regular clusters.
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

            processEntity(player, level, worldClusters, worldUltraClusters, processedIds, config, gameState);
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

                    if (processEntity(entity, level, worldClusters, worldUltraClusters, processedIds, config, gameState)) {
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

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<BlockPos, LightCluster> worldUltraClusters, Set<Integer> processedIds, LivelyConfig config, LightGameState gameState) {
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
            processLightData(entity, level, worldClusters, worldUltraClusters, config, lightData, "forced", gameState, velocity);
            return true;
        }

        // If cluster growing is enabled, we check all slots and add them as separate sources to the cluster
        if (config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            boolean foundLight = false;
            
            // Main Hand
            LightData main = LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, main, "mainhand", gameState, velocity);
            if (main.level > 0) foundLight = true;

            // Off Hand
            LightData off = LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, off, "offhand", gameState, velocity);
            if (off.level > 0) foundLight = true;

            // Armor
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                    LightData armor = LightCalculator.getItemLightLevel(living.getItemBySlot(slot), entity, level, config);
                    processLightData(entity, level, worldClusters, worldUltraClusters, config, armor, slot.getName(), gameState, velocity);
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
                processLightData(entity, level, worldClusters, worldUltraClusters, config, blockLight, "carried_block", gameState, velocity);
                if (blockLight.level > 0) foundLight = true;
            }

            // Check other sources (fire, glow effect, etc)
            LightData entityLight = LightCalculator.getEntityLightLevel(entity, level, config);
            processLightData(entity, level, worldClusters, worldUltraClusters, config, entityLight, "body", gameState, velocity);
            if (entityLight.level > 0) foundLight = true;
            
            return foundLight;
        } else {
            // Standard behavior: get best light level and process once
            LightData lightData = LightCalculator.getBestLightLevel(entity, level, config);
            LightData lastData = getEntityLitState(level, entity.getId(), "combined", gameState);
            
            if (lightData.level > 0 || (lastData != null && lastData.level > 0)) {
                processLightData(entity, level, worldClusters, worldUltraClusters, config, lightData, "combined", gameState, velocity);
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
    
    private static void processLightData(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<BlockPos, LightCluster> worldUltraClusters, LivelyConfig config, LightData lightData, String sourceId, LightGameState gameState, Vec3 velocity) {
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
            // Entities on Valkyrien Skies ships have true world coordinates, so
            // they take the same world paths below — light blocks are only ever
            // placed in the static world, never inside a shipyard.
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
