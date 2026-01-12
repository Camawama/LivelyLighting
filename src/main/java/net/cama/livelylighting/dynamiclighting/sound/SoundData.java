package net.cama.livelylighting.dynamiclighting.sound;

import net.minecraft.sounds.SoundEvent;

import java.util.Objects;

public class SoundData {
    public final SoundEvent sound;
    public final float volume;
    public final float pitch;

    public SoundData(SoundEvent sound, float volume, float pitch) {
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SoundData)) return false;
        SoundData that = (SoundData) o;
        return Float.compare(that.volume, volume) == 0 &&
               Float.compare(that.pitch, pitch) == 0 &&
               Objects.equals(sound, that.sound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sound, volume, pitch);
    }
}
