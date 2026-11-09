package net.cama.livelylighting.client.dynamic;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The blocks of Valkyrien Skies ships for Colorful Lighting's block volume (0.6.0): a ship's blocks live in the shipyard,
 * so the volume the dynamic lights are propagated through knew nothing of them, and a lamp on a ship lit the ground through
 * the ship's hull and its glass tinted nothing. Every client tick the loaded ships and their world boxes are noted; when
 * Colorful Lighting rebuilds its volume (on its worker), every block of every ship in the cube is written at the world cell
 * it is drawn in, a block turned off the grid or scaled at the cells of its corners as well, so a turned hull has no gaps.
 */
final class ShipBlocks {
    private static final int BLOCK_BUDGET = 400_000;

    private record Snap(ShipRef ship, AABB box, boolean aligned, double scale) {}

    private static volatile List<Snap> snapshot = Collections.emptyList();

    private ShipBlocks() {}

    /** Where a block goes: the world cell, its state and the shipyard position its state is read at. */
    interface Writer {
        void set(int x, int y, int z, BlockState state, BlockPos statePos);
    }

    /** On the client tick: the loaded ships and their world boxes, for the worker that fills the volume. */
    static void snapshot(ClientLevel level) {
        if (!Ships.available()) {
            if (!snapshot.isEmpty()) snapshot = Collections.emptyList();
            return;
        }
        List<Snap> out = new ArrayList<>();
        Ships.forEachLoaded(level, ship -> {
            if (!ship.isLoaded()) return;
            AABB box = ship.worldAabb();
            if (box == null) return;
            Vec3 up = ship.dirToWorld(new Vec3(0, 1, 0)), east = ship.dirToWorld(new Vec3(1, 0, 0));
            boolean aligned = up != null && east != null && Math.abs(up.y) > 0.999 && (Math.abs(east.x) > 0.999 || Math.abs(east.z) > 0.999);
            out.add(new Snap(ship, box, aligned, ship.scale()));
        });
        snapshot = out;
    }

    /** Colorful Lighting's worker: every block of every ship in the cube, at the world cell it is drawn in. */
    static void fill(ClientLevel level, int ox, int oy, int oz, int size, Writer out) {
        List<Snap> ships = snapshot;
        if (ships.isEmpty()) return;
        AABB cube = new AABB(ox, oy, oz, ox + size, oy + size, oz + size);
        int[] budget = {BLOCK_BUDGET};
        for (Snap s : ships) {
            if (budget[0] <= 0) return;
            if (!s.box.intersects(cube)) continue;
            ShipRef ship = s.ship;
            boolean corners = !s.aligned || Math.abs(s.scale - 1.0) > 0.01;
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
    }
}
