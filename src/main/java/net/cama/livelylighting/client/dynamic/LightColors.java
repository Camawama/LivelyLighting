package net.cama.livelylighting.client.dynamic;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The colour Colorful Lighting gives a light's source (0.6.0): a held or worn stack's, an entity's (its own, then what it
 * holds, then fire), a lamp block's. A definition without a {@code color} takes it, so a held glowstone block lights the
 * ground orange as the placed block does, and a ship's sea lantern lights the water its own blue. Reached by reflection, as
 * the rest of the Colorful Lighting integration is; -1 (white) without it or when it has no colour for the thing.
 */
final class LightColors {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static MethodHandle item, entity, block;
    private static boolean tried;

    private LightColors() {}

    private static synchronized void bind() {
        if (tried) return;
        tried = true;
        if (!ModList.get().isLoaded("colorful_lighting")) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> dyn = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat");
            item = lookup.findStatic(dyn, "itemLightColor", MethodType.methodType(int.class, ItemStack.class));
            entity = lookup.findStatic(dyn, "entityLightColor", MethodType.methodType(int.class, Entity.class, boolean.class));
            try {
                block = lookup.findStatic(dyn, "blockLightColor", MethodType.methodType(int.class, BlockState.class));
            } catch (NoSuchMethodException e) {
                block = null; // Colorful Lighting before 2.9.0: lamps on ships stay white
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.info("[LivelyLighting] this Colorful Lighting gives dynamic lights no colours ({}); they are white", e.toString());
        }
    }

    /** A held, worn or dropped stack's light colour, or -1. */
    static int ofItem(ItemStack stack) {
        bind();
        if (item == null || stack == null || stack.isEmpty()) return -1;
        try {
            return (int) item.invoke(stack);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** An entity's light colour as Colorful Lighting resolves it for a dynamic light (its own, what it holds, fire), or -1. */
    static int ofEntity(Entity e) {
        bind();
        if (entity == null || e == null) return -1;
        try {
            return (int) entity.invoke(e, true);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** A lamp block's light colour (Colorful Lighting 2.9.0+), or -1. */
    static int ofBlock(BlockState state) {
        bind();
        if (block == null || state == null) return -1;
        try {
            return (int) block.invoke(state);
        } catch (Throwable t) {
            return -1;
        }
    }
}
