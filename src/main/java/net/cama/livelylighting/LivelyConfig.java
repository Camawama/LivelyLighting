package net.cama.livelylighting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LivelyConfig {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("livelylighting");
    private static final File CONFIG_FILE = CONFIG_DIR.resolve("livelylighting.json").toFile();
    public static final Path ITEM_DEFINITIONS_DIR = CONFIG_DIR.resolve("item_definitions");
    public static final Path ENTITY_DEFINITIONS_DIR = CONFIG_DIR.resolve("entity_definitions");

    private static LivelyConfig instance;

    // Config Fields
    public boolean enable = true;
    public int max_light_sources = 500;
    public boolean enchanted_items_glow = true;
    public boolean fire_aspect_glow = true;
    public boolean enable_entity_lights = true;
    
    public boolean creeper_lighting = true;
    public boolean tnt_lighting = true;
    public boolean burning_entity_lighting = true;
    public boolean glowing_effect_lighting = true;
    
    public boolean enable_particles = true;
    public boolean enable_sounds = true;

    public boolean smoothing = true;
    public boolean smoothing_all_entities = false;
    public int trail_decay_rate = 2;
    public int fade_in_rate = 2;
    // Run the light engine every N ticks (1 = every tick). Movement handoffs only
    // shift light by one level per block, so 2 still looks smooth on busy servers.
    public int light_update_interval = 1;

    public boolean auto_detect_block_light = true;
    public List<String> auto_detect_blacklist = new ArrayList<>(Arrays.asList(
            "minecraft:light"
    ));
    
    public boolean vs_support = false; // Disabled by default due to instability

    public Experimental experimental = new Experimental();

    public static class Experimental {
        public boolean cluster_growing = false;
        public double cluster_merge_distance = 6.0;
        public int max_influence_radius = 3;
        public double light_source_spacing = 0.0; // Minimum distance between light updates (0.0 = every block)

        // Legacy fields, migrated to the top level on load. Boxed so we can tell
        // "present in an old config file" apart from "absent"; Gson drops nulls
        // on save, so they disappear after the first migration.
        public Boolean smoothing;
        public Boolean smoothing_all_entities;
        public Integer trail_decay_rate;
        public Integer fade_in_rate;
    }

    // Default lists used for generation
    private static final List<String> DEFAULT_ITEMS = Arrays.asList(
            "minecraft:torch|14|true|minecraft:flame|minecraft:block.fire.ambient|minecraft:block.fire.extinguish",
            "minecraft:soul_torch|10|true|minecraft:soul_fire_flame|minecraft:block.soul_fire.ambient|minecraft:block.fire.extinguish",
            "minecraft:lantern|15|false|minecraft:flame|null|null",
            "minecraft:soul_lantern|10|false|minecraft:soul_fire_flame|null|null",
            "minecraft:campfire|15|true|minecraft:flame|minecraft:block.campfire.crackle|minecraft:block.fire.extinguish",
            "minecraft:soul_campfire|10|true|minecraft:soul_fire_flame|minecraft:block.campfire.crackle|minecraft:block.fire.extinguish",
            "minecraft:lava_bucket|15|false|minecraft:lava|minecraft:block.lava.pop|null",
            "minecraft:blaze_rod|10|false|minecraft:flame|null|null",
            "minecraft:magma_cream|8|false|minecraft:flame|null|null",
            "minecraft:glowstone|15|false|minecraft:end_rod|null|null",
            "minecraft:glowstone_dust|8|false|minecraft:end_rod|null|null",
            "minecraft:sea_lantern|15|false|minecraft:end_rod|null|null",
            "minecraft:jack_o_lantern|15|false|minecraft:flame|null|null",
            "minecraft:shroomlight|15|false|minecraft:end_rod|null|null",
            "minecraft:end_rod|14|false|minecraft:end_rod|null|null",
            "minecraft:beacon|15|false|minecraft:end_rod|minecraft:block.beacon.ambient|null",
            "minecraft:conduit|15|false|minecraft:nautilus|minecraft:block.conduit.ambient|null",
            "minecraft:nether_star|10|false|minecraft:end_rod|null|null",
            "minecraft:ender_chest|7|false|minecraft:portal|minecraft:block.ender_chest.open|null",
            "minecraft:enchanting_table|10|false|minecraft:enchant|null|null",
            "minecraft:end_crystal|15|false|minecraft:end_rod|null|null"
    );

    private static final List<String> DEFAULT_ENTITIES = Arrays.asList(
            "minecraft:blaze|15",
            "minecraft:magma_cube|10",
            "minecraft:spectral_arrow|8",
            "minecraft:glow_item_frame|8",
            "minecraft:glow_squid|10",
            "minecraft:allay|7",
            "minecraft:wither_skull|10",
            "minecraft:dragon_fireball|15",
            "minecraft:shulker_bullet|10",
            "minecraft:eye_of_ender|10",
            "minecraft:end_crystal|15"
    );

    // Static Access
    public static LivelyConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            if (!Files.exists(ITEM_DEFINITIONS_DIR)) {
                Files.createDirectories(ITEM_DEFINITIONS_DIR);
                generateDefaultItems();
            } else if (isEmpty(ITEM_DEFINITIONS_DIR)) {
                generateDefaultItems();
            }
            
            if (!Files.exists(ENTITY_DEFINITIONS_DIR)) {
                Files.createDirectories(ENTITY_DEFINITIONS_DIR);
                generateDefaultEntities();
            } else if (isEmpty(ENTITY_DEFINITIONS_DIR)) {
                generateDefaultEntities();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                instance = GSON.fromJson(reader, LivelyConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                instance = new LivelyConfig();
            }
            if (instance == null) {
                instance = new LivelyConfig();
            }
            if (instance.experimental == null) {
                instance.experimental = new Experimental();
            }
            migrateLegacyFields();
        } else {
            instance = new LivelyConfig();
            save();
        }
    }

    // Carry smoothing settings from pre-0.3.2 config files (experimental.*) to the top level.
    private static void migrateLegacyFields() {
        Experimental exp = instance.experimental;
        boolean migrated = false;
        if (exp.smoothing != null) {
            instance.smoothing = exp.smoothing;
            exp.smoothing = null;
            migrated = true;
        }
        if (exp.smoothing_all_entities != null) {
            instance.smoothing_all_entities = exp.smoothing_all_entities;
            exp.smoothing_all_entities = null;
            migrated = true;
        }
        if (exp.trail_decay_rate != null) {
            instance.trail_decay_rate = exp.trail_decay_rate;
            exp.trail_decay_rate = null;
            migrated = true;
        }
        if (exp.fade_in_rate != null) {
            instance.fade_in_rate = exp.fade_in_rate;
            exp.fade_in_rate = null;
            migrated = true;
        }
        if (migrated) {
            save();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                return !entries.findAny().isPresent();
            }
        }
        return false;
    }
    
    private static void generateDefaultItems() {
        for (String entry : DEFAULT_ITEMS) {
            try {
                String[] parts = entry.split("\\|");
                if (parts.length >= 3) {
                    JsonObject json = new JsonObject();
                    String id = parts[0];
                    json.addProperty("item_id", id);
                    json.addProperty("light_level", Integer.parseInt(parts[1]));
                    json.addProperty("water_sensitive", Boolean.parseBoolean(parts[2]));
                    
                    if (parts.length >= 4 && !parts[3].equals("null")) {
                        json.addProperty("particles", parts[3]);
                    }
                    if (parts.length >= 5 && !parts[4].equals("null")) {
                        json.addProperty("activate_sounds", parts[4]);
                    }
                    if (parts.length >= 6 && !parts[5].equals("null")) {
                        json.addProperty("deactivate_sounds", parts[5]);
                    }
                    
                    String filename = id.replace(":", "_") + ".json";
                    File file = ITEM_DEFINITIONS_DIR.resolve(filename).toFile();
                    try (FileWriter writer = new FileWriter(file)) {
                        GSON.toJson(json, writer);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    private static void generateDefaultEntities() {
        for (String entry : DEFAULT_ENTITIES) {
            try {
                String[] parts = entry.split("\\|");
                if (parts.length >= 2) {
                    JsonObject json = new JsonObject();
                    String id = parts[0];
                    json.addProperty("entity_id", id);
                    json.addProperty("light_level", Integer.parseInt(parts[1]));
                    
                    String filename = id.replace(":", "_") + ".json";
                    File file = ENTITY_DEFINITIONS_DIR.resolve(filename).toFile();
                    try (FileWriter writer = new FileWriter(file)) {
                        GSON.toJson(json, writer);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
