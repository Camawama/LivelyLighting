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

/**
 * An entity's block light: raised to what a light it carries asks for it (the carrier glow, 0.6.2; Colorful Lighting's own
 * entity light takes it from {@code DynamicLightsCompat.entityGlow} instead, as its engine answers the packed light before
 * this is asked), and on the standalone lightmap renderer to the dynamic light at its place.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void livelylighting$dynamicLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int light = cir.getReturnValue();
        int glow = ClientLights.glowOf(entity);
        if (glow > light) {
            light = glow;
            cir.setReturnValue(light);
        }
        if (!StandaloneLights.active() || !ClientLights.standalone()) return;
        int dynamic = StandaloneLights.blockLightAt(pos.getX(), pos.getY(), pos.getZ());
        if (dynamic > light) cir.setReturnValue(dynamic);
    }
}
