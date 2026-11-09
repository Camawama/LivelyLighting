package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.source.Falloff;
import net.cama.livelylighting.source.LightShape;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One dynamic light the client draws this tick: where it is (and where it will be at any moment of the next frame, through
 * its {@link Mover}), its level after fades, its colour, its shape and reach, and (0.6.2) the entity it asks to be drawn lit
 * by it. Built by {@link ClientLightEngine}; handed to the renderers (Colorful Lighting's dynamic sources, or the standalone
 * lightmap path) as positions.
 */
public final class DynamicLight {
    /** Where a light is at a moment of the frame: an entity's interpolated position, a ship's render pose. */
    @FunctionalInterface
    public interface Mover {
        Vec3 at(float partialTick);
    }

    /** What the light is attached to; the owner key tells the fades and the renderers which light is which across ticks. */
    public enum Kind { ENTITY, ITEM_ENTITY, SHIP_LAMP, CONTRAPTION_LAMP, SHIP_FIRE, FORCED, API }

    public final long ownerKey;
    public final Kind kind;
    public final Mover mover;
    @Nullable
    public final Mover direction;
    /** The level this tick, after fades; may be fractional while fading. */
    public final float level;
    /** {@code 0xRRGGBB}, or -1 for the renderer's default (Colorful Lighting's colour for the source, else white). */
    public final int rgb;
    public final LightShape shape;
    public final float outerDegrees;
    public final float range;
    public final Falloff falloff;
    /** The tick position, for the CPU paths and the fades. */
    public final Vec3 pos;
    /** The entity carrying this light that asks to be drawn at least {@link #glowLevel} bright (and its riders with it); -1 for none. */
    public final int glowEntity;
    /** How bright the carrier is drawn at least (0 to 15), after fades. */
    public final float glowLevel;

    public DynamicLight(long ownerKey, Kind kind, Mover mover, @Nullable Mover direction, float level, int rgb, LightShape shape,
                        float outerDegrees, float range, Falloff falloff) {
        this(ownerKey, kind, mover, direction, level, rgb, shape, outerDegrees, range, falloff, -1, 0f);
    }

    public DynamicLight(long ownerKey, Kind kind, Mover mover, @Nullable Mover direction, float level, int rgb, LightShape shape,
                        float outerDegrees, float range, Falloff falloff, int glowEntity, float glowLevel) {
        this.ownerKey = ownerKey;
        this.kind = kind;
        this.mover = mover;
        this.direction = direction;
        this.level = level;
        this.rgb = rgb;
        this.shape = shape;
        this.outerDegrees = outerDegrees;
        this.range = range;
        this.falloff = falloff;
        this.pos = mover.at(1.0f);
        this.glowEntity = glowEntity;
        this.glowLevel = glowLevel;
    }

    public boolean isSpot() {
        return shape == LightShape.SPOT && direction != null;
    }

    /** The inner, fully lit angle. */
    public float innerDegrees() {
        return outerDegrees * LightShape.INNER_SHARE;
    }

    /** The level left at a world point, by the falloff, 0 when out of reach. Spot lights are cut by their cone. */
    public double levelAt(double x, double y, double z) {
        double dx = x - pos.x, dy = y - pos.y, dz = z - pos.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > range * range) return 0;
        double l;
        if (isSpot()) {
            Vec3 dir = direction.at(1.0f);
            double d = Math.sqrt(d2);
            if (d < 1.0e-6) return level;
            double cos = (dx * dir.x + dy * dir.y + dz * dir.z) / d;
            double outer = Math.cos(Math.toRadians(outerDegrees * 0.5));
            double inner = Math.cos(Math.toRadians(innerDegrees() * 0.5));
            double cone = cos <= outer ? 0.0 : inner <= outer ? 1.0 : Math.min(1.0, (cos - outer) / (inner - outer));
            if (cone <= 0.0) return 0;
            // a beam dims with the distance along it over its range, not per block as a point light does
            l = level * (1.0 - d / range) * cone;
        } else {
            l = falloff.levelAt(level, dx, dy, dz);
        }
        return Math.max(0, l);
    }
}
