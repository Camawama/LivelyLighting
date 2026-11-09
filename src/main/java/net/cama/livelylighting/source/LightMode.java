package net.cama.livelylighting.source;

import java.util.Locale;

/**
 * Who shows dynamic light ({@code lighting_mode} in livelylighting.json, read by the server; the integrated server in
 * singleplayer too):
 * <ul>
 * <li>{@code SERVER_BLOCKS}: the server places light blocks for everyone, as before 0.5.0; a client with the mod draws
 * nothing of its own.</li>
 * <li>{@code CLIENT}: the server places nothing; clients with the mod draw the light, clients without see none.</li>
 * <li>{@code HYBRID} (default): the server places light blocks only where a player whose client lacks the mod can see
 * them; clients with the mod draw their own light and are told the server places nothing for them.</li>
 * </ul>
 * The server announces the mode to each client ({@code LightSourceNet.Mode}); a server without the packet is an older
 * Lively Lighting, taken as {@code SERVER_BLOCKS}.
 */
public enum LightMode {
    SERVER_BLOCKS,
    CLIENT,
    HYBRID;

    public static LightMode parse(String s, LightMode fallback) {
        if (s == null) return fallback;
        return switch (s.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "server", "server_blocks", "blocks", "light_blocks" -> SERVER_BLOCKS;
            case "client", "client_only", "render" -> CLIENT;
            case "hybrid", "both", "auto" -> HYBRID;
            default -> fallback;
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the server module places light blocks at all in this mode. */
    public boolean serverPlacesBlocks() {
        return this != CLIENT;
    }

    /** Whether a client that has the mod draws its own light in this mode. */
    public boolean clientDraws() {
        return this != SERVER_BLOCKS;
    }
}
