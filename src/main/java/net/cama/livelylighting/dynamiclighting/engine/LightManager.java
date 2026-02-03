package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.compat.valkyrienskies.IVSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID)
public class LightManager {

    private static final LightGameState gameState = new LightGameState();
    private static final Set<UUID> playersDisabledSounds = new HashSet<>();
    
    // Track players who need an update due to inventory change
    private static final Set<Integer> dirtyPlayers = new HashSet<>();

    private static boolean isVsLoaded;
    private static boolean checkedVs = false;
    private static IVSCompat vsCompat;

    public static void togglePlayerSounds(UUID uuid) {
        if (playersDisabledSounds.contains(uuid)) {
            playersDisabledSounds.remove(uuid);
        } else {
            playersDisabledSounds.add(uuid);
        }
    }
    
    public static boolean arePlayerSoundsDisabled(UUID uuid) {
        return playersDisabledSounds.contains(uuid);
    }

    public static void reloadConfig() {
        LightCalculator.reloadConfig();
    }
    
    public static void purgeAllLights(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            Map<BlockPos, Integer> lights = gameState.levelLights.get(dimension);
            if (lights != null) {
                for (BlockPos pos : lights.keySet()) {
                    LightPropagator.removeLight(level, pos);
                }
                lights.clear();
            }
        }
        gameState.clear();
    }
    
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            dirtyPlayers.add(player.getId());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide) return;

        if (!checkedVs) {
            isVsLoaded = ModList.get().isLoaded("valkyrienskies");
            if (isVsLoaded) {
                try {
                    Class<?> clazz = Class.forName("net.cama.livelylighting.compat.valkyrienskies.VSCompat");
                    vsCompat = (IVSCompat) clazz.getDeclaredConstructor().newInstance();
                } catch (Throwable t) {
                    isVsLoaded = false;
                    t.printStackTrace();
                }
            }
            checkedVs = true;
        }

        LivelyConfig config = LivelyConfig.get();
        if (!config.enable) return;

        ServerLevel level = (ServerLevel) event.level;
        
        // Delegate to LightLogic
        LightLogic.runLightLogic(level, config, gameState, vsCompat, isVsLoaded && config.vs_support && vsCompat != null, dirtyPlayers);
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        purgeAllLights(event.getServer());
    }
}
