package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.source.Falloff;
import net.cama.livelylighting.source.LightClusters;
import net.cama.livelylighting.source.LightShape;
import net.cama.livelylighting.source.LightSight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Lights near each other merged into one larger light on the client (0.6.3), as the server module's light blocks merge
 * ({@code experimental.cluster_growing}, {@code client.cluster_merging}): point lights of entities, dropped items and other
 * mods closer than {@code experimental.cluster_merge_distance} to one another, with nothing solid between them, form a group
 * ({@link LightClusters}), and each group adds one light where its members are, as bright as their light added up by how
 * close each is: two torches side by side make a light three levels brighter than a torch, and the nearer the merge distance
 * they stand, the less it adds, down to nothing where the group parts (0.6.4). It is capped at 15 plus
 * {@code experimental.max_influence_radius}. The members' own lights stay; the merged light lies over them. A strength above
 * 15 is drawn by Colorful Lighting 2.10.1 as full light out to the blocks above 15 and a reach that much longer (an older
 * one draws a light of 15 there).
 *
 * <p>Lamps of ships and contraptions (a burning ship's fire among them) join a group that has a carried light in it and
 * count as far as the carried lights do ({@code client.cluster_lamps}); lamps alone don't merge, as their clusters never
 * grew in the server module. Spot lights never merge: a beam added to a round light would no longer be a beam.
 *
 * <p>Nothing of it pops. A merged light keeps its key while its group keeps most of its members, so Colorful Lighting keeps
 * its slab. It starts at the level its members already give its place (where it shows nothing new) and eases to its level,
 * a fifth of the way a tick and drawn between ticks; when its group parts, when merging is switched off or when a nearer
 * group needs its place ({@code client.max_merged_lights}), it eases back down to that level and goes. Its place is its
 * members' weighted middle at the moment of the frame, and when its members change, the step that would make is eased out
 * over a few ticks. A new merged light is held dark until Colorful Lighting has its slab.
 */
final class ClientClusters {
    /** The merged lights' keys: this bit and a counter (entity lights stay below 2^40, lamps and other mods' lights set other top bits). */
    static final long KEY_BIT = 0x2000000000000000L;
    /** The most {@code client.max_merged_lights} can ask for: Colorful Lighting's slab class for reaches over 15 has sixteen slabs. */
    private static final int MAX_CLUSTERS = 16;
    /** The share of the way to its level a merged light goes a tick, and the least it goes. */
    private static final float EASE = 0.2f, MIN_STEP = 0.1f;
    /** A new merged light's level while its slab is built: enough to count as a light, nothing to see. */
    static final float HIDDEN = 0.05f;
    private static final int WARM_UP_TICKS = 10;
    /** Lights that were merged stay merged this much beyond the merge distance. */
    private static final double SLACK = 0.5;
    /** The share of a membership change's step left after a tick, and the step taken as a jump instead (a teleport). */
    private static final double OFFSET_KEPT = 0.8, JUMP = 12.0;
    /** A group without a merged light takes the place of the farthest one when it is this much nearer the camera. */
    private static final double REPLACE_MARGIN = 4.0;
    /** A merged light is kept while its group counts at least this much (a new one needs {@link LightClusters#MIN_EFFECTIVE}). */
    private static final double KEPT_EFFECTIVE = LightClusters.MIN_EFFECTIVE * 0.25;
    /** A line between two lights is looked at again after this many ticks, or when either light changes its block. */
    private static final int SIGHT_TICKS = 5;
    /** Lines looked at per tick at most; the rest keep what they had, new ones wait unlinked. */
    private static final int SIGHT_BUDGET = 200;

    private static final class State {
        final long key;
        /** The members' keys, sorted. */
        long[] members = new long[0];
        boolean live;
        /** Given up for a nearer group: eases out and takes no group again. */
        boolean retired;
        /** The level at the end of the last tick; meaningless until {@link #ready}. */
        float strength;
        /** The level its members gave its place at the end of the last tick. */
        float base;
        /** Where the light was at the end of the last tick; null before its first. */
        Vec3 end;
        boolean ready;
        int age;
        boolean coloured;
        float r, g, b;

        State(long key) {
            this.key = key;
        }
    }

    private record Pair(long a, long b) {}

    private static final class Seen {
        long cellA, cellB, tick;
        boolean clear;
    }

    private final List<State> states = new ArrayList<>();
    /** The merged light each member belonged to last tick, by the member's key. */
    private final Map<Long, Long> groupOf = new HashMap<>();
    /** Whether the line between two lights was clear when last looked at. */
    private final Map<Pair, Seen> sight = new HashMap<>();
    private int sightLooks;
    private long ticks;
    private long counter;

    void forget() {
        states.clear();
        groupOf.clear();
        sight.clear();
    }

    /** How many merged lights there are now, the ones easing out included (for tests). */
    int count() {
        return states.size();
    }

    static boolean enabled(LivelyConfig config) {
        return config.experimental.cluster_growing && config.client.cluster_merging && config.experimental.cluster_merge_distance > 0
                && config.client.max_merged_lights > 0;
    }

    /** Whether a light can take part in merging: a point light at its full reach. */
    private static boolean merges(DynamicLight light) {
        if (light.isSpot() || light.level <= 0f) return false;
        if (light.range < light.level - 0.01f) return false; // a light cut short on purpose
        return light.kind != DynamicLight.Kind.CLUSTER;
    }

    private static boolean lamp(DynamicLight light) {
        return light.kind == DynamicLight.Kind.SHIP_LAMP || light.kind == DynamicLight.Kind.CONTRAPTION_LAMP || light.kind == DynamicLight.Kind.SHIP_FIRE;
    }

    /** Whether light passes between two lights, looked up or looked at within the tick's budget. */
    private boolean clear(@Nullable Level level, DynamicLight a, DynamicLight b) {
        if (level == null) return true;
        DynamicLight first = a.ownerKey < b.ownerKey ? a : b, second = first == a ? b : a;
        Pair pair = new Pair(first.ownerKey, second.ownerKey);
        long cellA = BlockPos.asLong((int) Math.floor(first.pos.x), (int) Math.floor(first.pos.y), (int) Math.floor(first.pos.z));
        long cellB = BlockPos.asLong((int) Math.floor(second.pos.x), (int) Math.floor(second.pos.y), (int) Math.floor(second.pos.z));
        Seen seen = sight.get(pair);
        if (seen != null && seen.cellA == cellA && seen.cellB == cellB && ticks - seen.tick < SIGHT_TICKS) return seen.clear;
        if (sightLooks >= SIGHT_BUDGET) return seen != null && seen.clear;
        sightLooks++;
        if (seen == null) sight.put(pair, seen = new Seen());
        seen.cellA = cellA;
        seen.cellB = cellB;
        seen.tick = ticks;
        seen.clear = LightSight.clear(level, first.pos, second.pos, true);
        return seen.clear;
    }

    /** One client tick, after the tick's lights were gathered: adds the merged lights to {@code out}. */
    void collect(LivelyConfig config, Falloff falloff, Vec3 camera, @Nullable Level level, List<DynamicLight> out) {
        boolean on = enabled(config);
        if (!on && states.isEmpty()) {
            groupOf.clear();
            sight.clear();
            return;
        }
        ticks++;
        sightLooks = 0;
        if (ticks % 100 == 0) sight.values().removeIf(seen -> ticks - seen.tick > 100);
        double link = config.experimental.cluster_merge_distance;
        float cap = LightClusters.cap(config.experimental.max_influence_radius);
        int room = Math.max(0, Math.min(MAX_CLUSTERS, config.client.max_merged_lights));

        // every light that can be a member, by key; the ones grouped this tick: carried lights, the nearest first, and the
        // lamps near one of them
        Map<Long, DynamicLight> byKey = new HashMap<>();
        List<DynamicLight> lights = new ArrayList<>(), lamps = new ArrayList<>();
        for (DynamicLight light : out) {
            if (!merges(light)) continue;
            byKey.put(light.ownerKey, light);
            if (!on) continue;
            if (!lamp(light)) lights.add(light);
            else if (config.client.cluster_lamps) lamps.add(light);
        }
        if (lights.size() > LightClusters.MAX_LIGHTS) {
            lights.sort((a, b) -> Double.compare(a.pos.distanceToSqr(camera), b.pos.distanceToSqr(camera)));
            lights = new ArrayList<>(lights.subList(0, LightClusters.MAX_LIGHTS));
        }
        int carried = lights.size();
        double near = (link + SLACK) * (link + SLACK);
        for (DynamicLight lampLight : lamps) {
            if (lights.size() >= LightClusters.MAX_LIGHTS) break;
            for (int i = 0; i < carried; i++) {
                if (lights.get(i).pos.distanceToSqr(lampLight.pos) > near) continue;
                lights.add(lampLight);
                break;
            }
        }
        int n = lights.size();
        double[] x = new double[n], y = new double[n], z = new double[n];
        int[] before = new int[n];
        Map<Long, Integer> stateIndex = new HashMap<>();
        for (int i = 0; i < states.size(); i++) stateIndex.put(states.get(i).key, i);
        for (int i = 0; i < n; i++) {
            DynamicLight light = lights.get(i);
            x[i] = light.pos.x;
            y[i] = light.pos.y;
            z[i] = light.pos.z;
            Long was = groupOf.get(light.ownerKey);
            Integer index = was == null ? null : stateIndex.get(was);
            before[i] = index == null ? -1 : index;
        }
        final List<DynamicLight> grouped = lights;
        LightClusters.Sight lines = (i, j) -> clear(level, grouped.get(i), grouped.get(j));
        // the parts that count for something, each as its members' keys
        List<long[]> groups = new ArrayList<>();
        if (n >= 2) {
            int[] group = LightClusters.groups(x, y, z, n, link, before, SLACK, lines);
            for (int[] part : LightClusters.parts(x, y, z, group, n, link, before, SLACK)) {
                double[] w = LightClusters.weights(x, y, z, part, link, lines);
                boolean[] isLamp = new boolean[part.length];
                boolean kept = false;
                for (int p = 0; p < part.length; p++) {
                    isLamp[p] = lamp(lights.get(part[p]));
                    kept |= before[part[p]] >= 0;
                }
                if (LightClusters.effective(w, isLamp, false) < (kept ? KEPT_EFFECTIVE : LightClusters.MIN_EFFECTIVE)) continue;
                long[] keys = new long[part.length];
                for (int p = 0; p < part.length; p++) keys[p] = lights.get(part[p]).ownerKey;
                Arrays.sort(keys);
                groups.add(keys);
            }
        }
        match(groups, byKey, camera, room);

        groupOf.clear();
        for (Iterator<State> it = states.iterator(); it.hasNext(); ) {
            State s = it.next();
            DynamicLight light = advance(s, byKey, falloff, cap, link, level);
            if (light == null) {
                it.remove();
                continue;
            }
            out.add(light);
            if (s.live) for (long member : s.members) groupOf.put(member, s.key);
        }
    }

    /**
     * Gives each group the merged light that shares most members with it (the largest shares first), new ones to the groups
     * left over, the nearest first, while there is room; the merged lights no group took are left with what remains of
     * their members, to ease out. With no room left, the farthest merged light gives way to a group clearly nearer.
     */
    private void match(List<long[]> groups, Map<Long, DynamicLight> byKey, Vec3 camera, int room) {
        List<int[]> pairs = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            for (int s = 0; s < states.size(); s++) {
                if (states.get(s).retired) continue;
                int shared = shared(groups.get(g), states.get(s).members);
                if (shared > 0) pairs.add(new int[]{shared, g, s});
            }
        }
        pairs.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : a[2] != b[2] ? Integer.compare(a[2], b[2]) : Integer.compare(a[1], b[1]));
        boolean[] groupTaken = new boolean[groups.size()], stateTaken = new boolean[states.size()];
        for (int[] pair : pairs) {
            if (groupTaken[pair[1]] || stateTaken[pair[2]]) continue;
            groupTaken[pair[1]] = stateTaken[pair[2]] = true;
            State s = states.get(pair[2]);
            s.members = groups.get(pair[1]);
            s.live = true;
        }
        int live = 0;
        for (int s = 0; s < stateTaken.length; s++) {
            State state = states.get(s);
            if (stateTaken[s]) {
                live++;
                continue;
            }
            state.live = false;
            int kept = 0;
            long[] left = new long[state.members.length];
            for (long member : state.members) if (byKey.containsKey(member)) left[kept++] = member;
            state.members = Arrays.copyOf(left, kept);
        }
        List<long[]> fresh = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) if (!groupTaken[g]) fresh.add(groups.get(g));
        if (fresh.isEmpty()) return;
        Map<long[], Double> distance = new HashMap<>();
        for (long[] keys : fresh) {
            double best = Double.MAX_VALUE;
            for (long key : keys) best = Math.min(best, byKey.get(key).pos.distanceToSqr(camera));
            distance.put(keys, Math.sqrt(best));
        }
        fresh.sort((a, b) -> Double.compare(distance.get(a), distance.get(b)));
        int made = 0;
        for (long[] keys : fresh) {
            if (states.size() >= room) break;
            State s = new State(KEY_BIT | (++counter & 0xFFFFFFFFFFFFL));
            s.members = keys;
            s.live = true;
            states.add(s);
            made++;
        }
        // no room: the farthest merged light gives way to the nearest group without one, one at a time
        if (made < fresh.size() && live >= room && room > 0) {
            State farthest = null;
            double far = -1;
            for (State s : states) {
                if (!s.live || s.end == null) continue;
                double d = Math.sqrt(s.end.distanceToSqr(camera));
                if (d > far) {
                    far = d;
                    farthest = s;
                }
            }
            if (farthest != null && distance.get(fresh.get(made)) + REPLACE_MARGIN < far) {
                farthest.live = false;
                farthest.retired = true;
            }
        }
    }

    private static int shared(long[] a, long[] b) {
        int i = 0, j = 0, count = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) i++;
            else if (a[i] > b[j]) j++;
            else {
                count++;
                i++;
                j++;
            }
        }
        return count;
    }

    /** Moves one merged light a tick on and gives its light, or null when it is done. */
    private DynamicLight advance(State s, Map<Long, DynamicLight> byKey, Falloff falloff, float cap, double link, @Nullable Level level) {
        int found = 0;
        DynamicLight[] all = new DynamicLight[s.members.length];
        for (long key : s.members) {
            DynamicLight m = byKey.get(key);
            if (m != null) all[found++] = m;
        }
        final int count = found;
        final DynamicLight[] members = Arrays.copyOf(all, count);
        Vec3 offsetStart = Vec3.ZERO, offsetEnd = Vec3.ZERO, end;
        double[] share = new double[count];
        float peak = 0f;
        double effective = 0;
        if (count == 0) {
            if (s.end == null) return null;
            end = s.end;
        } else {
            double[] x = new double[count], y = new double[count], z = new double[count];
            int[] part = new int[count];
            float[] levels = new float[count];
            boolean[] isLamp = new boolean[count];
            for (int i = 0; i < count; i++) {
                x[i] = members[i].pos.x;
                y[i] = members[i].pos.y;
                z[i] = members[i].pos.z;
                part[i] = i;
                levels[i] = members[i].level;
                isLamp[i] = lamp(members[i]);
            }
            if (s.live) {
                double[] w = LightClusters.weights(x, y, z, part, link, (i, j) -> clear(level, members[i], members[j]));
                effective = LightClusters.effective(w, isLamp, false);
                peak = LightClusters.peak(LightClusters.placeLevels(x, y, z, part, levels), w, isLamp, false, cap);
                share = LightClusters.placeShares(x, y, z, part, w);
            } else {
                Arrays.fill(share, 1.0 / count);
            }
            Vec3 meanEnd = mean(members, share, 1.0f);
            // a middle inside a wall lights nothing: the light sits with the member nearest it instead
            if (level != null && count > 1) {
                BlockPos cell = BlockPos.containing(meanEnd);
                BlockState state = LightSight.loadedState(level, cell);
                if (state != null && LightSight.blocks(level, cell, state)) {
                    int nearest = 0;
                    for (int i = 1; i < count; i++) {
                        if (members[i].pos.distanceToSqr(meanEnd) < members[nearest].pos.distanceToSqr(meanEnd)) nearest = i;
                    }
                    Arrays.fill(share, 0.0);
                    share[nearest] = 1.0;
                    meanEnd = members[nearest].pos;
                }
            }
            // where it was drawn at the end of the last tick, against where its members' middle starts this one: the step a
            // change of members or of their weights makes, eased out
            if (s.end != null) {
                offsetStart = s.end.subtract(mean(members, share, 0f));
                if (offsetStart.lengthSqr() > JUMP * JUMP) offsetStart = Vec3.ZERO;
                else if (offsetStart.lengthSqr() > 0.02 * 0.02) offsetEnd = offsetStart.scale(OFFSET_KEPT);
            }
            end = meanEnd.add(offsetEnd);
        }
        // the level its members already give its place: at that level the merged light shows nothing new
        float base = 0f;
        for (DynamicLight m : members) base = Math.max(base, (float) falloff.levelAt(m.level, end.x - m.pos.x, end.y - m.pos.y, end.z - m.pos.z));
        float target = s.live ? LightClusters.target(base, peak, effective) : base;
        s.end = end;
        s.age++;
        // the colour: its coloured members' by their levels, eased
        float r = 0, g = 0, b = 0, weight = 0;
        for (DynamicLight m : members) {
            if (m.rgb < 0) continue;
            r += ((m.rgb >> 16) & 0xFF) * m.level;
            g += ((m.rgb >> 8) & 0xFF) * m.level;
            b += (m.rgb & 0xFF) * m.level;
            weight += m.level;
        }
        if (weight > 0) {
            r /= weight;
            g /= weight;
            b /= weight;
            if (!s.coloured) {
                s.r = r;
                s.g = g;
                s.b = b;
                s.coloured = true;
            } else {
                s.r += (r - s.r) * EASE;
                s.g += (g - s.g) * EASE;
                s.b += (b - s.b) * EASE;
            }
        } else if (count > 0) {
            s.coloured = false;
        }
        int rgb = s.coloured ? Math.round(s.r) << 16 | Math.round(s.g) << 8 | Math.round(s.b) : -1;

        final double[] shares = share;
        final Vec3 from = offsetStart, to = offsetEnd, tickEnd = end;
        DynamicLight.Mover mover = count == 0 ? pt -> tickEnd : pt -> {
            if (pt >= 1.0f) return tickEnd;
            Vec3 middle = mean(members, shares, pt);
            return new Vec3(middle.x + from.x + (to.x - from.x) * pt, middle.y + from.y + (to.y - from.y) * pt, middle.z + from.z + (to.z - from.z) * pt);
        };
        if (!s.ready) {
            if (!s.live) return null;
            s.base = base;
            if (!ColorfulBridge.slabReady(s.key) && s.age <= WARM_UP_TICKS) {
                return new DynamicLight(s.key, DynamicLight.Kind.CLUSTER, mover, null, HIDDEN, rgb, LightShape.POINT, 0f, cap, falloff);
            }
            s.ready = true;
            s.strength = base;
        }
        final float was = s.strength;
        float next;
        if (s.live) {
            float step = Math.max(MIN_STEP, Math.abs(target - was) * EASE);
            next = target > was ? Math.min(target, was + step) : Math.max(target, was - step);
        } else {
            // parting: what it adds over its members eases out, whatever their own light does meanwhile; none left, done
            float excess = was - s.base;
            if (excess <= 1.0e-3f) return null;
            excess -= Math.max(MIN_STEP, excess * EASE);
            next = base + Math.max(0f, excess);
        }
        final float now = next;
        s.strength = now;
        s.base = base;
        DynamicLight.FrameLevel frame = pt -> Math.max(HIDDEN, pt >= 1.0f ? now : was + (now - was) * Math.max(0f, pt));
        return new DynamicLight(s.key, DynamicLight.Kind.CLUSTER, mover, null, Math.max(HIDDEN, now), rgb, LightShape.POINT, 0f, cap, falloff, -1, 0f, frame);
    }

    /** The members' middle by their shares at a moment of the frame. */
    private static Vec3 mean(DynamicLight[] members, double[] share, float pt) {
        double mx = 0, my = 0, mz = 0;
        for (int i = 0; i < members.length; i++) {
            if (share[i] <= 0) continue;
            Vec3 p = pt >= 1.0f ? members[i].pos : members[i].mover.at(pt);
            mx += p.x * share[i];
            my += p.y * share[i];
            mz += p.z * share[i];
        }
        return new Vec3(mx, my, mz);
    }
}
