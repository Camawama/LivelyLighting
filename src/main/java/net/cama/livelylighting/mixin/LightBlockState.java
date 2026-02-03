package net.cama.livelylighting.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class LightBlockState {

    @Shadow public abstract Block getBlock();

    @Inject(
            method = "updateNeighbourShapes(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void livelylighting$cancelShapeUpdatesForLight(LevelAccessor level, BlockPos pos, int flags, int recursionLeft, CallbackInfo ci) {
        if (this.getBlock() == Blocks.LIGHT) {
            ci.cancel();
        }
    }
}
