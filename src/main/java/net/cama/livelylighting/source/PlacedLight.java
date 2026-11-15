package net.cama.livelylighting.source;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light a block gives when it is set down with nothing round it (0.10.0): what its item gives in the hand. A lamp that
 * needs redstone, an unlit candle or a furnace is dark then. A block's default state does not always say so: a block that
 * never names one gets the first of its states, and the first value of a true-or-false property is true (Quark's crystal
 * lamps were "lit" by default and shone in the hand, dark when placed without power).
 *
 * <p>Only a block whose states differ in the light they give is asked, once: its own placement rule, far below the world,
 * where every neighbour is void air (no chunk is touched, nothing powers it, no water is there) and with no player. A
 * block whose rule needs more than that keeps its default state's light.
 */
public final class PlacedLight {
    private static final Map<Block, Integer> KNOWN = new ConcurrentHashMap<>();

    private PlacedLight() {}

    /** The light of the block as its item would place it, 0 to 15. Without a level the default state's, asked again later. */
    public static int of(Block block, @Nullable Level level) {
        Integer known = KNOWN.get(block);
        if (known != null) return known;
        BlockState state = block.defaultBlockState();
        int light = state.getLightEmission();
        boolean differs = false;
        for (BlockState other : block.getStateDefinition().getPossibleStates()) {
            if (other.getLightEmission() != light) {
                differs = true;
                break;
            }
        }
        if (differs) {
            if (level == null) return light;
            try {
                BlockPos pos = new BlockPos(0, level.getMinBuildHeight() - 1024, 0);
                BlockPlaceContext context = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, new ItemStack(block),
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                BlockState placed = block.getStateForPlacement(context);
                if (placed != null && placed.getBlock() == block) light = placed.getLightEmission();
            } catch (Throwable t) {
                // a placement rule that needs a player or a real place: the default state's light
            }
        }
        KNOWN.put(block, light);
        return light;
    }

    /** Whether the block has been asked. */
    public static boolean known(Block block) {
        return KNOWN.containsKey(block);
    }

    /** The answers are forgotten (a config reload: another mod's config may change what a block gives). */
    public static void forget() {
        KNOWN.clear();
    }
}
