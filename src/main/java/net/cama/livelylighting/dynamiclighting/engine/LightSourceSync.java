package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.network.LightSourceNet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells each client what the mod's light blocks round it stand for ({@link LightSources}; light_source_colors), after
 * every engine run. A light keeps the sources it had while it was planned; one fading out (its source gone, moved on
 * or put away) keeps the ones it was lit with, so its colour stays to the end. A player is sent each light of a chunk
 * it is sent blocks of (vanilla's watchers, and Valkyrien Skies' of a ship's chunks) once, again when its sources
 * change, and again after it stopped and started watching that chunk; nothing when nothing changed.
 */
public final class LightSourceSync {
    private LightSourceSync() {}

    static void afterRun(ServerLevel level, LivelyConfig config, LightGameState state, Map<BlockPos, Integer> current,
                         Map<BlockPos, long[]> desired) {
        ResourceKey<Level> dimension = level.dimension();
        if (!config.light_source_colors || !LightSourceNet.ready()) {
            state.lightSources.remove(dimension);
            state.lightSourcesSent.remove(dimension);
            return;
        }
        Map<BlockPos, long[]> sources = state.lightSources.computeIfAbsent(dimension, k -> new HashMap<>());
        sources.keySet().retainAll(current.keySet());
        for (Map.Entry<BlockPos, long[]> entry : desired.entrySet()) {
            long[] want = entry.getValue();
            if (want.length == 0 || !current.containsKey(entry.getKey())) continue;
            long[] had = sources.get(entry.getKey());
            if (had == null || !Arrays.equals(had, want)) sources.put(entry.getKey(), want);
        }

        Map<UUID, Map<BlockPos, long[]>> sentTo = state.lightSourcesSent.computeIfAbsent(dimension, k -> new HashMap<>());
        // players that left the level (logged out, changed dimension): sent everything again when they come back
        sentTo.keySet().removeIf(id -> !(level.getPlayerByUUID(id) instanceof ServerPlayer player) || !LightSourceNet.hasChannel(player));
        for (ServerPlayer player : level.players()) {
            if (LightSourceNet.hasChannel(player)) sentTo.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        }
        if (sentTo.isEmpty()) return;

        Map<Long, List<ServerPlayer>> watchers = new HashMap<>();
        Map<UUID, Set<BlockPos>> watched = new HashMap<>();
        Map<ServerPlayer, List<LightSourceNet.Entry>> out = new HashMap<>();
        for (Map.Entry<BlockPos, long[]> entry : sources.entrySet()) {
            BlockPos pos = entry.getKey();
            List<ServerPlayer> players = watchers.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4),
                    chunk -> level.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunk), false));
            for (ServerPlayer player : players) {
                Map<BlockPos, long[]> sent = sentTo.get(player.getUUID());
                if (sent == null) continue; // no channel, or another level's
                watched.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(pos);
                long[] told = sent.get(pos);
                if (told != null && Arrays.equals(told, entry.getValue())) continue;
                sent.put(pos, entry.getValue());
                out.computeIfAbsent(player, k -> new ArrayList<>()).add(new LightSourceNet.Entry(pos.asLong(), entry.getValue()));
            }
        }
        // a light gone, or in a chunk the player no longer watches: sent again should it be seen again
        for (Map.Entry<UUID, Map<BlockPos, long[]>> player : sentTo.entrySet()) {
            Set<BlockPos> seen = watched.get(player.getKey());
            if (seen == null) player.getValue().clear();
            else player.getValue().keySet().retainAll(seen);
        }
        out.forEach(LightSourceNet::send);
    }
}
