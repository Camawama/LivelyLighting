package net.cama.livelylighting.mixin.client;

import net.cama.livelylighting.client.dynamic.ClientLights;
import net.cama.livelylighting.client.dynamic.StandaloneLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The standalone lightmap renderer: an entity drawn under a dynamic light is lit by it (its block light raised). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void livelylighting$dynamicLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!StandaloneLights.active() || !ClientLights.standalone()) return;
        int dynamic = StandaloneLights.blockLightAt(pos.getX(), pos.getY(), pos.getZ());
        if (dynamic > cir.getReturnValue()) cir.setReturnValue(dynamic);
    }
}
