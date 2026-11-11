package net.cama.livelylighting.dynamiclighting.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.data.ModTags;
import net.cama.livelylighting.dynamiclighting.RegexLightData;
import net.cama.livelylighting.dynamiclighting.entity.RegexEntityData;
import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.cama.livelylighting.source.LightShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * What gives light: the item and entity definitions ({@code config/livelylighting/item_definitions}, {@code
 * entity_definitions}) and the rules on top of them (burning, glowing, creepers, TNT, tags, enchantments, auto-detected
 * block items, water and suffocation). Pure reads of the world; used by the server module to place light and by the
 * client module to draw it, so both sides answer the same for the same entity. Thread-safe: the caches are concurrent and
 * the per-tick suffocation memo is per thread (the client and the integrated server share this class in singleplayer).
 */
public class LightCalculator {

    private static final Map<Item, LightData> itemCache = new ConcurrentHashMap<>();
    /** Definitions of the lowest priority (LambDynamicLights-format resource files on the client): used when nothing else names the item. */
    private static volatile Map<Item, LightData> fallbackItems = Map.of();
    private static final List<RegexLightData> regexItems = new CopyOnWriteArrayList<>();

    private static final Map<EntityType<?>, LightData> entityCache = new ConcurrentHashMap<>();
    private static final List<RegexEntityData> regexEntities = new CopyOnWriteArrayList<>();

    // Constants to ensure object equality and reduce garbage
    private static final List<ParticleType<?>> FLAME_PARTICLES = Collections.singletonList(ParticleTypes.FLAME);
    // A body catching fire whooshes (the fire charge's flame) without a flint-and-steel click: nothing struck it.
    private static final List<SoundData> FLAME_SOUNDS = Collections.singletonList(new SoundData(SoundEvents.FIRECHARGE_USE, 0.2f, 1.0f));
    private static final List<SoundData> FLAME_EXTINGUISH_SOUNDS = Collections.singletonList(new SoundData(SoundEvents.GENERIC_EXTINGUISH_FIRE, 0.5f, 1.0f));

    private static final List<ParticleType<?>> SPARK_PARTICLES = Collections.singletonList(ParticleTypes.ELECTRIC_SPARK);
    private static final List<SoundData> SPARK_SOUNDS = Collections.singletonList(new SoundData(SoundEvents.BEACON_ACTIVATE, 0.5f, 2.0f));

    private static final LightData ENCHANTED_LIGHT_DATA = new LightData(6, false, SPARK_PARTICLES, Collections.emptyList(), Collections.emptyList());
    public static final LightData EMPTY_LIGHT_DATA = new LightData(0, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    /** A tagged flashlight with no definition of its own: a spot light of level 14 with the default cone. */
    private static final LightData FLASHLIGHT_LIGHT_DATA = new LightData(14, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            LightShape.SPOT, LightShape.DEFAULT_CONE_DEGREES, LightShape.DEFAULT_SPOT_RANGE, -1);

    private static final Gson GSON = new Gson();

    /** Replaces the fallback item definitions (the client's resource reload); the item cache is dropped so they take. */
    public static void setFallbackItemDefinitions(Map<Item, LightData> definitions) {
        fallbackItems = definitions == null ? Map.of() : Map.copyOf(definitions);
        itemCache.clear();
    }

    /** Every exact-id item definition by its id, resolved when the item is looked up: an item registered after the definitions were read (a KubeJS item) still finds its definition (0.6.1). */
    private static final Map<ResourceLocation, LightData> itemsById = new java.util.concurrent.ConcurrentHashMap<>();
    /** Definitions that apply only to stacks whose tag holds given values ({@code "nbt": {...}} in the file), by item id, in file order (0.6.2). */
    private static final Map<ResourceLocation, List<NbtLightData>> nbtItems = new java.util.concurrent.ConcurrentHashMap<>();

    private record NbtLightData(net.minecraft.nbt.CompoundTag required, LightData data) {}

    public static void reloadConfig() {
        itemCache.clear();
        itemsById.clear();
        nbtItems.clear();
        regexItems.clear();
        entityCache.clear();
        regexEntities.clear();
        loadItemDefinitions();
        loadEntityDefinitions();
        net.cama.livelylighting.source.BlockLightDefs.reload();
    }

    private static LightData plain(int level) {
        return new LightData(level, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /** The optional shape keys of a definition: {@code shape}, {@code cone_degrees}, {@code range}, {@code color}, {@code color_nbt}. */
    static LightData withShape(JsonObject json, int level, boolean sensitive, List<ParticleType<?>> particles, List<SoundData> sounds, List<SoundData> extinguish) {
        LightShape shape = json.has("shape") ? LightShape.parse(json.get("shape").getAsString(), LightShape.POINT) : LightShape.POINT;
        float cone = json.has("cone_degrees") ? json.get("cone_degrees").getAsFloat() : 0f;
        float range = json.has("range") ? json.get("range").getAsFloat() : 0f;
        int color = json.has("color") ? parseColor(json.get("color").getAsString()) : -1;
        String colorNbt = json.has("color_nbt") && json.get("color_nbt").isJsonPrimitive() ? json.get("color_nbt").getAsString() : null;
        return new LightData(level, sensitive, particles, sounds, extinguish, shape, cone, range, color, colorNbt);
    }

    /**
     * {@code #RRGGBB}, {@code RRGGBB} or {@code 0xRRGGBB}; since 0.7.0 also {@code uv}, {@code blacklight} or
     * {@code ultraviolet} ({@link LightData#BLACKLIGHT}) and the sixteen dye names ({@code red}, {@code light_blue}, ...);
     * -1 for anything else (the default colour).
     */
    public static int parseColor(String text) {
        if (text == null) return -1;
        String s = text.trim();
        String name = s.toLowerCase(java.util.Locale.ROOT);
        if (name.equals("uv") || name.equals("blacklight") || name.equals("ultraviolet")) return LightData.BLACKLIGHT;
        // a light that absorbs (0.8.0): all light, or the channels of a colour after "absorb:" ("absorb:#00FFFF" leaves red)
        if (name.equals("absorb") || name.equals("dark") || name.equals("darkness") || name.equals("void")) return LightData.ABSORB | 0xFFFFFF;
        if (name.startsWith("absorb:")) {
            int of = parseColor(s.substring("absorb:".length()));
            return of >= 0 && of != LightData.BLACKLIGHT && !LightData.isAbsorber(of) ? LightData.ABSORB | of : LightData.ABSORB | 0xFFFFFF;
        }
        net.minecraft.world.item.DyeColor dye = net.minecraft.world.item.DyeColor.byName(name, null);
        if (dye != null) return dye.getTextColor() & 0xFFFFFF;
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.length() != 6) return -1;
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return -1;
        }
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

                List<ParticleType<?>> particles = new ArrayList<>();
                if (json.has("particles")) {
                    String[] pIds = json.get("particles").getAsString().split(",");
                    for (String pId : pIds) {
                        if (particles.size() >= 3) break;
                        if (pId.trim().equals("null")) continue;
                        ParticleType<?> p = ForgeRegistries.PARTICLE_TYPES.getValue(ResourceLocation.parse(pId.trim()));
                        if (p != null) particles.add(p);
                    }
                }

                List<SoundData> sounds = parseSounds(json.has("activate_sounds") ? json.get("activate_sounds").getAsString() : "null");
                List<SoundData> extinguishSounds = parseSounds(json.has("deactivate_sounds") ? json.get("deactivate_sounds").getAsString() : "null");

                LightData data = withShape(json, level, sensitive, particles, sounds, extinguishSounds);

                if (id.startsWith("regex:")) {
                    String patternStr = id.substring(6);
                    regexItems.add(new RegexLightData(Pattern.compile(patternStr), data));
                } else {
                    ResourceLocation key = ResourceLocation.parse(id);
                    if (json.has("nbt") && json.get("nbt").isJsonObject()) {
                        // applies only to stacks whose tag holds these values (a flashlight a script switches on): matched when the stack is looked up
                        net.minecraft.nbt.CompoundTag required = net.minecraft.nbt.TagParser.parseTag(json.get("nbt").toString());
                        nbtItems.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(new NbtLightData(required, data));
                        continue;
                    }
                    itemsById.put(key, data);
                    Item item = ForgeRegistries.ITEMS.getValue(key);
                    if (item != null && item != Items.AIR) {
                        itemCache.put(item, data);
                    }
                    // else the item isn't registered yet (a KubeJS item registers after the definitions are first read): matched by id when it is looked up
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
                LightData data = withShape(json, level, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

                if (id.startsWith("regex:")) {
                    String patternStr = id.substring(6);
                    regexEntities.add(new RegexEntityData(Pattern.compile(patternStr), data));
                } else {
                    EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.parse(id));
                    if (type != null) {
                        entityCache.put(type, data);
                    }
                }

            } catch (Exception e) {
                System.err.println("LivelyLighting: Failed to load entity definition: " + file.getName());
                e.printStackTrace();
            }
        }
    }

    static List<SoundData> parseSounds(String entry) {
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
                            for (int i = 0; i < sParts.length - 2; i++) {
                                if (i > 0) sb.append(":");
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
                            for (int i = 0; i < sParts.length - 1; i++) {
                                if (i > 0) sb.append(":");
                                sb.append(sParts[i]);
                            }
                            soundId = sb.toString();
                        } catch (NumberFormatException ignored) {}
                    }
                }

                try {
                    ResourceLocation sLoc = ResourceLocation.parse(soundId);
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

    /** The brightest light an entity carries or is: its own, its hands, armour, curios, a carried block, a framed item. */
    public static LightData getBestLightLevel(Entity entity, Level level, LivelyConfig config) {
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
            // Curios (a torch or lantern on a belt): the brightest worn curio
            if (config.curios_lighting && net.cama.livelylighting.compat.CuriosLights.loaded()) {
                for (ItemStack worn : net.cama.livelylighting.compat.CuriosLights.worn(living)) {
                    LightData curio = getItemLightLevel(worn, entity, level, config);
                    if (curio.level > best.level) best = curio;
                }
            }

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

        if (entity instanceof ItemFrame itemFrame) {
            LightData frameLight = getItemLightLevel(itemFrame.getItem(), entity, level, config);
            LightData best = entityLight;
            if (frameLight.level > best.level) best = frameLight;
            return best;
        }

        return entityLight;
    }

    /**
     * Whether the caller draws lights that absorb (0.8.0): the client module does, while it gathers its lights. For everyone
     * else (the server module's light blocks, the game's rules) a definition whose colour absorbs gives no light at all.
     */
    private static final ThreadLocal<Boolean> ABSORBERS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void absorbers(boolean drawn) {
        ABSORBERS.set(drawn);
    }

    private static LightData gate(LightData data) {
        return data != null && data.level > 0 && LightData.isAbsorber(data.color) && !ABSORBERS.get() ? EMPTY_LIGHT_DATA : data;
    }

    public static LightData getEntityLightLevel(Entity entity, Level level, LivelyConfig config) {
        return gate(entityLightLevel(entity, level, config));
    }

    private static LightData entityLightLevel(Entity entity, Level level, LivelyConfig config) {
        LightData result = computeEntityLightLevel(entity, level, config);
        // Suffocation reads world state and is the expensive test here, so it
        // runs last and only for entities that would actually emit: the vast
        // majority of entities are unlit and skip it entirely.
        if (result.level > 0 && isSuffocating(entity, level)) {
            return EMPTY_LIGHT_DATA;
        }
        return result;
    }

    /** A burning entity's light level, and a burning ship's (ship_fire_lights). */
    public static final int BURNING_LIGHT_LEVEL = 15;

    private static LightData computeEntityLightLevel(Entity entity, Level level, LivelyConfig config) {
        if (config.burning_entity_lighting && entity.isOnFire()) return new LightData(BURNING_LIGHT_LEVEL, false, FLAME_PARTICLES, FLAME_SOUNDS, FLAME_EXTINGUISH_SOUNDS);

        if (config.glowing_effect_lighting && entity instanceof LivingEntity living && living.hasEffect(MobEffects.GLOWING)) {
            return plain(6);
        }

        if (config.enable_entity_lights) {
            EntityType<?> type = entity.getType();
            LightData cached = entityCache.get(type);
            if (cached != null) {
                if (cached.level > 0) return cached;
            } else {
                String id = ForgeRegistries.ENTITY_TYPES.getKey(type).toString();
                LightData found = EMPTY_LIGHT_DATA;
                for (RegexEntityData regex : regexEntities) {
                    if (regex.pattern.matcher(id).matches()) {
                        found = regex.data;
                        break;
                    }
                }
                entityCache.put(type, found);
                if (found.level > 0) return found;
            }
        }

        if (config.creeper_lighting && entity instanceof Creeper creeper) {
            float swell = creeper.getSwelling(0); // 0-1
            if (swell > 0) {
                int levelVal = (int) (swell * 15);
                return plain(Math.max(1, levelVal));
            }
        }

        if (config.tnt_lighting && entity instanceof PrimedTnt tnt) {
            int fuse = tnt.getFuse();
            if ((fuse / 5) % 2 == 0) {
                return new LightData(15, false, FLAME_PARTICLES, Collections.emptyList(), Collections.emptyList());
            }
            return new LightData(0, false, FLAME_PARTICLES, Collections.emptyList(), Collections.emptyList());
        }

        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_15)) return plain(15);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_12)) return plain(12);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_9)) return plain(9);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_6)) return plain(6);
        if (entity.getType().is(ModTags.Entities.LIGHT_LEVEL_3)) return plain(3);

        if (entity instanceof Creeper creeper && creeper.isIgnited()) return plain(15);

        if (config.fire_aspect_glow && entity instanceof LivingEntity living) {
            if (EnchantmentHelper.getFireAspect(living) > 0) {
                return new LightData(9, false, FLAME_PARTICLES, Collections.emptyList(), Collections.emptyList());
            }
        }

        return EMPTY_LIGHT_DATA;
    }

    /** The definition an item has, with no holder in play (no water or suffocation): for the API and for tooltips. */
    public static LightData definitionOf(ItemStack stack, LivelyConfig config) {
        if (stack.isEmpty() || stack.getItem() == Items.LIGHT) return EMPTY_LIGHT_DATA;
        LightData data = lookupStack(stack, config);
        if (data.level > 0) return data;
        return config.enchanted_items_glow && stack.isEnchanted() ? ENCHANTED_LIGHT_DATA : EMPTY_LIGHT_DATA;
    }

    /** The definition for a stack: one keyed to its NBT first (0.6.2), else the item's; in the colour its tag names (0.7.0). */
    private static LightData lookupStack(ItemStack stack, LivelyConfig config) {
        if (!nbtItems.isEmpty()) {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            List<NbtLightData> defs = key == null ? null : nbtItems.get(key);
            if (defs != null) {
                net.minecraft.nbt.CompoundTag tag = stack.getTag();
                for (NbtLightData def : defs) {
                    if (net.minecraft.nbt.NbtUtils.compareNbt(def.required, tag, true)) return tinted(def.data, stack);
                }
            }
        }
        return tinted(lookupItem(stack.getItem(), config), stack);
    }

    /** What a tag's colour names came to, so a held stack's is parsed once. */
    private static final Map<String, Integer> tagColours = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A definition in the colour its stack names (0.7.0): the definition's {@code color_nbt} is a key of the stack's tag,
     * holding a colour as text (whatever {@link #parseColor} reads: {@code "#FF0000"}, {@code "red"}, {@code "uv"}) or as a
     * number ({@code 0xRRGGBB}, {@link LightData#BLACKLIGHT} for ultraviolet). Without the key in the tag, or with a
     * value that is no colour, the definition's own colour stays.
     */
    private static LightData tinted(LightData data, ItemStack stack) {
        if (data.colorNbt == null || data.level <= 0) return data;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null) return data;
        net.minecraft.nbt.Tag value = tag.get(data.colorNbt);
        if (value == null) return data;
        int color;
        if (value instanceof net.minecraft.nbt.NumericTag number) {
            int n = number.getAsInt();
            color = n == LightData.BLACKLIGHT ? n : n < 0 ? -1 : (n & LightData.ABSORB) != 0 ? LightData.ABSORB | (n & 0xFFFFFF) : n & 0xFFFFFF;
        } else {
            String text = value.getAsString();
            Integer known = tagColours.get(text);
            if (known == null) {
                if (tagColours.size() > 1024) tagColours.clear();
                known = parseColor(text);
                tagColours.put(text, known);
            }
            color = known;
        }
        return color < 0 ? data : data.withColor(color);
    }

    /** The colour a stack's definition gives its light, or -1 where it leaves it to Colorful Lighting. */
    public static int definedColor(ItemStack stack, LivelyConfig config) {
        if (stack == null || stack.isEmpty() || stack.getItem() == Items.LIGHT) return -1;
        LightData data = lookupStack(stack, config);
        return data.level > 0 ? data.color : -1;
    }

    private static LightData lookupItem(Item item, LivelyConfig config) {
        LightData data = itemCache.get(item);
        if (data != null) return data;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key != null) data = itemsById.get(key);
        if (data != null) {
            itemCache.put(item, data);
            return data;
        }
        String id = String.valueOf(key);
        for (RegexLightData regex : regexItems) {
            if (regex.pattern.matcher(id).matches()) {
                data = regex.data;
                break;
            }
        }
        if (data == null && item.builtInRegistryHolder().is(ModTags.Items.FLASHLIGHTS)) {
            data = FLASHLIGHT_LIGHT_DATA;
        }
        if (data == null) data = fallbackItems.get(item);
        if (data == null && config.auto_detect_block_light && item instanceof BlockItem blockItem && !config.auto_detect_blacklist.contains(id)) {
            // Auto-detect block light
            Block block = blockItem.getBlock();
            BlockState defaultState = block.defaultBlockState();
            int light = defaultState.getLightEmission();
            if (light > 0) data = plain(light);
        }
        if (data == null) data = EMPTY_LIGHT_DATA;
        itemCache.put(item, data);
        return data;
    }

    public static LightData getItemLightLevel(ItemStack stack, Entity entity, Level level, LivelyConfig config) {
        return gate(itemLightLevel(stack, entity, level, config));
    }

    private static LightData itemLightLevel(ItemStack stack, Entity entity, Level level, LivelyConfig config) {
        if (stack.isEmpty()) return EMPTY_LIGHT_DATA;

        Item item = stack.getItem();
        // The light block's own item is the tool for seeing and editing light blocks. A player
        // holding it must never become a light source: every light block they broke came straight
        // back at their eyes, and a purge "came back" the next tick. Hard-coded on top of
        // auto_detect_blacklist, which a config file written before that default was added lacks.
        if (item == Items.LIGHT) return EMPTY_LIGHT_DATA;
        LightData data = lookupStack(stack, config);

        if (data.level > 0) {
            // Suffocation only matters for stacks that would actually emit
            if (isSuffocating(entity, level)) {
                return new LightData(0, false, data.particles, data.sounds, data.extinguishSounds);
            }

            if (data.waterSensitive) {
                // In water (the hand or chest under the surface), or in rain under the open sky, the light goes out.
                boolean inWater = entity.isInWater() && entity.getFluidHeight(FluidTags.WATER) > entity.getEyeHeight() - 0.3;
                boolean inRain = level.isRainingAt(entity.blockPosition()) && level.canSeeSky(entity.blockPosition());

                if (inWater || inRain) {
                    return new LightData(0, false, data.particles, data.sounds, data.extinguishSounds);
                }
            }
            return data;
        }

        if (config.enchanted_items_glow && stack.isEnchanted()) {
            // Suffocation gates enchanted glow too
            return isSuffocating(entity, level) ? EMPTY_LIGHT_DATA : ENCHANTED_LIGHT_DATA;
        }

        return EMPTY_LIGHT_DATA;
    }

    /**
     * Per-tick memo: a lit entity asks this up to 7 times per tick (body check plus every equipment slot). Per thread,
     * because the client thread and the integrated server thread run this class at once in singleplayer; an entity's
     * network id is unique within a level and the memo is keyed by level too.
     */
    private static final class SuffocationMemo {
        long time = Long.MIN_VALUE;
        Level level;
        final Map<Integer, Boolean> cache = new HashMap<>();
    }

    private static final ThreadLocal<SuffocationMemo> SUFFOCATION = ThreadLocal.withInitial(SuffocationMemo::new);

    public static boolean isSuffocating(Entity entity, Level level) {
        // Only living entities suffocate. Projectiles stuck in walls (spectral arrows), dropped items and item frames
        // keep their light; a stuck arrow's tip being embedded made isInWall() wrongly extinguish it.
        if (!(entity instanceof LivingEntity)) return false;

        SuffocationMemo memo = SUFFOCATION.get();
        long now = level.getGameTime();
        if (now != memo.time || memo.level != level) {
            memo.time = now;
            memo.level = level;
            memo.cache.clear();
        }
        Boolean cached = memo.cache.get(entity.getId());
        if (cached != null) return cached;

        // Single suffocation test at the eye block: the light goes out when the head is inside a solid block.
        BlockPos eyePos = BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ());
        BlockState state = level.getBlockState(eyePos);
        boolean result = state.isSuffocating(level, eyePos);
        memo.cache.put(entity.getId(), result);
        return result;
    }
}
