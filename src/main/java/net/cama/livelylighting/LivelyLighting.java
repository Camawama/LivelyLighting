package net.cama.livelylighting;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.command.LivelyLightingCommand;
import net.cama.livelylighting.dynamiclighting.engine.LightManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(LivelyLighting.MODID)
public class LivelyLighting
{
    public static final String MODID = "livelylighting";
    private static final Logger LOGGER = LogUtils.getLogger();

    public LivelyLighting(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        // Optional on either side: a server with the mod takes clients without it (its blocks light for them), and a client
        // with the mod joins servers without it (it draws its own light there, client.draw_without_server)
        context.registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (a, b) -> true));
        // dynamic light as the world's own light on the server (0.6.0), and Camafy's lightAt on both sides
        net.cama.livelylighting.source.GameplayLights.init();

        // What each light stands for, to clients that have the mod (light_source_colors); optional on both sides
        if (net.minecraftforge.fml.ModList.get().isLoaded("camafy")) {
            net.cama.livelylighting.network.LightSourceNet.register();
        }
        // the client module (0.5.0): draws dynamic light where the server leaves it to the client
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            modEventBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(net.cama.livelylighting.client.dynamic.ClientLights::init));
            // LambDynamicLights-format item light files in resource packs, read at every resource reload (0.5.1)
            modEventBus.addListener((net.minecraftforge.client.event.RegisterClientReloadListenersEvent e) ->
                    e.registerReloadListener(net.cama.livelylighting.client.dynamic.LdlDefinitions.INSTANCE));
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Load config on startup
        LivelyConfig.load();
        LightManager.reloadConfig();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("LivelyLighting server starting");
    }
    
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LivelyLightingCommand.register(event.getDispatcher());
    }
}
