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

    // What the cluster's light stands for (see LightSources): every member's source, so a
    // merged light keeps the colour of each lamp or holder in it on clients.
    private long[] sources = LightSources.NONE;

    // Members that are a ship's own lamps projected into the world. A cluster of nothing else
    // never grows (LightPropagator.calculateLightField): growth is for lights spread over a span
    // (several holders' torches); a ship's lamps already light the ship, and a merged cluster of
    // them grown by their span put up to a hundred light blocks round its centroid, all moving
    // with the ship (2026-10-17).
    public int lampMembers;

    /** Counts the member just added as a ship's lamp. */
    public LightCluster lamp() {
        lampMembers++;
        return this;
    }

    // the level of the member last added: its source is noted at that level (LightSources.levelOf)
    private int lastLevel = 15;

    /**
     * Adds the source of the member just added (LightSources.ENTITY, WORLD_LAMP, SHIP_LAMP or FIRE with its value), at
     * that member's level: clients colour a merged light by its brightest source first.
     */
    public LightCluster source(int kind, long value) {
        sources = LightSources.with(sources, LightSources.packed(kind, lastLevel), value);
        return this;
    }

    public long[] sources() {
        return sources;
    }

    public void add(double ex, double ey, double ez, int light, boolean player, int carry) {
        lastLevel = light;
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
