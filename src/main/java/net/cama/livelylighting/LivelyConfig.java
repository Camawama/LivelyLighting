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
    /** Block definitions (0.5.0): what a luminous block on a ship or contraption is as a dynamic light (a headlight). */
    public static final Path BLOCK_DEFINITIONS_DIR = CONFIG_DIR.resolve("block_definitions");

    private static LivelyConfig instance;

    // Config Fields
    public boolean enable = true;
    // Who shows the light (0.5.0). "server_blocks": the server places light blocks for everyone, as before.
    // "client": the server places nothing; clients with the mod draw the light themselves, clients without see
    // none. "hybrid" (default): the server places light blocks only where a player whose client lacks the mod
    // can see them, and clients with the mod draw their own. Read by the server (the integrated one too);
    // every client is told the mode on login.
    public String lighting_mode = "hybrid";
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
    // Dynamic light counts as the world's own light on the server (0.6.0): mob spawning, snow and ice melting, crops
    // and anything else that asks how bright a place is, as the light blocks gave it. A point light of each source's
    // level losing a level per block, from carried, worn and burning lights and other mods' lights. Idle in
    // server_blocks mode, where the blocks are real light.
    public boolean gameplay_light = true;
    // That light stops at walls and goes round corners as a placed lamp's does, and another mod's spot light counts
    // inside its cone only (0.6.4). Off: a straight count through everything, every light a point light, as 0.6.0.
    public boolean gameplay_light_occlusion = true;

    public Experimental experimental = new Experimental();
    // The client module (0.5.0): how a client that draws its own light draws it. Read by the client only.
    public Client client = new Client();

    public static class Client {
        // "auto" (default): the shader renderer where it can run (Embeddium, no shader pack), which rebuilds no mesh when a
        // light moves, the lightmap renderer otherwise; "shader" and "lightmap" force one (the lightmap renderer puts the
        // light into the meshes, entities and particles through the light lookups: Colorful Lighting's, in colour, propagated
        // and tinted by glass); "off" draws nothing (the server's blocks, if any, still show).
        public String renderer = "auto";
        // "diamond": a level lost per block of Manhattan distance, vanilla's look; "round": per block of straight
        // distance, a round light.
        public String falloff = "diamond";
        // Lightmap renderer: chunk sections rebuilt for moved lights per tick, nearest the camera first (the rest wait;
        // through Colorful Lighting four times this many, as its sections are smaller work). Without a budget 32 lamps on
        // spinning ships asked for a thousand sections a tick and the client stalled.
        public int lightmap_rebuilds_per_tick = 12;
        // Lightmap renderer: the farthest a dynamic light reaches, blocks.
        public int lightmap_max_reach = 15;
        // Lightmap renderer: at most this many lamps on moving ships and contraptions are drawn at once, nearest first.
        public int lightmap_max_moving_lamps = 32;
        // Shader renderer: lights sent to the shaders per frame, nearest the camera first (at most 64). Every terrain
        // vertex in view evaluates every one of them, so 64 lights cost a frame several times what 16 do; 16 is the
        // usual budget of a forward renderer.
        public int shader_max_lights = 16;
        // Shader renderer: lights farther than this from the camera are left out.
        public int shader_max_distance = 64;
        // Whether a spot light also draws a faint beam in the air (off: only the surfaces it lights).
        public boolean spot_beams = false;
        // The beam's opacity at the light, 0 to 1 (0.22 before 0.6.0: far too solid).
        public double spot_beam_opacity = 0.06;
        // A server without Lively Lighting (a vanilla server, another pack) says nothing about a mode: on, this client
        // draws the light it can see anyway (its own and other players' held items, burning mobs, ships' lamps), as other
        // dynamic lighting mods do; off, nothing is drawn there. A server with an older Lively Lighting places blocks and
        // is never drawn over.
        public boolean draw_without_server = true;
        // The world's own light on this client answers the drawn dynamic light too (0.6.0): mods that read how bright the
        // player's place is (Stellar View dims its stars and dust near a light source) see a held torch as they saw its
        // light block. The meshes and Colorful Lighting's samples don't read this.
        public boolean world_light = true;
        // A lit item in an item frame on a Valkyrien Skies ship is a headlight (0.6.1): a cone along the frame's facing, turned
        // with the ship, the default cone and reach; off, it is the point light it is on the ground.
        public boolean frames_on_ships_are_headlights = true;
        // An entity carrying a light that asks to be lit by it (0.6.2; Camafy's LightSource.glow: a kart under its headlights,
        // which point away from it) is drawn at least that bright, and whoever rides it with it, with nothing round it lit. Off:
        // carriers are lit only by what reaches them.
        public boolean lit_carriers = true;
        // Whoever holds a spot light (a flashlight) is drawn at least this bright in its colour, 0 to 15, 0 for off (0.6.5):
        // the beam starts at the holder and points away, so nothing of it lights them. The beam itself never lights its
        // holder. Needs lit_carriers.
        public int spot_holder_glow = 6;
        // Xaero's minimap and World Map redraw the chunks a dynamic light left or moved in (0.6.5): they read the world's
        // light (world_light), but redraw a chunk only when told of a change, and kept the light they had drawn.
        public boolean map_refresh = true;
        // With experimental.cluster_growing: lights near each other merge on the client as the server's light blocks do
        // (0.6.3): point lights of entities, dropped items and other mods closer than experimental.cluster_merge_distance
        // to one another, with nothing solid between them, add one larger light in their middle. It is their light added
        // up by how close they are (0.6.4): two torches side by side give a light three levels brighter than one, and the
        // nearer the merge distance they stand the less it adds, down to nothing where they part. Up to 15 plus
        // experimental.max_influence_radius (full light out to the blocks above 15 with Colorful Lighting 2.10.1).
        // Off: every light is drawn on its own, and the merged lights there are ease out.
        public boolean cluster_merging = true;
        // Lamps on ships and contraptions (a burning ship's fire among them) join a merged light that has a carried light
        // in it, counting as far as the carried lights do (0.6.4). Lamps alone never merge. Off: lamps stay out.
        public boolean cluster_lamps = true;
        // Merged lights drawn at once, nearest the camera first (0.6.4; 6 before, 16 at most: Colorful Lighting 2.10.2
        // keeps sixteen slabs for lights that reach beyond 15 blocks). A nearer group takes the farthest one's place,
        // which eases out first.
        public int max_merged_lights = 12;
        // Shader renderer: a new light stays dark until Colorful Lighting has worked out where its light may go, at most
        // six ticks, and is then let up over two (0.6.4). Off: it shows at once and shines through walls for a tick or two.
        public boolean hold_new_lights = true;
        // Shader renderer: how dynamic light passes through blocks. "voxel" (Colorful Lighting 2.9.0) propagates each light
        // through a 96-block cube of the blocks round the camera the way a placed lamp's light spreads: a level lost per
        // block along the path, stopped by walls, tinted by stained glass, round corners, with ships' blocks included; the
        // look of Minecraft's own light. "off" lets light through everything, as other dynamic lighting mods do.
        public String transmission = "voxel";
        // Lightmap renderer with Colorful Lighting: point lights are propagated by its engine as lamps of their colour, so
        // their light stops at walls as a block's does and shader packs read it as block light. Off: a straight-line light
        // through walls, cheaper when many lights move.
        public boolean lightmap_propagate = true;
    }

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
            net.cama.livelylighting.source.BlockLightDefs.generateDefaults();
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
