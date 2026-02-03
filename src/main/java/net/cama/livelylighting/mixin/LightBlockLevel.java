package net.cama.livelylighting.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Level.class)
public abstract class LightBlockLevel {

    @Redirect(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;markAndNotifyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;II)V"
            )
    )
    private void livelylighting$redirectMarkAndNotify(Level instance, BlockPos pos, LevelChunk chunk, BlockState oldState, BlockState newState, int flags, int recursionLeft) {
        if (oldState.is(Blocks.LIGHT) && newState.isAir()) {
            flags |= 16; // Prevent neighbor reactions (Flag 16)
        }
        instance.markAndNotifyBlock(pos, chunk, oldState, newState, flags, recursionLeft);
    }
}
