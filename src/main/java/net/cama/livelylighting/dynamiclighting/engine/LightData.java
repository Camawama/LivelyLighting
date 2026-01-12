package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.minecraft.core.particles.ParticleType;

import java.util.List;
import java.util.Objects;

public class LightData {
    public final int level;
    public final boolean waterSensitive;
    public final List<ParticleType<?>> particles;
    public final List<SoundData> sounds;
    public final List<SoundData> extinguishSounds;

    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds, List<SoundData> extinguishSounds) {
        this.level = level;
        this.waterSensitive = waterSensitive;
        this.particles = particles;
        this.sounds = sounds;
        this.extinguishSounds = extinguishSounds;
    }
    
    public LightData(int level, boolean waterSensitive, List<ParticleType<?>> particles, List<SoundData> sounds) {
        this(level, waterSensitive, particles, sounds, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LightData)) return false;
        LightData that = (LightData) o;
        return level == that.level &&
               waterSensitive == that.waterSensitive &&
               Objects.equals(particles, that.particles) &&
               Objects.equals(sounds, that.sounds) &&
               Objects.equals(extinguishSounds, that.extinguishSounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, waterSensitive, particles, sounds, extinguishSounds);
    }
}
