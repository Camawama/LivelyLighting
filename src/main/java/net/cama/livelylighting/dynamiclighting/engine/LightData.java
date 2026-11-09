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
    /** {@code 0xRRGGBB}, or -1 for the colour Colorful Lighting gives the item or block (white without it). */
    public final int color;

    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds, List<SoundData> extinguishSounds,
                     LightShape shape, float coneDegrees, float range, int color) {
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
        return new LightData(newLevel, waterSensitive, particles, sounds, extinguishSounds, shape, coneDegrees, range, color);
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
               Objects.equals(particles, that.particles) &&
               Objects.equals(sounds, that.sounds) &&
               Objects.equals(extinguishSounds, that.extinguishSounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, waterSensitive, particles, sounds, extinguishSounds, shape, coneDegrees, range, color);
    }
}
