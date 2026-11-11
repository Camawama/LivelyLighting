package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.source.BlockLightDefs;
import net.cama.livelylighting.source.Falloff;
import net.cama.livelylighting.source.LightShape;
import net.cama.livelylighting.source.ShipLampScale;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lamps of Valkyrien Skies ships as dynamic lights on the client: every luminous block of a loaded ship (scanned every
 * 60 ticks, as the server module scans), capped per ship ({@code max_lamps_per_ship}: the brightest, then the nearest the
 * camera) and over all ships ({@code client.lightmap_max_moving_lamps}), each moving with its ship's render pose. A lamp
 * with a block definition (or in {@code #livelylighting:headlights}) is a spot light along its facing: a headlight. Needs
 * {@code vs_support}; light blocks count as lamps only with {@code light_blocks_are_lamps}.
 *
 * <p>Where nothing takes the ship's own blocks into account (the lightmap renderer, or transmission off), a point lamp
 * shines in the colour its light leaves the ship's blocks with (0.6.4): Colorful Lighting's stored light round the lamp in
 * the shipyard, so glowstone boxed in red glass lights the ground red, as it does through the shaders' block volume. Asked
 * again every {@link #HUE_TICKS} ticks, a lamp at a time.
 */
final class ShipLamps {
    private static final int RESCAN_TICKS = 60;

    private static final int HUE_TICKS = 20;

    private static final class Lamp {
        final BlockPos pos;
        final BlockState state;
        final int emission;
        /** What the block takes away instead (0.9.0): 0xRRGGBB, or 0 for a lamp that gives light. */
        final int absorb;
        /** The colour the lamp's light leaves the ship with, -1 unknown; asked at {@link #hueTick}. */
        int hue = -1;
        /** Far enough back that a new lamp is asked at once (not Long.MIN_VALUE: the tick minus it overflows). */
        long hueTick = -1_000_000L;

        Lamp(BlockPos pos, BlockState state, int emission, int absorb) {
            this.pos = pos;
            this.state = state;
            this.emission = emission;
            this.absorb = absorb;
        }
    }

    private record Scan(long tick, List<Lamp> lamps) {}

    private final Map<Long, Scan> scans = new HashMap<>();

    void forget() {
        scans.clear();
    }

    /** Whether a ship was found to carry lamps at its last scan. */
    boolean hasLamps(long shipId) {
        Scan scan = scans.get(shipId);
        return scan != null && !scan.lamps.isEmpty();
    }

    /** The ships' lamps this tick, as lights; nearest the camera first across ships, under the caps. */
    void collect(ClientLevel level, Vec3 camera, LivelyConfig config, Falloff falloff, List<DynamicLight> out) {
        if (!config.vs_support || !Ships.available()) {
            scans.clear();
            return;
        }
        long now = level.getGameTime();
        // the shaders' block volume holds the ship's blocks and tints the lamp itself; elsewhere the lamp carries the tint
        boolean ownHue = !ClientLights.slabsTintLamps();
        List<DynamicLight> found = new ArrayList<>();
        Ships.forEachLoaded(level, ship -> {
            if (!ship.isLoaded()) return;
            Scan scan = scans.get(ship.id());
            if (scan == null || now - scan.tick >= RESCAN_TICKS) {
                Scan old = scan;
                scan = new Scan(now, scan(ship, config));
                if (old != null) keepHues(old.lamps, scan.lamps);
                scans.put(ship.id(), scan);
            }
            List<Lamp> kept = cap(ship, scan.lamps, camera, config.max_lamps_per_ship);
            // a ship drawn at another size: its lamps reach as far as it is scaled (0.6.8)
            double scale = ShipLampScale.of(ship, config);
            float maxReach = Math.max(15, Math.min(48, config.client.scaled_lamp_max_reach));
            for (Lamp lamp : kept) {
                if (ownHue && lamp.absorb == 0 && now - lamp.hueTick >= HUE_TICKS) {
                    lamp.hueTick = now + (lamp.pos.hashCode() & 7);
                    lamp.hue = LightColors.ofLamp(level, lamp.pos);
                }
                found.add(toLight(ship, lamp, falloff, ownHue, scale, maxReach));
            }
        });
        List<DynamicLight> lamps = found;
        scans.keySet().removeIf(id -> {
            ShipRef ref = Ships.byId(level, id);
            return ref == null || !ref.exists();
        });
        int cap = Math.max(0, config.client.lightmap_max_moving_lamps);
        if (cap > 0 && lamps.size() > cap) {
            lamps.sort((a, b) -> Double.compare(a.pos.distanceToSqr(camera), b.pos.distanceToSqr(camera)));
            lamps = lamps.subList(0, cap);
        }
        out.addAll(lamps);
    }

    /** A lamp found again by a new scan keeps the hue it was given, and its turn to be asked. */
    private static void keepHues(List<Lamp> old, List<Lamp> fresh) {
        if (old.isEmpty() || fresh.isEmpty()) return;
        Map<BlockPos, Lamp> byPos = new HashMap<>();
        for (Lamp lamp : old) byPos.put(lamp.pos, lamp);
        for (Lamp lamp : fresh) {
            Lamp was = byPos.get(lamp.pos);
            if (was == null || was.state != lamp.state) continue;
            lamp.hue = was.hue;
            lamp.hueTick = was.hueTick;
        }
    }

    private static List<Lamp> scan(ShipRef ship, LivelyConfig config) {
        List<Lamp> lamps = new ArrayList<>();
        // what each block state is, asked once a scan: {the level it gives light at, what it takes away}. The level is
        // Colorful Lighting's for the block (0.9.0): an end gateway gives light for the game and none for Colorful Lighting,
        // which makes it take light away instead, and so it does here
        Map<BlockState, int[]> known = new java.util.IdentityHashMap<>();
        java.util.function.Function<BlockState, int[]> what = state -> known.computeIfAbsent(state, s -> {
            int absorb = LightColors.absorbOf(s);
            return new int[]{absorb != 0 ? 0 : LightColors.levelOf(s), absorb};
        });
        ship.forEachBlock(state -> {
            int[] is = what.apply(state);
            return is[0] > 0 || is[1] != 0;
        }, (pos, state) -> {
            if (state.is(Blocks.LIGHT) && !config.light_blocks_are_lamps) return;
            int[] is = what.apply(state);
            if (is[1] != 0) lamps.add(new Lamp(pos.immutable(), state, is[1] >>> 24, is[1] & 0xFFFFFF));
            else if (is[0] > 0) lamps.add(new Lamp(pos.immutable(), state, is[0], 0));
        });
        return lamps;
    }

    private static List<Lamp> cap(ShipRef ship, List<Lamp> lamps, Vec3 camera, int cap) {
        if (cap <= 0 || lamps.size() <= cap) return lamps;
        List<Lamp> ranked = new ArrayList<>(lamps);
        ranked.sort((a, b) -> {
            if (a.emission != b.emission) return Integer.compare(b.emission, a.emission);
            Vec3 wa = ship.toWorld(Vec3.atCenterOf(a.pos)), wb = ship.toWorld(Vec3.atCenterOf(b.pos));
            double da = wa == null ? Double.MAX_VALUE : wa.distanceToSqr(camera), db = wb == null ? Double.MAX_VALUE : wb.distanceToSqr(camera);
            return Double.compare(da, db);
        });
        return ranked.subList(0, cap);
    }

    private static DynamicLight toLight(ShipRef ship, Lamp lamp, Falloff falloff, boolean ownHue, double scale, float maxReach) {
        Vec3 centre = Vec3.atCenterOf(lamp.pos);
        DynamicLight.Mover mover = pt -> {
            Vec3 w = ship.toWorld(centre, pt >= 1.0f ? ShipRef.Pose.TICK : ShipRef.Pose.RENDER);
            return w == null ? centre : w;
        };
        BlockLightDefs.BlockLightData def = BlockLightDefs.get(lamp.state.getBlock());
        long key = (ship.id() << 24) ^ (lamp.pos.asLong() & 0xFFFFFFL) ^ 0x5A00000000000000L;
        if (lamp.absorb != 0) {
            // a block that takes light away (0.9.0): a light that absorbs, a level lost a block as its darkness is in the world
            float stretch = ShipLampScale.stretch(lamp.emission, scale, maxReach);
            return new DynamicLight(key, DynamicLight.Kind.SHIP_LAMP, mover, null, lamp.emission, net.cama.livelylighting.dynamiclighting.engine.LightData.ABSORB | lamp.absorb,
                    LightShape.POINT, 0f, lamp.emission * stretch, Falloff.DIAMOND, -1, 0f, null, stretch);
        }
        if (def != null && def.shape() == LightShape.SPOT && def.isLit(lamp.state)) {
            Direction facing = def.directionOf(lamp.state);
            if (facing != null) {
                Vec3 local = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                DynamicLight.Mover dir = pt -> {
                    Vec3 d = ship.dirToWorld(local);
                    return d == null ? local : d;
                };
                float range = ShipLampScale.spotRange(def.range() > 0 ? def.range() : LightShape.DEFAULT_SPOT_RANGE, scale);
                return new DynamicLight(key, DynamicLight.Kind.SHIP_LAMP, mover, dir, def.levelOf(lamp.state), def.color() >= 0 ? def.color() : LightColors.ofBlock(lamp.state), LightShape.SPOT,
                        def.coneDegrees() > 0 ? def.coneDegrees() : LightShape.DEFAULT_CONE_DEGREES, range, falloff);
            }
        }
        int level = def != null ? def.levelOf(lamp.state) : lamp.emission;
        // a lamp without a colour of its own takes the colour Colorful Lighting gives its block (a sea lantern's blue)
        // a blacklight stays one (0.7.1): the light stored round it is only the violet that shows of it
        int own = def != null && def.color() >= 0 ? def.color() : LightColors.ofBlock(lamp.state);
        int rgb = def != null && def.color() >= 0 || own == net.cama.livelylighting.dynamiclighting.engine.LightData.BLACKLIGHT ? own
                : ownHue && lamp.hue >= 0 ? lamp.hue : own;
        float stretch = ShipLampScale.stretch(level, scale, maxReach);
        return new DynamicLight(key, DynamicLight.Kind.SHIP_LAMP, mover, null, level, rgb, LightShape.POINT, 0f, level * stretch, falloff, -1, 0f, null, stretch);
    }
}
