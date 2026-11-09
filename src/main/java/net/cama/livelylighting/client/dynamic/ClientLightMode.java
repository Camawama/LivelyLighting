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

    /** Whether this client draws dynamic light itself: the server leaves it to the client and the renderer is on. */
    public static boolean drawsOwnLight() {
        if (renderer().equals("off")) return false;
        if (FORCED_DRAW) return true;
        LightSourceNet.ModeInfo i = info;
        return i != null && i.mode().clientDraws() && !i.serverLightsForYou();
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
