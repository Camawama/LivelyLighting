package net.cama.livelylighting.compat.valkyrienskies;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IVSCompat {
    Set<Long> getLoadedShipIds(ServerLevel level);
    long getShipId(Object shipObj);
    double[] transformShipToWorld(Object shipObj, double x, double y, double z);
    double[] transformWorldToShip(Object shipObj, double x, double y, double z);
    List<Object> getShipsIntersecting(ServerLevel level, AABB aabb);

    /**
     * The ship whose shipyard region contains this position, or null for ordinary
     * world positions. Entities that live at shipyard coordinates (item frames
     * and other hanging entities mounted on ships) are detected through this so
     * their light can be projected out into the static world.
     */
    Object getShipManagingPos(ServerLevel level, BlockPos pos);

    /**
     * The ship's block bounds in shipyard coordinates as
     * {minX, minY, minZ, maxX, maxY, maxZ}, or null if unknown. Shipyard light
     * anchors must stay strictly inside these bounds so a placed block can never
     * extend the ship's AABB (the old runaway-expansion bug).
     */
    int[] getShipBlockBounds(Object shipObj);

    /**
     * Every light-emitting block on the ship, in shipyard coordinates, mapped to
     * its light emission. Used to project the ship's lights into the world as
     * dynamic light sources; nothing is ever placed inside the shipyard.
     */
    Map<BlockPos, Integer> getShipLightEmitters(ServerLevel level, Object shipObj);
}
