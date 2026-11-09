package net.cama.livelylighting.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.data.ModTags;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Block definitions ({@code config/livelylighting/block_definitions/*.json}, 0.5.0): what a luminous block on a moving
 * structure (a Valkyrien Skies ship, a Create contraption) is as a dynamic light. Without one a lamp is a point light of
 * its emission in the colour Colorful Lighting gives the block. A definition gives it a {@code shape} ({@code spot} for a
 * headlight), {@code cone_degrees}, {@code range}, {@code color}, a {@code direction} ({@code facing} to take the block's
 * facing property, else a fixed direction such as {@code north}) and a {@code lit_property} (a boolean block property that
 * must be true, such as {@code lit}). Keys: {@code block_id} (an id, or {@code regex:...}). The default set makes
 * {@code minecraft:end_rod} a headlight, a 30-degree cone of range 32 along its rod. A block in the tag
 * {@code #livelylighting:headlights} is a headlight too, with the default cone, when it has no definition.
 */
public final class BlockLightDefs {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A block's definition: shape, cone, range, colour, how its direction is found, which property must be true. */
    public record BlockLightData(LightShape shape, float coneDegrees, float range, int color, @Nullable Direction fixedDirection,
                                 boolean useFacing, @Nullable String litProperty, int level) {
        /** The direction a block state points its light, or null for a point light / an unknown facing. */
        @Nullable
        public Direction directionOf(BlockState state) {
            if (shape != LightShape.SPOT) return null;
            if (useFacing) {
                for (Property<?> p : state.getProperties()) {
                    if (p instanceof DirectionProperty dp) return state.getValue(dp);
                }
                return fixedDirection;
            }
            return fixedDirection;
        }

        /** Whether this state is lit by its property (always when no property is named). */
        public boolean isLit(BlockState state) {
            if (litProperty == null) return true;
            for (Property<?> p : state.getProperties()) {
                if (p instanceof BooleanProperty bp && bp.getName().equals(litProperty)) return state.getValue(bp);
            }
            return true;
        }

        /** The light level: the definition's, else the state's emission. */
        public int levelOf(BlockState state) {
            return level > 0 ? level : state.getLightEmission();
        }
    }

    private record Regex(Pattern pattern, BlockLightData data) {}

    private static final BlockLightData TAGGED_HEADLIGHT = new BlockLightData(LightShape.SPOT, LightShape.DEFAULT_CONE_DEGREES,
            LightShape.DEFAULT_SPOT_RANGE, -1, null, true, null, 0);
    private static final Map<Block, BlockLightData> BY_BLOCK = new ConcurrentHashMap<>();
    private static final List<Regex> REGEX = new CopyOnWriteArrayList<>();
    private static final Map<Block, BlockLightData> RESOLVED = new ConcurrentHashMap<>();
    private static final BlockLightData NONE = new BlockLightData(LightShape.POINT, 0f, 0f, -1, null, false, null, 0);

    private BlockLightDefs() {}

    public static Path dir() {
        return LivelyConfig.BLOCK_DEFINITIONS_DIR;
    }

    /** Writes the default definitions when the directory is new or empty. */
    public static void generateDefaults() {
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            try (var files = Files.list(dir)) {
                if (files.findAny().isPresent()) return;
            }
            JsonObject endRod = new JsonObject();
            endRod.addProperty("block_id", "minecraft:end_rod");
            endRod.addProperty("shape", "spot");
            endRod.addProperty("cone_degrees", 30);
            endRod.addProperty("range", 32);
            endRod.addProperty("direction", "facing");
            try (FileWriter w = new FileWriter(dir.resolve("minecraft_end_rod.json").toFile())) {
                GSON.toJson(endRod, w);
            }
        } catch (IOException e) {
            LOGGER.warn("[LivelyLighting] couldn't write the default block definitions", e);
        }
    }

    public static void reload() {
        BY_BLOCK.clear();
        REGEX.clear();
        RESOLVED.clear();
        File dir = dir().toFile();
        if (!dir.exists()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                String id = json.get("block_id").getAsString();
                LightShape shape = json.has("shape") ? LightShape.parse(json.get("shape").getAsString(), LightShape.POINT) : LightShape.POINT;
                float cone = json.has("cone_degrees") ? json.get("cone_degrees").getAsFloat() : 0f;
                float range = json.has("range") ? json.get("range").getAsFloat() : 0f;
                int color = json.has("color") ? LightCalculator.parseColor(json.get("color").getAsString()) : -1;
                int level = json.has("light_level") ? json.get("light_level").getAsInt() : 0;
                String direction = json.has("direction") ? json.get("direction").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "facing";
                boolean useFacing = direction.equals("facing");
                Direction fixed = useFacing ? null : Direction.byName(direction);
                String lit = json.has("lit_property") ? json.get("lit_property").getAsString() : null;
                BlockLightData data = new BlockLightData(shape, cone, range, color, fixed, useFacing, lit, level);
                if (id.startsWith("regex:")) {
                    REGEX.add(new Regex(Pattern.compile(id.substring(6)), data));
                } else {
                    Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
                    if (block != null) BY_BLOCK.put(block, data);
                }
            } catch (Exception e) {
                LOGGER.warn("[LivelyLighting] failed to load block definition {}", file.getName(), e);
            }
        }
    }

    /** The definition for a block, or null when it is a plain lamp. */
    @Nullable
    public static BlockLightData get(Block block) {
        BlockLightData data = RESOLVED.get(block);
        if (data == null) {
            data = BY_BLOCK.get(block);
            if (data == null) {
                String id = String.valueOf(ForgeRegistries.BLOCKS.getKey(block));
                for (Regex r : REGEX) {
                    if (r.pattern.matcher(id).matches()) {
                        data = r.data;
                        break;
                    }
                }
            }
            if (data == null && block.builtInRegistryHolder().is(ModTags.Blocks.HEADLIGHTS)) data = TAGGED_HEADLIGHT;
            if (data == null) data = NONE;
            RESOLVED.put(block, data);
        }
        return data == NONE ? null : data;
    }

    /** Every block that has a definition, for the validator and the API. */
    public static List<Block> defined() {
        return new ArrayList<>(BY_BLOCK.keySet());
    }
}
