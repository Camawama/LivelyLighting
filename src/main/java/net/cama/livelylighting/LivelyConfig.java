package net.cama.livelylighting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LivelyConfig {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("livelylighting.json").toFile();

    private static LivelyConfig instance;

    // Config Fields
    public boolean enable = true;
    public int max_light_sources = 500;
    public boolean enchanted_items_glow = true;
    public boolean fire_aspect_glow = true;
    public boolean enable_entity_lights = true;
    
    public boolean creeper_lighting = true;
    public boolean tnt_lighting = true;
    
    public boolean vs_support = false; // Disabled by default due to instability

    public Experimental experimental = new Experimental();

    public static class Experimental {
        public boolean mode = false;
        public boolean smoothing = true;
        public boolean cluster_growing = true;
        public double cluster_merge_distance = 6.0;
        public int max_influence_radius = 3;
        public int trail_decay_rate = 2;
        public int fade_in_rate = 5;
    }

    public List<String> custom_items = new ArrayList<>(Arrays.asList(
            "minecraft:torch|14|true|FLAME",
            "minecraft:soul_torch|10|true|FLAME",
            "minecraft:lantern|15|false|FLAME",
            "minecraft:soul_lantern|10|false|FLAME",
            "minecraft:campfire|15|true|FLAME",
            "minecraft:soul_campfire|10|true|FLAME",
            "minecraft:lava_bucket|15|false|FLAME",
            "minecraft:blaze_rod|10|false|FLAME",
            "minecraft:magma_cream|8|false|FLAME",
            "minecraft:glowstone|15|false|GLOW",
            "minecraft:glowstone_dust|8|false|GLOW",
            "minecraft:sea_lantern|15|false|GLOW",
            "minecraft:jack_o_lantern|15|false|GLOW",
            "minecraft:shroomlight|15|false|GLOW",
            "minecraft:end_rod|14|false|GLOW",
            "minecraft:beacon|15|false|GLOW",
            "minecraft:conduit|15|false|GLOW",
            "minecraft:nether_star|10|false|GLOW",
            "minecraft:ender_chest|7|false|GLOW",
            "minecraft:enchanting_table|10|false|GLOW",
            "minecraft:end_crystal|15|false|GLOW"
    ));

    public List<String> custom_entities = new ArrayList<>(Arrays.asList(
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
    ));

    // Static Access
    public static LivelyConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                instance = GSON.fromJson(reader, LivelyConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                instance = new LivelyConfig();
            }
        } else {
            instance = new LivelyConfig();
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
}
