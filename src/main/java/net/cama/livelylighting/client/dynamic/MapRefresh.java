package net.cama.livelylighting.client.dynamic;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tells Xaero's maps that the dynamic light over a chunk changed (0.6.5, {@code client.map_refresh}). The maps read the
 * world's block light, which answers the drawn dynamic light on this client ({@code client.world_light}), so a held torch
 * shows on them. But Xaero redraws a chunk only when it hears of a change in it, and a light that moves on or goes out
 * changes no block: the map kept the light it had drawn until something else in the chunk changed.
 *
 * <p>Every four ticks each light's cell, direction and level are compared with what they were; the chunks its light
 * reached before and reaches now are marked for a redraw when they differ, and those of a light that is gone. The mark
 * is the one Xaero's own mixins set on a block or light update: the "clean" flag they add to {@link LevelChunk}
 * ({@code xaero_chunkClean} for the minimap, {@code xaero_wm_chunkClean} for the World Map), found by name, so no Xaero
 * class is used and without Xaero this does nothing. A light standing still marks nothing.
 */
public final class MapRefresh {
    private static final Field MINIMAP_CLEAN = field("xaero_chunkClean");
    private static final Field WORLD_MAP_CLEAN = field("xaero_wm_chunkClean");
    private static final int EVERY_TICKS = 4;
    /** The most chunks one light marks (a long beam's box). */
    private static final int MAX_CHUNKS = 64;

    private static final class Seen {
        long cell;
        int direction, level;
        long[] chunks;
        long stamp;
    }

    private static final Map<Long, Seen> seen = new HashMap<>();
    private static long ticks;
    /** For tests: chunks marked since the client started. */
    private static long marked;

    private MapRefresh() {}

    private static Field field(String name) {
        try {
            Field field = LevelChunk.class.getDeclaredField(name);
            return field.getType() == boolean.class ? field : null;
        } catch (NoSuchFieldException | SecurityException e) {
            return null;
        }
    }

    static boolean present() {
        return MINIMAP_CLEAN != null || WORLD_MAP_CLEAN != null;
    }

    public static long marked() {
        return marked;
    }

    static void forget() {
        seen.clear();
    }

    /** On the client tick, after the lights are gathered. With {@code on} false (or no lights) what was lit is marked once. */
    static void tick(ClientLevel level, List<DynamicLight> lights, boolean on) {
        if (!present()) return;
        if (!on) {
            if (seen.isEmpty()) return;
            LongOpenHashSet dirty = new LongOpenHashSet();
            for (Seen s : seen.values()) for (long c : s.chunks) dirty.add(c);
            seen.clear();
            mark(level, dirty);
            return;
        }
        if (++ticks % EVERY_TICKS != 0) return;
        LongOpenHashSet dirty = null;
        for (DynamicLight light : lights) {
            Vec3 p = light.pos;
            long cell = BlockPos.asLong((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            int direction = 0;
            Vec3 d = null;
            if (light.isSpot()) {
                d = light.direction.at(1.0f);
                direction = ((int) Math.round(d.x * 8) + 8) | ((int) Math.round(d.y * 8) + 8) << 5 | ((int) Math.round(d.z * 8) + 8) << 10;
            }
            int lvl = Math.round(light.level);
            Seen was = seen.get(light.ownerKey);
            if (was != null && was.cell == cell && was.direction == direction && was.level == lvl) {
                was.stamp = ticks;
                continue;
            }
            long[] chunks = footprint(light, p, d);
            if (dirty == null) dirty = new LongOpenHashSet();
            if (was == null) seen.put(light.ownerKey, was = new Seen());
            else for (long c : was.chunks) dirty.add(c);
            for (long c : chunks) dirty.add(c);
            was.cell = cell;
            was.direction = direction;
            was.level = lvl;
            was.chunks = chunks;
            was.stamp = ticks;
        }
        for (Iterator<Seen> it = seen.values().iterator(); it.hasNext(); ) {
            Seen s = it.next();
            if (s.stamp == ticks) continue;
            if (dirty == null) dirty = new LongOpenHashSet();
            for (long c : s.chunks) dirty.add(c);
            it.remove();
        }
        if (dirty != null) mark(level, dirty);
    }

    /** The chunks a light's light reaches: a point's square, a beam's box. */
    private static long[] footprint(DynamicLight light, Vec3 p, Vec3 d) {
        double x0, x1, z0, z1;
        if (d != null) {
            double range = light.range;
            double spread = Math.min(range, range * Math.tan(Math.toRadians(Math.min(170.0, light.outerDegrees) * 0.5)));
            double ex = p.x + d.x * range, ez = p.z + d.z * range;
            x0 = Math.min(p.x - 1, ex - spread);
            x1 = Math.max(p.x + 1, ex + spread);
            z0 = Math.min(p.z - 1, ez - spread);
            z1 = Math.max(p.z + 1, ez + spread);
        } else {
            double reach = Math.min(light.range, light.level) + 1;
            x0 = p.x - reach;
            x1 = p.x + reach;
            z0 = p.z - reach;
            z1 = p.z + reach;
        }
        int cx0 = (int) Math.floor(x0) >> 4, cx1 = (int) Math.floor(x1) >> 4, cz0 = (int) Math.floor(z0) >> 4, cz1 = (int) Math.floor(z1) >> 4;
        int n = (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        if (n <= 0) return new long[0];
        if (n > MAX_CHUNKS) {
            // a box that large is a long beam seen from the side: the chunks along it
            int steps = MAX_CHUNKS;
            LongOpenHashSet along = new LongOpenHashSet();
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                along.add(ChunkPos.asLong((int) Math.floor(x0 + (x1 - x0) * t) >> 4, (int) Math.floor(z0 + (z1 - z0) * t) >> 4));
            }
            return along.toLongArray();
        }
        long[] out = new long[n];
        int i = 0;
        for (int cz = cz0; cz <= cz1; cz++) for (int cx = cx0; cx <= cx1; cx++) out[i++] = ChunkPos.asLong(cx, cz);
        return out;
    }

    private static void mark(ClientLevel level, LongOpenHashSet chunks) {
        for (long pos : chunks) {
            LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), false);
            if (chunk == null) continue;
            try {
                if (MINIMAP_CLEAN != null) MINIMAP_CLEAN.setBoolean(chunk, false);
                if (WORLD_MAP_CLEAN != null) WORLD_MAP_CLEAN.setBoolean(chunk, false);
                marked++;
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
    }
}
