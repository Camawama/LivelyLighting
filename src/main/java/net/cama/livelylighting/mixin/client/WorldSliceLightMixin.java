package net.cama.livelylighting.mixin.client;

import net.cama.livelylighting.client.dynamic.ClientLights;
import net.cama.livelylighting.client.dynamic.StandaloneLights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The standalone lightmap renderer with Embeddium: its chunk builders read block light from their world slice, not from
 * {@code LevelRenderer.getLightColor}, so the slice's block light is raised here. Pseudo: applies only where Embeddium's
 * slice class exists.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.WorldSlice", remap = false)
public abstract class WorldSliceLightMixin {
    @Inject(method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true, require = 0)
    private void livelylighting$dynamicLight(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (layer != LightLayer.BLOCK || !StandaloneLights.active() || !ClientLights.standalone()) return;
        int dynamic = StandaloneLights.blockLightAt(pos.getX(), pos.getY(), pos.getZ());
        if (dynamic > cir.getReturnValue()) cir.setReturnValue(dynamic);
    }
}
