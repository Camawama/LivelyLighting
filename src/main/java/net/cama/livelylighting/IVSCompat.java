package net.cama.livelylighting;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IVSCompat {
    Set<Long> getLoadedShipIds(ServerLevel level);
    Map<Long, Object> getShipLookup(ServerLevel level);
    Object getShipObjectManagingPos(ServerLevel level, BlockPos pos);
    double[] transformShipToWorld(Object shipObj, double x, double y, double z);
    double[] transformWorldToShip(Object shipObj, double x, double y, double z);
    long getShipId(Object shipObj);
    boolean isPosInShipBounds(Object shipObj, BlockPos pos);
    List<Object> getShipsIntersecting(ServerLevel level, AABB aabb);
}
