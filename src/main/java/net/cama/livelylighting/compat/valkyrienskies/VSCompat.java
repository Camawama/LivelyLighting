package net.cama.livelylighting.compat.valkyrienskies;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
    public long getShipId(Object shipObj) {
        if (shipObj instanceof Ship ship) {
            return ship.getId();
        }
        return -1;
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
    public int[] getShipBlockBounds(Object shipObj) {
        if (shipObj instanceof Ship ship) {
            AABBic aabb = ship.getShipAABB();
            if (aabb != null) {
                return new int[]{aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ()};
            }
        }
        return null;
    }

    @Override
    public List<Object> getShipsIntersecting(ServerLevel level, AABB aabb) {
        List<Object> list = new ArrayList<>();
        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(level, aabb)) {
            list.add(ship);
        }
        return list;
    }

    @Override
    public Map<BlockPos, Integer> getShipLightEmitters(ServerLevel level, Object shipObj) {
        if (!(shipObj instanceof Ship ship)) return Collections.emptyMap();
        AABBic aabb = ship.getShipAABB();
        if (aabb == null) return Collections.emptyMap();

        Map<BlockPos, Integer> emitters = new HashMap<>();

        // Walk the shipyard AABB chunk section by chunk section: ships are mostly
        // air within their bounds and hasOnlyAir() skips those sections without
        // touching a single block state.
        int minSectionX = aabb.minX() >> 4;
        int maxSectionX = aabb.maxX() >> 4;
        int minSectionZ = aabb.minZ() >> 4;
        int maxSectionZ = aabb.maxZ() >> 4;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int chunkX = minSectionX; chunkX <= maxSectionX; chunkX++) {
            for (int chunkZ = minSectionZ; chunkZ <= maxSectionZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                if (chunk == null) continue;

                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) continue;

                    int sectionMinY = level.getSectionYFromSectionIndex(sectionIndex) << 4;
                    if (sectionMinY + 15 < aabb.minY() || sectionMinY > aabb.maxY()) continue;

                    int baseX = SectionPos.sectionToBlockCoord(chunkX);
                    int baseZ = SectionPos.sectionToBlockCoord(chunkZ);

                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                int emission = state.getLightEmission();
                                if (emission <= 0) continue;

                                cursor.set(baseX + x, sectionMinY + y, baseZ + z);
                                if (cursor.getX() < aabb.minX() || cursor.getX() > aabb.maxX()
                                        || cursor.getY() < aabb.minY() || cursor.getY() > aabb.maxY()
                                        || cursor.getZ() < aabb.minZ() || cursor.getZ() > aabb.maxZ()) {
                                    continue;
                                }
                                emitters.put(cursor.immutable(), emission);
                            }
                        }
                    }
                }
            }
        }
        return emitters;
    }
}
