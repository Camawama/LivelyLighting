package net.cama.livelylighting.client.dynamic;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.network.LightSourceNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * The client module (0.5.0): draws dynamic light on this client when the server leaves it to the client
 * ({@code lighting_mode} {@code client} or {@code hybrid}, told by the {@code Mode} message). Every client tick the
 * {@link ClientLightEngine} gathers the lights; Colorful Lighting draws them when it is present and new enough
 * ({@link ColorfulBridge}), else the standalone lightmap path does ({@link StandaloneLights} and the client mixins).
 * {@code client.renderer} picks the renderer: {@code auto} takes Colorful Lighting's shader path where it can run.
 */
public final class ClientLights {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ClientLightEngine ENGINE = new ClientLightEngine();
    private static boolean colorful;
    private static boolean announced;
    /** For tests: lights published this tick. */
    public static List<DynamicLight> lights() {
        return ENGINE.lights();
    }

    private ClientLights() {}

    public static void init() {
        LightSourceNet.setClientModeSink(ClientLightMode::accept);
        MinecraftForge.EVENT_BUS.addListener(ClientLights::onClientTick);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            ClientLightMode.reset();
            ENGINE.forget();
            StandaloneLights.setActive(false);
            announced = false;
        });
        if (ModList.get().isLoaded("colorful_lighting")) {
            colorful = ColorfulBridge.register(ENGINE);
        }
        LOGGER.info("[LivelyLighting] client module ready ({})", colorful ? "Colorful Lighting draws the light" : "standalone lightmap renderer");
    }

    /** Whether this client draws its own light now. */
    public static boolean drawing() {
        return ClientLightMode.drawsOwnLight() && Minecraft.getInstance().level != null;
    }

    /** Whether the standalone lightmap mixins apply (no Colorful Lighting to draw for us). */
    public static boolean standalone() {
        return !colorful;
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        boolean draw = ClientLightMode.drawsOwnLight();
        if (!draw) {
            if (!ENGINE.lights().isEmpty()) ENGINE.forget();
            if (StandaloneLights.active()) StandaloneLights.setActive(false);
            if (colorful) ColorfulBridge.setMeshLighting(true);
            return;
        }
        if (!announced) {
            announced = true;
            LOGGER.info("[LivelyLighting] drawing dynamic light on this client (server mode {}, renderer {})", ClientLightMode.serverMode().key(), ClientLightMode.renderer());
        }
        ENGINE.tick(level);
        LivelyConfig config = LivelyConfig.get();
        if (colorful) {
            ColorfulBridge.setLimits(config.client.shader_max_lights, config.client.shader_max_distance);
            ColorfulBridge.setRebuildBudget(Math.max(8, config.client.lightmap_rebuilds_per_tick * 4));
            ColorfulBridge.setRebuildBudget(Math.max(8, config.client.lightmap_rebuilds_per_tick * 4));
            // the lightmap renderer bakes the lights into meshes; the shader renderer leaves meshes to Colorful Lighting's shaders
            ColorfulBridge.setMeshLighting(!ShaderRenderer.active());
        } else {
            if (!StandaloneLights.active()) StandaloneLights.setActive(true);
            StandaloneLights.publish(level, ENGINE.lights(), config);
        }
    }

    /** Whether the shader renderer is the one drawing (Colorful Lighting's shader path, chosen by client.renderer). */
    static final class ShaderRenderer {
        static boolean active() {
            String r = ClientLightMode.renderer();
            if (r.equals("lightmap") || r.equals("off")) return false;
            if (!colorful) return false;
            return ColorfulBridge.shaderPathAvailable() && (r.equals("shader") || r.equals("auto"));
        }
    }
}
