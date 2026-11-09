package net.cama.livelylighting.mixin;

import net.cama.livelylighting.source.GameplayLights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic light as the world's own (0.6.0, {@link GameplayLights}): a server level's block light layer answers the dynamic
 * light where it is brighter than the stored light (mob spawning, snow and ice melting ask it through
 * {@code Level.getBrightness}), and the raw brightness ({@code getMaxLocalRawBrightness}: crops, many mods) on both sides,
 * the client's from what it draws. The engine's own work (updates, data layers, packets) is untouched.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Unique
    private LayerLightEventListener livelylighting$wrapper;
    @Unique
    private LayerLightEventListener livelylighting$wrapped;

    @Inject(method = "getRawBrightness", at = @At("RETURN"), cancellable = true)
    private void livelylighting$rawBrightness(BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        int dynamic = GameplayLights.levelAt((LevelLightEngine) (Object) this, pos);
        if (dynamic > cir.getReturnValueI()) cir.setReturnValue(dynamic);
    }

    @Inject(method = "getLayerListener", at = @At("RETURN"), cancellable = true)
    private void livelylighting$blockLayer(LightLayer layer, CallbackInfoReturnable<LayerLightEventListener> cir) {
        if (layer != LightLayer.BLOCK) return;
        LevelLightEngine self = (LevelLightEngine) (Object) this;
        if (!GameplayLights.wrapsEngine(self)) return;
        LayerLightEventListener inner = cir.getReturnValue();
        if (inner == null || inner instanceof GameplayLights.BlockListener) return;
        if (livelylighting$wrapper == null || livelylighting$wrapped != inner) {
            livelylighting$wrapped = inner;
            livelylighting$wrapper = new GameplayLights.BlockListener(self, inner);
        }
        cir.setReturnValue(livelylighting$wrapper);
    }
}
