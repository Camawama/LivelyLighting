package net.cama.livelylighting.source;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.data.LivelyLightingData;
import net.cama.livelylighting.network.LightSourceNet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The server's side of {@link LightMode}: which players the block engine lights for, and telling each client the mode.
 * {@code -Dlivelylighting.mode=server_blocks|client|hybrid} overrides the config for a run (the harness A/Bs).
 */
public final class LightModes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LightMode FORCED = LightMode.parse(System.getProperty("livelylighting.mode"), null);
    private static LightMode lastSeen;

    private LightModes() {}

    /** The mode in force on this server now. */
    public static LightMode current() {
        if (FORCED != null) return FORCED;
        return LightMode.parse(LivelyConfig.get().lighting_mode, LightMode.HYBRID);
    }

    /** Whether the block engine runs at all. */
    public static boolean serverPlacesBlocks() {
        return current().serverPlacesBlocks();
    }

    /**
     * Whether the block engine lights for this player: everyone in {@code server_blocks}; in {@code hybrid} only players
     * whose client has no Lively Lighting (no channel); nobody in {@code client}.
     */
    public static boolean lightsFor(ServerPlayer player) {
        return switch (current()) {
            case SERVER_BLOCKS -> true;
            case CLIENT -> false;
            case HYBRID -> !LightSourceNet.hasChannel(player);
        };
    }

    /** The players of a level the block engine lights for (see {@link #lightsFor}). */
    public static List<? extends Player> litFor(ServerLevel level) {
        if (current() == LightMode.SERVER_BLOCKS) return level.players();
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (lightsFor(p)) out.add(p);
        return out;
    }

    /** Whether any of the players is within a Chebyshev distance of the entity (so a light block near it would be seen). */
    public static boolean anyNear(Entity entity, List<? extends Player> players, double chebyshev) {
        for (Player p : players) {
            double dx = Math.abs(p.getX() - entity.getX()), dy = Math.abs(p.getY() - entity.getY()), dz = Math.abs(p.getZ() - entity.getZ());
            if (Math.max(dx, Math.max(dy, dz)) <= chebyshev) return true;
        }
        return false;
    }

    /** The mode message for one player. */
    public static LightSourceNet.ModeInfo infoFor(ServerPlayer player) {
        LivelyLightingData data = LivelyLightingData.get(player.serverLevel());
        return new LightSourceNet.ModeInfo(current(), lightsFor(player), data.forcedEntities(), data.disabledEntities());
    }

    public static void sendModeTo(ServerPlayer player) {
        if (!LightSourceNet.ready()) return;
        LightSourceNet.sendMode(player, infoFor(player));
    }

    /** After a change of mode, forced levels or disabled entities: every client is told again. */
    public static void broadcastMode(MinecraftServer server) {
        if (server == null || !LightSourceNet.ready()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sendModeTo(p);
    }

    /**
     * Called each server tick by the block engine: when the mode has just become {@code client}, the blocks already
     * placed are purged once, and every client is told the new mode on any change.
     */
    public static void tick(MinecraftServer server, Runnable purge) {
        LightMode now = current();
        if (now == lastSeen) return;
        LightMode before = lastSeen;
        lastSeen = now;
        if (before != null) {
            LOGGER.info("[LivelyLighting] lighting mode is now {} (was {})", now.key(), before.key());
            if (!now.serverPlacesBlocks()) purge.run();
            broadcastMode(server);
        }
    }

    public static void reset() {
        lastSeen = null;
    }
}
