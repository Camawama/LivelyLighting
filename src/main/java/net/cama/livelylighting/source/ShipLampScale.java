package net.cama.livelylighting.source;

import net.cama.livelylighting.LivelyConfig;
import net.camacraft.camafy.compat.vs.ShipRef;

/**
 * A lamp on a Valkyrien Skies ship drawn at another size reaches as far as its ship is scaled (0.6.8): a glowstone on a
 * ship at twice its size lights 30 blocks round it, on one at half its size 7.5, as bright at the lamp either way. The
 * client module stretches the light's falloff ({@link #stretch}, a beam's reach {@link #spotRange}); the server module,
 * whose light blocks lose a level per block whatever is asked, levels the block by the scale ({@link #blockLevel}), so a
 * small ship's lamps reach less and a large ship's no further than level 15 does.
 * {@code lamps_scale_with_ships} (or {@code -Dlivelylighting.lampsScaleWithShips=false}) is the switch.
 */
public final class ShipLampScale {
    /** The longest a beam on a scaled ship reaches: the widest volume Colorful Lighting keeps a light's paths in. */
    public static final float MAX_SPOT_RANGE = 48f;

    private ShipLampScale() {}

    public static boolean enabled(LivelyConfig config) {
        String forced = System.getProperty("livelylighting.lampsScaleWithShips");
        return forced != null ? Boolean.parseBoolean(forced) : config.lamps_scale_with_ships;
    }

    /** The ship's scale as its lamps take it: 1 with the switch off, for a ship at its own size and for one VS can't answer for. */
    public static double of(ShipRef ship, LivelyConfig config) {
        if (ship == null || !enabled(config)) return 1.0;
        double s = ship.scale();
        if (!(s > 0.0) || Double.isInfinite(s) || Math.abs(s - 1.0) < 1.0e-3) return 1.0;
        return s;
    }

    /** Blocks a point lamp of {@code level} loses a level over on a ship at {@code scale}, its reach held to {@code maxReach}. */
    public static float stretch(float level, double scale, float maxReach) {
        if (scale == 1.0 || level <= 0f) return 1f;
        if (scale < 1.0) return (float) Math.max(1.0 / 32.0, scale);
        float cap = Math.max(level, maxReach);
        return (float) Math.max(1.0, Math.min(scale, cap / level));
    }

    /** A beam's reach on a ship at {@code scale}; its cone is the same at any size. */
    public static float spotRange(float range, double scale) {
        if (scale == 1.0) return range;
        if (scale < 1.0) return (float) Math.max(1.0, range * scale);
        return (float) Math.min(range * scale, Math.max(range, MAX_SPOT_RANGE));
    }

    /** The level of the light block the server module places for a lamp of {@code emission} on a ship at {@code scale}. */
    public static int blockLevel(int emission, double scale) {
        if (scale == 1.0 || emission <= 0) return emission;
        return (int) Math.max(1, Math.min(15, Math.round(emission * scale)));
    }
}
