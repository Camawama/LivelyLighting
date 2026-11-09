package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lightmap renderer without Colorful Lighting: the client's lights as white block light. Mesh builders, entity and
 * particle renderers ask {@link #blockLightAt} through the mixins and get the vanilla block light raised to what the lights
 * give there; chunk sections a light moved in or out of are rebuilt, nearest the camera first, under
 * {@code client.lightmap_rebuilds_per_tick}. With Colorful Lighting present this class is idle: Colorful Lighting draws the
 * lights in colour through its own sampling.
 */
public final class StandaloneLights {
    private static volatile boolean active;
    /** The lights bucketed by the sections they can reach, rebuilt each tick; read from chunk-build threads. */
    private static volatile Map<Long, DynamicLight[]> buckets = Collections.emptyMap();
    private static Map<Long, int[]> anchors = new HashMap<>();
    private static final Set<Long> pendingDirty = new HashSet<>();

    private StandaloneLights() {}

    public static boolean active() {
        return active;
    }

    static void setActive(boolean value) {
        active = value;
        if (!value) {
            buckets = Collections.emptyMap();
            anchors = new HashMap<>();
            pendingDirty.clear();
        }
    }

    /** The block light (0 to 15) the dynamic lights give a block, or 0. Thread-safe. */
    public static int blockLightAt(int x, int y, int z) {
        Map<Long, DynamicLight[]> b = buckets;
        if (b.isEmpty()) return 0;
        DynamicLight[] lights = b.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (lights == null) return 0;
        double best = 0;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        for (DynamicLight light : lights) {
            double l = light.levelAt(cx, cy, cz);
            if (l > best) best = l;
        }
        return (int) Math.min(15, Math.round(best));
    }

    /** The lights of this tick: buckets them, finds what moved, and rebuilds under the budget. Render thread. */
    static void publish(ClientLevel level, List<DynamicLight> lights, LivelyConfig config) {
        int maxReach = Math.max(1, config.client.lightmap_max_reach);
        Map<Long, List<DynamicLight>> lists = new HashMap<>();
        Map<Long, int[]> now = new HashMap<>();
        Set<Long> dirty = new HashSet<>(pendingDirty);
        for (DynamicLight light : lights) {
            int reach = (int) Math.ceil(Math.min(maxReach, light.range));
            int minX = SectionPos.blockToSectionCoord((int) Math.floor(light.pos.x - reach)), maxX = SectionPos.blockToSectionCoord((int) Math.floor(light.pos.x + reach));
            int minY = SectionPos.blockToSectionCoord((int) Math.floor(light.pos.y - reach)), maxY = SectionPos.blockToSectionCoord((int) Math.floor(light.pos.y + reach));
            int minZ = SectionPos.blockToSectionCoord((int) Math.floor(light.pos.z - reach)), maxZ = SectionPos.blockToSectionCoord((int) Math.floor(light.pos.z + reach));
            for (int sx = minX; sx <= maxX; sx++)
                for (int sy = minY; sy <= maxY; sy++)
                    for (int sz = minZ; sz <= maxZ; sz++)
                        lists.computeIfAbsent(SectionPos.asLong(sx, sy, sz), k -> new ArrayList<>(2)).add(light);
            int[] anchor = {(int) Math.floor(light.pos.x), (int) Math.floor(light.pos.y), (int) Math.floor(light.pos.z), Math.round(light.level), reach};
            now.put(light.ownerKey, anchor);
            int[] before = anchors.get(light.ownerKey);
            if (before == null || before[0] != anchor[0] || before[1] != anchor[1] || before[2] != anchor[2] || before[3] != anchor[3]) {
                if (before != null) around(dirty, before);
                around(dirty, anchor);
            }
        }
        for (Map.Entry<Long, int[]> gone : anchors.entrySet()) {
            if (!now.containsKey(gone.getKey())) around(dirty, gone.getValue());
        }
        anchors = now;
        Map<Long, DynamicLight[]> built = new HashMap<>(lists.size());
        for (Map.Entry<Long, List<DynamicLight>> e : lists.entrySet()) built.put(e.getKey(), e.getValue().toArray(new DynamicLight[0]));
        buckets = built;
        rebuild(level, dirty, Math.max(1, config.client.lightmap_rebuilds_per_tick));
    }

    private static void around(Set<Long> dirty, int[] anchor) {
        int reach = anchor[4];
        for (int sx = (anchor[0] - reach) >> 4; sx <= (anchor[0] + reach) >> 4; sx++)
            for (int sy = (anchor[1] - reach) >> 4; sy <= (anchor[1] + reach) >> 4; sy++)
                for (int sz = (anchor[2] - reach) >> 4; sz <= (anchor[2] + reach) >> 4; sz++)
                    dirty.add(SectionPos.asLong(sx, sy, sz));
    }

    private static void rebuild(ClientLevel level, Set<Long> dirty, int budget) {
        pendingDirty.clear();
        if (dirty.isEmpty()) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        List<Long> sorted = new ArrayList<>(dirty);
        sorted.sort((a, b) -> Double.compare(dist(a, cam), dist(b, cam)));
        int done = 0;
        for (long key : sorted) {
            if (done >= budget) {
                pendingDirty.add(key);
                continue;
            }
            SectionPos pos = SectionPos.of(key);
            if (pos.y() < level.getMinSection() || pos.y() >= level.getMaxSection()) continue;
            Minecraft.getInstance().levelRenderer.setSectionDirty(pos.x(), pos.y(), pos.z());
            done++;
        }
    }

    private static double dist(long key, Vec3 cam) {
        SectionPos p = SectionPos.of(key);
        double dx = p.minBlockX() + 8 - cam.x, dy = p.minBlockY() + 8 - cam.y, dz = p.minBlockZ() + 8 - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
