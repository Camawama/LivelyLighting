package net.cama.livelylighting.source;

/**
 * How a dynamic light falls off with distance when the client draws it. {@code DIAMOND} is vanilla's look: a level is
 * lost per block of Manhattan distance, so a light's reach is a diamond; {@code ROUND} loses a level per block of
 * Euclidean distance, a round light. Server light blocks are always diamonds (vanilla propagates them).
 */
public enum Falloff {
    DIAMOND,
    ROUND;

    public static Falloff parse(String s, Falloff fallback) {
        if (s == null) return fallback;
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "round", "circular", "circle", "euclidean", "real" -> ROUND;
            case "diamond", "manhattan", "vanilla", "blocky" -> DIAMOND;
            default -> fallback;
        };
    }

    /** The light level left at a point {@code (dx, dy, dz)} from a source of {@code level}, by this falloff. */
    public double levelAt(double level, double dx, double dy, double dz) {
        double d = this == ROUND ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        return level - d;
    }
}
