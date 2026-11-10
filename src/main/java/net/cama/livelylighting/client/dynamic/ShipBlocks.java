package net.cama.livelylighting.client.dynamic;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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
 * The blocks of Valkyrien Skies ships for Colorful Lighting's block volume (0.6.0): a ship's blocks live in the shipyard,
 * so the volume the dynamic lights are propagated through knew nothing of them, and a lamp on a ship lit the ground through
 * the ship's hull and its glass tinted nothing. Every client tick the loaded ships and their world boxes are noted; when
 * Colorful Lighting rebuilds its volume (on its worker), every block of every ship in the cube is written at the world cells
 * it is drawn in.
 *
 * <p>A block is written at the cells whose centres lie in it (0.6.5), a little widened so a turned wall one block thick has
 * no gaps for light going diagonally: one cell for a ship on the grid, two across for one standing half a block off it.
 * Until then a block of a turned or scaled ship took its centre's cell and the cells of its eight corners: up to eight cells
 * for one block, so a one-block ship with a lamp on each face stood in a dark lump of its own cells that changed with every
 * turn, and its light flickered.
 * A block that gives light itself takes its centre's cell only, where its light starts: written beside that cell it shadowed
 * one side of its own light. {@code -Dlivelylighting.shipBlocksCentreSampled=false} gives the corners back.
 *
 * <p>The volume is rebuilt every ten ticks on its own. When a ship with lamps near the camera has moved a quarter of a block
 * or turned a few degrees since the last build was asked for, the next is asked for at once (0.6.5, Colorful Lighting
 * 2.10.3's {@code externalBlocksMoved}; {@code -Dlivelylighting.shipLampRebuilds=false}): its lamps move every frame, and
 * for half a second they shone from among the cells its blocks had left.
 *
 * <p>A block that tints light (stained glass) is written by the share of each cell it takes up (0.6.7, Colorful Lighting
 * 2.10.5's {@code BlockWriter.cover}; {@code -Dlivelylighting.shipBlockCoverage=false}): written to whole cells, the colour
 * behind a ship's glass moved a block at a time however slowly the ship went. And a ship with such blocks within reach of a
 * dynamic light has the volume rebuilt as it moves, a fiftieth of a block at a time and as often as
 * {@code client.light_path_update_ticks} allows ({@code -Dlivelylighting.shipGlassRebuilds=false}): the colour followed a
 * ship without lamps only every ten ticks. Which ships tint light is learnt from the shares' answers, so without the shares
 * no ship asks. Blocks that stop light stay whole cells: a hull written in shares would let light along itself.
 */
final class ShipBlocks {
    private static final int BLOCK_BUDGET = 400_000;
    private static final boolean CENTRE_SAMPLED = !"false".equalsIgnoreCase(System.getProperty("livelylighting.shipBlocksCentreSampled", "true"));
    private static final boolean LAMP_REBUILDS = !"false".equalsIgnoreCase(System.getProperty("livelylighting.shipLampRebuilds", "true"));
    /** How far past its faces a turned block still takes a cell's centre, in blocks of the world. */
    private static final double WIDEN = 0.08;
    /** A ship with lamps that went this far (blocks), or whose axes changed by this much, has the volume rebuilt. */
    private static final double MOVED = 0.25, TURNED = 0.05;
    /** The ships asked about lie within this many blocks of the camera (the volume is a 96-block cube round it). */
    private static final double NEAR = 56.0;
    private static final int ASK_TICKS = 2;
    private static final boolean COVERAGE = !"false".equalsIgnoreCase(System.getProperty("livelylighting.shipBlockCoverage", "true"));
    private static final boolean GLASS_REBUILDS = !"false".equalsIgnoreCase(System.getProperty("livelylighting.shipGlassRebuilds", "true"));
    /** A ship with blocks that tint light, within reach of a light, that went this far or turned this much has the volume rebuilt. */
    private static final double GLASS_MOVED = 0.02, GLASS_TURNED = 0.004;
    /** A ship whose axes lie this near the world's is written by its blocks' exact overlap with the cells (under two degrees). */
    private static final double ALIGNED = 0.9995;
    /** The share of a cell a turned block takes up is counted over this many points (a Halton set: no two share a coordinate). */
    private static final int POINTS = 32;
    private static final double[] PX = new double[POINTS], PY = new double[POINTS], PZ = new double[POINTS];

    static {
        for (int i = 0; i < POINTS; i++) {
            PX[i] = halton(i + 1, 2);
            PY[i] = halton(i + 1, 3);
            PZ[i] = halton(i + 1, 5);
        }
    }

    private static double halton(int index, int base) {
        double f = 1.0, r = 0.0;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    private record Snap(ShipRef ship, AABB box, boolean aligned, double scale) {}

    private static volatile List<Snap> snapshot = Collections.emptyList();
    /** The ships the last fill found a block that tints light on (the worker's answer, read on the client tick). */
    private static volatile Set<Long> tinting = Collections.emptySet();
    /** The fewest ticks between two rebuilds asked for a ship's glass ({@code client.light_path_update_ticks}). */
    private static volatile int glassAskTicks = 1;
    /** Each lamp or glass ship's place and axes when a rebuild was last asked for. */
    private static final Map<Long, double[]> asked = new HashMap<>();
    private static long ticks, lastAsk;

    private ShipBlocks() {}

    /** Where a block goes: the world cell, its state and the shipyard position its state is read at. */
    interface Writer {
        void set(int x, int y, int z, BlockState state, BlockPos statePos);

        /** Whether {@link #cover} is there (Colorful Lighting 2.10.5). */
        boolean covers();

        /** A block that takes up a share of a cell (0 to 1); false when the state takes no light away. */
        boolean cover(int x, int y, int z, BlockState state, BlockPos statePos, float share);
    }

    static void forget() {
        asked.clear();
        snapshot = Collections.emptyList();
        tinting = Collections.emptySet();
    }

    /** How often a moving ship's glass may ask for the volume: the light paths' own update interval. */
    static void pathInterval(int ticks) {
        glassAskTicks = Math.max(1, ticks);
    }

    /** On the client tick: the loaded ships and their world boxes, for the worker that fills the volume. */
    static void snapshot(ClientLevel level, ShipLamps lamps, List<DynamicLight> lights) {
        ticks++;
        if (!Ships.available()) {
            if (!snapshot.isEmpty()) snapshot = Collections.emptyList();
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        AABB near = new AABB(camera.x - NEAR, camera.y - NEAR, camera.z - NEAR, camera.x + NEAR, camera.y + NEAR, camera.z + NEAR);
        boolean[] moved = {false}, crept = {false};
        Set<Long> glass = GLASS_REBUILDS && !lights.isEmpty() ? tinting : Collections.emptySet();
        Map<Long, double[]> poses = new HashMap<>();
        List<Snap> out = new ArrayList<>();
        Ships.forEachLoaded(level, ship -> {
            if (!ship.isLoaded()) return;
            AABB box = ship.worldAabb();
            if (box == null) return;
            Vec3 up = ship.dirToWorld(new Vec3(0, 1, 0)), east = ship.dirToWorld(new Vec3(1, 0, 0));
            boolean aligned = up != null && east != null && Math.abs(up.y) > 0.999 && (Math.abs(east.x) > 0.999 || Math.abs(east.z) > 0.999);
            out.add(new Snap(ship, box, aligned, ship.scale()));
            if (up == null || east == null || !box.intersects(near)) return;
            boolean lamp = LAMP_REBUILDS && lamps.hasLamps(ship.id());
            boolean tints = glass.contains(ship.id()) && reached(box, lights);
            if (!lamp && !tints) return;
            Vec3 c = box.getCenter();
            double[] pose = {c.x, c.y, c.z, up.x, up.y, up.z, east.x, east.y, east.z};
            poses.put(ship.id(), pose);
            double[] was = asked.get(ship.id());
            if (was == null) {
                if (lamp) moved[0] = true;
                else crept[0] = true;
                return;
            }
            double went = 0, turned = 0;
            for (int i = 0; i < pose.length; i++) {
                double by = Math.abs(pose[i] - was[i]);
                if (i < 3) went = Math.max(went, by);
                else turned = Math.max(turned, by);
            }
            if (lamp && (went > MOVED || turned > TURNED)) moved[0] = true;
            if (tints && (went > GLASS_MOVED || turned > GLASS_TURNED)) crept[0] = true;
        });
        snapshot = out;
        asked.keySet().retainAll(poses.keySet());
        if (moved[0] && ticks - lastAsk >= ASK_TICKS || crept[0] && ticks - lastAsk >= glassAskTicks) {
            lastAsk = ticks;
            asked.putAll(poses);
            ColorfulBridge.blocksMoved();
        }
    }

    /** Whether any light reaches a ship's box. */
    private static boolean reached(AABB box, List<DynamicLight> lights) {
        for (DynamicLight l : lights) {
            double dx = Math.max(0.0, Math.max(box.minX - l.pos.x, l.pos.x - box.maxX));
            double dy = Math.max(0.0, Math.max(box.minY - l.pos.y, l.pos.y - box.maxY));
            double dz = Math.max(0.0, Math.max(box.minZ - l.pos.z, l.pos.z - box.maxZ));
            double reach = Math.max(16.0, l.range);
            if (dx * dx + dy * dy + dz * dz <= reach * reach) return true;
        }
        return false;
    }

    /** Colorful Lighting's worker: every block of every ship in the cube, at the world cells it is drawn in. */
    static void fill(ClientLevel level, int ox, int oy, int oz, int size, Writer out) {
        List<Snap> ships = snapshot;
        if (ships.isEmpty()) {
            if (!tinting.isEmpty()) tinting = Collections.emptySet();
            return;
        }
        AABB cube = new AABB(ox, oy, oz, ox + size, oy + size, oz + size);
        int[] budget = {BLOCK_BUDGET};
        Set<Long> found = new HashSet<>();
        for (Snap s : ships) {
            if (budget[0] <= 0) break;
            if (!s.box.intersects(cube)) continue;
            if (!CENTRE_SAMPLED) fillCells(s.ship, !s.aligned || Math.abs(s.scale - 1.0) > 0.01, budget, out);
            else if (fillSampled(level, s.ship, budget, out)) found.add(s.ship.id());
        }
        tinting = found;
    }

    /** A block at its centre's cell; with {@code corners} at the cells of its eight corners too (the way before 0.6.5). */
    private static void fillCells(ShipRef ship, boolean corners, int[] budget, Writer out) {
        ship.forEachBlock(state -> !state.isAir(), (pos, state) -> {
            if (budget[0]-- <= 0) return;
            Vec3 centre = Vec3.atCenterOf(pos);
            Vec3 w = ship.toWorld(centre);
            if (w == null) return;
            out.set((int) Math.floor(w.x), (int) Math.floor(w.y), (int) Math.floor(w.z), state, pos);
            if (!corners) return;
            for (int c = 0; c < 8; c++) {
                Vec3 corner = ship.toWorld(centre.add((c & 1) == 0 ? -0.45 : 0.45, (c & 2) == 0 ? -0.45 : 0.45, (c & 4) == 0 ? -0.45 : 0.45));
                if (corner != null) out.set((int) Math.floor(corner.x), (int) Math.floor(corner.y), (int) Math.floor(corner.z), state, pos);
            }
        });
    }

    /**
     * Each block at the cells whose centres lie in it; a block that tints light at every cell it reaches into, by the share of
     * the cell it takes up (the exact overlap for a ship along the world's axes, counted over {@link #POINTS} points in the
     * cell for a turned one). The ship's axes in the world are taken once from its first block (the map is linear), so a block
     * costs no transform of its own. True when the ship has a block that tints light.
     */
    private static boolean fillSampled(ClientLevel level, ShipRef ship, int[] budget, Writer out) {
        // 0..2 the first block's centre in the world, 3..11 the world's step per block along the ship's x, y and z,
        // 12..14 one over each step's length squared, 15..17 how far a cell's centre may lie from the block's along each
        // of the ship's axes, in blocks of the ship, 18..20 the reach of a block along the world's axes,
        // 21..23 half the block itself along the world's axes (its own size for a ship along them, its box for a turned one)
        final double[] m = new double[24];
        final int[] first = new int[3];
        final boolean[] have = {false}, failed = {false}, aligned = {true}, tints = {false};
        final boolean shares = COVERAGE && out.covers();
        ship.forEachBlock(state -> !state.isAir(), (pos, state) -> {
            if (failed[0] || budget[0]-- <= 0) return;
            if (!have[0]) {
                Vec3 ref = Vec3.atCenterOf(pos);
                Vec3 w0 = ship.toWorld(ref), wx = ship.toWorld(ref.add(1, 0, 0)), wy = ship.toWorld(ref.add(0, 1, 0)), wz = ship.toWorld(ref.add(0, 0, 1));
                if (w0 == null || wx == null || wy == null || wz == null) {
                    failed[0] = true;
                    return;
                }
                m[0] = w0.x;
                m[1] = w0.y;
                m[2] = w0.z;
                Vec3[] steps = {wx.subtract(w0), wy.subtract(w0), wz.subtract(w0)};
                for (int a = 0; a < 3; a++) {
                    m[3 + a * 3] = steps[a].x;
                    m[4 + a * 3] = steps[a].y;
                    m[5 + a * 3] = steps[a].z;
                    double len2 = Math.max(1.0e-9, steps[a].lengthSqr()), len = Math.sqrt(len2);
                    m[12 + a] = 1.0 / len2;
                    // half the block, and never less than half a cell of the world, a little widened
                    m[15 + a] = (Math.max(0.5 * len, 0.5) + WIDEN) / len;
                }
                for (int a = 0; a < 3; a++) {
                    m[18 + a] = Math.abs(m[3 + a]) * m[15] + Math.abs(m[6 + a]) * m[16] + Math.abs(m[9 + a]) * m[17];
                    m[21 + a] = 0.5 * (Math.abs(m[3 + a]) + Math.abs(m[6 + a]) + Math.abs(m[9 + a]));
                }
                double[] own = new double[3];
                for (int a = 0; a < 3; a++) {
                    double len = steps[a].length();
                    double ax = Math.abs(steps[a].x), ay = Math.abs(steps[a].y), az = Math.abs(steps[a].z);
                    int k = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
                    if (Math.max(ax, Math.max(ay, az)) < ALIGNED * len || own[k] != 0.0) aligned[0] = false;
                    own[k] = 0.5 * len;
                }
                if (aligned[0]) System.arraycopy(own, 0, m, 21, 3);
                first[0] = pos.getX();
                first[1] = pos.getY();
                first[2] = pos.getZ();
                have[0] = true;
            }
            int bx = pos.getX() - first[0], by = pos.getY() - first[1], bz = pos.getZ() - first[2];
            double wx = m[0] + bx * m[3] + by * m[6] + bz * m[9];
            double wy = m[1] + bx * m[4] + by * m[7] + bz * m[10];
            double wz = m[2] + bx * m[5] + by * m[8] + bz * m[11];
            if (state.getLightEmission() > 0) {
                // a lamp: where its light starts, and nowhere beside it
                out.set((int) Math.floor(wx), (int) Math.floor(wy), (int) Math.floor(wz), state, pos);
                return;
            }
            if (shares && !state.isSolidRender(level, pos)) {
                // a block that may tint light: the share of every cell it reaches into
                double lx = wx - m[21], ux = wx + m[21], ly = wy - m[22], uy = wy + m[22], lz = wz - m[23], uz = wz + m[23];
                int x0 = (int) Math.floor(lx), x1 = (int) Math.ceil(ux) - 1;
                int y0 = (int) Math.floor(ly), y1 = (int) Math.ceil(uy) - 1;
                int z0 = (int) Math.floor(lz), z1 = (int) Math.ceil(uz) - 1;
                if (!aligned[0]) budget[0] -= 8;
                for (int cz = z0; cz <= z1; cz++) {
                    for (int cy = y0; cy <= y1; cy++) {
                        for (int cx = x0; cx <= x1; cx++) {
                            float share;
                            if (aligned[0]) {
                                double sx = Math.min(cx + 1, ux) - Math.max(cx, lx), sy = Math.min(cy + 1, uy) - Math.max(cy, ly), sz = Math.min(cz + 1, uz) - Math.max(cz, lz);
                                if (sx <= 0 || sy <= 0 || sz <= 0) continue;
                                share = (float) (sx * sy * sz);
                            } else {
                                int in = 0;
                                for (int p = 0; p < POINTS; p++) {
                                    double qx = cx + PX[p] - wx, qy = cy + PY[p] - wy, qz = cz + PZ[p] - wz;
                                    if (Math.abs(qx * m[3] + qy * m[4] + qz * m[5]) * m[12] > 0.5) continue;
                                    if (Math.abs(qx * m[6] + qy * m[7] + qz * m[8]) * m[13] > 0.5) continue;
                                    if (Math.abs(qx * m[9] + qy * m[10] + qz * m[11]) * m[14] > 0.5) continue;
                                    in++;
                                }
                                share = in / (float) POINTS;
                            }
                            if (share < 0.004f) continue;
                            // a clear state takes nothing: none of its cells need asking
                            if (!out.cover(cx, cy, cz, state, pos, share)) return;
                            tints[0] = true;
                        }
                    }
                }
                return;
            }
            int x0 = (int) Math.ceil(wx - m[18] - 0.5), x1 = (int) Math.floor(wx + m[18] - 0.5);
            int y0 = (int) Math.ceil(wy - m[19] - 0.5), y1 = (int) Math.floor(wy + m[19] - 0.5);
            int z0 = (int) Math.ceil(wz - m[20] - 0.5), z1 = (int) Math.floor(wz + m[20] - 0.5);
            for (int cz = z0; cz <= z1; cz++) {
                double qz = cz + 0.5 - wz;
                for (int cy = y0; cy <= y1; cy++) {
                    double qy = cy + 0.5 - wy;
                    for (int cx = x0; cx <= x1; cx++) {
                        double qx = cx + 0.5 - wx;
                        if (Math.abs(qx * m[3] + qy * m[4] + qz * m[5]) * m[12] > m[15]) continue;
                        if (Math.abs(qx * m[6] + qy * m[7] + qz * m[8]) * m[13] > m[16]) continue;
                        if (Math.abs(qx * m[9] + qy * m[10] + qz * m[11]) * m[14] > m[17]) continue;
                        out.set(cx, cy, cz, state, pos);
                    }
                }
            }
        });
        return tints[0];
    }
}
