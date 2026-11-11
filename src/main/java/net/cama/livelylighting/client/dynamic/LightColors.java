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
    private static MethodHandle item, entity, block, lamp, blockLevel, blockAbsorb;
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
            try {
                lamp = lookup.findStatic(dyn, "lampLightColor", MethodType.methodType(int.class, net.minecraft.world.level.Level.class, net.minecraft.core.BlockPos.class));
            } catch (NoSuchMethodException e) {
                lamp = null;
            }
            try {
                blockLevel = lookup.findStatic(dyn, "blockLightLevel", MethodType.methodType(int.class, BlockState.class));
                blockAbsorb = lookup.findStatic(dyn, "blockAbsorption", MethodType.methodType(int.class, BlockState.class));
            } catch (NoSuchMethodException e) {
                blockLevel = blockAbsorb = null; // Colorful Lighting before 2.13.0: every block gives the light it says it gives
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

    /**
     * The colour a lamp's light leaves the blocks round it with (a ship's lamp behind the ship's stained glass: the glass's),
     * from the light Colorful Lighting has stored there; -1 while it has none or can't say.
     */
    static int ofLamp(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        bind();
        if (lamp == null || level == null) return -1;
        try {
            return (int) lamp.invoke(level, pos);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The level a block gives light at (0.9.0): Colorful Lighting's for it where it says one (2.13.0; its
     * {@code light/emitters.json} gives an end gateway none), else the block's own.
     */
    static int levelOf(BlockState state) {
        bind();
        if (blockLevel == null || state == null) return state == null ? 0 : state.getLightEmission();
        try {
            return (int) blockLevel.invoke(state);
        } catch (Throwable t) {
            return state.getLightEmission();
        }
    }

    /**
     * What a block takes away for Colorful Lighting (2.13.0, its {@code light/absorbers.json}: an end gateway, an end
     * portal): the level in the top byte over the channels taken as 0xRRGGBB, or 0 for a block that absorbs nothing.
     */
    static int absorbOf(BlockState state) {
        bind();
        if (blockAbsorb == null || state == null) return 0;
        try {
            return (int) blockAbsorb.invoke(state);
        } catch (Throwable t) {
            return 0;
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
