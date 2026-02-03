package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.minecraft.core.particles.ParticleType;

import java.util.List;

public class LightCluster {
    public double x, y, z;
    public float strength;
    public int count;
    public boolean isNewSource;
    public List<ParticleType<?>> particles;
    public List<SoundData> sounds;
    public int maxLightLevel;
    
    public double minX, minY, minZ;
    public double maxX, maxY, maxZ;
    public boolean first = true;

    public void add(double ex, double ey, double ez, int light, boolean isNew, List<ParticleType<?>> p, List<SoundData> s) {
        x += ex;
        y += ey;
        z += ez;
        count++;
        
        if (isNew) {
            isNewSource = true;
            particles = p;
            sounds = s;
        }
        
        if (light > maxLightLevel) {
            maxLightLevel = light;
        }
        
        if (first) {
            minX = ex;
            maxX = ex;
            minY = ey;
            maxY = ey;
            minZ = ez;
            maxZ = ez;
            first = false;
        } else {
            if (ex < minX) minX = ex;
            if (ex > maxX) maxX = ex;
            if (ey < minY) minY = ey;
            if (ey > maxY) maxY = ey;
            if (ez < minZ) minZ = ez;
            if (ez > maxZ) maxZ = ez;
        }
    }

    public void normalize() {
        if (count > 0) {
            x /= count;
            y /= count;
            z /= count;
            
            double dx = maxX - minX;
            double dy = maxY - minY;
            double dz = maxZ - minZ;
            double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
            
            strength = (float) (maxLightLevel + distance);
        }
    }
}
