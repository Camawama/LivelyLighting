package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.data.ModTags;
import net.cama.livelylighting.compat.valkyrienskies.IVSCompat;
import net.cama.livelylighting.data.LivelyLightingData;
import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID)
public class LightManager {

    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> levelLights = new HashMap<>();
    private static final Map<Long, Map<BlockPos, Integer>> shipLights = new HashMap<>();
    
    // Changed to be per-dimension to avoid cross-dimension cleanup issues
    private static final Map<ResourceKey<Level>, Map<Integer, LightData>> entityLitState = new HashMap<>();
    private static final Set<UUID> playersDisabledSounds = new HashSet<>();

    private static boolean isVsLoaded;
    private static boolean checkedVs = false;
    private static IVSCompat vsCompat;

    public static void togglePlayerSounds(UUID uuid) {
        if (playersDisabledSounds.contains(uuid)) {
            playersDisabledSounds.remove(uuid);
        } else {
            playersDisabledSounds.add(uuid);
        }
    }
    
    public static boolean arePlayerSoundsDisabled(UUID uuid) {
        return playersDisabledSounds.contains(uuid);
    }

    public static void reloadConfig() {
        LightCalculator.reloadConfig();
    }
    
    public static void purgeAllLights(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            Map<BlockPos, Integer> lights = levelLights.get(dimension);
            if (lights != null) {
                for (BlockPos pos : lights.keySet()) {
                    LightPropagator.removeLight(level, pos);
                }
                lights.clear();
            }
        }
        levelLights.clear();
        shipLights.clear();
        entityLitState.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide) return;

        if (!checkedVs) {
            isVsLoaded = ModList.get().isLoaded("valkyrienskies");
            if (isVsLoaded) {
                try {
                    Class<?> clazz = Class.forName("net.cama.livelylighting.compat.valkyrienskies.VSCompat");
                    vsCompat = (IVSCompat) clazz.getDeclaredConstructor().newInstance();
                } catch (Throwable t) {
                    isVsLoaded = false;
                    t.printStackTrace();
                }
            }
            checkedVs = true;
        }

        LivelyConfig config = LivelyConfig.get();
        if (!config.enable) return;

        ServerLevel level = (ServerLevel) event.level;
        runLightLogic(level, config);
    }

    private static void runLightLogic(ServerLevel level, LivelyConfig config) {
        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, Integer> worldCurrentLights = levelLights.computeIfAbsent(dimension, k -> new HashMap<>());
        Map<BlockPos, Integer> worldDesiredLights = new HashMap<>();
        
        Map<Long, Map<BlockPos, Integer>> shipDesiredLights = new HashMap<>();

        boolean experimental = config.experimental.mode;
        boolean smoothing = experimental && config.experimental.smoothing;
        boolean clusterGrowing = experimental && config.experimental.cluster_growing;
        double mergeDistance = config.experimental.cluster_merge_distance;
        int maxRadius = config.experimental.max_influence_radius;
        int decayRate = config.experimental.trail_decay_rate;
        int fadeInRate = config.experimental.fade_in_rate;
        int maxSources = config.max_light_sources;

        boolean useVs = isVsLoaded && config.vs_support && vsCompat != null;

        Map<BlockPos, LightCluster> worldClusters = new HashMap<>();
        Map<Long, Map<BlockPos, LightCluster>> shipClusters = new HashMap<>();
        int sourceCount = 0;

        Set<Integer> processedIds = new HashSet<>();

        for (Player player : level.players()) {
            if (sourceCount >= maxSources) break;
            if (player.isSpectator()) continue;
            processEntity(player, level, worldClusters, shipClusters, processedIds, config, useVs);
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

                if (processEntity(entity, level, worldClusters, shipClusters, processedIds, config, useVs)) {
                    sourceCount++;
                }
            }
        }
        
        // Cleanup logic now scoped to the current dimension
        if (level.getGameTime() % 100 == 0) {
            Map<Integer, LightData> dimensionState = entityLitState.get(dimension);
            if (dimensionState != null) {
                dimensionState.keySet().removeIf(id -> level.getEntity(id) == null);
            }
            
            if (useVs) {
                Set<Long> loadedShipIds = vsCompat.getLoadedShipIds(level);
                shipLights.keySet().removeIf(id -> !loadedShipIds.contains(id));
            }
        }

        // 2. Calculate Desired Light Field
        LightPropagator.calculateLightField(level, worldClusters, worldDesiredLights, smoothing, clusterGrowing, maxRadius);
        
        if (useVs) {
            Map<Long, Object> shipLookup = vsCompat.getShipLookup(level);

            for (Map.Entry<Long, Map<BlockPos, LightCluster>> entry : shipClusters.entrySet()) {
                Long shipId = entry.getKey();
                Map<BlockPos, LightCluster> clusters = entry.getValue();
                Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());
                LightPropagator.calculateLightField(level, clusters, desired, smoothing, clusterGrowing, maxRadius);
            }
            
            for (LightCluster lightCluster : worldClusters.values()) {
                AABB aabb = new AABB(lightCluster.x - maxRadius, lightCluster.y - maxRadius, lightCluster.z - maxRadius,
                                     lightCluster.x + maxRadius, lightCluster.y + maxRadius, lightCluster.z + maxRadius);
                for (Object ship : vsCompat.getShipsIntersecting(level, aabb)) {
                    double[] shipPos = vsCompat.transformWorldToShip(ship, lightCluster.x, lightCluster.y, lightCluster.z);

                    long shipId = vsCompat.getShipId(ship);
                    Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());

                    LightCluster shipLightCluster = new LightCluster();
                    shipLightCluster.add(shipPos[0], shipPos[1], shipPos[2], (int) lightCluster.strength, lightCluster.isNewSource, lightCluster.particles, lightCluster.sounds);
                    shipLightCluster.maxLightLevel = lightCluster.maxLightLevel;
                    shipLightCluster.normalize();

                    LightPropagator.calculateLightField(level, Map.of(BlockPos.containing(shipPos[0], shipPos[1], shipPos[2]), shipLightCluster), desired, smoothing, clusterGrowing, maxRadius);
                }
            }
            
            for (Map.Entry<Long, Map<BlockPos, LightCluster>> entry : shipClusters.entrySet()) {
                Long shipId = entry.getKey();
                Object ship = shipLookup.get(shipId);
                if (ship == null) continue;
                
                for (LightCluster lightCluster : entry.getValue().values()) {
                    double[] worldPos = vsCompat.transformShipToWorld(ship, lightCluster.x, lightCluster.y, lightCluster.z);

                    LightCluster worldLightCluster = new LightCluster();
                    worldLightCluster.add(worldPos[0], worldPos[1], worldPos[2], (int) lightCluster.strength, lightCluster.isNewSource, lightCluster.particles, lightCluster.sounds);
                    worldLightCluster.maxLightLevel = lightCluster.maxLightLevel;
                    worldLightCluster.normalize();

                    LightPropagator.calculateLightField(level, Map.of(BlockPos.containing(worldPos[0], worldPos[1], worldPos[2]), worldLightCluster), worldDesiredLights, smoothing, clusterGrowing, maxRadius);
                }
            }
        }

        // 3. Apply Changes
        LightPropagator.applyChanges(level, worldCurrentLights, worldDesiredLights, smoothing, decayRate, fadeInRate, worldClusters, null, vsCompat);
        
        if (useVs) {
            Map<Long, Object> shipLookup = vsCompat.getShipLookup(level);

            for (Map.Entry<Long, Map<BlockPos, Integer>> entry : shipDesiredLights.entrySet()) {
                Long shipId = entry.getKey();
                Object ship = shipLookup.get(shipId);
                if (ship == null) continue;
                
                Map<BlockPos, Integer> desired = entry.getValue();
                Map<BlockPos, Integer> current = shipLights.computeIfAbsent(shipId, k -> new HashMap<>());

                Map<BlockPos, LightCluster> relevantClusters = shipClusters.getOrDefault(shipId, new HashMap<>());

                LightPropagator.applyChanges(level, current, desired, smoothing, decayRate, fadeInRate, relevantClusters, ship, vsCompat);
            }
            
            Iterator<Map.Entry<Long, Map<BlockPos, Integer>>> shipIt = shipLights.entrySet().iterator();
            while (shipIt.hasNext()) {
                Map.Entry<Long, Map<BlockPos, Integer>> entry = shipIt.next();
                Long shipId = entry.getKey();
                if (!shipDesiredLights.containsKey(shipId)) {
                    Map<BlockPos, Integer> current = entry.getValue();
                    Object ship = shipLookup.get(shipId);
                    if (ship == null) {
                        shipIt.remove();
                        continue;
                    }
                    
                    if (current.isEmpty()) {
                        shipIt.remove();
                    } else {
                        LightPropagator.applyChanges(level, current, new HashMap<>(), smoothing, decayRate, fadeInRate, new HashMap<>(), ship, vsCompat);
                        if (current.isEmpty()) {
                            shipIt.remove();
                        }
                    }
                }
            }
        }
    }

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<Long, Map<BlockPos, LightCluster>> shipClusters, Set<Integer> processedIds, LivelyConfig config, boolean useVs) {
        if (!processedIds.add(entity.getId())) return false;
        
        LivelyLightingData data = LivelyLightingData.get(level);
        if (data.isEntityDisabled(entity.getUUID())) return false;

        // Check for forced light level
        Integer forcedLevel = data.getForcedLevel(entity.getUUID());
        if (forcedLevel != null) {
            LightData lightData = new LightData(forcedLevel, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            processLightData(entity, level, worldClusters, shipClusters, config, useVs, lightData);
            return true;
        }

        // If cluster growing is enabled, we check all slots and add them as separate sources to the cluster
        if (config.experimental.mode && config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            boolean foundLight = false;
            
            // Main Hand
            LightData main = LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config);
            if (main.level > 0) {
                processLightData(entity, level, worldClusters, shipClusters, config, useVs, main);
                foundLight = true;
            }
            
            // Off Hand
            LightData off = LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config);
            if (off.level > 0) {
                processLightData(entity, level, worldClusters, shipClusters, config, useVs, off);
                foundLight = true;
            }
            
            // Armor
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) {
                    LightData armor = LightCalculator.getItemLightLevel(living.getItemBySlot(slot), entity, level, config);
                    if (armor.level > 0) {
                        processLightData(entity, level, worldClusters, shipClusters, config, useVs, armor);
                        foundLight = true;
                    }
                }
            }
            
            // Special Enderman check
            if (entity instanceof net.minecraft.world.entity.monster.EnderMan enderman) {
                net.minecraft.world.level.block.state.BlockState heldBlock = enderman.getCarriedBlock();
                if (heldBlock != null) {
                    net.minecraft.world.item.ItemStack blockStack = new net.minecraft.world.item.ItemStack(heldBlock.getBlock());
                    LightData blockLight = LightCalculator.getItemLightLevel(blockStack, entity, level, config);
                    if (blockLight.level > 0) {
                        processLightData(entity, level, worldClusters, shipClusters, config, useVs, blockLight);
                        foundLight = true;
                    }
                }
            }
            
            // Check other sources (fire, glow effect, etc)
            LightData entityLight = LightCalculator.getEntityLightLevel(entity, level, config);
            if (entityLight.level > 0) {
                processLightData(entity, level, worldClusters, shipClusters, config, useVs, entityLight);
                foundLight = true;
            }
            
            return foundLight;
        } else {
            // Standard behavior: get best light level and process once
            LightData lightData = LightCalculator.getBestLightLevel(entity, level, config);
            if (lightData.level > 0 || getEntityLitState(level, entity.getId()) != null) {
                processLightData(entity, level, worldClusters, shipClusters, config, useVs, lightData);
                return lightData.level > 0;
            }
            return false;
        }
    }
    
    private static LightData getEntityLitState(ServerLevel level, int entityId) {
        Map<Integer, LightData> dimensionState = entityLitState.get(level.dimension());
        return dimensionState != null ? dimensionState.get(entityId) : null;
    }
    
    private static void setEntityLitState(ServerLevel level, int entityId, LightData data) {
        entityLitState.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(entityId, data);
    }
    
    private static void processLightData(Entity entity, ServerLevel level, Map<BlockPos, LightCluster> worldClusters, Map<Long, Map<BlockPos, LightCluster>> shipClusters, LivelyConfig config, boolean useVs, LightData lightData) {
        int lightLevel = lightData.level;
        List<ParticleType<?>> particles = lightData.particles;
        List<SoundData> sounds = lightData.sounds;
        List<SoundData> extinguishSounds = lightData.extinguishSounds;
        
        LightData lastData = getEntityLitState(level, entity.getId());
        boolean wasLit = lastData != null && lastData.level > 0;
        boolean isLit = lightLevel > 0;
        
        boolean isNew = isLit && (!wasLit || !lightData.equals(lastData));
        
        Vec3 handPos = entity.getEyePosition().add(entity.getViewVector(1.0f).scale(0.5)).add(entity.getViewVector(1.0f).cross(new Vec3(0, 1, 0)).scale(0.3));
        
        if (wasLit && !isLit) {
            if (config.enable_sounds && !arePlayerSoundsDisabled(entity.getUUID())) {
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
            if (!(entity instanceof Creeper) && !(entity instanceof PrimedTnt)) {
                 if (config.enable_sounds && !arePlayerSoundsDisabled(entity.getUUID())) {
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
        
        setEntityLitState(level, entity.getId(), lightData);

        if (lightLevel > 0) {
            Object ship = useVs ? vsCompat.getShipObjectManagingPos(level, entity.blockPosition()) : null;
            
            if (ship != null) {
                double[] shipPos = vsCompat.transformWorldToShip(ship, entity.getX(), entity.getEyeY(), entity.getZ());
                
                double mergeDistance = config.experimental.cluster_merge_distance;
                
                int gridX = (int) (shipPos[0] / mergeDistance);
                int gridY = (int) (shipPos[1] / mergeDistance);
                int gridZ = (int) (shipPos[2] / mergeDistance);
                BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                
                shipClusters.computeIfAbsent(vsCompat.getShipId(ship), s -> new HashMap<>())
                            .computeIfAbsent(gridPos, k -> new LightCluster())
                            .add(shipPos[0], shipPos[1], shipPos[2], lightLevel, isNew, particles, sounds);
            } else {
                double lightX = entity.getX();
                double lightY = entity.getEyeY();
                double lightZ = entity.getZ();
                
                BlockPos eyePos = BlockPos.containing(lightX, lightY, lightZ);
                BlockPos bestPos = LightPropagator.findValidLightPos(level, eyePos, entity.blockPosition());
                
                if (bestPos != null) {
                    lightX = bestPos.getX() + 0.5;
                    lightY = bestPos.getY() + 0.5;
                    lightZ = bestPos.getZ() + 0.5;
                }

                double mergeDistance = config.experimental.cluster_merge_distance;
                int gridX = (int) (lightX / mergeDistance);
                int gridY = (int) (lightY / mergeDistance);
                int gridZ = (int) (lightZ / mergeDistance);
                BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                
                worldClusters.computeIfAbsent(gridPos, k -> new LightCluster()).add(lightX, lightY, lightZ, lightLevel, isNew, particles, sounds);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            Map<BlockPos, Integer> lights = levelLights.get(dimension);
            if (lights != null) {
                for (BlockPos pos : lights.keySet()) {
                    LightPropagator.removeLight(level, pos);
                }
                lights.clear();
            }
        }
        levelLights.clear();
        shipLights.clear();
        entityLitState.clear();
    }

    private static boolean shouldCheckEntity(Entity entity, LivelyConfig config) {
        // Check forced entities first (handled in processEntity, but we need to return true here to reach it)
        if (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Creeper || entity instanceof PrimedTnt) {
             // Always allow checking these, processEntity will check the saved data
        } else {
             // For other entities, check if they are forced
             // We can't easily check saved data here without level access, but processEntity is called for all entities if we return true?
             // No, the loop in runLightLogic filters by shouldCheckEntity.
             // We need to check saved data here.
             if (entity.level() instanceof ServerLevel serverLevel) {
                 LivelyLightingData data = LivelyLightingData.get(serverLevel);
                 if (data.getForcedLevel(entity.getUUID()) != null) return true;
             }
        }

        if (config.enable_entity_lights) {
            // We can't access entityCache directly here as it's private in LightCalculator?
            // Actually, we moved it to LightCalculator.
            // We should expose a method in LightCalculator to check if an entity type is cached/configured.
            // But for now, let's just assume true if enable_entity_lights is on, or check tags.
            // The original code checked the cache.
            // Let's just return true if enabled, processEntity will filter it out if it has 0 light.
            return true;
        }
        return entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Creeper || entity instanceof PrimedTnt
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_15)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_12)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_9)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_6)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_3);
    }
}
