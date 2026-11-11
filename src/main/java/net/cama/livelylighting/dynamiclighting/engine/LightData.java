package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.cama.livelylighting.source.LightShape;
import net.minecraft.core.particles.ParticleType;

import java.util.List;
import java.util.Objects;

/**
 * What a definition gives a source: its light level, whether water puts it out, its effects, and since 0.5.0 its shape
 * (point or spot), the spot's cone angle, its reach and its colour ({@code -1}: Colorful Lighting's colour for the item
 * or block, else white). The server module reads the level and the effects; the client module draws the shape and the
 * colour too.
 */
public class LightData {
    public final int level;
    public final boolean waterSensitive;
    public final List<ParticleType<?>> particles;
    public final List<SoundData> sounds;
    public final List<SoundData> extinguishSounds;
    public final LightShape shape;
    /** The spot's outer angle, degrees; unused for a point. */
    public final float coneDegrees;
    /** Reach in blocks; 0 means the default for the shape (a point's level, a spot's {@link LightShape#DEFAULT_SPOT_RANGE}). */
    public final float range;
    /**
     * {@code 0xRRGGBB}, {@link #BLACKLIGHT} for ultraviolet, or -1 for the colour Colorful Lighting gives the item or block
     * (white without it).
     */
    public final int color;
    /**
     * The key in a stack's tag that names this light's colour (0.7.0, {@code "color_nbt"} in an item definition), or null:
     * one item, a colour a stack. A stack without the key keeps {@link #color}.
     */
    public final String colorNbt;
    /** This definition in each colour its stacks have named, made once a colour. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, LightData> tinted = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ultraviolet (0.7.0): the colour of a blacklight, the value Colorful Lighting 2.11.0 takes for one
     * ({@code DynamicLightSink.BLACKLIGHT}). It shows as a dim violet and makes saturated colours glow.
     */
    public static final int BLACKLIGHT = 0x1000000;
    /** What a blacklight is drawn as where nothing can make colours glow: its own violet. */
    public static final int BLACKLIGHT_SHOWN = 0x6020FF;

    /**
     * Or'd into a colour: a light that absorbs light (0.8.0, Colorful Lighting 2.12.0's {@code DynamicLightSink.ABSORB}).
     * Its red, green and blue say how much of each channel it takes; {@code "absorb"} in a definition takes all of it. Only
     * the client module draws it, and only with a Colorful Lighting that knows it: it is never a light block, never light
     * for the game's rules, and nothing at all for the server module.
     */
    public static final int ABSORB = 0x2000000;

    public static boolean isAbsorber(int rgb) {
        return rgb >= 0 && (rgb & ABSORB) != 0;
    }

    /** A light's colour as plain {@code 0xRRGGBB}: a blacklight's violet, any other colour itself, -1 kept. */
    public static int shown(int rgb) {
        return rgb == BLACKLIGHT ? BLACKLIGHT_SHOWN : isAbsorber(rgb) ? 0 : rgb;
    }

    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds, List<SoundData> extinguishSounds,
                     LightShape shape, float coneDegrees, float range, int color) {
        this(level, waterSensitive, particles, sounds, extinguishSounds, shape, coneDegrees, range, color, null);
    }

    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds, List<SoundData> extinguishSounds,
                     LightShape shape, float coneDegrees, float range, int color, String colorNbt) {
        this.colorNbt = colorNbt == null || colorNbt.isBlank() ? null : colorNbt.trim();
        this.level = level;
        this.waterSensitive = waterSensitive;
        this.particles = particles;
        this.sounds = sounds;
        this.extinguishSounds = extinguishSounds;
        this.shape = shape == null ? LightShape.POINT : shape;
        this.coneDegrees = coneDegrees > 0 ? coneDegrees : LightShape.DEFAULT_CONE_DEGREES;
        this.range = Math.max(0f, range);
        this.color = color;
    }

    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds, List<SoundData> extinguishSounds) {
        this(level, waterSensitive, particles, sounds, extinguishSounds, LightShape.POINT, 0f, 0f, -1);
    }

    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds) {
        this(level, waterSensitive, particles, sounds, null);
    }

    /** The same definition at another level (a fade, a forced level). */
    public LightData withLevel(int newLevel) {
        return new LightData(newLevel, waterSensitive, particles, sounds, extinguishSounds, shape, coneDegrees, range, color, colorNbt);
    }

    /** The same definition in another colour (the one a stack's tag names); itself for its own colour. */
    public LightData withColor(int newColor) {
        if (newColor == color) return this;
        if (tinted.size() > 512) tinted.clear(); // colours without end (a script cycling hues): start over
        return tinted.computeIfAbsent(newColor, c -> new LightData(level, waterSensitive, particles, sounds, extinguishSounds, shape, coneDegrees, range, c, colorNbt));
    }

    /** The reach in blocks this light is drawn to. */
    public float reach() {
        if (range > 0) return range;
        return shape == LightShape.SPOT ? LightShape.DEFAULT_SPOT_RANGE : level;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LightData that)) return false;
        return level == that.level &&
               waterSensitive == that.waterSensitive &&
               shape == that.shape &&
               Float.compare(coneDegrees, that.coneDegrees) == 0 &&
               Float.compare(range, that.range) == 0 &&
               color == that.color &&
               Objects.equals(colorNbt, that.colorNbt) &&
               Objects.equals(particles, that.particles) &&
               Objects.equals(sounds, that.sounds) &&
               Objects.equals(extinguishSounds, that.extinguishSounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, waterSensitive, particles, sounds, extinguishSounds, shape, coneDegrees, range, color, colorNbt);
    }
}
