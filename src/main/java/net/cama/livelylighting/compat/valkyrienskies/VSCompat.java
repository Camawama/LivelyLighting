package net.cama.livelylighting.compat.valkyrienskies;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.joml.primitives.AABBic;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.*;
import java.util.stream.Collectors;

public class VSCompat implements IVSCompat {

    @Override
    public Set<Long> getLoadedShipIds(ServerLevel level) {
        return VSGameUtilsKt.getAllShips(level).stream().map(Ship::getId).collect(Collectors.toSet());
    }

    @Override
    public Map<Long, Object> getShipLookup(ServerLevel level) {
        return VSGameUtilsKt.getAllShips(level).stream().collect(Collectors.toMap(Ship::getId, ship -> ship));
    }

    @Override
    public Object getShipObjectManagingPos(ServerLevel level, BlockPos pos) {
        return VSGameUtilsKt.getShipObjectManagingPos(level, pos);
    }

    @Override
    public double[] transformShipToWorld(Object shipObj, double x, double y, double z) {
        if (shipObj instanceof Ship ship) {
            Vector3d pos = new Vector3d(x, y, z);
            ship.getShipToWorld().transformPosition(pos);
            return new double[]{pos.x, pos.y, pos.z};
        }
        return new double[]{x, y, z};
    }

    @Override
    public double[] transformWorldToShip(Object shipObj, double x, double y, double z) {
        if (shipObj instanceof Ship ship) {
            Vector3d pos = new Vector3d(x, y, z);
            ship.getWorldToShip().transformPosition(pos);
            return new double[]{pos.x, pos.y, pos.z};
        }
        return new double[]{x, y, z};
    }

    @Override
    public long getShipId(Object shipObj) {
        if (shipObj instanceof Ship ship) {
            return ship.getId();
        }
        return -1;
    }

    @Override
    public boolean isPosInShipBounds(Object shipObj, BlockPos pos) {
        if (shipObj instanceof Ship ship) {
            AABBic aabb = ship.getShipAABB();
            if (aabb == null) return false;
            return pos.getX() >= aabb.minX() && pos.getX() <= aabb.maxX() &&
                   pos.getY() >= aabb.minY() && pos.getY() <= aabb.maxY() &&
                   pos.getZ() >= aabb.minZ() && pos.getZ() <= aabb.maxZ();
        }
        return false;
    }

    @Override
    public List<Object> getShipsIntersecting(ServerLevel level, AABB aabb) {
        List<Object> list = new ArrayList<>();
        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(level, aabb)) {
            list.add(ship);
        }
        return list;
    }
}
