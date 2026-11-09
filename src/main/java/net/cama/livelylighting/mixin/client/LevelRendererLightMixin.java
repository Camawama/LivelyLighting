package net.cama.livelylighting.mixin.client;

import net.cama.livelylighting.client.dynamic.ClientLights;
import net.cama.livelylighting.client.dynamic.StandaloneLights;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The standalone lightmap renderer (no Colorful Lighting): the packed light every vanilla mesh builder, block entity and
 * particle asks for a position has its block light raised to what the client's dynamic lights give there. Idle while
 * Colorful Lighting draws the lights (it answers this very lookup itself).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererLightMixin {
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"), cancellable = true)
    private static void livelylighting$dynamicLight(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!StandaloneLights.active() || !ClientLights.standalone()) return;
        int dynamic = StandaloneLights.blockLightAt(pos.getX(), pos.getY(), pos.getZ());
        if (dynamic <= 0) return;
        int packed = cir.getReturnValue();
        int block = LightTexture.block(packed);
        if (dynamic <= block) return;
        cir.setReturnValue(LightTexture.pack(dynamic, LightTexture.sky(packed)));
    }
}
