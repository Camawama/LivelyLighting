package net.cama.livelylighting.dynamiclighting.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.data.ModTags;
import net.cama.livelylighting.dynamiclighting.entity.RegexEntityData;
import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.cama.livelylighting.dynamiclighting.RegexLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class LightCalculator {

    private static final Map<Item, LightData> itemCache = new HashMap<>();
    private static final List<RegexLightData> regexItems = new ArrayList<>();
    
    private static final Map<EntityType<?>, Integer> entityCache = new HashMap<>();
    private static final List<RegexEntityData> regexEntities = new ArrayList<>();

    // Constants to ensure object equality and reduce garbage
    private static final List<net.minecraft.core.particles.ParticleType<?>> FLAME_PARTICLES = Collections.singletonList(ParticleTypes.FLAME);
    private static final List<SoundData> FLAME_SOUNDS = Arrays.asList(new SoundData(SoundEvents.FIRECHARGE_USE, 0.2f, 1.0f), new SoundData(SoundEvents.FLINTANDSTEEL_USE, 0.5f, 1.2f));
    private static final List<SoundData> FLAME_EXTINGUISH_SOUNDS = Collections.singletonList(new SoundData(SoundEvents.GENERIC_EXTINGUISH_FIRE, 0.5f, 1.0f));
    
    private static final List<net.minecraft.core.particles.ParticleType<?>> SPARK_PARTICLES = Collections.singletonList(ParticleTypes.ELECTRIC_SPARK);
    private static final List<SoundData> SPARK_SOUNDS = Collections.singletonList(new SoundData(SoundEvents.BEACON_ACTIVATE, 0.5f, 2.0f));
    
    private static final LightData ENCHANTED_LIGHT_DATA = new LightData(6, false, SPARK_PARTICLES, Collections.emptyList(), Collections.emptyList());
    private static final LightData EMPTY_LIGHT_DATA = new LightData(0, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    private static final Gson GSON = new Gson();

    public static void reloadConfig() {
        itemCache.clear();
        regexItems.clear();
        entityCache.clear();
        regexEntities.clear();
        
        LivelyConfig config = LivelyConfig.get();
        
        // Load Item Definitions
        loadItemDefinitions();
        
        // Load Entity Definitions
        loadEntityDefinitions();
    }
    
    private static void loadItemDefinitions() {
        File dir = LivelyConfig.ITEM_DEFINITIONS_DIR.toFile();
        if (!dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                
                String id = json.get("item_id").getAsString();
                int level = json.get("light_level").getAsInt();
                boolean sensitive = json.has("water_sensitive") && json.get("water_sensitive").getAsBoolean();
                
                List<net.minecraft.core.particles.ParticleType<?>> particles = new ArrayList<>();
                if (json.has("particles")) {
                    String[] pIds = json.get("particles").getAsString().split(",");
                    for (String pId : pIds) {
                        if (particles.size() >= 3) break;
                        if (pId.trim().equals("null")) continue;
                        net.minecraft.core.particles.ParticleType<?> p = ForgeRegistries.PARTICLE_TYPES.getValue(new ResourceLocation(pId.trim()));
                        if (p != null) particles.add(p);
                    }
                }
                
                List<SoundData> sounds = parseSounds(json.has("activate_sounds") ? json.get("activate_sounds").getAsString() : "null");
                List<SoundData> extinguishSounds = parseSounds(json.has("deactivate_sounds") ? json.get("deactivate_sounds").getAsString() : "null");
                
                LightData data = new LightData(level, sensitive, particles, sounds, extinguishSounds);

                if (id.startsWith("regex:")) {
                    String patternStr = id.substring(6);
                    regexItems.add(new RegexLightData(Pattern.compile(patternStr), data));
                } else {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                    if (item != null && item != net.minecraft.world.item.Items.AIR) {
                        itemCache.put(item, data);
                    }
                }
                
            } catch (Exception e) {
                System.err.println("LivelyLighting: Failed to load item definition: " + file.getName());
                e.printStackTrace();
            }
        }
    }
    
    private static void loadEntityDefinitions() {
        File dir = LivelyConfig.ENTITY_DEFINITIONS_DIR.toFile();
        if (!dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                
                String id = json.get("entity_id").getAsString();
                int level = json.get("light_level").getAsInt();
                
                if (id.startsWith("regex:")) {
                    String patternStr = id.substring(6);
                    regexEntities.add(new RegexEntityData(Pattern.compile(patternStr), level));
                } else {
                    EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
                    if (type != null) {
                        entityCache.put(type, level);
                    }
                }
                
            } catch (Exception e) {
                System.err.println("LivelyLighting: Failed to load entity definition: " + file.getName());
                e.printStackTrace();
            }
        }
    }
    
    private static List<SoundData> parseSounds(String entry) {
        List<SoundData> sounds = new ArrayList<>();
        if (!entry.equals("null")) {
            String[] sEntries = entry.split(",");
            for (String sEntry : sEntries) {
                if (sounds.size() >= 3) break;
                sEntry = sEntry.trim();
                if (sEntry.isEmpty()) continue;
                
                String[] sParts = sEntry.split(":");
                float vol = 1.0f;
                float pitch = 1.0f;
                String soundId = sEntry;
                
                if (sParts.length > 1) {
                    boolean parsedParams = false;
                    if (sParts.length > 2) {
                        try {
                            float p = Float.parseFloat(sParts[sParts.length - 1]);
                            float v = Float.parseFloat(sParts[sParts.length - 2]);
                            pitch = p;
                            vol = v;
                            StringBuilder sb = new StringBuilder();
                            for(int i=0; i<sParts.length-2; i++) {
                                if(i>0) sb.append(":");
                                sb.append(sParts[i]);
                            }
                            soundId = sb.toString();
                            parsedParams = true;
                        } catch (NumberFormatException ignored) {}
                    }
                    
                    if (!parsedParams) {
                        try {
                            float v = Float.parseFloat(sParts[sParts.length - 1]);
                            vol = v;
                            StringBuilder sb = new StringBuilder();
                            for(int i=0; i<sParts.length-1; i++) {
                                if(i>0) sb.append(":");
                                sb.append(sParts[i]);
                            }
                            soundId = sb.toString();
                        } catch (NumberFormatException ignored) {}
                    }
                }
                
                try {
                    ResourceLocation sLoc = new ResourceLocation(soundId);
                    SoundEvent s = ForgeRegistries.SOUND_EVENTS.getValue(sLoc);
                    if (s != null) {
                        sounds.add(new SoundData(s, vol, pitch));
                    }
                } catch (Exception e) {
                    System.err.println("LivelyLighting: Invalid sound resource location: " + soundId);
                }
            }
        }
        return sounds;
    }

    public static LightData getBestLightLevel(Entity entity, ServerLevel level, LivelyConfig config) {
        LightData entityLight = getEntityLightLevel(entity, level, config);
        if (entityLight.level == 15) return entityLight; // Max possible
        
        if (entity instanceof LivingEntity living) {
            LightData main = getItemLightLevel(living.getMainHandItem(), entity, level, config);
            LightData off = getItemLightLevel(living.getOffhandItem(), entity, level, config);
            LightData helm = getItemLightLevel(living.getItemBySlot(EquipmentSlot.HEAD), entity, level, config);
            LightData chest = getItemLightLevel(living.getItemBySlot(EquipmentSlot.CHEST), entity, level, config);
            LightData legs = getItemLightLevel(living.getItemBySlot(EquipmentSlot.LEGS), entity, level, config);
            LightData feet = getItemLightLevel(living.getItemBySlot(EquipmentSlot.FEET), entity, level, config);
            
            LightData best = entityLight;
            if (main.level > best.level) best = main;
            if (off.level > best.level) best = off;
            if (helm.level > best.level) best = helm;
            if (chest.level > best.level) best = chest;
            if (legs.level > best.level) best = legs;
            if (feet.level > best.level) best = feet;
            
            // Enderman check
            if (entity instanceof EnderMan enderman) {
                BlockState heldBlock = enderman.getCarriedBlock();
                if (heldBlock != null) {
                    ItemStack blockStack = new ItemStack(heldBlock.getBlock());
                    LightData blockLight = getItemLightLevel(blockStack, entity, level, config);
                    if (blockLight.level > best.level) best = blockLight;
                }
            }
            
            return best;
        }
        
        if (entity instanceof ItemEntity itemEntity) {
            return getItemLightLevel(itemEntity.getItem(), entity, level, config);
        }
        
        return entityLight;
    }

    public static LightData getEntityLightLevel(Entity entity, ServerLevel level, LivelyConfig config) {
        // Check if entity is suffocating inside a block
        if (isSuffocating(entity, level)) {
            return EMPTY_LIGHT_DATA;
        }

        if (config.burning_entity_lighting && entity.isOnFire()) return new LightData(15, false, FLAME_PARTICLES, FLAME_SOUNDS, FLAME_EXTINGUISH_SOUNDS);
        
        if (config.glowing_effect_lighting && entity instanceof LivingEntity living && living.hasEffect(MobEffects.GLOWING)) {
            return new LightData(6, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        
        if (config.enable_entity_lights) {
            EntityType<?> type = entity.getType();
            Integer cachedLevel = entityCache.get(type);
            if (cachedLevel != null) {
                if (cachedLevel > 0) return new LightData(cachedLevel, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
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
                if (foundLevel > 0) return new LightData(foundLevel, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            }
        }
        
        if (config.creeper_lighting && entity instanceof Creeper creeper) {
            float swell = creeper.getSwelling(0); // 0-1
            if (swell > 0) {
                int levelVal = (int) (swell * 15);
                return new LightData(Math.max(1, levelVal), false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            }
        }
        
        if (config.tnt_lighting && entity instanceof PrimedTnt tnt) {
            int fuse = tnt.getFuse();
            if ((fuse / 5) % 2 == 0) {
                return new LightData(15, false, FLAME_PARTICLES, Collections.emptyList(), Collections.emptyList());
            }
            return new LightData(0, false, FLAME_PARTICLES, Collections.emptyList(), Collections.emptyList());
        }

        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_15)) return new LightData(15, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_12)) return new LightData(12, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_9)) return new LightData(9, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_6)) return new LightData(6, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_3)) return new LightData(3, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        
        if (entity instanceof Creeper creeper && creeper.isIgnited()) return new LightData(15, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        
        if (config.fire_aspect_glow && entity instanceof LivingEntity living) {
             if (EnchantmentHelper.getFireAspect(living) > 0) {
                 return new LightData(9, false, FLAME_PARTICLES, Collections.emptyList(), Collections.emptyList());
             }
        }

        return EMPTY_LIGHT_DATA;
    }

    public static LightData getItemLightLevel(ItemStack stack, Entity entity, ServerLevel level, LivelyConfig config) {
        if (stack.isEmpty()) return EMPTY_LIGHT_DATA;
        
        Item item = stack.getItem();
        LightData data = itemCache.get(item);
        
        if (data == null) {
            String id = ForgeRegistries.ITEMS.getKey(item).toString();
            for (RegexLightData regex : regexItems) {
                if (regex.pattern.matcher(id).matches()) {
                    data = regex.data;
                    itemCache.put(item, data);
                    break;
                }
            }
            if (data == null) {
                // Auto-detect block light
                if (config.auto_detect_block_light && item instanceof BlockItem blockItem) {
                    // Check blacklist
                    if (!config.auto_detect_blacklist.contains(id)) {
                        Block block = blockItem.getBlock();
                        BlockState defaultState = block.defaultBlockState();
                        int light = defaultState.getLightEmission();
                        if (light > 0) {
                            data = new LightData(light, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
                            itemCache.put(item, data);
                        }
                    }
                }
                
                if (data == null) {
                    itemCache.put(item, EMPTY_LIGHT_DATA);
                    return EMPTY_LIGHT_DATA;
                }
            }
        }
        
        if (data != null) {
            // Check if entity is suffocating inside a block - GLOBAL CHECK for all items
            if (isSuffocating(entity, level)) {
                return new LightData(0, false, data.particles, data.sounds, data.extinguishSounds);
            }

            if (data.level > 0 && data.waterSensitive) {
                // Fix edge case: in water AND rain.
                // If in water, extinguish.
                // If raining and can see sky, extinguish.
                
                // Check if fluid height is significant (approx hand/chest level)
                boolean inWater = entity.isInWater() && entity.getFluidHeight(FluidTags.WATER) > entity.getEyeHeight() - 0.3;
                boolean inRain = level.isRainingAt(entity.blockPosition()) && level.canSeeSky(entity.blockPosition());
                
                if (inWater || inRain) {
                    return new LightData(0, false, data.particles, data.sounds, data.extinguishSounds);
                }
            }
            if (data.level > 0) return data;
        }
        
        if (config.enchanted_items_glow && stack.isEnchanted()) {
            return ENCHANTED_LIGHT_DATA;
        }

        return EMPTY_LIGHT_DATA;
    }
    
    private static boolean isSuffocating(Entity entity, ServerLevel level) {
        if (entity.isInWall()) return true;
        
        BlockPos eyePos = BlockPos.containing(entity.getEyePosition());
        BlockState state = level.getBlockState(eyePos);
        return state.isSuffocating(level, eyePos);
    }
}
