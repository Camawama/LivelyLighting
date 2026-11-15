package net.cama.livelylighting.client.dynamic;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.source.UvDefs;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.function.Function;

/**
 * Hands the list of what glows under ultraviolet ({@link UvDefs}, {@code config/livelylighting/uv_definitions}) to Colorful
 * Lighting (2.14.0), which draws it: its {@code compat.vanilla.EntityFluorescence} asks here, for each entity drawn while
 * ultraviolet is about, whether its type is on the list, and takes the listed textures when the list is read again. By
 * reflection, as the rest of the Colorful Lighting integration; an older Colorful Lighting has no such hook and the list
 * then does nothing, logged once. {@code client.uv_reactive_entities} switches it off.
 */
final class Fluorescence {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static MethodHandle setTextures;
    private static boolean registered;
    private static int sent = Integer.MIN_VALUE;

    private Fluorescence() {}

    static void init() {
        try {
            Class<?> fluorescence = Class.forName("net.camacraft.colorfullighting.compat.vanilla.EntityFluorescence");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Function<Entity, float[]> entities = Fluorescence::of;
            lookup.findStatic(fluorescence, "setProvider", MethodType.methodType(void.class, Function.class)).invoke(entities);
            setTextures = lookup.findStatic(fluorescence, "setTextures", MethodType.methodType(void.class, Map.class));
            registered = true;
            LOGGER.info("[LivelyLighting] what is on the uv_definitions glows under ultraviolet in Colorful Lighting");
        } catch (Throwable t) {
            LOGGER.info("[LivelyLighting] this Colorful Lighting draws no fluorescence (2.14.0+): the uv_definitions do nothing ({})", t.toString());
        }
    }

    /** Colorful Lighting asks on the render thread: the entity's glow, or null. */
    private static float[] of(Entity entity) {
        return LivelyConfig.get().client.uv_reactive_entities ? UvDefs.get(entity.getType()) : null;
    }

    /** On the client tick: the listed textures go to Colorful Lighting when the list was read again or the switch flipped. */
    static void tick() {
        if (!registered) return;
        boolean on = LivelyConfig.get().client.uv_reactive_entities;
        int now = UvDefs.version() * 2 + (on ? 1 : 0);
        if (now == sent) return;
        sent = now;
        try {
            setTextures.invoke(on ? UvDefs.textures() : Map.of());
        } catch (Throwable ignored) {
        }
    }
}
