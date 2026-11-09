package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.network.LightSourceNet;
import net.cama.livelylighting.source.LightMode;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What this client knows of the server's lighting mode: the {@code Mode} message, or nothing (an older server, or no
 * Lively Lighting there: the server's blocks light, the client draws nothing). {@code -Dlivelylighting.clientRenderer}
 * and {@code -Dlivelylighting.clientMode=client} override the config and the server for a run (the harness A/Bs; the
 * second makes a client draw even where the server places blocks).
 */
public final class ClientLightMode {
    private static volatile LightSourceNet.@Nullable ModeInfo info;
    private static final String FORCED_RENDERER = System.getProperty("livelylighting.clientRenderer");
    private static final boolean FORCED_DRAW = "client".equalsIgnoreCase(System.getProperty("livelylighting.clientMode"));

    private ClientLightMode() {}

    public static void accept(LightSourceNet.ModeInfo received) {
        info = received;
    }

    public static void reset() {
        info = null;
    }

    @Nullable
    public static LightSourceNet.ModeInfo info() {
        return info;
    }

    public static LightMode serverMode() {
        LightSourceNet.ModeInfo i = info;
        return i == null ? LightMode.SERVER_BLOCKS : i.mode();
    }

    /**
     * Whether this client draws dynamic light itself: the server leaves it to the client and the renderer is on. A server
     * that said nothing (0.6.0): one without Lively Lighting at all (a vanilla server, another pack) leaves the light to this
     * client when {@code client.draw_without_server}; one with an older Lively Lighting places blocks and is never drawn over.
     */
    public static boolean drawsOwnLight() {
        if (renderer().equals("off")) return false;
        if (FORCED_DRAW) return true;
        LightSourceNet.ModeInfo i = info;
        if (i != null) return i.mode().clientDraws() && !i.serverLightsForYou();
        return LivelyConfig.get().client.draw_without_server && !serverHasMod();
    }

    private static net.minecraft.network.Connection checkedConnection;
    private static boolean checkedHasMod;

    /** Whether the server this client is on runs Lively Lighting (from the login handshake's mod list); true when unknown. */
    private static boolean serverHasMod() {
        net.minecraft.client.multiplayer.ClientPacketListener listener = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (listener == null) return true;
        net.minecraft.network.Connection connection = listener.getConnection();
        if (connection == checkedConnection) return checkedHasMod;
        boolean has = true;
        if (net.minecraftforge.network.NetworkHooks.isVanillaConnection(connection)) {
            has = false;
        } else {
            net.minecraftforge.network.ConnectionData data = net.minecraftforge.network.NetworkHooks.getConnectionData(connection);
            if (data != null && data.getModList() != null) has = data.getModList().contains(net.cama.livelylighting.LivelyLighting.MODID);
        }
        checkedConnection = connection;
        checkedHasMod = has;
        return has;
    }

    /** The renderer asked for: {@code auto}, {@code shader}, {@code lightmap} or {@code off}. */
    public static String renderer() {
        String r = FORCED_RENDERER != null ? FORCED_RENDERER : LivelyConfig.get().client.renderer;
        return r == null ? "auto" : r.trim().toLowerCase(Locale.ROOT);
    }

    /** The forced level of an entity (/ll add), or null. */
    @Nullable
    public static Integer forcedLevel(UUID uuid) {
        LightSourceNet.ModeInfo i = info;
        if (i == null) return null;
        Map<UUID, Integer> forced = i.forced();
        return forced == null ? null : forced.get(uuid);
    }

    /** Whether an entity's light is switched off (/ll toggle). */
    public static boolean disabled(UUID uuid) {
        LightSourceNet.ModeInfo i = info;
        if (i == null) return false;
        Set<UUID> d = i.disabled();
        return d != null && d.contains(uuid);
    }
}
