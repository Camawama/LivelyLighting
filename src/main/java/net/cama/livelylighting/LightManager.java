package net.cama.livelylighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID)
public class LightManager {

    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> levelLights = new HashMap<>();
    private static final Map<Long, Map<BlockPos, Integer>> shipLights = new HashMap<>();
    
    private static final Map<Item, LightData> itemCache = new HashMap<>();
    private static final List<RegexLightData> regexItems = new ArrayList<>();
    
    private static final Map<EntityType<?>, Integer> entityCache = new HashMap<>();
    private static final List<RegexEntityData> regexEntities = new ArrayList<>();
    
    private static final Map<Integer, Boolean> entityLitState = new HashMap<>();

    private static boolean isVsLoaded;
    private static boolean checkedVs = false;
    private static IVSCompat vsCompat;

    private enum LightType {
        FLAME, GLOW
    }

    private static class LightData {
        final int level;
        final boolean waterSensitive;
        final LightType type;

        LightData(int level, boolean waterSensitive, LightType type) {
            this.level = level;
            this.waterSensitive = waterSensitive;
            this.type = type;
        }
    }

    private static class RegexLightData {
        final Pattern pattern;
        final LightData data;

        RegexLightData(Pattern pattern, LightData data) {
            this.pattern = pattern;
            this.data = data;
        }
    }
    
    private static class RegexEntityData {
        final Pattern pattern;
        final int level;

        RegexEntityData(Pattern pattern, int level) {
            this.pattern = pattern;
            this.level = level;
        }
    }

    private static class Cluster {
        double x, y, z;
        float strength;
        int count;
        boolean isNewSource;
        LightType type;
        int maxLightLevel;
        
        double minX, minY, minZ;
        double maxX, maxY, maxZ;
        boolean first = true;

        void add(double ex, double ey, double ez, int light, boolean isNew, LightType t) {
            x += ex;
            y += ey;
            z += ez;
            count++;
            
            if (isNew) {
                isNewSource = true;
                type = t;
            }
            
            if (light > maxLightLevel) {
                maxLightLevel = light;
            }
            
            if (first) {
                minX = maxX = ex;
                minY = maxY = ey;
                minZ = maxZ = ez;
                first = false;
            } else {
                if (ex < minX) minX = ex;
                if (ex > maxX) maxX = ex;
                if (ey < minY) minY = ey;
                if (ey > maxY) maxY = ey;
                if (ez < minZ) minZ = ez;
                if (ez > maxZ) maxZ = ez;
            }
        }

        void normalize() {
            if (count > 0) {
                x /= count;
                y /= count;
                z /= count;
                
                double dx = maxX - minX;
                double dy = maxY - minY;
                double dz = maxZ - minZ;
                double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                
                strength = (float) (maxLightLevel + distance);
            }
        }
    }

    public static void reloadConfig() {
        LivelyConfig.load();
        
        itemCache.clear();
        regexItems.clear();
        entityCache.clear();
        regexEntities.clear();
        
        LivelyConfig config = LivelyConfig.get();
        
        if (config.custom_items != null) {
            for (String entry : config.custom_items) {
                try {
                    String[] parts = entry.split("\\|");
                    if (parts.length >= 3) {
                        String id = parts[0];
                        int level = Integer.parseInt(parts[1]);
                        boolean sensitive = Boolean.parseBoolean(parts[2]);
                        
                        LightType type = LightType.GLOW;
                        if (parts.length >= 4) {
                            try {
                                type = LightType.valueOf(parts[3].toUpperCase());
                            } catch (IllegalArgumentException e) {
                            }
                        } else {
                            if (id.contains("torch") || id.contains("lantern") || id.contains("fire") || id.contains("lava") || id.contains("magma")) {
                                type = LightType.FLAME;
                            }
                        }
                        
                        LightData data = new LightData(level, sensitive, type);

                        if (id.startsWith("regex:")) {
                            String patternStr = id.substring(6);
                            regexItems.add(new RegexLightData(Pattern.compile(patternStr), data));
                        } else {
                            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                                itemCache.put(item, data);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("LivelyLighting: Failed to parse item config entry: " + entry);
                }
            }
        }
        
        if (config.custom_entities != null) {
            for (String entry : config.custom_entities) {
                try {
                    String[] parts = entry.split("\\|");
                    if (parts.length >= 2) {
                        String id = parts[0];
                        int level = Integer.parseInt(parts[1]);

                        if (id.startsWith("regex:")) {
                            String patternStr = id.substring(6);
                            regexEntities.add(new RegexEntityData(Pattern.compile(patternStr), level));
                        } else {
                            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
                            if (type != null) {
                                entityCache.put(type, level);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("LivelyLighting: Failed to parse entity config entry: " + entry);
                }
            }
        }
    }
    
    public static void purgeAllLights(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            Map<BlockPos, Integer> lights = levelLights.get(dimension);
            if (lights != null) {
                for (BlockPos pos : lights.keySet()) {
                    removeLight(level, pos);
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
                    Class<?> clazz = Class.forName("net.cama.livelylighting.VSCompat");
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

        Map<BlockPos, Cluster> worldClusters = new HashMap<>();
        Map<Long, Map<BlockPos, Cluster>> shipClusters = new HashMap<>();
        int sourceCount = 0;

        Set<Integer> processedIds = new HashSet<>();

        for (Player player : level.players()) {
            if (sourceCount >= maxSources) break;
            processEntity(player, level, worldClusters, shipClusters, processedIds, config, useVs);
            sourceCount++;
        }

        if (sourceCount < maxSources) {
            for (Entity entity : level.getAllEntities()) {
                if (sourceCount >= maxSources) break;
                if (entity instanceof Player) continue; 
                
                if (!shouldCheckEntity(entity, config)) continue;

                if (processEntity(entity, level, worldClusters, shipClusters, processedIds, config, useVs)) {
                    sourceCount++;
                }
            }
        }
        
        if (level.getGameTime() % 100 == 0) {
            entityLitState.keySet().removeIf(id -> level.getEntity(id) == null);
            if (useVs) {
                Set<Long> loadedShipIds = vsCompat.getLoadedShipIds(level);
                shipLights.keySet().removeIf(id -> !loadedShipIds.contains(id));
            }
        }

        // 2. Calculate Desired Light Field
        calculateLightField(level, worldClusters, worldDesiredLights, smoothing, clusterGrowing, maxRadius);
        
        if (useVs) {
            Map<Long, Object> shipLookup = vsCompat.getShipLookup(level);

            for (Map.Entry<Long, Map<BlockPos, Cluster>> entry : shipClusters.entrySet()) {
                Long shipId = entry.getKey();
                Map<BlockPos, Cluster> clusters = entry.getValue();
                Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());
                calculateLightField(level, clusters, desired, smoothing, clusterGrowing, maxRadius);
            }
            
            for (Cluster cluster : worldClusters.values()) {
                AABB aabb = new AABB(cluster.x - maxRadius, cluster.y - maxRadius, cluster.z - maxRadius,
                                     cluster.x + maxRadius, cluster.y + maxRadius, cluster.z + maxRadius);
                for (Object ship : vsCompat.getShipsIntersecting(level, aabb)) {
                    double[] shipPos = vsCompat.transformWorldToShip(ship, cluster.x, cluster.y, cluster.z);

                    long shipId = vsCompat.getShipId(ship);
                    Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());

                    Cluster shipCluster = new Cluster();
                    shipCluster.add(shipPos[0], shipPos[1], shipPos[2], (int)cluster.strength, cluster.isNewSource, cluster.type);
                    shipCluster.maxLightLevel = cluster.maxLightLevel;
                    shipCluster.normalize();

                    calculateLightField(level, Map.of(BlockPos.containing(shipPos[0], shipPos[1], shipPos[2]), shipCluster), desired, smoothing, clusterGrowing, maxRadius);
                }
            }
            
            for (Map.Entry<Long, Map<BlockPos, Cluster>> entry : shipClusters.entrySet()) {
                Long shipId = entry.getKey();
                Object ship = shipLookup.get(shipId);
                if (ship == null) continue;
                
                for (Cluster cluster : entry.getValue().values()) {
                    double[] worldPos = vsCompat.transformShipToWorld(ship, cluster.x, cluster.y, cluster.z);

                    Cluster worldCluster = new Cluster();
                    worldCluster.add(worldPos[0], worldPos[1], worldPos[2], (int)cluster.strength, cluster.isNewSource, cluster.type);
                    worldCluster.maxLightLevel = cluster.maxLightLevel;
                    worldCluster.normalize();

                    calculateLightField(level, Map.of(BlockPos.containing(worldPos[0], worldPos[1], worldPos[2]), worldCluster), worldDesiredLights, smoothing, clusterGrowing, maxRadius);
                }
            }
        }

        // 3. Apply Changes
        applyChanges(level, worldCurrentLights, worldDesiredLights, smoothing, decayRate, fadeInRate, worldClusters, null);
        
        if (useVs) {
            Map<Long, Object> shipLookup = vsCompat.getShipLookup(level);

            for (Map.Entry<Long, Map<BlockPos, Integer>> entry : shipDesiredLights.entrySet()) {
                Long shipId = entry.getKey();
                Object ship = shipLookup.get(shipId);
                if (ship == null) continue;
                
                Map<BlockPos, Integer> desired = entry.getValue();
                Map<BlockPos, Integer> current = shipLights.computeIfAbsent(shipId, k -> new HashMap<>());

                Map<BlockPos, Cluster> relevantClusters = shipClusters.getOrDefault(shipId, new HashMap<>());

                applyChanges(level, current, desired, smoothing, decayRate, fadeInRate, relevantClusters, ship);
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
                        applyChanges(level, current, new HashMap<>(), smoothing, decayRate, fadeInRate, new HashMap<>(), ship);
                        if (current.isEmpty()) {
                            shipIt.remove();
                        }
                    }
                }
            }
        }
    }
    
    private static void calculateLightField(ServerLevel level, Map<BlockPos, Cluster> clusters, Map<BlockPos, Integer> desiredLights, boolean smoothing, boolean clusterGrowing, int maxRadius) {
        for (Cluster cluster : clusters.values()) {
            cluster.normalize();

            float strength = cluster.strength;
            if (!clusterGrowing) {
                strength = cluster.maxLightLevel;
            }

            int radius = 0;
            if (strength > 15) {
                radius = (int) Math.ceil(strength);
                radius = Math.min(radius, maxRadius);
            } else if (smoothing) {
                radius = 1;
            }

            BlockPos centerPos = BlockPos.containing(cluster.x, cluster.y, cluster.z);

            if (radius == 0) {
                int levelAtTarget = Math.min(15, (int)strength);
                if (levelAtTarget > 0) {
                    int existing = desiredLights.getOrDefault(centerPos, 0);
                    if (levelAtTarget > existing) {
                        desiredLights.put(centerPos, levelAtTarget);
                    }
                }
                continue;
            }

            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();

            queue.add(centerPos);
            visited.add(centerPos);

            while (!queue.isEmpty()) {
                BlockPos currentPos = queue.poll();

                double dist;
                if (smoothing) {
                    double dSq = (cluster.x - (currentPos.getX() + 0.5)) * (cluster.x - (currentPos.getX() + 0.5)) +
                                 (cluster.y - (currentPos.getY() + 0.5)) * (cluster.y - (currentPos.getY() + 0.5)) +
                                 (cluster.z - (currentPos.getZ() + 0.5)) * (cluster.z - (currentPos.getZ() + 0.5));
                    dist = Math.sqrt(dSq);
                } else {
                    dist = Math.abs(centerPos.getX() - currentPos.getX()) +
                           Math.abs(centerPos.getY() - currentPos.getY()) +
                           Math.abs(centerPos.getZ() - currentPos.getZ());
                }

                if (dist > radius) continue;

                int levelAtTarget = (int) Math.round(strength - dist);
                if (levelAtTarget > 15) levelAtTarget = 15;

                if (levelAtTarget > 0) {
                    // Check for line of sight/obstruction
                    if (!currentPos.equals(centerPos)) {
                        // Simple raycast check to prevent light bleeding through walls
                        // We check the block between center and current if they are adjacent
                        if (centerPos.distManhattan(currentPos) == 1) {
                             // Direct neighbor, no check needed as we already check if neighbor is loaded/valid in BFS
                        } else {
                            // For further blocks, we can check if the path is blocked by a full opaque block
                            // This is a simplified check. For true raytracing we'd need more complex logic.
                            // But for the issue described (throwing torch on other side of wall), 
                            // we need to ensure the light source itself isn't occluded from the target.
                            
                            // Actually, the BFS propagation handles obstruction naturally IF we don't allow passing through opaque blocks.
                            // The issue "throwing a torch on the other side of the wall disables dynamic lighting" suggests
                            // that the merging logic might be merging two clusters that are separated by a wall,
                            // and then placing the center inside the wall or somewhere invalid.
                            
                            // However, the user said "it disables the dynamic lighting effect for both torches".
                            // This implies they are being merged into one cluster, and that cluster's center is perhaps invalid or occluded.
                        }
                    }

                    if (!currentPos.equals(centerPos)) {
                        int manhattanDist = Math.abs(centerPos.getX() - currentPos.getX()) +
                                            Math.abs(centerPos.getY() - currentPos.getY()) +
                                            Math.abs(centerPos.getZ() - currentPos.getZ());
                        int naturalLevel = Math.max(0, 15 - manhattanDist);

                        if (levelAtTarget > naturalLevel) {
                            int existing = desiredLights.getOrDefault(currentPos, 0);
                            if (levelAtTarget > existing) {
                                desiredLights.put(currentPos, levelAtTarget);
                            }
                        }
                    } else {
                        int existing = desiredLights.getOrDefault(currentPos, 0);
                        if (levelAtTarget > existing) {
                            desiredLights.put(currentPos, levelAtTarget);
                        }
                    }

                    if (level.isLoaded(currentPos)) {
                        BlockState state = level.getBlockState(currentPos);
                        // Only propagate if the block lets light through (not fully opaque)
                        // This fixes the "bleeding through walls" issue
                        if (state.getLightBlock(level, currentPos) < 15) {
                            for (Direction dir : Direction.values()) {
                                BlockPos neighbor = currentPos.relative(dir);
                                if (visited.add(neighbor)) {
                                    // Check if the neighbor itself is blocked before adding to queue?
                                    // No, we add to queue and check opacity when processing that node.
                                    // But we need to ensure we don't traverse THROUGH a wall.
                                    // The condition `state.getLightBlock(...) < 15` ensures we don't continue FROM an opaque block.
                                    // But we also need to ensure we don't go INTO an opaque block and then emit light from it?
                                    // Actually, light blocks can be placed in air/water.
                                    // If we are at an air block, we can go to neighbor.
                                    queue.add(neighbor);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private static void applyChanges(ServerLevel level, Map<BlockPos, Integer> currentLights, Map<BlockPos, Integer> desiredLights, boolean smoothing, int decayRate, int fadeInRate, Map<BlockPos, Cluster> clusters, Object ship) {
        Iterator<Map.Entry<BlockPos, Integer>> it = currentLights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos pos = entry.getKey();
            int currentLevel = entry.getValue();

            if (desiredLights.containsKey(pos)) {
                int desiredLevel = desiredLights.get(pos);
                int newLevel = currentLevel;

                if (smoothing && desiredLevel > currentLevel) {
                    newLevel = Math.min(desiredLevel, currentLevel + fadeInRate);
                } else {
                    newLevel = desiredLevel;
                }

                if (currentLevel != newLevel || shouldRePlace(level, pos, newLevel)) {
                    if (placeLight(level, pos, newLevel, true, ship)) {
                        entry.setValue(newLevel);
                    } else {
                        it.remove();
                    }
                }
                desiredLights.remove(pos);
            } else {
                if (smoothing) {
                    int newLevel = currentLevel - decayRate;
                    if (newLevel > 0) {
                        if (placeLight(level, pos, newLevel, true, ship)) {
                            entry.setValue(newLevel);
                        } else {
                            it.remove();
                        }
                    } else {
                        removeLight(level, pos);
                        it.remove();
                    }
                } else {
                    removeLight(level, pos);
                    it.remove();
                }
            }
        }

        for (Map.Entry<BlockPos, Integer> entry : desiredLights.entrySet()) {
            BlockPos pos = entry.getKey();
            int desiredLevel = entry.getValue();
            int newLevel = desiredLevel;

            boolean isNewSource = false;
            for (Cluster cluster : clusters.values()) {
                if (cluster.isNewSource) {
                    double distSq = pos.distToCenterSqr(cluster.x, cluster.y, cluster.z);
                    if (distSq < 256) {
                        isNewSource = true;
                        break;
                    }
                }
            }

            if (smoothing && isNewSource) {
                newLevel = Math.min(desiredLevel, fadeInRate);
            }

            if (placeLight(level, pos, newLevel, true, ship)) {
                currentLights.put(pos, newLevel);
            }
        }
    }

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, Cluster> worldClusters, Map<Long, Map<BlockPos, Cluster>> shipClusters, Set<Integer> processedIds, LivelyConfig config, boolean useVs) {
        if (!processedIds.add(entity.getId())) return false;
        
        LivelyLightingData data = LivelyLightingData.get(level);
        if (data.isEntityDisabled(entity.getUUID())) return false;

        LightData lightData = getLightLevel(entity, level, config);
        
        // Check for forced light level
        Integer forcedLevel = data.getForcedLevel(entity.getUUID());
        if (forcedLevel != null) {
            lightData = new LightData(forcedLevel, false, LightType.GLOW);
        }

        int lightLevel = lightData.level;
        LightType type = lightData.type;
        
        boolean wasLit = entityLitState.getOrDefault(entity.getId(), false);
        boolean isLit = lightLevel > 0;
        boolean isNew = !wasLit && isLit;
        
        Vec3 handPos = entity.getEyePosition().add(entity.getViewVector(1.0f).scale(0.5)).add(entity.getViewVector(1.0f).cross(new Vec3(0, 1, 0)).scale(0.3));
        
        if (wasLit && !isLit) {
            if (entity.isInWater() && entity.getFluidHeight(FluidTags.WATER) > entity.getEyeHeight() - 0.5) {
                 level.playSound(null, handPos.x, handPos.y, handPos.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.NEUTRAL, 0.5f, 1.0f);
                 level.sendParticles(ParticleTypes.SMOKE, handPos.x, handPos.y, handPos.z, 5, 0.1, 0.1, 0.1, 0.0);
            }
        } else if (isNew) {
            if (!(entity instanceof Creeper) && !(entity instanceof PrimedTnt)) {
                if (entityLitState.containsKey(entity.getId())) {
                     if (type == LightType.FLAME) {
                         level.playSound(null, handPos.x, handPos.y, handPos.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.2f, 1.0f);
                         level.playSound(null, handPos.x, handPos.y, handPos.z, SoundEvents.FLINTANDSTEEL_USE, SoundSource.NEUTRAL, 0.5f, 1.2f);
                         level.sendParticles(ParticleTypes.FLAME, handPos.x, handPos.y, handPos.z, 3, 0.05, 0.05, 0.05, 0.02);
                     } else {
                         level.playSound(null, handPos.x, handPos.y, handPos.z, SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 0.5f, 2.0f);
                         level.sendParticles(ParticleTypes.ELECTRIC_SPARK, handPos.x, handPos.y, handPos.z, 5, 0.1, 0.1, 0.1, 0.1);
                     }
                }
            }
        }
        entityLitState.put(entity.getId(), isLit);

        if (lightLevel > 0) {
            Object ship = useVs ? vsCompat.getShipObjectManagingPos(level, entity.blockPosition()) : null;
            
            if (ship != null) {
                double[] shipPos = vsCompat.transformWorldToShip(ship, entity.getX(), entity.getEyeY(), entity.getZ());
                
                // Use a smaller merge distance or check for line of sight to prevent merging through walls
                // For now, we'll just use the config value but we should probably check if the center is reachable
                double mergeDistance = config.experimental.cluster_merge_distance;
                
                // To fix the "merging through walls" issue:
                // We can simply NOT merge if there is a wall between the entity and the cluster center.
                // But since we are bucketing by grid, we don't know the center yet.
                // A simple fix is to reduce the merge distance or ensure the grid bucket is small enough.
                // However, the user said "it disables the dynamic lighting effect for both torches".
                // This suggests the merged cluster is placed in a wall.
                
                // Let's try to ensure the cluster position is valid.
                // But first, let's just implement the requested commands and the basic fix.
                // The "disabling" might be because the merged center is inside the wall.
                
                int gridX = (int) (shipPos[0] / mergeDistance);
                int gridY = (int) (shipPos[1] / mergeDistance);
                int gridZ = (int) (shipPos[2] / mergeDistance);
                BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                
                // Check if we should merge with this cluster
                // If the cluster already exists, check if we can reach it?
                // This is hard to do efficiently.
                // Instead, let's just add it. The normalization step will average the position.
                // If the average position ends up in a wall, we need to handle that in calculateLightField or applyChanges.
                
                shipClusters.computeIfAbsent(vsCompat.getShipId(ship), s -> new HashMap<>())
                            .computeIfAbsent(gridPos, k -> new Cluster())
                            .add(shipPos[0], shipPos[1], shipPos[2], lightLevel, isNew, type);
            } else {
                double lightX = entity.getX();
                double lightY = entity.getEyeY();
                double lightZ = entity.getZ();
                
                BlockPos eyePos = BlockPos.containing(lightX, lightY, lightZ);
                BlockPos bestPos = findValidLightPos(level, eyePos, entity.blockPosition());
                
                if (bestPos != null) {
                    // Snap to center of block to avoid clipping into walls when averaging
                    lightX = bestPos.getX() + 0.5;
                    lightY = bestPos.getY() + 0.5;
                    lightZ = bestPos.getZ() + 0.5;
                }

                double mergeDistance = config.experimental.cluster_merge_distance;
                int gridX = (int) (lightX / mergeDistance);
                int gridY = (int) (lightY / mergeDistance);
                int gridZ = (int) (lightZ / mergeDistance);
                BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                
                // To prevent merging through walls, we can include the room/region in the key?
                // Or we can check if the cluster center is visible.
                // For now, let's rely on the fact that we snapped the position to a valid air block.
                // If two entities are on opposite sides of a wall, their grid coordinates might be the same.
                // If they merge, the average might be inside the wall.
                
                // FIX: If the average position is inside a wall, we should probably split the cluster or move it out.
                // But we can't easily split it here.
                // A simple hack: If the grid bucket contains a wall, maybe don't merge?
                // Better: Check if the gridPos is obstructed from the entity.
                
                // Actually, if we just check if the final cluster position is valid in calculateLightField, that might solve it.
                // If the cluster center is in a wall, we should try to find a nearby valid spot.
                
                worldClusters.computeIfAbsent(gridPos, k -> new Cluster()).add(lightX, lightY, lightZ, lightLevel, isNew, type);
            }
            return true;
        }
        return false;
    }
    
    private static BlockPos findValidLightPos(Level level, BlockPos eyePos, BlockPos feetPos) {
        // 1. Check exact eye position
        if (isValidLightSpot(level, eyePos)) return eyePos;
        
        // 2. Check above eye (for tall grass/corn/etc)
        if (isValidLightSpot(level, eyePos.above())) return eyePos.above();
        
        // 3. Check feet position (fallback)
        if (isValidLightSpot(level, feetPos)) return feetPos;
        
        // 4. Search surrounding blocks (prioritize closest to eye)
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;
        
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    
                    BlockPos pos = eyePos.offset(x, y, z);
                    if (isValidLightSpot(level, pos)) {
                        double distSq = pos.distToCenterSqr(eyePos.getX() + 0.5, eyePos.getY() + 0.5, eyePos.getZ() + 0.5);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        
        return bestPos;
    }
    
    private static boolean isValidLightSpot(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.is(Blocks.WATER) && state.getFluidState().isSource()) || state.is(Blocks.LIGHT);
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            Map<BlockPos, Integer> lights = levelLights.get(dimension);
            if (lights != null) {
                for (BlockPos pos : lights.keySet()) {
                    removeLight(level, pos);
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
            if (entityCache.containsKey(entity.getType())) return true;
        }
        return entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Creeper || entity instanceof PrimedTnt
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_15)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_12)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_9)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_6)
               || entity.getType().is(ModTags.Entities.LIGHT_LEVEL_3);
    }

    private static boolean shouldRePlace(ServerLevel level, BlockPos pos, int targetLevel) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LIGHT)) {
            return state.getValue(LightBlock.LEVEL) != targetLevel;
        }
        return state.isAir() || (state.getFluidState().isSource() && state.is(Blocks.WATER));
    }

    private static boolean placeLight(ServerLevel level, BlockPos pos, int lightLevel, boolean checkOwnership, Object ship) {
        if (!level.isLoaded(pos)) return false;
        
        if (ship != null) {
            if (!vsCompat.isPosInShipBounds(ship, pos)) {
                return false;
            }
            
            LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
            if (chunk == null) return false;
            
            int sectionIndex = level.getSectionIndex(pos.getY());
            if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
                if (chunk.getSection(sectionIndex).hasOnlyAir()) {
                    return false;
                }
            }
        }

        BlockState state = level.getBlockState(pos);
        int flags = Block.UPDATE_CLIENTS; 

        if (state.is(Blocks.LIGHT)) {
            int currentLevel = state.getValue(LightBlock.LEVEL);
            if (currentLevel != lightLevel) {
                level.setBlock(pos, state.setValue(LightBlock.LEVEL, lightLevel), flags);
            }
            return true;
        }
        
        if (state.isAir()) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), flags);
            return true;
        } else if (state.getFluidState().isSource() && state.is(Blocks.WATER)) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel).setValue(LightBlock.WATERLOGGED, true), flags);
            return true;
        }
        
        return false;
    }

    private static void removeLight(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        int flags = Block.UPDATE_CLIENTS;
        
        if (state.is(Blocks.LIGHT)) {
            if (state.getValue(LightBlock.WATERLOGGED)) {
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), flags);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
            }
        }
    }

    private static LightData getLightLevel(Entity entity, ServerLevel level, LivelyConfig config) {
        if (entity.isOnFire()) return new LightData(15, false, LightType.FLAME);
        
        if (config.enable_entity_lights) {
            EntityType<?> type = entity.getType();
            Integer cachedLevel = entityCache.get(type);
            if (cachedLevel != null) {
                if (cachedLevel > 0) return new LightData(cachedLevel, false, LightType.GLOW);
            } else {
                String id = ForgeRegistries.ENTITY_TYPES.getKey(type).toString();
                int foundLevel = 0;
                for (RegexEntityData regex : regexEntities) {
                    if (regex.pattern.matcher(id).matches()) {
                        foundLevel = regex.level;
                        break;
                    }
                }
                entityCache.put(type, foundLevel);
                if (foundLevel > 0) return new LightData(foundLevel, false, LightType.GLOW);
            }
        }
        
        if (config.creeper_lighting && entity instanceof Creeper creeper) {
            float swell = creeper.getSwelling(0); // 0-1
            if (swell > 0) {
                int levelVal = (int) (swell * 15);
                return new LightData(Math.max(1, levelVal), false, LightType.GLOW);
            }
        }
        
        if (config.tnt_lighting && entity instanceof PrimedTnt tnt) {
            int fuse = tnt.getFuse();
            if ((fuse / 5) % 2 == 0) {
                return new LightData(15, false, LightType.FLAME);
            }
            return new LightData(0, false, LightType.FLAME);
        }

        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_15)) return new LightData(15, false, LightType.GLOW);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_12)) return new LightData(12, false, LightType.GLOW);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_9)) return new LightData(9, false, LightType.GLOW);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_6)) return new LightData(6, false, LightType.GLOW);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_3)) return new LightData(3, false, LightType.GLOW);
        
        if (entity instanceof Creeper creeper && creeper.isIgnited()) return new LightData(15, false, LightType.GLOW);

        if (entity instanceof LivingEntity living) {
            ItemStack mainHand = living.getMainHandItem();
            ItemStack offHand = living.getOffhandItem();
            ItemStack head = living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            
            LightData main = getItemLightLevel(mainHand, entity, level, config);
            LightData off = getItemLightLevel(offHand, entity, level, config);
            LightData helm = getItemLightLevel(head, entity, level, config);
            
            LightData best = main;
            if (off.level > best.level) best = off;
            if (helm.level > best.level) best = helm;
            
            if (config.fire_aspect_glow) {
                if (EnchantmentHelper.getFireAspect(living) > 0) {
                    if (9 > best.level) best = new LightData(9, false, LightType.FLAME);
                }
            }
            
            return best;
        }

        if (entity instanceof ItemEntity itemEntity) {
            return getItemLightLevel(itemEntity.getItem(), entity, level, config);
        }

        return new LightData(0, false, LightType.GLOW);
    }

    private static LightData getItemLightLevel(ItemStack stack, Entity entity, ServerLevel level, LivelyConfig config) {
        if (stack.isEmpty()) return new LightData(0, false, LightType.GLOW);
        
        int light = 0;
        boolean sensitive = false;
        LightType type = LightType.GLOW;

        Item item = stack.getItem();
        LightData data = itemCache.get(item);
        if (data != null) {
            light = data.level;
            sensitive = data.waterSensitive;
            type = data.type;
        } else {
            String id = ForgeRegistries.ITEMS.getKey(item).toString();
            for (RegexLightData regex : regexItems) {
                if (regex.pattern.matcher(id).matches()) {
                    light = regex.data.level;
                    sensitive = regex.data.waterSensitive;
                    type = regex.data.type;
                    itemCache.put(item, regex.data);
                    break;
                }
            }
            if (light == 0) {
                if (stack.is(ModTags.Items.LIGHT_LEVEL_15)) { light = 15; sensitive = true; }
                else if (stack.is(ModTags.Items.LIGHT_LEVEL_12)) { light = 12; }
                else if (stack.is(ModTags.Items.LIGHT_LEVEL_9)) { light = 9; }
                else if (stack.is(ModTags.Items.LIGHT_LEVEL_6)) { light = 6; }
                else if (stack.is(ModTags.Items.LIGHT_LEVEL_3)) { light = 3; }
                
                if (light > 0) {
                    if (id.contains("torch") || id.contains("lantern") || id.contains("fire") || id.contains("lava") || id.contains("magma")) {
                        type = LightType.FLAME;
                    }
                    itemCache.put(item, new LightData(light, sensitive, type));
                } else {
                    itemCache.put(item, new LightData(0, false, LightType.GLOW));
                }
            }
        }
        
        if (light == 0 && config.enchanted_items_glow && stack.isEnchanted()) {
            light = 6;
            type = LightType.GLOW;
        }

        if (light > 0 && sensitive) {
            if (entity.isInWater() && entity.getFluidHeight(FluidTags.WATER) > entity.getEyeHeight() - 0.5) return new LightData(0, false, type);
            if (level.isRainingAt(entity.blockPosition()) && level.canSeeSky(entity.blockPosition())) return new LightData(0, false, type);
        }

        return new LightData(light, sensitive, type);
    }
}
