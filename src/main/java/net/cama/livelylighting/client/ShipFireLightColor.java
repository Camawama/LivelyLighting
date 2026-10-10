package net.cama.livelylighting.client;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyLighting;
import net.camacraft.camafy.client.render.ShipFireRender;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Colorful Lighting colours a light block Lively Lighting placed by the coloured entity nearest it (a burning entity:
 * fire's colour). A burning Valkyrien Skies ship is no entity, so its lights ({@code ship_fire_lights}) came out pure
 * red. On a client with Colorful Lighting, this answers Colorful Lighting's block light colour API for a light block in
 * or beside a ship this client draws burning (Camafy's {@code ShipFireRender}) with the colour Colorful Lighting gives a
 * burning entity (its {@code minecraft:fire} colour), and -1 for every other block, so every other light keeps its
 * colour. Colorful Lighting asks the API before its own lookup for a dynamic light block since 2026-10-04 (before that
 * the lookup answered first and this changed nothing). Registered by
 * reflection: no compile-time dependency, and nothing happens without Colorful Lighting or Camafy.
 * {@code -Dlivelylighting.noShipFireColor=true} (read every few ticks) answers nothing, for an A/B.
 */
@Mod.EventBusSubscriber(modid = LivelyLighting.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShipFireLightColor {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Colorful Lighting's minecraft:fire colour, should its config not be readable. */
    private static final int DEFAULT_FIRE_COLOR = 0xFF894D;
    /** Ticks between two looks at the ships round the camera. */
    private static final int REFRESH_TICKS = 2;
    /** How far from the camera burning ships are looked for (blocks). */
    private static final double RANGE = 192.0;
    /** Light blocks this far beyond a burning ship's world box (the world light snaps up to 3) or shipyard box count. */
    private static final int WORLD_MARGIN = 3;
    private static final int SHIPYARD_MARGIN = 2;

    /** Per burning ship: its world box then its shipyard box, each minX, minY, minZ, maxX, maxY, maxZ, already grown. */
    private static volatile double[] boxes = new double[0];
    private static volatile int fireColor = DEFAULT_FIRE_COLOR;
    private static int ticks;
    private static final java.util.concurrent.atomic.LongAdder ASKED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder ANSWERED = new java.util.concurrent.atomic.LongAdder();

    /** {light blocks Colorful Lighting asked about, answered with fire's colour, burning ship boxes now} (the harness). */
    public static long[] stats() {
        return new long[]{ASKED.sum(), ANSWERED.sum(), boxes.length / 6};
    }

    private ShipFireLightColor() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("colorful_lighting") || !ModList.get().isLoaded("camafy")) return;
        event.enqueueWork(() -> {
            try {
                Class<?> provider = Class.forName("net.camacraft.colorfullighting.api.CLBlockLightColorProvider");
                Object instance = Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[]{provider}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLightColor" -> colorAt((BlockPos) args[1], (BlockState) args[2]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Lively Lighting: burning ship lights";
                    default -> null;
                });
                Class.forName("net.camacraft.colorfullighting.api.ColorfulLightingApi")
                        .getMethod("registerBlockLightColorProvider", provider).invoke(null, instance);
                MinecraftForge.EVENT_BUS.addListener(ShipFireLightColor::onClientTick);
                LOGGER.info("[LivelyLighting] burning ships' lights take fire's colour in Colorful Lighting");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("[LivelyLighting] couldn't give burning ships' lights fire's colour in Colorful Lighting: {}", e.toString());
            }
        });
    }

    /** Colorful Lighting's question (its light worker and meshing threads): fire's colour, or -1 to pass. */
    private static int colorAt(BlockPos pos, BlockState state) {
        double[] b = boxes;
        if (!state.is(Blocks.LIGHT)) return -1;
        ASKED.increment();
        if (b.length == 0) return -1;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        for (int i = 0; i + 5 < b.length; i += 6) {
            if (x >= b[i] && y >= b[i + 1] && z >= b[i + 2] && x <= b[i + 3] && y <= b[i + 4] && z <= b[i + 5]) {
                ANSWERED.increment();
                return fireColor;
            }
        }
        return -1;
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % REFRESH_TICKS != 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !Ships.available() || Boolean.getBoolean("livelylighting.noShipFireColor")) {
            boxes = new double[0];
            return;
        }
        Vec3 at = mc.gameRenderer.getMainCamera().getPosition();
        List<double[]> found = new ArrayList<>();
        for (ShipRef ship : Ships.intersecting(level, new AABB(at, at).inflate(RANGE))) {
            if (!ShipFireRender.burning(ship.id())) continue;
            AABB world = ship.worldAabb();
            BoundingBox yard = ship.shipyardBlocks();
            if (world != null) {
                found.add(new double[]{world.minX - WORLD_MARGIN, world.minY - WORLD_MARGIN, world.minZ - WORLD_MARGIN,
                        world.maxX + WORLD_MARGIN, world.maxY + WORLD_MARGIN, world.maxZ + WORLD_MARGIN});
            }
            if (yard != null) {
                found.add(new double[]{yard.minX() - SHIPYARD_MARGIN, yard.minY() - SHIPYARD_MARGIN, yard.minZ() - SHIPYARD_MARGIN,
                        yard.maxX() + 1 + SHIPYARD_MARGIN, yard.maxY() + 1 + SHIPYARD_MARGIN, yard.maxZ() + 1 + SHIPYARD_MARGIN});
            }
        }
        if (!found.isEmpty()) fireColor = readFireColor();
        double[] next = new double[found.size() * 6];
        for (int i = 0; i < found.size(); i++) System.arraycopy(found.get(i), 0, next, i * 6, 6);
        boxes = next;
    }

    /** The colour Colorful Lighting gives a burning entity: its configured minecraft:fire colour (as 0xRRGGBB). */
    private static int readFireColor() {
        try {
            Object color = Class.forName("net.camacraft.colorfullighting.common.Config")
                    .getMethod("getLightColor", net.minecraft.world.level.block.Block.class).invoke(null, Blocks.FIRE);
            if (color == null) return DEFAULT_FIRE_COLOR;
            Class<?> type = color.getClass();
            int r = type.getField("red4").getInt(color), g = type.getField("green4").getInt(color), b = type.getField("blue4").getInt(color);
            // RGB4 to RGB8 so that Colorful Lighting's >> 4 gives the same RGB4 back
            return (r * 17) << 16 | (g * 17) << 8 | b * 17;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return DEFAULT_FIRE_COLOR;
        }
    }
}
