package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.compat.valkyrienskies.IVSCompat;
import net.cama.livelylighting.data.LivelyLightingData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID)
public class LightManager {

    private static final LightGameState gameState = new LightGameState();

    // Track players who need an update due to inventory change
    private static final Set<Integer> dirtyPlayers = new HashSet<>();

    // Chunks that loaded and may contain orphaned light blocks from a previous
    // session/crash or an unload mid-fade. ChunkEvent.Load can fire off-thread,
    // so only the chunk position is queued; the sweep runs on the level tick.
    private static final Map<ResourceKey<Level>, Queue<ChunkPos>> pendingChunkSweeps = new ConcurrentHashMap<>();
    private static final int MAX_CHUNK_SWEEPS_PER_TICK = 16;

    private static boolean isVsLoaded;
    private static boolean checkedVs = false;
    private static IVSCompat vsCompat;

    public static void reloadConfig() {
        LivelyConfig.load();
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

            // Also clear recorded stragglers in loaded chunks (crash leftovers, ship
            // lights, etc.). Records in unloaded chunks are kept for the load sweep.
            LivelyLightingData data = LivelyLightingData.get(level);
            for (BlockPos pos : new ArrayList<>(data.getPlacedLights(dimension))) {
                LightPropagator.removeLight(level, pos);
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
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() == null || event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        pendingChunkSweeps.computeIfAbsent(level.dimension(), k -> new ConcurrentLinkedQueue<>())
                          .add(event.getChunk().getPos());
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

        sweepOrphanedLights(level);

        // Equipment changes force an immediate update so hotbar swaps feel instant
        // even with light_update_interval > 1.
        boolean forced = false;
        for (Player player : level.players()) {
            if (dirtyPlayers.remove(player.getId())) {
                forced = true;
            }
        }
        if (level.getGameTime() % 600 == 0 && !dirtyPlayers.isEmpty()) {
            // Drop ids of players who logged off before their flag was consumed
            MinecraftServer server = level.getServer();
            dirtyPlayers.removeIf(id -> {
                for (ServerLevel l : server.getAllLevels()) {
                    if (l.getEntity(id) instanceof Player) return false;
                }
                return true;
            });
        }

        int interval = Math.max(1, config.light_update_interval);
        if (!forced && interval > 1 && level.getGameTime() % interval != 0) return;

        LightLogic.runLightLogic(level, config, gameState, vsCompat, isVsLoaded && config.vs_support && vsCompat != null);
    }

    private static void sweepOrphanedLights(ServerLevel level) {
        Queue<ChunkPos> queue = pendingChunkSweeps.get(level.dimension());
        if (queue == null || queue.isEmpty()) return;

        LivelyLightingData data = LivelyLightingData.get(level);
        Set<BlockPos> placed = data.getPlacedLights(level.dimension());
        if (placed.isEmpty()) {
            queue.clear();
            return;
        }

        int processed = 0;
        ChunkPos chunkPos;
        while (processed < MAX_CHUNK_SWEEPS_PER_TICK && (chunkPos = queue.poll()) != null) {
            processed++;

            List<BlockPos> inChunk = new ArrayList<>();
            for (BlockPos pos : placed) {
                if ((pos.getX() >> 4) == chunkPos.x && (pos.getZ() >> 4) == chunkPos.z) {
                    inChunk.add(pos);
                }
            }

            for (BlockPos pos : inChunk) {
                if (isTracked(level.dimension(), pos)) continue; // actively managed, leave it
                LightPropagator.removeLight(level, pos); // also drops the record
            }
        }
    }

    private static boolean isTracked(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Integer> lights = gameState.levelLights.get(dimension);
        if (lights != null && lights.containsKey(pos)) return true;
        for (Map<BlockPos, Integer> shipMap : gameState.shipLights.values()) {
            if (shipMap.containsKey(pos)) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        purgeAllLights(event.getServer());
    }
}
