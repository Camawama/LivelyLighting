package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.cama.livelylighting.source.Falloff;
import net.cama.livelylighting.source.LightShape;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lamps of the world for the ships beside them (0.9.0). A Valkyrien Skies ship's blocks are kept, and lit, far from where
 * the ship is drawn, so a torch on the quay lights the quay and not the hull moored at it. The server module puts a light
 * block onto the ship for every such lamp; the client module had nothing in its place, and a ship beside a lamp stayed dark.
 *
 * <p>For every loaded ship near the camera the light-giving blocks of the world within reach of its bounds are found (per
 * chunk section, a palette test first; kept {@link #RESCAN_TICKS} ticks), the {@code client.world_lamps_per_ship} that reach
 * it brightest are kept, and each is handed to Colorful Lighting as a point light in the lamp's colour that is drawn on
 * ships' blocks alone ({@code DynamicLightSink.SHIPS_ONLY}, 2.13.0): the world has that lamp's light already. It is a light
 * like any other from there: the ship's own blocks are in its way, its glass tints it, and it costs a lamp of a ship what it
 * costs. A lamp two ships share is one light.
 *
 * <p>Only with the shader renderer and a Colorful Lighting that knows such lights; needs {@code vs_support}. Blacklights are
 * left out (their field reaches ships as it is), and light blocks count only with {@code light_blocks_are_lamps}.
 */
final class WorldLamps {
    private static final int RESCAN_TICKS = 40, FORGET_TICKS = 400, REACH = 15;
    /** A section full of lava gives thousands of lamps; the first so many stand for it. */
    private static final int PER_SECTION = 256;
    /** A ship whose bounds, with the reach round them, take more sections than this is left alone (a ship the size of a town). */
    private static final int MAX_SECTIONS = 1024;

    private record Lamp(BlockPos pos, BlockState state, int level, int rgb) {}

    private static final class Scan {
        long tick, used;
        List<Lamp> lamps = Collections.emptyList();
    }

    private record Reached(Lamp lamp, int effective, double camera) {}

    private final Map<Long, Scan> sections = new HashMap<>();
    /** The ships that took a lamp of the world this tick. */
    private Set<Long> lit = Collections.emptySet();
    private long sweepTick;
    private int lastCount;

    void forget() {
        sections.clear();
        lit = Collections.emptySet();
        lastCount = 0;
    }

    /** Whether a ship is lit by a lamp of the world this tick (its blocks' place then matters to that light as it moves). */
    boolean lights(long shipId) {
        return lit.contains(shipId);
    }

    /** For tests: the lamps of the world handed over this tick. */
    int count() {
        return lastCount;
    }

    void collect(ClientLevel level, Vec3 camera, LivelyConfig config, List<DynamicLight> out) {
        int cap = Math.min(32, config.client.world_lamps_per_ship);
        if (cap <= 0 || !config.vs_support || !Ships.available() || !ColorfulBridge.shipLightsDrawn()) {
            if (!sections.isEmpty() || !lit.isEmpty()) forget();
            lastCount = 0;
            return;
        }
        long now = level.getGameTime();
        double far = Math.max(16, config.client.shader_max_distance);
        Set<Long> taken = new HashSet<>(), ships = new HashSet<>();
        int before = out.size();
        Ships.forEachLoaded(level, ship -> {
            if (!ship.isLoaded()) return;
            AABB box = ship.worldAabb();
            if (box == null) return;
            // beyond the lights' own distance nothing of it would be drawn
            double cx = Math.max(0.0, Math.max(box.minX - camera.x, camera.x - box.maxX));
            double cy = Math.max(0.0, Math.max(box.minY - camera.y, camera.y - box.maxY));
            double cz = Math.max(0.0, Math.max(box.minZ - camera.z, camera.z - box.maxZ));
            if (cx * cx + cy * cy + cz * cz > far * far) return;
            int sx0 = SectionPos.blockToSectionCoord((int) Math.floor(box.minX) - REACH), sx1 = SectionPos.blockToSectionCoord((int) Math.floor(box.maxX) + REACH);
            int sy0 = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord((int) Math.floor(box.minY) - REACH));
            int sy1 = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord((int) Math.floor(box.maxY) + REACH));
            int sz0 = SectionPos.blockToSectionCoord((int) Math.floor(box.minZ) - REACH), sz1 = SectionPos.blockToSectionCoord((int) Math.floor(box.maxZ) + REACH);
            if (sy1 < sy0 || (long) (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1) > MAX_SECTIONS) return;
            List<Reached> reached = new ArrayList<>();
            for (int sx = sx0; sx <= sx1; sx++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    for (int sy = sy0; sy <= sy1; sy++) {
                        long key = SectionPos.asLong(sx, sy, sz);
                        Scan scan = sections.get(key);
                        if (scan == null || now - scan.tick >= RESCAN_TICKS) {
                            if (scan == null) sections.put(key, scan = new Scan());
                            scan.tick = now;
                            scan.lamps = scan(level, sx, sy, sz, config);
                        }
                        scan.used = now;
                        for (Lamp lamp : scan.lamps) {
                            double x = lamp.pos.getX() + 0.5, y = lamp.pos.getY() + 0.5, z = lamp.pos.getZ() + 0.5;
                            double dx = Math.max(0.0, Math.max(box.minX - x, x - box.maxX));
                            double dy = Math.max(0.0, Math.max(box.minY - y, y - box.maxY));
                            double dz = Math.max(0.0, Math.max(box.minZ - z, z - box.maxZ));
                            // what is left of the lamp where it first meets the ship's bounds, as a light block would be placed
                            int effective = lamp.level - (int) Math.floor(Math.max(0.0, dx + dy + dz - 1.0));
                            if (effective <= 0) continue;
                            reached.add(new Reached(lamp, effective, camera.distanceToSqr(x, y, z)));
                        }
                    }
                }
            }
            if (reached.isEmpty()) return;
            reached.sort((a, b) -> a.effective != b.effective ? Integer.compare(b.effective, a.effective) : Double.compare(a.camera, b.camera));
            int given = 0;
            for (Reached r : reached) {
                if (given >= cap) break;
                Lamp lamp = r.lamp;
                // gone since its section was read: its light goes with it at once
                if (level.getBlockState(lamp.pos) != lamp.state) continue;
                given++;
                ships.add(ship.id());
                if (!taken.add(lamp.pos.asLong())) continue; // another ship's too: one light for both
                final Vec3 centre = Vec3.atCenterOf(lamp.pos);
                out.add(new DynamicLight(0x7700000000000000L ^ lamp.pos.asLong(), DynamicLight.Kind.WORLD_LAMP, pt -> centre, null, lamp.level, lamp.rgb,
                        LightShape.POINT, 0f, lamp.level, Falloff.DIAMOND));
            }
        });
        lit = ships;
        lastCount = out.size() - before;
        if (now - sweepTick >= 100 || now < sweepTick) {
            sweepTick = now;
            sections.values().removeIf(s -> now - s.used > FORGET_TICKS);
        }
    }

    /** The lamps of one chunk section of the world; none while its chunk is not loaded. */
    private static List<Lamp> scan(ClientLevel level, int sx, int sy, int sz, LivelyConfig config) {
        ChunkAccess access = level.getChunk(sx, sz, ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return Collections.emptyList();
        int index = level.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] column = chunk.getSections();
        if (index < 0 || index >= column.length) return Collections.emptyList();
        LevelChunkSection section = column[index];
        if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() > 0)) return Collections.emptyList();
        List<Lamp> lamps = new ArrayList<>();
        int bx = SectionPos.sectionToBlockCoord(sx), by = SectionPos.sectionToBlockCoord(sy), bz = SectionPos.sectionToBlockCoord(sz);
        Map<BlockState, int[]> known = new java.util.IdentityHashMap<>();
        for (int y = 0; y < 16 && lamps.size() < PER_SECTION; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.getLightEmission() <= 0) continue;
                    int[] lamp = known.get(state);
                    if (lamp == null) {
                        // the level and the colour Colorful Lighting gives the block (an end gateway gives none there)
                        int lvl = state.is(Blocks.LIGHT) && !config.light_blocks_are_lamps ? 0 : LightColors.levelOf(state);
                        int rgb = lvl > 0 ? LightColors.ofBlock(state) : -1;
                        if (rgb == LightData.BLACKLIGHT) lvl = 0;
                        known.put(state, lamp = new int[]{lvl, rgb});
                    }
                    if (lamp[0] <= 0) continue;
                    lamps.add(new Lamp(new BlockPos(bx + x, by + y, bz + z), state, lamp[0], lamp[1]));
                }
            }
        }
        return lamps;
    }
}
