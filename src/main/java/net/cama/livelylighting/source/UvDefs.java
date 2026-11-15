package net.cama.livelylighting.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What glows under ultraviolet whatever its own colours (0.10.0): {@code config/livelylighting/uv_definitions/*.json}. A
 * blacklight makes saturated colours glow by itself; a scorpion is brown and glows blue-green all the same. A file names
 * entity types and the glow they have:
 * <pre>
 * {
 *   "entity_ids": ["minecraft:spider", "regex:.*:(.*_)?scorpion", "#mypack:fluorescent"],
 *   "glow_color": "#3DFFD2",
 *   "strength": 0.9,
 *   "min_brightness": 0.0, "max_brightness": 1.0,
 *   "hue": "#FFD000", "hue_range": 30,
 *   "textures": ["mymod:textures/entity/extra_layer.png"]
 * }
 * </pre>
 * {@code entity_ids} (or one {@code entity_id}) are ids, {@code regex:} patterns over ids, or {@code #} entity type tags.
 * {@code glow_color} is a hex colour or a dye's name, {@code strength} 0 to 4 (1 by default). The four that follow are
 * optional and pick a part of the texture: only what is as bright as {@code min_brightness} to {@code max_brightness}
 * (0 to 1), and only what is within {@code hue_range} degrees of {@code hue} (a colour, or degrees round the colour wheel),
 * so the markings of a body can glow and the rest stay dark. {@code textures} are textures that glow whoever draws them (a
 * layer of a mob, a block entity's texture); the body's own texture needs no naming.
 *
 * <p>Drawn by Colorful Lighting (2.14.0), on the client, where ultraviolet reaches: the list is this client's own and
 * nothing of it goes over the network. Read at startup, by {@code /ll reload} and at every resource reload (F3 + T).
 */
public final class UvDefs {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Red, green, blue, strength; brightness from, to; hue (0..1, below 0 for any), how far from it (0..0.5). */
    public static final int PARAMETERS = 8;
    private static final float[] NONE = new float[0];

    private record Regex(Pattern pattern, float[] glow) {}

    private record Tagged(TagKey<EntityType<?>> tag, float[] glow) {}

    private static final Map<EntityType<?>, float[]> BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, float[]> BY_ID = new ConcurrentHashMap<>();
    private static volatile List<Regex> regex = List.of();
    private static volatile List<Tagged> tagged = List.of();
    private static volatile Map<ResourceLocation, float[]> textures = Map.of();
    private static final Map<EntityType<?>, float[]> RESOLVED = new ConcurrentHashMap<>();
    private static volatile int version;

    private UvDefs() {}

    private static Path dir() {
        return LivelyConfig.UV_DEFINITIONS_DIR;
    }

    /** The glow of an entity type, or null for one that is not on the list. */
    @Nullable
    public static float[] get(EntityType<?> type) {
        float[] glow = RESOLVED.get(type);
        if (glow == null) {
            glow = BY_TYPE.get(type);
            ResourceLocation key = glow == null ? ForgeRegistries.ENTITY_TYPES.getKey(type) : null;
            // by id as well: a type registered after the files were read
            if (glow == null && key != null) glow = BY_ID.get(key);
            if (glow == null && key != null) {
                String id = key.toString();
                for (Regex r : regex) {
                    if (r.pattern.matcher(id).matches()) {
                        glow = r.glow;
                        break;
                    }
                }
            }
            if (glow == null) {
                for (Tagged t : tagged) {
                    if (type.is(t.tag)) {
                        glow = t.glow;
                        break;
                    }
                }
            }
            if (glow == null) glow = NONE;
            RESOLVED.put(type, glow);
        }
        return glow == NONE ? null : glow;
    }

    /** The textures that glow whoever draws them. */
    public static Map<ResourceLocation, float[]> textures() {
        return textures;
    }

    /** Goes up whenever the list is read again. */
    public static int version() {
        return version;
    }

    /** Whether the list holds anything. */
    public static boolean any() {
        return !BY_ID.isEmpty() || !regex.isEmpty() || !tagged.isEmpty() || !textures.isEmpty();
    }

    /** The tags were bound again (joining a world, /reload): who is in a tag is asked again. */
    public static void tagsChanged() {
        RESOLVED.clear();
    }

    public static void reload() {
        Map<EntityType<?>, float[]> byType = new HashMap<>();
        Map<ResourceLocation, float[]> byId = new HashMap<>();
        List<Regex> patterns = new ArrayList<>();
        List<Tagged> tags = new ArrayList<>();
        Map<ResourceLocation, float[]> fixed = new HashMap<>();
        File[] files = dir().toFile().listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    if (json == null) continue;
                    float[] glow = glow(json);
                    if (glow == null) {
                        LOGGER.warn("[LivelyLighting] uv definition {} has no usable glow_color and is left out", file.getName());
                        continue;
                    }
                    for (String id : strings(json, "entity_ids", "entity_id")) {
                        if (id.startsWith("regex:")) {
                            patterns.add(new Regex(Pattern.compile(id.substring(6)), glow));
                        } else if (id.startsWith("#")) {
                            ResourceLocation tag = ResourceLocation.tryParse(id.substring(1));
                            if (tag != null) tags.add(new Tagged(TagKey.create(Registries.ENTITY_TYPE, tag), glow));
                        } else {
                            ResourceLocation key = ResourceLocation.tryParse(id);
                            if (key == null) continue;
                            byId.put(key, glow);
                            if (ForgeRegistries.ENTITY_TYPES.containsKey(key)) byType.put(ForgeRegistries.ENTITY_TYPES.getValue(key), glow);
                        }
                    }
                    for (String texture : strings(json, "textures", "texture")) {
                        ResourceLocation key = ResourceLocation.tryParse(texture);
                        if (key != null) fixed.put(key, glow);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[LivelyLighting] failed to load uv definition {}", file.getName(), e);
                }
            }
        }
        BY_TYPE.clear();
        BY_TYPE.putAll(byType);
        BY_ID.clear();
        BY_ID.putAll(byId);
        regex = List.copyOf(patterns);
        tagged = List.copyOf(tags);
        textures = Map.copyOf(fixed);
        RESOLVED.clear();
        version++;
    }

    private static List<String> strings(JsonObject json, String many, String one) {
        List<String> out = new ArrayList<>();
        for (String name : new String[]{many, one}) {
            JsonElement el = json.get(name);
            if (el == null) continue;
            if (el.isJsonArray()) {
                for (JsonElement part : el.getAsJsonArray()) if (part.isJsonPrimitive()) out.add(part.getAsString().trim());
            } else if (el.isJsonPrimitive()) {
                out.add(el.getAsString().trim());
            }
        }
        return out;
    }

    /** A file's glow as the eight parameters, or null when its colour is none. */
    @Nullable
    public static float[] glow(JsonObject json) {
        if (!json.has("glow_color")) return null;
        int rgb = LightCalculator.parseColor(json.get("glow_color").getAsString());
        if (rgb < 0 || rgb == LightData.BLACKLIGHT || LightData.isAbsorber(rgb)) return null;
        float strength = json.has("strength") ? json.get("strength").getAsFloat() : 1f;
        if (!(strength > 0f)) return null;
        float from = json.has("min_brightness") ? clamp01(json.get("min_brightness").getAsFloat()) : 0f;
        float to = json.has("max_brightness") ? clamp01(json.get("max_brightness").getAsFloat()) : 1f;
        float hue = -1f, range = 0f;
        if (json.has("hue")) {
            hue = hue(json.get("hue"));
            float degrees = json.has("hue_range") ? json.get("hue_range").getAsFloat() : 30f;
            range = Math.max(0f, Math.min(180f, degrees)) / 360f;
        }
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, Math.min(4f, strength), from, Math.max(from, to), hue, range};
    }

    private static float clamp01(float v) {
        return v != v ? 0f : Math.max(0f, Math.min(1f, v));
    }

    /** A hue as a part of the colour wheel, 0..1: from degrees, or from a colour's own hue; below 0 for none. */
    private static float hue(JsonElement el) {
        if (!el.isJsonPrimitive()) return -1f;
        if (el.getAsJsonPrimitive().isNumber()) {
            float degrees = el.getAsFloat() % 360f;
            return (degrees < 0f ? degrees + 360f : degrees) / 360f;
        }
        String text = el.getAsString().trim();
        if (text.isEmpty()) return -1f;
        try {
            float degrees = Float.parseFloat(text) % 360f;
            return (degrees < 0f ? degrees + 360f : degrees) / 360f;
        } catch (NumberFormatException e) {
            // a colour then
        }
        int rgb = LightCalculator.parseColor(text);
        if (rgb < 0 || rgb == LightData.BLACKLIGHT || LightData.isAbsorber(rgb)) return -1f;
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d < 1.0e-4f) return -1f;
        float h = mx == r ? (g - b) / d : mx == g ? 2f + (b - r) / d : 4f + (r - g) / d;
        h /= 6f;
        return h < 0f ? h + 1f : h;
    }

    // ---- the files written when there are none -----------------------------------------------------------------------

    /** Name, colour, strength, brightest-part floor (or -1), then the ids. What fluoresces in nature, among the mobs players meet. */
    private static final String[][] DEFAULTS = {
            // arachnids and their kin: the cuticle of a scorpion glows blue-green, and so does many a spider's and millipede's
            {"arachnids", "#3DFFD2", "0.9", "-1",
                    "regex:.*:(.*_)?scorpion(_.*)?", "regex:.*:(.*_)?spider", "alexsmobs:centipede_head", "alexsmobs:centipede_body", "alexsmobs:centipede_tail"},
            // jellyfish: the green of the crystal jelly
            {"jellyfish", "#5CFF7A", "1.0", "-1", "aquaculture:jellyfish", "alexsmobs:comb_jelly"},
            // crustaceans: a mantis shrimp's markings, the shells of crabs and lobsters
            {"crustaceans", "#9CFF6A", "0.6", "-1", "alexsmobs:mantis_shrimp", "alexsmobs:lobster", "quark:crab"},
            // fungi
            {"fungi", "#8CFF5A", "0.6", "-1", "alexsmobs:bunfungus", "aquaculture:brown_shrooma", "aquaculture:red_shrooma", "endermanoverhaul:mushroom_fields_enderman"},
            // bone glows a pale blue-white; only the bright part of the texture, so clothes and shadows stay dark
            {"bones", "#CFE4FF", "0.55", "0.4",
                    "minecraft:skeleton", "minecraft:stray", "minecraft:skeleton_horse", "alexsmobs:bone_serpent", "alexsmobs:bone_serpent_part",
                    "twilightforest:skeleton_druid", "irons_spellbooks:summoned_skeleton"},
            // frogs, and the mammals found to glow under ultraviolet: platypus, gliders, the Tasmanian devil
            {"frogs_and_mammals", "#6CFFB0", "0.5", "-1",
                    "minecraft:frog", "alexsmobs:rain_frog", "alexsmobs:platypus", "alexsmobs:sugar_glider", "alexsmobs:tasmanian_devil"},
    };

    /** Writes the default files when the folder is missing or empty. */
    public static void generateDefaults() {
        Path dir = dir();
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    if (files.findAny().isPresent()) return;
                }
            } else {
                Files.createDirectories(dir);
            }
            for (String[] entry : DEFAULTS) {
                JsonObject json = new JsonObject();
                JsonArray ids = new JsonArray();
                for (int i = 4; i < entry.length; i++) ids.add(entry[i]);
                json.add("entity_ids", ids);
                json.addProperty("glow_color", entry[1]);
                json.addProperty("strength", Float.parseFloat(entry[2]));
                float floor = Float.parseFloat(entry[3]);
                if (floor >= 0f) json.addProperty("min_brightness", floor);
                try (FileWriter w = new FileWriter(dir.resolve(entry[0] + ".json").toFile())) {
                    GSON.toJson(json, w);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[LivelyLighting] couldn't write the default uv definitions", e);
        }
    }
}
