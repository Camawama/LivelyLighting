package net.cama.livelylighting.source;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Whether light passes straight from one point to another (0.6.4): no block on the line between them that lets no light
 * through (a full opaque block, tinted glass). Lights merge only with such a line between them ({@link LightClusters}), and
 * a spot light counts on the server only where one reaches ({@link GameplayLights}). The cells the two points are in don't
 * count (a lamp is inside its own block). Reads the world's blocks in chunks that are loaded, never loads one; a ship's
 * blocks and a contraption's are not in the world and stop nothing here.
 */
public final class LightSight {
    private LightSight() {}

    /** The block at a position if its chunk is loaded, without loading it (on a server: from the server thread only). */
    @Nullable
    public static BlockState loadedState(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** Whether a block lets no light through. */
    public static boolean blocks(Level level, BlockPos pos, BlockState state) {
        return !state.isAir() && state.getLightBlock(level, pos) >= 15;
    }

    /**
     * Whether nothing that stops light lies on the line from {@code from} to {@code to}. A part of the line that is not
     * loaded is clear with {@code clearWhereUnloaded}, else it stops the light.
     */
    public static boolean clear(Level level, Vec3 from, Vec3 to, boolean clearWhereUnloaded) {
        BlockPos first = BlockPos.containing(from), last = BlockPos.containing(to);
        if (first.equals(last)) return true;
        return BlockGetter.traverseBlocks(from, to, level, (world, pos) -> {
            if (pos.equals(first) || pos.equals(last)) return null;
            if (world.isOutsideBuildHeight(pos)) return null;
            BlockState state = loadedState(world, pos);
            if (state == null) return clearWhereUnloaded ? null : Boolean.FALSE;
            return blocks(world, pos, state) ? Boolean.FALSE : null;
        }, world -> Boolean.TRUE);
    }
}
