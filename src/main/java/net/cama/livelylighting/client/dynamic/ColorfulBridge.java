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
    private static MethodHandle fieldEnabled, fieldHolds, blocksMoved;
    /** Whether Colorful Lighting was last told to bake the lights into meshes (then no light has a slab). */
    private static boolean meshLighting = true;
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
            try {
                Class<?> field = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightField");
                fieldEnabled = lookup.findStatic(field, "enabled", MethodType.methodType(boolean.class));
                fieldHolds = lookup.findStatic(field, "holds", MethodType.methodType(boolean.class, long.class));
            } catch (ReflectiveOperationException e) {
                fieldEnabled = fieldHolds = null;
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

    /**
     * Gives Colorful Lighting the ships' blocks for the volume the dynamic lights are propagated through (2.9.0,
     * {@code setExternalBlockFiller}): without them a ship's hull stopped no light and its glass tinted none. Older
     * Colorful Lightings have no such hook; then ships stay transparent to dynamic light, logged once.
     */
    static void registerShipBlocks() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> compat = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat");
            Class<?> fillerType = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.VoxelOcclusion$BlockFiller");
            Class<?> writerType = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.VoxelOcclusion$BlockWriter");
            MethodHandle set = lookup.findVirtual(writerType, "set", MethodType.methodType(void.class, int.class, int.class, int.class,
                    net.minecraft.world.level.block.state.BlockState.class, net.minecraft.core.BlockPos.class));
            MethodHandle register = lookup.findStatic(compat, "setExternalBlockFiller", MethodType.methodType(void.class, fillerType));
            Object filler = Proxy.newProxyInstance(fillerType.getClassLoader(), new Class<?>[]{fillerType}, (proxy, method, args) -> {
                if (method.getName().equals("fill") && args != null && args.length == 6) {
                    Object writer = args[5];
                    ShipBlocks.fill((ClientLevel) args[0], (Integer) args[1], (Integer) args[2], (Integer) args[3], (Integer) args[4], (x, y, z, state, pos) -> {
                        try {
                            set.invoke(writer, x, y, z, state, pos);
                        } catch (Throwable ignored) {
                        }
                    });
                    return null;
                }
                return switch (method.getName()) {
                    case "toString" -> "LivelyLighting ship blocks";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            });
            register.invoke(filler);
            try {
                blocksMoved = lookup.findStatic(compat, "externalBlocksMoved", MethodType.methodType(void.class));
            } catch (ReflectiveOperationException e) {
                blocksMoved = null; // before 2.10.3: the volume follows a moving ship every ten ticks
            }
            LOGGER.info("[LivelyLighting] ships' blocks shadow and tint dynamic light");
        } catch (Throwable t) {
            LOGGER.info("[LivelyLighting] this Colorful Lighting takes no structure blocks for dynamic light (2.9.0+): ships don't shadow it ({})", t.toString());
        }
    }

    /**
     * Tells Colorful Lighting (2.10.0, {@code DynamicLightsCompat.setEntityGlow}) who says how bright an entity carrying a light is
     * drawn at least: it answers an entity's packed light itself, before the mixin on the block light is asked. Older Colorful
     * Lightings have no such hook; then carriers are lit only by what reaches them, logged once.
     */
    static void setEntityGlow(java.util.function.ToIntFunction<net.minecraft.world.entity.Entity> glow) {
        try {
            Class<?> compat = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat");
            MethodHandles.publicLookup().findStatic(compat, "setEntityGlow", MethodType.methodType(void.class, java.util.function.ToIntFunction.class)).invoke(glow);
            LOGGER.info("[LivelyLighting] what carries a light is drawn lit by it in Colorful Lighting");
        } catch (Throwable t) {
            LOGGER.info("[LivelyLighting] this Colorful Lighting takes no carrier glow (2.10.0+): a kart under its headlights is lit only by what reaches it ({})", t.toString());
        }
    }

    /** A ship with lamps moved: Colorful Lighting (2.10.3) rebuilds its block volume on the next tick instead of within ten. */
    static void blocksMoved() {
        if (blocksMoved == null) return;
        try {
            blocksMoved.invoke();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Tells Colorful Lighting (2.10.3) the colour of a carrier's glow and which lights an entity is not lit by (its own beams).
     * Older ones have neither hook: the glow stays white and a beam's holder is lit by what its cone happens to cover.
     */
    static void setEntityLights(java.util.function.ToIntFunction<net.minecraft.world.entity.Entity> glowColor,
                                java.util.function.Function<net.minecraft.world.entity.Entity, long[]> own) {
        try {
            Class<?> compat = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            lookup.findStatic(compat, "setEntityGlowColor", MethodType.methodType(void.class, java.util.function.ToIntFunction.class)).invoke(glowColor);
            lookup.findStatic(compat, "setEntityOwnLights", MethodType.methodType(void.class, java.util.function.Function.class)).invoke(own);
        } catch (Throwable t) {
            LOGGER.info("[LivelyLighting] this Colorful Lighting lights a beam's holder by the beam (2.10.3+ leaves it out): {}", t.toString());
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
        meshLighting = meshes;
        if (setMeshLighting == null) return;
        try {
            setMeshLighting.invoke(meshes);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether a light may be shown now without shining through walls: Colorful Lighting holds the slab that says where its
     * light may go (2.10.0), or nothing here uses slabs (no Colorful Lighting, an older one, the lightmap renderer,
     * transmission off). A light beyond the slabs never gets one; its caller gives up waiting.
     */
    static boolean slabReady(long key) {
        if (!registered || meshLighting || fieldEnabled == null || fieldHolds == null) return true;
        try {
            return !(boolean) fieldEnabled.invoke() || (boolean) fieldHolds.invoke(key);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void collect(ClientLightEngine engine, ClientLevel level, float partialTick, Object sink) throws Throwable {
        List<DynamicLight> lights = engine.lights();
        for (DynamicLight light : lights) {
            Vec3 p = light.mover.at(partialTick);
            if (light.isSpot()) {
                Vec3 d = light.direction.at(partialTick);
                spot.invoke(sink, light.ownerKey, p.x, p.y, p.z, d.x, d.y, d.z, light.outerDegrees, light.innerDegrees(), light.levelAtFrame(partialTick), light.rgb, light.range);
            } else {
                point.invoke(sink, light.ownerKey, p.x, p.y, p.z, light.levelAtFrame(partialTick), light.rgb, light.falloff.ordinal(), light.range);
            }
        }
    }
}
