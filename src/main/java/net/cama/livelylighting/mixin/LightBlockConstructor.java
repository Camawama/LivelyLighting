package net.cama.livelylighting.mixin;

import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LightBlock.class)
public class LightBlockConstructor {

    @ModifyVariable(
            method = "<init>",
            at = @At("HEAD"),
            argsOnly = true
    )
    private static BlockBehaviour.Properties livelyLighting$ModifyLightProperties(BlockBehaviour.Properties originalProperties) {
        return originalProperties
                .explosionResistance(0F)
                .pushReaction(PushReaction.DESTROY);
    }

}
