package net.cama.livelylighting.source;

/**
 * The shape of a dynamic light. A {@code POINT} shines all round, as vanilla light does; a {@code SPOT} is a cone with a
 * direction, an outer angle (where it ends) and an inner angle (fully lit), for headlights on ships and contraptions and
 * for flashlights in hand. The server module (light blocks) can only place point light: a spot there is placed as a point
 * of its level, and the shape is drawn by the client module.
 */
public enum LightShape {
    POINT,
    SPOT;

    public static LightShape parse(String s, LightShape fallback) {
        if (s == null) return fallback;
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "spot", "cone", "headlight", "flashlight" -> SPOT;
            case "point", "normal", "vanilla" -> POINT;
            default -> fallback;
        };
    }

    /** The default outer angle of a spot light, degrees. */
    public static final float DEFAULT_CONE_DEGREES = 40f;
    /** The inner, fully lit angle as a share of the outer one. */
    public static final float INNER_SHARE = 0.6f;
    /** Default reach in blocks: a point light's is its level; a spot light's beam is longer. */
    public static final float DEFAULT_SPOT_RANGE = 32f;
}
