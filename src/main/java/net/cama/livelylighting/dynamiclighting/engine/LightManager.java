package net.cama.livelylighting.dynamiclighting.engine;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.data.LivelyLightingData;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID)
public class LightManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final LightGameState gameState = new LightGameState();

    // Track players who need an update due to inventory change
    private static final Set<Integer> dirtyPlayers = new HashSet<>();

    // Chunks that loaded and may contain orphaned light blocks from a previous
    // session/crash or an unload mid-fade. ChunkEvent.Load can fire off-thread,
    // so only the chunk position is queued; the sweep runs on the level tick.
    private static final Map<ResourceKey<Level>, Queue<ChunkPos>> pendingChunkSweeps = new ConcurrentHashMap<>();
    private static final int MAX_CHUNK_SWEEPS_PER_TICK = 16;

    /**
     * How often (ticks) the placed-light records are written to disk while they have changed. A
     * crash loses whatever the save data hadn't written yet while the chunks, which save on their
     * own as they unload, keep the light blocks: those blocks have no record and nothing (not even
     * a purge of the records) finds them again. A minute bounds that window; the file is small.
     */
    private static final int RECORD_FLUSH_INTERVAL = 1200;

    /** What a purge did, for the command's reply and the log. */
    public record PurgeResult(int recordedRemoved, int recordsKeptUnloaded, int unrecordedRemoved, boolean everyLightBlock) {
        public String describe() {
            String text = "Lively Lighting purge: removed " + recordedRemoved + " light block(s) the mod had placed, kept "
                    + recordsKeptUnloaded + " record(s) of blocks in unloaded chunks for their chunk load, and cleared every cache and queue.";
            if (everyLightBlock) {
                text += " Also removed " + unrecordedRemoved + " light block(s) without a record from the loaded chunks around the players and in the ships (logged).";
            }
            return text;
        }
    }

    public static void reloadConfig() {
        LivelyConfig.load();
        LightCalculator.reloadConfig();
    }

    public static void purgeAllLights(MinecraftServer server) {
        purgeAllLights(server, false);
    }

    /**
     * Removes every light block the mod knows it placed and forgets every source, cache and queue,
     * so the next tick starts from nothing but the live entities and lamps. With
     * {@code everyLightBlock}, also removes every minecraft:light block, recorded or not, in the
     * loaded chunks within view distance of each player and in every loaded ship's shipyard: the
     * way to be rid of light blocks whose records a crash lost. Those positions are logged, since a
     * light block someone placed on purpose there goes with them.
     */
    public static PurgeResult purgeAllLights(MinecraftServer server, boolean everyLightBlock) {
        int recordedRemoved = 0;
        int keptUnloaded = 0;
        int unrecordedRemoved = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimension = level.dimension();
            Map<BlockPos, Integer> lights = gameState.levelLights.get(dimension);
            if (lights != null) {
                for (BlockPos pos : lights.keySet()) {
                    if (LightPropagator.removeLight(level, pos)) recordedRemoved++;
                }
                lights.clear();
            }
            // Also clear recorded stragglers in loaded chunks (crash leftovers, ship
            // lights, etc.). Records in unloaded chunks are kept for the load sweep.
            LivelyLightingData data = LivelyLightingData.get(level);
            for (BlockPos pos : new ArrayList<>(data.getPlacedLights(dimension))) {
                if (LightPropagator.loadedState(level, pos) == null) keptUnloaded++;
                else if (LightPropagator.removeLight(level, pos)) recordedRemoved++;
            }
            if (everyLightBlock) {
                unrecordedRemoved += removeEveryLightBlock(level);
            }
        }
        gameState.clear();
        LightLogic.clearCaches();
        pendingChunkSweeps.clear();
        dirtyPlayers.clear();
        PurgeResult result = new PurgeResult(recordedRemoved, keptUnloaded, unrecordedRemoved, everyLightBlock);
        LOGGER.info("[LivelyLighting] {}", result.describe());
        return result;
    }

    /**
     * Every minecraft:light block in the loaded chunks around the level's players (their view
     * distance) and in the shipyards of the level's loaded ships, removed and logged. Only chunks
     * that are loaded right now are read; nothing waits for a chunk.
     */
    private static int removeEveryLightBlock(ServerLevel level) {
        Set<Long> chunks = new HashSet<>();
        int viewChunks = Math.max(2, level.getServer().getPlayerList().getViewDistance());
        for (Player player : level.players()) {
            int cx = player.blockPosition().getX() >> 4;
            int cz = player.blockPosition().getZ() >> 4;
            for (int dx = -viewChunks; dx <= viewChunks; dx++) {
                for (int dz = -viewChunks; dz <= viewChunks; dz++) {
                    chunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
        if (Ships.available()) {
            Ships.forEachLoaded(level, ship -> {
                BoundingBox blocks = ship.shipyardBlocks();
                if (blocks == null) return;
                for (int cx = blocks.minX() >> 4; cx <= blocks.maxX() >> 4; cx++) {
                    for (int cz = blocks.minZ() >> 4; cz <= blocks.maxZ() >> 4; cz++) {
                        chunks.add(ChunkPos.asLong(cx, cz));
                    }
                }
            });
        }
        int removed = 0;
        for (long key : chunks) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            List<BlockPos> found = new ArrayList<>();
            LevelChunkSection[] sections = chunk.getSections();
            int baseX = chunk.getPos().x << 4;
            int baseZ = chunk.getPos().z << 4;
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) continue;
                if (!section.maybeHas(state -> state.is(Blocks.LIGHT))) continue;
                int sectionMinY = level.getSectionYFromSectionIndex(sectionIndex) << 4;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (section.getBlockState(x, y, z).is(Blocks.LIGHT)) {
                                found.add(new BlockPos(baseX + x, sectionMinY + y, baseZ + z));
                            }
                        }
                    }
                }
            }
            for (BlockPos pos : found) {
                boolean recorded = LivelyLightingData.get(level).getPlacedLights(level.dimension()).contains(pos);
                if (LightPropagator.removeLight(level, pos)) {
                    if (!recorded) {
                        removed++;
                        LOGGER.info("[LivelyLighting] purge all: removed a light block without a record at {} in {}", pos, level.dimension().location());
                    }
                }
            }
        }
        return removed;
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

        LivelyConfig config = LivelyConfig.get();
        if (!config.enable) return;

        ServerLevel level = (ServerLevel) event.level;

        sweepOrphanedLights(level);
        flushRecords(level);

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

        // Valkyrien Skies is reached through Camafy, which answers "no ships" without it;
        // available() is false when VS is absent or Camafy switched its ship support off.
        boolean vsAvailable = Ships.available();
        LightLogic.runLightLogic(level, config, gameState, vsAvailable, vsAvailable && config.vs_support);
    }

    /**
     * Writes the placed-light records once a minute while they have changed (see
     * RECORD_FLUSH_INTERVAL). Only this mod's own file, where the overworld's data storage keeps it,
     * so no other mod's save data is written at this moment.
     */
    private static void flushRecords(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return; // the records live in the overworld's save data
        if (level.getGameTime() % RECORD_FLUSH_INTERVAL != 0) return;
        LivelyLightingData data = LivelyLightingData.get(level);
        if (data.isDirty()) {
            java.nio.file.Path file = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data").resolve(LivelyLightingData.DATA_NAME + ".dat");
            data.save(file.toFile());
        }
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
        return lights != null && lights.containsKey(pos);
    }

    /** The engine's state, for the gametests. */
    public static LightGameState gameState() {
        return gameState;
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        purgeAllLights(event.getServer());
    }
}
