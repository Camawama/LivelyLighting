package net.cama.livelylighting.source;

import net.cama.livelylighting.LivelyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;

/**
 * An item frame's light switch (0.10.2): a redstone signal into the block a frame hangs on switches the frame's light off,
 * a glow frame's headlight beam and a plain frame's round light alike. A lever on that block is a switch; a daylight sensor
 * on it powers it by day, so the frame shines only at night. Unpowered, every frame shines as before, so no build changes.
 *
 * <p>The signal is read as vanilla reads one for a block, {@code Level.hasNeighborSignal} on the support position (its six
 * neighbours' signals, from their block states, so a client answers it from what it has). On a Valkyrien Skies ship the
 * frame and its block are in the shipyard, where the ship's own redstone works as on land (and Camafy's ship redstone
 * answers this same question across the ship's edge). On the server the seven positions' chunks must already be loaded, or
 * the frame counts as unpowered: a light question never loads a chunk.
 */
public final class FrameSwitch {
    private FrameSwitch() {}

    /** True when a redstone signal into the block the frame hangs on has switched its light off. */
    public static boolean switchedOff(ItemFrame frame, Level level, LivelyConfig config) {
        if (config == null || !config.frame_lights_off_when_powered || level == null) return false;
        BlockPos support = frame.getPos().relative(frame.getDirection().getOpposite());
        if (level instanceof ServerLevel server && !loaded(server, support)) return false;
        return level.hasNeighborSignal(support);
    }

    /** Whether the support block and its six neighbours are in chunks the server has loaded now. */
    private static boolean loaded(ServerLevel level, BlockPos support) {
        if (level.getChunkSource().getChunkNow(support.getX() >> 4, support.getZ() >> 4) == null) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = support.relative(d);
            if ((n.getX() >> 4) == (support.getX() >> 4) && (n.getZ() >> 4) == (support.getZ() >> 4)) continue;
            if (level.getChunkSource().getChunkNow(n.getX() >> 4, n.getZ() >> 4) == null) return false;
        }
        return true;
    }
}
