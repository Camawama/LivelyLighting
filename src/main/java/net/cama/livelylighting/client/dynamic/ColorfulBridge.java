package net.cama.livelylighting.client.dynamic;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Hands the client's lights to Colorful Lighting (2.8.0+), which draws them: into every light sample it answers (entities,
 * block entities, particles, chunk meshes with their rebuilds) in the source's colour, and under a shader pack through the
 * packed light the pack decodes. Reached by reflection, as the rest of the Colorful Lighting integration is, through its
 * {@code api.dynamic.DynamicLightProvider}: a proxy of that interface is registered with
 * {@code DynamicLightsCompat.registerExternalProvider}, and the sink's two methods are bound once. Stands down with a
 * warning when the installed Colorful Lighting is older.
 */
final class ColorfulBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean registered;
    private static MethodHandle point, spot, setMeshLighting, shaderAvailable, setLimits, setBudget, setTransmission, setPropagation;
    private static Boolean sentTransmission, sentPropagation;
    private static int sentLights = -1, sentBudget = -1;
    private static float sentDistance = -1f;

    private ColorfulBridge() {}

    /** Registers the provider; true when Colorful Lighting takes it. */
    static boolean register(ClientLightEngine engine) {
        if (registered) return true;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> compat = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat");
            Class<?> providerType = Class.forName("net.camacraft.colorfullighting.api.dynamic.DynamicLightProvider");
            Class<?> sinkType = Class.forName("net.camacraft.colorfullighting.api.dynamic.DynamicLightSink");
            point = lookup.findVirtual(sinkType, "point", MethodType.methodType(void.class, long.class, double.class, double.class, double.class,
                    float.class, int.class, int.class, float.class));
            spot = lookup.findVirtual(sinkType, "spot", MethodType.methodType(void.class, long.class, double.class, double.class, double.class,
                    double.class, double.class, double.class, float.class, float.class, float.class, int.class, float.class));
            MethodHandle register = lookup.findStatic(compat, "registerExternalProvider", MethodType.methodType(void.class, providerType));
            try {
                setMeshLighting = lookup.findStatic(compat, "setExternalMeshLighting", MethodType.methodType(void.class, boolean.class));
            } catch (NoSuchMethodException e) {
                setMeshLighting = null;
            }
            try {
                shaderAvailable = lookup.findStatic(compat, "shaderDynamicLightsAvailable", MethodType.methodType(boolean.class));
            } catch (NoSuchMethodException e) {
                shaderAvailable = null;
            }
            try {
                setBudget = lookup.findStatic(compat, "setExternalRebuildBudget", MethodType.methodType(void.class, int.class));
            } catch (NoSuchMethodException e) {
                setBudget = null;
            }
            try {
                Class<?> shaderLights = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights");
                setTransmission = lookup.findStatic(shaderLights, "setTransmission", MethodType.methodType(void.class, boolean.class));
            } catch (ReflectiveOperationException e) {
                setTransmission = null;
            }
            try {
                setPropagation = lookup.findStatic(compat, "setExternalPropagation", MethodType.methodType(void.class, boolean.class));
            } catch (NoSuchMethodException e) {
                setPropagation = null;
            }
            try {
                Class<?> shaderLights = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.ShaderDynamicLights");
                setLimits = lookup.findStatic(shaderLights, "setLimits", MethodType.methodType(void.class, int.class, float.class));
            } catch (ReflectiveOperationException e) {
                setLimits = null;
            }
            Object provider = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType}, (proxy, method, args) -> {
                if (method.getName().equals("collect") && args != null && args.length == 3) {
                    collect(engine, (ClientLevel) args[0], (Float) args[1], args[2]);
                    return null;
                }
                return switch (method.getName()) {
                    case "toString" -> "LivelyLighting dynamic lights";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            });
            register.invoke(provider);
            registered = true;
            LOGGER.info("[LivelyLighting] dynamic lights are drawn by Colorful Lighting");
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[LivelyLighting] Colorful Lighting is present but has no dynamic light provider API (2.8.0+): drawing light standalone ({})", t.toString());
            return false;
        }
    }

    /** Whether Colorful Lighting propagates point lights as lamps on the lightmap path (client.lightmap_propagate). */
    static void setPropagation(boolean on) {
        if (setPropagation == null || (sentPropagation != null && sentPropagation == on)) return;
        try {
            setPropagation.invoke(on);
            sentPropagation = on;
        } catch (Throwable ignored) {
        }
    }

    /** Light through blocks on the shader path (client.transmission: walls stop it, glass tints it), sent when it changes. */
    static void setTransmission(boolean on) {
        if (setTransmission == null || (sentTransmission != null && sentTransmission == on)) return;
        try {
            setTransmission.invoke(on);
            sentTransmission = on;
        } catch (Throwable ignored) {
        }
    }

    /** The lightmap path's remesh budget (client.lightmap_rebuilds_per_tick), sent when it changes. */
    static void setRebuildBudget(int sectionsPerTick) {
        if (setBudget == null || sectionsPerTick == sentBudget) return;
        try {
            setBudget.invoke(sectionsPerTick);
            sentBudget = sectionsPerTick;
        } catch (Throwable ignored) {
        }
    }

    /** The shader path's limits from the config (client.shader_max_lights, client.shader_max_distance), sent when they change. */
    static void setLimits(int lights, float distance) {
        if (setLimits == null || (lights == sentLights && distance == sentDistance)) return;
        try {
            setLimits.invoke(lights, distance);
            sentLights = lights;
            sentDistance = distance;
        } catch (Throwable ignored) {
        }
    }

    /** Whether Colorful Lighting can draw the lights in its terrain shaders now (Embeddium, no shader pack). */
    static boolean shaderPathAvailable() {
        if (shaderAvailable == null) return false;
        try {
            return (boolean) shaderAvailable.invoke();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether Colorful Lighting is to apply the lights to chunk meshes (the lightmap renderer) or leave them to its shaders. */
    static void setMeshLighting(boolean meshes) {
        if (setMeshLighting == null) return;
        try {
            setMeshLighting.invoke(meshes);
        } catch (Throwable ignored) {
        }
    }

    private static void collect(ClientLightEngine engine, ClientLevel level, float partialTick, Object sink) throws Throwable {
        List<DynamicLight> lights = engine.lights();
        for (DynamicLight light : lights) {
            Vec3 p = light.mover.at(partialTick);
            if (light.isSpot()) {
                Vec3 d = light.direction.at(partialTick);
                spot.invoke(sink, light.ownerKey, p.x, p.y, p.z, d.x, d.y, d.z, light.outerDegrees, light.innerDegrees(), light.level, light.rgb, light.range);
            } else {
                point.invoke(sink, light.ownerKey, p.x, p.y, p.z, light.level, light.rgb, light.falloff.ordinal(), light.range);
            }
        }
    }
}
