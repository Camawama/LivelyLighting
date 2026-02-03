package net.cama.livelylighting.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side only: prevent minecraft:light from causing neighbor updates.
 *
 * This does NOT block lighting engine updates; it only blocks neighbor notifications.
 */
@Mixin(ServerLevel.class)
public abstract class LightBlockNoUpdate {

    @Inject(
            method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void livelylighting$cancelNeighborUpdatesForLight(BlockPos pos, Block block, CallbackInfo ci) {
        if (block == Blocks.LIGHT) {
            ci.cancel();
        }
    }

    @Inject(
            method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void livelylighting$cancelNeighborUpdatesExceptForLight(BlockPos pos, Block block, Direction skipSide, CallbackInfo ci) {
        if (block == Blocks.LIGHT) {
            ci.cancel();
        }
    }

    @Inject(
            method = "blockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void livelylighting$cancelBlockUpdatedForLight(BlockPos pos, Block block, CallbackInfo ci) {
        if (block == Blocks.LIGHT) {
            ci.cancel();
        }
    }
}
