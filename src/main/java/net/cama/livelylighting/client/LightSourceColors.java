package net.cama.livelylighting.client;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.dynamiclighting.engine.LightSources;
import net.cama.livelylighting.network.LightSourceNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The client half of light_source_colors: the server says what each of the mod's light blocks round the player stands
 * for (LightSources), this works out the colour Colorful Lighting gives that source and answers Colorful Lighting's block
 * light colour API with it, so the light keeps its source's colour (the owner, 2026-10-12: a fast holder's trail, a light
 * fading after it was put away, two ships' lamps merged into one light and the dim edge of a world lamp's light on a ship
 * all came out white). An entity's light takes the colour of what is in the slot it comes from, once this client's copy
 * of the entity holds what the server's did (a few ticks' grace for its equipment to arrive); a lamp's, the colour its
 * light leaves it with (Colorful Lighting's light stored round it, a filter beside it included); a merged light, every
 * source's per channel, as light adds up. A light keeps its colour while it fades. A light the server said nothing about
 * gets -1 here, and Colorful Lighting's own guess. Registered by reflection: no compile-time dependency on Colorful
 * Lighting, nothing happens without it or without the colour helpers of its version (logged once).
 * {@code -Dlivelylighting.noLightSourceColors=true} (read every tick) answers nothing, for an A/B.
 */
@Mod.EventBusSubscriber(modid = LivelyLighting.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LightSourceColors {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks an entity source may wait for this client's copy of the entity to hold what the server's did. */
    private static final int ENTITY_GRACE_TICKS = 20;
    /** Ticks a lamp source is retried while its light isn't stored round it yet (a ship's chunks just arrived). */
    private static final int LAMP_RETRY_TICKS = 200;
    /** Ticks a light whose block isn't (or is no longer) here is kept: the server sends a light before its block. */
    private static final int ORPHAN_TICKS = 60;

    /** A light the server described: its sources, the colour worked out (-1: none yet) and when. */
    private static final class Light {
        long[] sources;
        int rgb = -1;
        long received;
        boolean pending;
    }

    private static final Long2ObjectOpenHashMap<Light> LIGHTS = new Long2ObjectOpenHashMap<>();
    private static ClientLevel lightsLevel;
    private static boolean dirty;
    private static long ticks;

    /** What Colorful Lighting's light and meshing threads read: block position -> 0xRRGGBB, for {@link #publishedLevel}. */
    private static volatile Long2IntOpenHashMap published = new Long2IntOpenHashMap();
    private static volatile Level publishedLevel;

    private static MethodHandle entityColor, itemColor, lampColor, fireColor, defaultColor, lightView, notifyChanged;
    private static final java.util.concurrent.atomic.LongAdder ANSWERED = new java.util.concurrent.atomic.LongAdder();

    private LightSourceColors() {}

    /** {lights known, lights with a colour, lights waiting for a source, Colorful Lighting questions answered} (the harness). */
    public static long[] stats() {
        int coloured = 0, waiting = 0;
        for (Light light : LIGHTS.values()) {
            if (light.rgb >= 0) coloured++;
            if (light.pending) waiting++;
        }
        return new long[]{LIGHTS.size(), coloured, waiting, ANSWERED.sum()};
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("colorful_lighting") || !ModList.get().isLoaded("camafy")) return;
        event.enqueueWork(() -> {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> dyn = Class.forName("net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat");
                entityColor = lookup.findStatic(dyn, "entityLightColor", MethodType.methodType(int.class, Entity.class, boolean.class));
                itemColor = lookup.findStatic(dyn, "itemLightColor", MethodType.methodType(int.class, ItemStack.class));
                lampColor = lookup.findStatic(dyn, "lampLightColor", MethodType.methodType(int.class, Level.class, BlockPos.class));
                fireColor = lookup.findStatic(dyn, "fireLightColor", MethodType.methodType(int.class));
                defaultColor = lookup.findStatic(dyn, "defaultLightColor", MethodType.methodType(int.class));
                Class<?> api = Class.forName("net.camacraft.colorfullighting.api.ColorfulLightingApi");
                Class<?> view = Class.forName("net.camacraft.colorfullighting.api.CLLightView");
                lightView = lookup.findStatic(api, "getLightView", MethodType.methodType(view, Level.class));
                notifyChanged = lookup.findVirtual(view, "notifyBlockChanged", MethodType.methodType(void.class, BlockPos.class));
                Class<?> provider = Class.forName("net.camacraft.colorfullighting.api.CLBlockLightColorProvider");
                Object instance = Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[]{provider}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLightColor" -> colorAt((Level) args[0], (BlockPos) args[1], (BlockState) args[2]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Lively Lighting: light source colours";
                    default -> null;
                });
                api.getMethod("registerBlockLightColorProvider", provider).invoke(null, instance);
                LightSourceNet.setClientSink(LightSourceColors::accept);
                MinecraftForge.EVENT_BUS.addListener(LightSourceColors::onClientTick);
                LOGGER.info("[LivelyLighting] light blocks take their source's colour in Colorful Lighting");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("[LivelyLighting] this Colorful Lighting can't give light blocks their source's colour ({}); its own guess stays", e.toString());
            }
        });
    }

    /** Colorful Lighting's question (its light worker and meshing threads): the colour, or -1 to pass. */
    private static int colorAt(Level level, BlockPos pos, BlockState state) {
        if (level != publishedLevel || !state.is(Blocks.LIGHT)) return -1;
        int rgb = published.get(pos.asLong());
        if (rgb >= 0) ANSWERED.increment();
        return rgb;
    }

    /** Whether this answers for the light block at {@code pos} (any thread). */
    static boolean knows(Level level, BlockPos pos) {
        return level == publishedLevel && published.containsKey(pos.asLong());
    }

    /** The server's word on some lights (client thread). */
    private static void accept(List<LightSourceNet.Entry> entries) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || Boolean.getBoolean("livelylighting.noLightSourceColors")) return;
        if (level != lightsLevel) reset(level);
        List<BlockPos> changed = new ArrayList<>();
        for (LightSourceNet.Entry entry : entries) {
            Light light = LIGHTS.get(entry.pos());
            if (light == null) {
                light = new Light();
                LIGHTS.put(entry.pos(), light);
            }
            light.sources = entry.sources();
            light.received = ticks;
            if (resolve(level, light) && level.getBlockState(BlockPos.of(entry.pos())).is(Blocks.LIGHT)) {
                // the block came first (or this light changed source): Colorful Lighting lights it again with the colour
                changed.add(BlockPos.of(entry.pos()));
            }
        }
        publish(level);
        notifyChanged(level, changed);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ticks++;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lightsLevel || Boolean.getBoolean("livelylighting.noLightSourceColors")) {
            if (lightsLevel != null || !LIGHTS.isEmpty() || publishedLevel != null) reset(null);
            if (level != null && !Boolean.getBoolean("livelylighting.noLightSourceColors")) lightsLevel = level;
            return;
        }
        if (LIGHTS.isEmpty()) return;
        List<BlockPos> changed = new ArrayList<>();
        boolean sweep = ticks % 20 == 0;
        for (ObjectIterator<Long2ObjectOpenHashMap.Entry<Light>> it = LIGHTS.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectOpenHashMap.Entry<Light> entry = it.next();
            Light light = entry.getValue();
            if (sweep && ticks - light.received > ORPHAN_TICKS && !level.getBlockState(BlockPos.of(entry.getLongKey())).is(Blocks.LIGHT)) {
                it.remove();
                dirty = true;
                continue;
            }
            if (light.pending && ticks % 2 == 0 && resolve(level, light)) {
                BlockPos pos = BlockPos.of(entry.getLongKey());
                if (level.getBlockState(pos).is(Blocks.LIGHT)) changed.add(pos);
            }
        }
        publish(level);
        notifyChanged(level, changed);
    }

    private static void reset(ClientLevel level) {
        LIGHTS.clear();
        lightsLevel = level;
        Long2IntOpenHashMap empty = new Long2IntOpenHashMap();
        empty.defaultReturnValue(-1);
        published = empty;
        publishedLevel = null;
        dirty = false;
    }

    private static void publish(ClientLevel level) {
        if (!dirty) return;
        Long2IntOpenHashMap next = new Long2IntOpenHashMap(LIGHTS.size());
        next.defaultReturnValue(-1);
        for (Long2ObjectOpenHashMap.Entry<Light> entry : LIGHTS.long2ObjectEntrySet()) {
            if (entry.getValue().rgb >= 0) next.put(entry.getLongKey(), entry.getValue().rgb);
        }
        published = next;
        publishedLevel = level;
        dirty = false;
    }

    private static void notifyChanged(ClientLevel level, List<BlockPos> changed) {
        if (changed.isEmpty() || lightView == null) return;
        try {
            Object view = lightView.invoke((Level) level);
            if (view == null) return;
            for (BlockPos pos : changed) notifyChanged.invoke(view, pos);
        } catch (Throwable e) {
            LOGGER.debug("[LivelyLighting] couldn't relight coloured lights: {}", e.toString());
        }
    }

    /**
     * Works out a light's colour from its sources: the light each source gives (its colour at its own level, as
     * Colorful Lighting spreads light per channel), the brightest of each channel, brought back to full intensity for
     * the brightest source (the light block's level scales it again), so a burning mob's fire (15) colours its light
     * and the plain white of the enchanted sword it holds (6) only warms it (every source counted at full strength,
     * white won every channel, 2026-10-17); {@code pending} while a source can't be told yet. True when the colour
     * changed.
     */
    private static boolean resolve(ClientLevel level, Light light) {
        int r = -1, g = -1, b = -1, brightest = 0;
        boolean pending = false;
        long[] sources = light.sources;
        for (int i = 0; i + 1 < sources.length; i += 2) {
            int c = colourOf(level, light, LightSources.kindOf(sources[i]), sources[i + 1]);
            if (c == PENDING) {
                pending = true;
                continue;
            }
            if (c < 0) continue;
            int sourceLevel = LightSources.levelOf(sources[i]);
            if (sourceLevel <= 0) sourceLevel = 15; // an older server: every source at full
            brightest = Math.max(brightest, sourceLevel);
            r = Math.max(r, ((c >> 16) & 0xFF) * sourceLevel);
            g = Math.max(g, ((c >> 8) & 0xFF) * sourceLevel);
            b = Math.max(b, (c & 0xFF) * sourceLevel);
        }
        light.pending = pending;
        if (r >= 0 && brightest > 0) {
            r = Math.min(255, r / brightest);
            g = Math.min(255, g / brightest);
            b = Math.min(255, b / brightest);
        }
        int rgb = r < 0 ? light.rgb : r << 16 | g << 8 | b;
        if (rgb == light.rgb) return false;
        light.rgb = rgb;
        dirty = true;
        return true;
    }

    private static final int PENDING = -2;

    private static int colourOf(ClientLevel level, Light light, int kind, long value) {
        long waited = ticks - light.received;
        try {
            switch (kind) {
                case LightSources.ENTITY -> {
                    Entity entity = level.getEntity(LightSources.entityId(value));
                    if (entity == null) return waited < ENTITY_GRACE_TICKS ? PENDING : -1;
                    int slot = LightSources.slot(value);
                    // this client's copy of the entity doesn't hold what the server's did yet: wait for it a little (of a
                    // body's light only whether it burns: other entities' effects, glowing among them, aren't sent to clients)
                    boolean caughtUp = slot == LightSources.SLOT_BODY
                            ? (LightSources.token(value) == LightSources.BODY_FIRE) == entity.isOnFire()
                            : (LightSources.token(entity, slot) & 0x0FFFFFFF) == LightSources.token(value);
                    if (!caughtUp && waited < ENTITY_GRACE_TICKS) return PENDING;
                    int c = switch (slot) {
                        case LightSources.SLOT_MAINHAND -> stackColour(entity, EquipmentSlot.MAINHAND);
                        case LightSources.SLOT_OFFHAND -> stackColour(entity, EquipmentSlot.OFFHAND);
                        case LightSources.SLOT_HEAD -> stackColour(entity, EquipmentSlot.HEAD);
                        case LightSources.SLOT_CHEST -> stackColour(entity, EquipmentSlot.CHEST);
                        case LightSources.SLOT_LEGS -> stackColour(entity, EquipmentSlot.LEGS);
                        case LightSources.SLOT_FEET -> stackColour(entity, EquipmentSlot.FEET);
                        case LightSources.SLOT_CURIOS -> curioColour(entity);
                        case LightSources.SLOT_CARRIED -> {
                            BlockState carried = entity instanceof EnderMan enderman ? enderman.getCarriedBlock() : null;
                            yield carried == null ? -1 : (int) itemColor.invokeExact(new ItemStack(carried.getBlock()));
                        }
                        case LightSources.SLOT_BODY -> LightSources.token(value) == LightSources.BODY_FIRE
                                ? (int) fireColor.invokeExact() : (int) entityColor.invokeExact(entity, false);
                        // an item frame's light is the item in it (Colorful Lighting has no colour for the frame itself)
                        default -> entity instanceof ItemFrame frame && !frame.getItem().isEmpty()
                                ? (int) itemColor.invokeExact(frame.getItem()) : (int) entityColor.invokeExact(entity, true);
                    };
                    // a source with no colour of its own (an enchanted sword's glow) is plain light, as Colorful Lighting draws it
                    return c >= 0 ? c : (int) defaultColor.invokeExact();
                }
                case LightSources.WORLD_LAMP, LightSources.SHIP_LAMP -> {
                    int c = (int) lampColor.invokeExact((Level) level, BlockPos.of(value));
                    return c >= 0 ? c : waited < LAMP_RETRY_TICKS ? PENDING : -1;
                }
                case LightSources.FIRE -> {
                    return (int) fireColor.invokeExact();
                }
                default -> {
                    return -1;
                }
            }
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int stackColour(Entity entity, EquipmentSlot slot) throws Throwable {
        if (!(entity instanceof LivingEntity living)) return -1;
        ItemStack stack = living.getItemBySlot(slot);
        return stack.isEmpty() ? -1 : (int) itemColor.invokeExact(stack);
    }

    /** The first worn curio Colorful Lighting knows a colour for (a lantern on a belt), else none. */
    private static int curioColour(Entity entity) throws Throwable {
        if (!(entity instanceof LivingEntity living)) return -1;
        for (ItemStack stack : net.cama.livelylighting.compat.CuriosLights.worn(living)) {
            int c = (int) itemColor.invokeExact(stack);
            if (c >= 0) return c;
        }
        return -1;
    }
}
