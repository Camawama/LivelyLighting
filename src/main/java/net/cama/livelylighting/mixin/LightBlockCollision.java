package net.cama.livelylighting.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockBehaviour; // Target this class
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.class)
public class LightBlockCollision {

    /**
     * This mixin prevents Minecarts from colliding with Light Blocks.
     * Without this, Minecarts might get stuck or slowed down when passing through dynamic lights,
     * as Light Blocks can sometimes have a collision shape that interacts poorly with entities.
     */
    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void onGetCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {

        // 1. Strict Filter: Only run logic if the block is a Light Block
        if (state.getBlock() instanceof LightBlock) {

            // 2. Context Filter: Check if a Minecart is trying to collide
            if (context instanceof EntityCollisionContext entityContext && entityContext.getEntity() instanceof AbstractMinecart) {

                // 3. Force "No Collision"
                cir.setReturnValue(Shapes.empty());
            }
        }
    }
}