package net.cama.livelylighting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

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

    private static final Logger LOGGER = LogUtils.getLogger();
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
    // Re-check UNLIT non-player entities for new light sources only every N runs,
    // staggered by entity id (1 = every run). Entities that are already lit are
    // still processed every run, so existing lights, trails and fade-outs are
    // unaffected — a mob's light can merely ignite up to N-1 ticks late. TNT and
    // creepers are always exempt so their flash timing stays exact.
    public int unlit_entity_check_interval = 2;

    // At most this many entities may ignite (transition from unlit to lit) per
    // engine run; 0 = unlimited. Spawning into a world where dozens of sources
    // become visible at once used to place every light block in the same tick —
    // one huge light-propagation burst on the client and a visible freeze. With
    // a budget the backlog drains over a few ticks as a quick cascade instead.
    // Already-lit sources are unaffected; players, TNT and creepers are never
    // budgeted.
    public int max_ignitions_per_tick = 10;

    // Distance-based LOD. A lit entity farther (Chebyshev) than lod_start_distance
    // from every player refreshes its light only every N runs; N grows by 1 per
    // lod_distance_step blocks beyond the start, capped at lod_max_interval.
    // Between refreshes the entity's light block is frozen exactly in place, so a
    // skipped run emits zero block updates — and block updates are the entire
    // client-side cost of a dynamic light. Players, TNT, creepers and
    // shipyard-resident sources always run at full rate.
    public boolean distance_lod = true;
    public int lod_start_distance = 32;
    public int lod_distance_step = 16;
    public int lod_max_interval = 8;
    // 0 = disabled. Otherwise entity light sources farther than this from every
    // player are dropped entirely (the light fades out through the normal path
    // and re-ignites on approach). Applied on top of the view-distance cap; keep
    // it well above the lod distances or lights visibly pop at night.
    public int light_cull_distance = 0;

    public boolean auto_detect_block_light = true;
    public List<String> auto_detect_blacklist = new ArrayList<>(Arrays.asList(
            "minecraft:light"
    ));
    
    public boolean vs_support = false; // Disabled by default due to instability
    // Small ships take turns when many of them need their light changed at once. A light near a
    // ship keeps a light block in that ship's shipyard and re-levels or moves it as the light or
    // the ship moves: a block change and a light update on every client, per ship. A held torch
    // walked past hundreds of one-block ships became hundreds of those a second, and the client
    // spent its frames on them (lag, and the torch's own light stuttering with it). So of the
    // ships no larger than small_ship_max_blocks (the volume of the box their blocks fill), at
    // most small_ship_updates_per_tick have their lights changed in one engine run: the ones whose
    // light is most wrong and nearest a player first, each brought fully up to date in that one
    // go (no fade steps). The others keep exactly the light they have until their turn. While no
    // more small ships than that need a change in the same run, nothing is deferred and nothing
    // differs. Lights in the world (the player's own included) and larger ships are never
    // deferred. 0 = no budget.
    public int small_ship_updates_per_tick = 6;
    public int small_ship_max_blocks = 8;
    // At most this many of a ship's own lamps (its luminous blocks) are projected into the world as
    // it moves: the brightest first, then the ones nearest a player (chosen again every three
    // seconds with the ship's lamp scan); the rest give the world no light (the ship's own blocks
    // are lit by its lamps as ever). Every projected lamp is a light block that moves with the
    // ship, a block change on the server and a light update on every client each time it crosses a
    // cell, so a ship carrying very many lamps cost clients their frames (2026-10-17). 0 = no cap.
    public int max_lamps_per_ship = 32;
    // Whether light blocks (minecraft:light) found on ships and in the world count as lamps that
    // light nearby ships and the world around a ship. Off, only genuinely luminous blocks do: a
    // light block of the mod's own left behind by a crash (its record lost with the unsaved data)
    // can otherwise keep projecting light forever, which no purge of the records can reach.
    public boolean light_blocks_are_lamps = false;
    // A Valkyrien Skies ship on fire (Camafy's ship fire) is a light like a burning entity: up to
    // four lights at its burning parts (spread over it when it all burns), lighting the world round
    // it and its own blocks as it moves, gone within a second of the fire going out. Needs vs_support.
    public boolean ship_fire_lights = true;
    // The mod's lights inside a ship go with whatever copies the ship: a VMod schematic pasted or a
    // world area assembled into a ship takes them along, as light blocks the mod has no record of,
    // which then shine in the copy for good (the owner's ships "stuck full bright" in an old world,
    // 2026-10-05). On: the first time in a session a ship comes near a player, the light blocks in
    // and round it that the mod has no record of and no player placed by hand are taken out (the
    // mod puts back at once whatever light it wants there). Needs vs_support.
    public boolean clean_copied_ship_lights = true;
    // Clients are told what each of the mod's lights stands for (the holder and what it holds, a
    // lamp, a burning ship), so Colorful Lighting gives the light its source's colour wherever it is:
    // a fast holder's trail, a light fading out after it was put away, several lamps merged into one
    // light, the dim edge of a lamp's light on a ship (all white before, 2026-10-12). Needs the mod
    // on the client too; clients without it keep Colorful Lighting's own guess.
    public boolean light_source_colors = true;
    /** With Curios: an item with a light level worn in a curio slot (a torch or lantern on a belt) lights its wearer. */
    public boolean curios_lighting = true;

    public Experimental experimental = new Experimental();

    public static class Experimental {
        // Sub-block interpolation: treats the source as a continuous point light and
        // gives every nearby block ceil(level - manhattan distance to the entity),
        // so light levels crossfade exactly with movement (straddling two blocks
        // lights both). Smoothest possible with vanilla light blocks; more block
        // updates than normal smoothing. Requires smoothing to be enabled.
        public boolean ultra_smoothing = false;
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
            JsonObject onDisk = null;
            instance = null;
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                onDisk = JsonParser.parseReader(reader).getAsJsonObject();
                instance = GSON.fromJson(onDisk, LivelyConfig.class);
            } catch (Exception e) {
                // Unreadable, malformed, or the wrong shape. Set the broken file
                // aside for inspection and regenerate defaults instead of letting
                // the parse exception escape into the server tick.
                LOGGER.error("Could not parse {}; moving it to {}.broken and regenerating defaults",
                        CONFIG_FILE, CONFIG_FILE.getName(), e);
                backupBrokenConfig();
                onDisk = null;
            }
            if (instance == null) {
                instance = new LivelyConfig();
                save();
                return;
            }
            if (instance.experimental == null) {
                instance.experimental = new Experimental();
            }
            boolean changed = migrateLegacyFields();
            // Auto-upgrade outdated files: gson quietly fills options missing
            // from the file with their defaults, but never writes them back, so
            // the file drifts out of sync with the mod and new options stay
            // invisible to the user. Whenever the file's key set differs from
            // the current schema — options added since it was written, or stale
            // ones it still carries — rewrite it: user values kept, new options
            // at their defaults, removed ones dropped.
            if (onDisk != null && schemaDiffers(onDisk, GSON.toJsonTree(instance).getAsJsonObject())) {
                changed = true;
                LOGGER.info("Config file {} is outdated; rewriting it with current options (existing settings kept)",
                        CONFIG_FILE.getName());
            }
            if (changed) {
                save();
            }
        } else {
            instance = new LivelyConfig();
            save();
        }
    }

    /**
     * Whether the config file's structure disagrees with the current schema in
     * either direction: a key the schema has but the file lacks (option added
     * since the file was written) or a key the file has but the schema doesn't
     * (option removed or renamed). Values are never compared — user settings are
     * not the schema. Nested objects recurse; arrays are leaves.
     */
    private static boolean schemaDiffers(JsonObject onDisk, JsonObject current) {
        for (String key : current.keySet()) {
            JsonElement fileValue = onDisk.get(key);
            if (fileValue == null) return true;
            JsonElement currentValue = current.get(key);
            if (currentValue.isJsonObject() && fileValue.isJsonObject()
                    && schemaDiffers(fileValue.getAsJsonObject(), currentValue.getAsJsonObject())) {
                return true;
            }
        }
        for (String key : onDisk.keySet()) {
            if (!current.has(key)) return true;
        }
        return false;
    }

    private static void backupBrokenConfig() {
        try {
            Files.move(CONFIG_FILE.toPath(), CONFIG_FILE.toPath().resolveSibling(CONFIG_FILE.getName() + ".broken"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Could not back up broken config file", e);
        }
    }

    // Carry smoothing settings from pre-0.3.2 config files (experimental.*) to the
    // top level. Returns whether anything was migrated; the caller saves.
    private static boolean migrateLegacyFields() {
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
        return migrated;
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
