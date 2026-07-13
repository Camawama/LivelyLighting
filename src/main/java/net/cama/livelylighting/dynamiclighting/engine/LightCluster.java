package net.cama.livelylighting.dynamiclighting.engine;

public class LightCluster {
    public double x, y, z;
    public float strength;
    public int count;
    public int maxLightLevel;
    public boolean isPlayer;
    // Highest light level any member source had placed last tick. An established
    // source keeps this as its fade-in floor while moving, so fast movement never
    // re-triggers the ignition fade (which would dim the source below its level).
    public int carryLevel;

    public double minX, minY, minZ;
    public double maxX, maxY, maxZ;
    public boolean first = true;

    public void add(double ex, double ey, double ez, int light, boolean player, int carry) {
        x += ex;
        y += ey;
        z += ez;
        count++;

        if (light > maxLightLevel) {
            maxLightLevel = light;
        }

        if (carry > carryLevel) {
            carryLevel = carry;
        }

        if (player) {
            isPlayer = true;
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
