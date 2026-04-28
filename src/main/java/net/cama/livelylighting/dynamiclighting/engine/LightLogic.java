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

    public static void runLightLogic(ServerLevel level, LivelyConfig config, LightGameState gameState, IVSCompat vsCompat, boolean useVs, Set<Integer> dirtyPlayers) {
        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, Integer> worldCurrentLights = gameState.levelLights.computeIfAbsent(dimension, k -> new HashMap<>());
        Set<BlockPos> worldPlayerLights = gameState.playerLights.computeIfAbsent(dimension, k -> new HashSet<>());
        Map<BlockPos, Integer> worldDesiredLights = new HashMap<>();
        Set<BlockPos> worldDesiredPlayerLights = new HashSet<>();
        
        Map<Long, Map<BlockPos, Integer>> shipDesiredLights = new HashMap<>();
        Map<Long, Set<BlockPos>> shipDesiredPlayerLights = new HashMap<>();

        boolean smoothing = config.experimental.smoothing;
        boolean smoothingAllEntities = config.experimental.smoothing_all_entities;
        boolean clusterGrowing = config.experimental.cluster_growing;
        double mergeDistance = config.experimental.cluster_merge_distance;
        int maxRadius = config.experimental.max_influence_radius;
        int decayRate = config.experimental.trail_decay_rate;
        int fadeInRate = config.experimental.fade_in_rate;
        int maxSources = config.max_light_sources;

        Map<BlockPos, LightCluster> worldClusters = new HashMap<>();
        Map<Long, Map<BlockPos, LightCluster>> shipClusters = new HashMap<>();
        int sourceCount = 0;

        Set<Integer> processedIds = new HashSet<>();

        for (Player player : level.players()) {
            if (sourceCount >= maxSources) break;
            if (player.isSpectator()) continue;
            
            processEntity(player, level, worldClusters, shipClusters, processedIds, config, useVs, gameState, vsCompat);
            sourceCount++;
        }
        
        if (sourceCount < maxSources) {
            int viewDistance = level.getServer().getPlayerList().getViewDistance() * 16;
            double viewDistanceSq = viewDistance * viewDistance;

            for (Entity entity : level.getAllEntities()) {
                if (sourceCount >= maxSources) break;
                if (entity instanceof Player) continue;

                // Optimization: Skip entities not near any player
                boolean isNearPlayer = false;
                for (Player player : level.players()) {
                    if (player.distanceToSqr(entity) < viewDistanceSq) {
                        isNearPlayer = true;
                        break;
                    }
                }
                if (!isNearPlayer) continue;

                if (!shouldCheckEntity(entity, config)) continue;

                if (processEntity(entity, level, worldClusters, shipClusters, processedIds, config, useVs, gameState, vsCompat)) {
                    sourceCount++;
                }
            }
        }
        
        // Cleanup logic now scoped to the current dimension
        if (level.getGameTime() % 100 == 0) {
            Map<Integer, Map<String, LightData>> dimensionState = gameState.entityLitState.get(dimension);
            if (dimensionState != null) {
                dimensionState.keySet().removeIf(id -> level.getEntity(id) == null);
            }
            
            if (useVs) {
                Set<Long> loadedShipIds = vsCompat.getLoadedShipIds(level);
                gameState.shipLights.keySet().removeIf(id -> !loadedShipIds.contains(id));
                gameState.shipPlayerLights.keySet().removeIf(id -> !loadedShipIds.contains(id));
            }
            
            // Cleanup sound cooldowns
            gameState.lastSoundTime.keySet().removeIf(id -> level.getEntity(id) == null);
            gameState.lastSourcePos.keySet().removeIf(id -> level.getEntity(id) == null);
        }

        // 2. Calculate Desired Light Field
        LightPropagator.calculateLightField(level, worldClusters, worldDesiredLights, worldDesiredPlayerLights, smoothing, clusterGrowing, maxRadius);
        
        if (useVs) {
            Map<Long, Object> shipLookup = vsCompat.getShipLookup(level);

            for (Map.Entry<Long, Map<BlockPos, LightCluster>> entry : shipClusters.entrySet()) {
                Long shipId = entry.getKey();
                Map<BlockPos, LightCluster> clusters = entry.getValue();
                Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());
                Set<BlockPos> desiredPlayer = shipDesiredPlayerLights.computeIfAbsent(shipId, k -> new HashSet<>());
                LightPropagator.calculateLightField(level, clusters, desired, desiredPlayer, smoothing, clusterGrowing, maxRadius);
            }
            
            for (LightCluster lightCluster : worldClusters.values()) {
                AABB aabb = new AABB(lightCluster.x - maxRadius, lightCluster.y - maxRadius, lightCluster.z - maxRadius,
                                     lightCluster.x + maxRadius, lightCluster.y + maxRadius, lightCluster.z + maxRadius);
                for (Object ship : vsCompat.getShipsIntersecting(level, aabb)) {
                    double[] shipPos = vsCompat.transformWorldToShip(ship, lightCluster.x, lightCluster.y, lightCluster.z);

                    long shipId = vsCompat.getShipId(ship);
                    Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());
                    Set<BlockPos> desiredPlayer = shipDesiredPlayerLights.computeIfAbsent(shipId, k -> new HashSet<>());

                    LightCluster shipLightCluster = new LightCluster();
                    shipLightCluster.add(shipPos[0], shipPos[1], shipPos[2], (int) lightCluster.strength, lightCluster.isNewSource, lightCluster.particles, lightCluster.sounds, lightCluster.isPlayer);
                    shipLightCluster.maxLightLevel = lightCluster.maxLightLevel;
                    shipLightCluster.normalize();

                    LightPropagator.calculateLightField(level, Map.of(BlockPos.containing(shipPos[0], shipPos[1], shipPos[2]), shipLightCluster), desired, desiredPlayer, smoothing, clusterGrowing, maxRadius);
                }
            }
            
            for (Map.Entry<Long, Map<BlockPos, LightCluster>> entry : shipClusters.entrySet()) {
                Long shipId = entry.getKey();
                Object ship = shipLookup.get(shipId);
                if (ship == null) continue;
                
                for (LightCluster lightCluster : entry.getValue().values()) {
                    double[] worldPos = vsCompat.transformShipToWorld(ship, lightCluster.x, lightCluster.y, lightCluster.z);

                    LightCluster worldLightCluster = new LightCluster();
                    worldLightCluster.add(worldPos[0], worldPos[1], worldPos[2], (int) lightCluster.strength, lightCluster.isNewSource, lightCluster.particles, lightCluster.sounds, lightCluster.isPlayer);
                    worldLightCluster.maxLightLevel = lightCluster.maxLightLevel;
                    worldLightCluster.normalize();

                    LightPropagator.calculateLightField(level, Map.of(BlockPos.containing(worldPos[0], worldPos[1], worldPos[2]), worldLightCluster), worldDesiredLights, worldDesiredPlayerLights, smoothing, clusterGrowing, maxRadius);
                }
            }
        }

        // 3. Apply Changes
        LightPropagator.applyChanges(level, worldCurrentLights, worldPlayerLights, worldDesiredLights, worldDesiredPlayerLights, smoothing, smoothingAllEntities, decayRate, fadeInRate, worldClusters, null, vsCompat);
        
        if (useVs) {
            Map<Long, Object> shipLookup = vsCompat.getShipLookup(level);

            for (Map.Entry<Long, Map<BlockPos, Integer>> entry : shipDesiredLights.entrySet()) {
                Long shipId = entry.getKey();
                Object ship = shipLookup.get(shipId);
                if (ship == null) continue;
                
                Map<BlockPos, Integer> desired = entry.getValue();
                Set<BlockPos> desiredPlayer = shipDesiredPlayerLights.getOrDefault(shipId, new HashSet<>());
                
                Map<BlockPos, Integer> current = gameState.shipLights.computeIfAbsent(shipId, k -> new HashMap<>());
                Set<BlockPos> currentPlayer = gameState.shipPlayerLights.computeIfAbsent(shipId, k -> new HashSet<>());

                Map<BlockPos, LightCluster> relevantClusters = shipClusters.getOrDefault(shipId, new HashMap<>());

                LightPropagator.applyChanges(level, current, currentPlayer, desired, desiredPlayer, smoothing, smoothingAllEntities, decayRate, fadeInRate, relevantClusters, ship, vsCompat);
            }
            
            Iterator<Map.Entry<Long, Map<BlockPos, Integer>>> shipIt = gameState.shipLights.entrySet().iterator();
            while (shipIt.hasNext()) {
                Map.Entry<Long, Map<BlockPos, Integer>> entry = shipIt.next();
                Long shipId = entry.getKey();
                if (!shipDesiredLights.containsKey(shipId)) {
                    Map<BlockPos, Integer> current = entry.getValue();
                    Set<BlockPos> currentPlayer = gameState.shipPlayerLights.getOrDefault(shipId, new HashSet<>());
                    
                    Object ship = shipLookup.get(shipId);
                    if (ship == null) {
                        shipIt.remove();
                        gameState.shipPlayerLights.remove(shipId);
                        continue;
                    }
                    
                    if (current.isEmpty()) {
                        shipIt.remove();
                        gameState.shipPlayerLights.remove(shipId);
                    } else {
                        LightPropagator.applyChanges(level, current, currentPlayer, new HashMap<>(), new HashSet<>(), smoothing, smoothingAllEntities, decayRate, fadeInRate, new HashMap<>(), ship, vsCompat);
                        if (current.isEmpty()) {
                            shipIt.remove();
                            gameState.shipPlayerLights.remove(shipId);
                        }
                    }
                }
            }
        }
    }

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<Long, Map<BlockPos, LightCluster>> shipClusters, Set<Integer> processedIds, LivelyConfig config, boolean useVs, LightGameState gameState, IVSCompat vsCompat) {
        if (!processedIds.add(entity.getId())) return false;
        
        LivelyLightingData data = LivelyLightingData.get(level);
        if (data.isEntityDisabled(entity.getUUID())) return false;

        // Check for forced light level
        Integer forcedLevel = data.getForcedLevel(entity.getUUID());
        if (forcedLevel != null) {
            LightData lightData = new LightData(forcedLevel, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            processLightData(entity, level, worldClusters, shipClusters, config, useVs, lightData, "forced", gameState, vsCompat);
            return true;
        }

        // If cluster growing is enabled, we check all slots and add them as separate sources to the cluster
        if (config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            boolean foundLight = false;
            
            // Main Hand
            LightData main = LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, shipClusters, config, useVs, main, "mainhand", gameState, vsCompat);
            if (main.level > 0) foundLight = true;
            
            // Off Hand
            LightData off = LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config);
            processLightData(entity, level, worldClusters, shipClusters, config, useVs, off, "offhand", gameState, vsCompat);
            if (off.level > 0) foundLight = true;
            
            // Armor
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                    LightData armor = LightCalculator.getItemLightLevel(living.getItemBySlot(slot), entity, level, config);
                    processLightData(entity, level, worldClusters, shipClusters, config, useVs, armor, slot.getName(), gameState, vsCompat);
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
                processLightData(entity, level, worldClusters, shipClusters, config, useVs, blockLight, "carried_block", gameState, vsCompat);
                if (blockLight.level > 0) foundLight = true;
            }
            
            // Check other sources (fire, glow effect, etc)
            LightData entityLight = LightCalculator.getEntityLightLevel(entity, level, config);
            processLightData(entity, level, worldClusters, shipClusters, config, useVs, entityLight, "body", gameState, vsCompat);
            if (entityLight.level > 0) foundLight = true;
            
            return foundLight;
        } else {
            // Standard behavior: get best light level and process once
            LightData lightData = LightCalculator.getBestLightLevel(entity, level, config);
            LightData lastData = getEntityLitState(level, entity.getId(), "combined", gameState);
            
            if (lightData.level > 0 || (lastData != null && lastData.level > 0)) {
                processLightData(entity, level, worldClusters, shipClusters, config, useVs, lightData, "combined", gameState, vsCompat);
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
    
    private static void processLightData(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<Long, Map<BlockPos, LightCluster>> shipClusters, LivelyConfig config, boolean useVs, LightData lightData, String sourceId, LightGameState gameState, IVSCompat vsCompat) {
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
            if (config.enable_sounds && !LightManager.arePlayerSoundsDisabled(entity.getUUID())) {
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
                     long currentTime = level.getGameTime();
                     long lastTime = gameState.lastSoundTime.getOrDefault(entity.getId(), 0L);
                     
                     if (currentTime - lastTime > 20) { // 10 ticks cooldown (0.5s)
                         gameState.lastSoundTime.put(entity.getId(), currentTime);
                         
                         if (config.enable_sounds && !LightManager.arePlayerSoundsDisabled(entity.getUUID())) {
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

        if (lightLevel > 0) {
            Object ship = useVs ? vsCompat.getShipObjectManagingPos(level, entity.blockPosition()) : null;
            
            if (ship != null) {
                double[] shipPos = vsCompat.transformWorldToShip(ship, entity.getX(), entity.getEyeY(), entity.getZ());
                
                double mergeDistance = config.experimental.cluster_merge_distance;
                
                int gridX = (int) Math.floor(shipPos[0] / mergeDistance);
                int gridY = (int) Math.floor(shipPos[1] / mergeDistance);
                int gridZ = (int) Math.floor(shipPos[2] / mergeDistance);
                BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                
                shipClusters.computeIfAbsent(vsCompat.getShipId(ship), s -> new HashMap<>())
                            .computeIfAbsent(gridPos, k -> new LightCluster())
                            .add(shipPos[0], shipPos[1], shipPos[2], lightLevel, isNew, particles, sounds, entity instanceof Player);
            } else {
                double lightX = entity.getX();
                double lightY = entity.getEyeY();
                double lightZ = entity.getZ();
                
                BlockPos eyePos = BlockPos.containing(lightX, lightY, lightZ);
                
                // Calculate dynamic radius based on light level
                int searchRadius = Math.max(1, lightLevel / 2);
                
                // Use cached position if available and valid
                BlockPos lastPos = gameState.lastSourcePos.computeIfAbsent(entity.getId(), k -> new HashMap<>()).get(sourceId);
                BlockPos bestPos = null;
                
                double spacingSq = config.experimental.light_source_spacing * config.experimental.light_source_spacing;
                
                if (lastPos != null && LightPropagator.isValidLightSpot(level, lastPos)) {
                    double distSq = lastPos.distToCenterSqr(entity.getX(), entity.getEyeY(), entity.getZ());
                    if (distSq < spacingSq) { // Within spacing distance
                        bestPos = lastPos;
                    }
                }
                
                if (bestPos == null) {
                    bestPos = LightPropagator.findValidLightPos(level, eyePos, entity.blockPosition(), searchRadius);
                }
                
                if (bestPos != null) {
                    gameState.lastSourcePos.get(entity.getId()).put(sourceId, bestPos);
                    
                    lightX = bestPos.getX() + 0.5;
                    lightY = bestPos.getY() + 0.5;
                    lightZ = bestPos.getZ() + 0.5;
                    
                    // Distance penalty removed
                } else {
                    gameState.lastSourcePos.get(entity.getId()).remove(sourceId);
                }

                if (lightLevel > 0) {
                    double mergeDistance = config.experimental.cluster_merge_distance;
                    int gridX = (int) Math.floor(lightX / mergeDistance);
                    int gridY = (int) Math.floor(lightY / mergeDistance);
                    int gridZ = (int) Math.floor(lightZ / mergeDistance);
                    BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                    
                    worldClusters.computeIfAbsent(gridPos, k -> new LightCluster()).add(lightX, lightY, lightZ, lightLevel, isNew, particles, sounds, entity instanceof Player);
                }
            }
        } else {
            gameState.lastSourcePos.computeIfAbsent(entity.getId(), k -> new HashMap<>()).remove(sourceId);
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
