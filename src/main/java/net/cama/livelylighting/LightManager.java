package net.cama.livelylighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID)
public class LightManager {

    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> levelLights = new HashMap<>();
    static final Map<Long, Map<BlockPos, Integer>> shipLights = new HashMap<>();
    
    private static final Map<Item, LightData> itemCache = new HashMap<>();
    private static final List<RegexLightData> regexItems = new ArrayList<>();
    
    private static final Map<EntityType<?>, Integer> entityCache = new HashMap<>();
    private static final List<RegexEntityData> regexEntities = new ArrayList<>();
    
    private static final Map<Integer, Boolean> entityLitState = new HashMap<>();

    enum LightType {
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

    static class Cluster {
        double x, y, z;
        float strength;
        int count;
        boolean isNewSource;
        LightType type;

        void add(double ex, double ey, double ez, int light, boolean isNew, LightType t) {
            x += ex;
            y += ey;
            z += ez;
            strength += light;
            count++;
            if (isNew) {
                isNewSource = true;
                type = t;
            }
        }

        void normalize() {
            if (count > 0) {
                x /= count;
                y /= count;
                z /= count;
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
                                // Fallback to inference
                            }
                        } else {
                            // Infer type if not specified
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

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide) return;
        
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

        Map<BlockPos, Cluster> worldClusters = new HashMap<>();
        Map<Long, Map<BlockPos, Cluster>> shipClusters = new HashMap<>();
        int sourceCount = 0;

        Set<Integer> processedIds = new HashSet<>();
        
        boolean vsLoaded = ModList.get().isLoaded("valkyrienskies");

        for (Player player : level.players()) {
            if (sourceCount >= maxSources) break;
            processEntity(player, level, worldClusters, shipClusters, processedIds, config, vsLoaded);
            sourceCount++;
        }

        if (sourceCount < maxSources) {
            for (Entity entity : level.getAllEntities()) {
                if (sourceCount >= maxSources) break;
                if (entity instanceof Player) continue; 
                
                if (!shouldCheckEntity(entity, config)) continue;

                if (processEntity(entity, level, worldClusters, shipClusters, processedIds, config, vsLoaded)) {
                    sourceCount++;
                }
            }
        }
        
        if (level.getGameTime() % 100 == 0) {
            entityLitState.keySet().removeIf(id -> level.getEntity(id) == null);
            if (config.vs_support && vsLoaded) {
                VSCompat.pruneUnloadedShips(level, shipLights);
            }
        }

        // 2. Calculate Desired Light Field
        calculateLightField(level, worldClusters, worldDesiredLights, smoothing, clusterGrowing, maxRadius);
        
        if (config.vs_support && vsLoaded) {
            VSCompat.calculateShipLightFields(level, shipClusters, shipDesiredLights, worldClusters, worldDesiredLights, smoothing, clusterGrowing, maxRadius);
        }

        // 3. Apply Changes
        applyChanges(level, worldCurrentLights, worldDesiredLights, smoothing, decayRate, fadeInRate, worldClusters, false);
        
        if (config.vs_support && vsLoaded) {
            VSCompat.applyShipChanges(level, shipLights, shipDesiredLights, shipClusters, smoothing, decayRate, fadeInRate);
        }
    }
    
    static void calculateLightField(ServerLevel level, Map<BlockPos, Cluster> clusters, Map<BlockPos, Integer> desiredLights, boolean smoothing, boolean clusterGrowing, int maxRadius) {
        for (Cluster cluster : clusters.values()) {
            cluster.normalize();
            
            float strength = cluster.strength;
            if (!clusterGrowing && cluster.count > 1) {
                strength = Math.min(15, strength);
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

                    if (level.isLoaded(currentPos) && level.getBlockState(currentPos).getLightBlock(level, currentPos) < 15) {
                        for (Direction dir : Direction.values()) {
                            BlockPos neighbor = currentPos.relative(dir);
                            if (visited.add(neighbor)) {
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
        }
    }
    
    static void applyChanges(ServerLevel level, Map<BlockPos, Integer> currentLights, Map<BlockPos, Integer> desiredLights, boolean smoothing, int decayRate, int fadeInRate, Map<BlockPos, Cluster> clusters, boolean isShip) {
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
                    if (placeLight(level, pos, newLevel, true, isShip)) {
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
                        if (placeLight(level, pos, newLevel, true, isShip)) {
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

            if (placeLight(level, pos, newLevel, true, isShip)) {
                currentLights.put(pos, newLevel);
            }
        }
    }

    private static boolean processEntity(Entity entity, ServerLevel level, Map<BlockPos, Cluster> worldClusters, Map<Long, Map<BlockPos, Cluster>> shipClusters, Set<Integer> processedIds, LivelyConfig config, boolean vsLoaded) {
        if (!processedIds.add(entity.getId())) return false;

        LightData lightData = getLightLevel(entity, level, config);
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
            boolean processedByVS = false;
            if (config.vs_support && vsLoaded) {
                processedByVS = VSCompat.processEntity(entity, level, config, shipClusters, lightLevel, isNew, type);
            }
            
            if (!processedByVS) {
                double mergeDistance = config.experimental.cluster_merge_distance;
                
                // Use eye position for cluster calculation to avoid issues with ground blocks
                double x = entity.getX();
                double y = entity.getEyeY();
                double z = entity.getZ();
                
                int gridX = (int) (x / mergeDistance);
                int gridY = (int) (y / mergeDistance);
                int gridZ = (int) (z / mergeDistance);
                BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
                
                worldClusters.computeIfAbsent(gridPos, k -> new Cluster()).add(x, y, z, lightLevel, isNew, type);
            }
            return true;
        }
        return false;
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

    static boolean shouldRePlace(ServerLevel level, BlockPos pos, int targetLevel) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LIGHT)) {
            return state.getValue(LightBlock.LEVEL) != targetLevel;
        }
        return isSafeToReplace(state);
    }
    
    static boolean isSafeToReplace(BlockState state) {
        if (state.isAir()) return true;
        if (state.is(Blocks.LIGHT)) return true;
        if (state.getFluidState().isSource() && state.is(Blocks.WATER)) return true;
        return false;
    }

    static boolean placeLight(ServerLevel level, BlockPos pos, int lightLevel, boolean checkOwnership, boolean isShip) {
        if (!level.isLoaded(pos)) return false;
        
        if (isShip) {
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
        
        if (isSafeToReplace(state)) {
            boolean waterlogged = state.getFluidState().isSource() && state.is(Blocks.WATER);
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel).setValue(LightBlock.WATERLOGGED, waterlogged), flags);
            return true;
        }
        
        return false;
    }

    static void removeLight(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        
        BlockState current = level.getBlockState(pos);
        if (current.is(Blocks.LIGHT)) {
            if (current.getValue(LightBlock.WATERLOGGED)) {
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
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
