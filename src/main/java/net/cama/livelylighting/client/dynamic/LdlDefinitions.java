package net.cama.livelylighting.client.dynamic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * LambDynamicLights' item light sources from resource packs ({@code assets/<ns>/dynamiclights/item/*.json}: a {@code match}
 * of item ids, a {@code luminance} that is a number, {@code {"type":"block","block":id}} or {@code {"type":"block_self"}},
 * and {@code water_sensitive}), read on the client at every resource reload and given to the light calculator as
 * definitions of the lowest priority: Lively Lighting's own item definitions win, these fill in what a pack already wrote
 * for that mod, so packs light the same here without conversion. Tags in {@code match} are left to the tag system
 * ({@code #livelylighting:light_level/N} covers them).
 */
public final class LdlDefinitions implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final LdlDefinitions INSTANCE = new LdlDefinitions();

    private LdlDefinitions() {}

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Map<Item, LightData> found = new HashMap<>();
        Map<ResourceLocation, Resource> files = manager.listResources("dynamiclights/item", id -> id.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> e : files.entrySet()) {
            try (InputStreamReader reader = new InputStreamReader(e.getValue().open())) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                int level = luminance(json.get("luminance"));
                boolean water = json.has("water_sensitive") && json.get("water_sensitive").getAsBoolean();
                for (Item item : matched(json)) {
                    if (level <= 0) continue;
                    LightData data = new LightData(Math.min(15, level), water, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
                    found.merge(item, data, (a, b) -> a.level >= b.level ? a : b);
                }
            } catch (Exception ex) {
                LOGGER.warn("[LivelyLighting] couldn't read the LambDynamicLights item file {}: {}", e.getKey(), ex.toString());
            }
        }
        LightCalculator.setFallbackItemDefinitions(found);
        if (!found.isEmpty()) LOGGER.info("[LivelyLighting] {} item light source(s) read from LambDynamicLights-format resource files", found.size());
    }

    private static Iterable<Item> matched(JsonObject json) {
        Map<Item, Boolean> items = new HashMap<>();
        JsonElement match = json.has("match") ? json.get("match") : json.get("item");
        if (match == null) return items.keySet();
        if (match.isJsonObject()) match = match.getAsJsonObject().get("items");
        if (match == null) return items.keySet();
        if (match.isJsonArray()) {
            for (JsonElement el : match.getAsJsonArray()) add(items, el.getAsString());
        } else if (match.isJsonPrimitive()) {
            add(items, match.getAsString());
        }
        return items.keySet();
    }

    private static void add(Map<Item, Boolean> items, String id) {
        if (id == null || id.startsWith("#")) return;
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        if (item != null && item != net.minecraft.world.item.Items.AIR) items.put(item, Boolean.TRUE);
    }

    /** The luminance: a number, or a block's emission by id or of the item's own block ({@code block_self} is resolved per item below). */
    private static int luminance(JsonElement el) {
        if (el == null) return 0;
        if (el.isJsonPrimitive()) return el.getAsInt();
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (type.endsWith("block") && o.has("block")) {
                ResourceLocation key = ResourceLocation.tryParse(o.get("block").getAsString());
                Block block = key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
                return block == null ? 0 : block.defaultBlockState().getLightEmission();
            }
            if (type.endsWith("block_self")) return -1; // the item's own block: the calculator's auto-detection does that already
        }
        if (el.isJsonArray()) {
            int best = 0;
            for (JsonElement part : el.getAsJsonArray()) best = Math.max(best, luminance(part));
            return best;
        }
        return 0;
    }
}
