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
    List<Object> getShipsIntersecting(ServerLevel level, AABB aabb);

    /**
     * Every light-emitting block on the ship, in shipyard coordinates, mapped to
     * its light emission. Used to project the ship's lights into the world as
     * dynamic light sources; nothing is ever placed inside the shipyard.
     */
    Map<BlockPos, Integer> getShipLightEmitters(ServerLevel level, Object shipObj);
}
