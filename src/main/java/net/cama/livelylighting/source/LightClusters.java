package net.cama.livelylighting.source;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The rule by which lights near each other merge into one larger light, shared by the client, which draws it, the server's
 * gameplay light, which counts it, and the server module's light blocks (0.6.3; this rule since 0.6.4).
 *
 * <p>Lights closer than the merge distance to one another, with nothing solid between them, form a group, and so does
 * everything chained through them; a group spread wider than the merge distance round its middle is cut into parts
 * ({@link #parts}). Each member of a part counts by how close it is ({@link #weights}): fully while its nearest neighbour and
 * the part's middle are within half the merge distance, down smoothly to nothing at the merge distance. The counts add up to
 * the part's effective size, and the merged light, in the weighted middle, is its members' light added up by those counts: two
 * equal lights together make one {@link #GAIN_PER_DOUBLING} levels brighter than either ({@link #peak}), taken on by the share
 * the size has of two full members ({@link #target}). So a merged light is strongest where its members stand together, fades out as they move
 * apart and is gone at the merge distance, where the group itself parts: nothing steps there. 0.6.3 added the group's span to
 * the brightest member, which was strongest just before the group parted and weakest with its members side by side.
 *
 * <p>A strength above 15 is a level of 15 kept for the blocks above it and a reach that much longer; it is capped at 15 plus
 * {@code experimental.max_influence_radius}. Lights at one place count as one. A lamp (a ship's or a contraption's own
 * block) counts only as far as carried lights count beside it: lamps alone never merge, as their cluster never grew in the
 * server module.
 */
public final class LightClusters {
    /** The strongest a merged light gets whatever the config says: Colorful Lighting's middle slab class reaches this far. */
    public static final float MAX_STRENGTH = 24f;

    /** The lights looked at for merging at most (each against each). */
    public static final int MAX_LIGHTS = 256;

    /** The levels a merged light gains each time the lights that count in it double. */
    public static final float GAIN_PER_DOUBLING = 3f;

    /** Lights closer than this are at one place and count as one. */
    private static final double SAME_PLACE = 1.0e-3;

    /** The least a member weighs in a merged light's place, so the place is defined where no member counts. */
    private static final double PLACE_FLOOR = 0.05;

    /** A part whose members count less than this together makes no merged light. */
    public static final double MIN_EFFECTIVE = 0.02;

    /** Whether nothing solid lies between two of the lights being grouped. */
    @FunctionalInterface
    public interface Sight {
        boolean clear(int i, int j);
    }

    /** One merged light: its place, its level, the lights it was made of, the level those already give its place. */
    public static final class Merged {
        public final double x, y, z;
        public final float level;
        public final float base;
        public final double effective;
        public final int[] members;

        Merged(double x, double y, double z, float level, float base, double effective, int[] members) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.level = level;
            this.base = base;
            this.effective = effective;
            this.members = members;
        }
    }

    private LightClusters() {}

    /** The strongest a merged light may be with this {@code max_influence_radius}. */
    public static float cap(int maxInfluenceRadius) {
        return Math.min(MAX_STRENGTH, 15f + Math.max(0, maxInfluenceRadius));
    }

    /** How much a distance counts: 1 up to half the merge distance, smoothly down to 0 at the merge distance. */
    public static double fade(double distance, double link) {
        if (link <= 0) return 0;
        double t = distance / link * 2.0 - 1.0;
        if (t <= 0) return 1;
        if (t >= 1) return 0;
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    /**
     * Groups {@code n} points: two points closer than {@code link} with a clear line between them ({@code sight}, null for
     * always) are in one group, and so is everything chained through them. Points that shared a group before ({@code before}:
     * the same value of 0 or more) stay linked up to {@code slack} farther. Returns each point's group as the index of one
     * of its points.
     */
    public static int[] groups(double[] x, double[] y, double[] z, int n, double link, @Nullable int[] before, double slack, @Nullable Sight sight) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double near = link * link, kept = (link + slack) * (link + slack);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = x[i] - x[j], dy = y[i] - y[j], dz = z[i] - z[j];
                double d2 = dx * dx + dy * dy + dz * dz;
                boolean together = before != null && before[i] >= 0 && before[i] == before[j];
                if (d2 > (together ? kept : near)) continue;
                int a = find(parent, i), b = find(parent, j);
                if (a == b) continue;
                if (sight != null && d2 > SAME_PLACE * SAME_PLACE && !sight.clear(i, j)) continue;
                parent[Math.max(a, b)] = Math.min(a, b);
            }
        }
        for (int i = 0; i < n; i++) parent[i] = find(parent, i);
        return parent;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /**
     * The parts the groups are merged in: each group of two or more lights at different places, cut in two between its two
     * farthest members for as long as one of its members is farther than {@code link} from its middle (a long chain or a wide
     * ring would otherwise make one light far from most of its members). A group whose members all shared a merged light
     * before is cut only {@code slack} later. Each part is its members' indices, in rising order.
     */
    public static List<int[]> parts(double[] x, double[] y, double[] z, int[] group, int n, double link, @Nullable int[] before, double slack) {
        List<int[]> out = new ArrayList<>();
        boolean[] done = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (done[i] || group[i] != i) continue;
            int count = 0;
            for (int j = i; j < n; j++) if (group[j] == i) count++;
            if (count < 2) continue;
            int[] members = new int[count];
            int k = 0;
            for (int j = i; j < n; j++) {
                if (group[j] != i) continue;
                done[j] = true;
                members[k++] = j;
            }
            cut(x, y, z, members, link, before, slack, out);
        }
        return out;
    }

    private static void cut(double[] x, double[] y, double[] z, int[] members, double link, @Nullable int[] before, double slack, List<int[]> out) {
        int m = members.length;
        if (m < 2) return;
        double sx = 0, sy = 0, sz = 0;
        for (int i : members) {
            sx += x[i];
            sy += y[i];
            sz += z[i];
        }
        sx /= m;
        sy /= m;
        sz /= m;
        boolean together = before != null && before[members[0]] >= 0;
        if (together) for (int i : members) if (before[i] != before[members[0]]) together = false;
        double reach = together ? link + slack : link;
        boolean wide = false;
        for (int i : members) {
            double dx = x[i] - sx, dy = y[i] - sy, dz = z[i] - sz;
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                wide = true;
                break;
            }
        }
        if (!wide || m == 2) {
            // lights at one place are one light: a part needs two places
            double spread = 0;
            for (int i : members) spread = Math.max(spread, Math.abs(x[i] - x[members[0]]) + Math.abs(y[i] - y[members[0]]) + Math.abs(z[i] - z[members[0]]));
            if (spread >= SAME_PLACE) out.add(members);
            return;
        }
        int a = members[0], b = members[1];
        double far = -1;
        for (int p = 0; p < m; p++) {
            for (int q = p + 1; q < m; q++) {
                int i = members[p], j = members[q];
                double dx = x[i] - x[j], dy = y[i] - y[j], dz = z[i] - z[j];
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > far) {
                    far = d2;
                    a = i;
                    b = j;
                }
            }
        }
        int[] first = new int[m], second = new int[m];
        int nf = 0, ns = 0;
        for (int i : members) {
            double ax = x[i] - x[a], ay = y[i] - y[a], az = z[i] - z[a], bx = x[i] - x[b], by = y[i] - y[b], bz = z[i] - z[b];
            if (ax * ax + ay * ay + az * az <= bx * bx + by * by + bz * bz) first[nf++] = i;
            else second[ns++] = i;
        }
        cut(x, y, z, java.util.Arrays.copyOf(first, nf), link, before, slack, out);
        cut(x, y, z, java.util.Arrays.copyOf(second, ns), link, before, slack, out);
    }

    /**
     * How much each member of a part counts in its merged light, 0 to 1: by the distance to its nearest neighbour at another
     * place (one in sight, with {@code sight}) and by its distance to the part's middle, each through {@link #fade}. A member
     * at the place of an earlier one counts nothing.
     */
    public static double[] weights(double[] x, double[] y, double[] z, int[] part, double link, @Nullable Sight sight) {
        int m = part.length;
        double[] w = new double[m];
        boolean[] twin = new boolean[m];
        double sx = 0, sy = 0, sz = 0;
        int places = 0;
        for (int p = 0; p < m; p++) {
            int i = part[p];
            for (int q = 0; q < p && !twin[p]; q++) {
                int j = part[q];
                if (Math.abs(x[i] - x[j]) + Math.abs(y[i] - y[j]) + Math.abs(z[i] - z[j]) < SAME_PLACE) twin[p] = true;
            }
            if (twin[p]) continue;
            places++;
            sx += x[i];
            sy += y[i];
            sz += z[i];
        }
        if (places < 2) return w;
        sx /= places;
        sy /= places;
        sz /= places;
        for (int p = 0; p < m; p++) {
            if (twin[p]) continue;
            int i = part[p];
            double nearest = Double.MAX_VALUE;
            for (int q = 0; q < m; q++) {
                if (q == p || twin[q]) continue;
                int j = part[q];
                double dx = x[i] - x[j], dy = y[i] - y[j], dz = z[i] - z[j];
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= nearest || d2 < SAME_PLACE * SAME_PLACE) continue;
                if (sight != null && !sight.clear(Math.min(i, j), Math.max(i, j))) continue;
                nearest = d2;
            }
            if (nearest == Double.MAX_VALUE) continue;
            double dx = x[i] - sx, dy = y[i] - sy, dz = z[i] - sz;
            w[p] = fade(Math.sqrt(nearest), link) * fade(Math.sqrt(dx * dx + dy * dy + dz * dz), link);
        }
        return w;
    }

    /**
     * A part's effective size from its members' weights: the carried lights' weights, plus the lamps' ({@code lamp}, null for
     * none) as far as the carried ones go ({@code lampsCountAlone}: in full).
     */
    public static double effective(double[] weights, @Nullable boolean[] lamp, boolean lampsCountAlone) {
        double carried = 0, lamps = 0;
        for (int p = 0; p < weights.length; p++) {
            if (lamp != null && lamp[p]) lamps += weights[p];
            else carried += weights[p];
        }
        return lampsCountAlone ? carried + lamps : carried + Math.min(lamps, carried);
    }

    /** The share of a lamp's weight that counts: all of it while the lamps weigh no more than the carried lights beside them. */
    private static double lampShare(double[] weights, @Nullable boolean[] lamp, boolean lampsCountAlone) {
        if (lamp == null || lampsCountAlone) return 1;
        double carried = 0, lamps = 0;
        for (int p = 0; p < weights.length; p++) {
            if (lamp[p]) lamps += weights[p];
            else carried += weights[p];
        }
        return lamps <= carried ? 1 : carried / lamps;
    }

    /** Each member's level with the levels of the members at its place: lights at one place are one light, the brightest. */
    public static float[] placeLevels(double[] x, double[] y, double[] z, int[] part, float[] level) {
        int m = part.length;
        float[] out = new float[m];
        for (int p = 0; p < m; p++) {
            out[p] = level[p];
            for (int q = 0; q < m; q++) {
                if (q == p || level[q] <= out[p]) continue;
                if (Math.abs(x[part[p]] - x[part[q]]) + Math.abs(y[part[p]] - y[part[q]]) + Math.abs(z[part[p]] - z[part[q]]) < SAME_PLACE) out[p] = level[q];
            }
        }
        return out;
    }

    /**
     * Each member's share in the merged light's place (they add up to 1): its weight over a small floor, so the place moves
     * smoothly from the plain middle, where nothing counts yet, to the middle of the members that count. A member at the
     * place of an earlier one has no share.
     */
    public static double[] placeShares(double[] x, double[] y, double[] z, int[] part, double[] weights) {
        int m = part.length;
        double[] s = new double[m];
        double sum = 0;
        for (int p = 0; p < m; p++) {
            boolean twin = false;
            for (int q = 0; q < p && !twin; q++) {
                twin = Math.abs(x[part[p]] - x[part[q]]) + Math.abs(y[part[p]] - y[part[q]]) + Math.abs(z[part[p]] - z[part[q]]) < SAME_PLACE;
            }
            s[p] = twin ? 0 : weights[p] + PLACE_FLOOR;
            sum += s[p];
        }
        if (sum <= 0) {
            java.util.Arrays.fill(s, 1.0 / m);
            return s;
        }
        for (int p = 0; p < m; p++) s[p] /= sum;
        return s;
    }

    /**
     * The level a merged light reaches where it shows in full: its members' light added up by how much each counts
     * ({@code level} per member, as {@link #placeLevels} gives it). Light doubles every {@link #GAIN_PER_DOUBLING} levels, so
     * two equal lights that count in full give one that many levels brighter, a bright and a dim one little more than the
     * bright one, and members that count for little a level below their own. Capped at {@code cap}.
     */
    public static float peak(float[] level, double[] weights, @Nullable boolean[] lamp, boolean lampsCountAlone, float cap) {
        double lampShare = lampShare(weights, lamp, lampsCountAlone);
        double sum = 0;
        for (int p = 0; p < weights.length; p++) {
            if (weights[p] <= 0 || level[p] <= 0) continue;
            sum += weights[p] * (lamp != null && lamp[p] ? lampShare : 1.0) * Math.pow(2.0, level[p] / GAIN_PER_DOUBLING);
        }
        if (sum <= 0) return 0;
        return (float) Math.min(cap, GAIN_PER_DOUBLING * Math.log(sum) / Math.log(2.0));
    }

    /**
     * A merged light's level: from what its members already give its place ({@code base}, where it shows nothing new) toward
     * its {@link #peak} by the share its effective size has of two full members.
     */
    public static float target(float base, float peak, double effective) {
        if (peak <= base) return base;
        double share = Math.max(0.0, Math.min(1.0, effective / 2.0));
        return (float) (base + (peak - base) * share);
    }

    /**
     * The merged lights of {@code n} point lights, for the server, which has no fades to keep: grouped, cut and weighed as
     * above, each placed in the weighted middle of its part with the level {@link #target} gives over what its members give
     * that place at a level a block ({@code level} less the Manhattan distance). Parts that add nothing are left out.
     */
    public static List<Merged> merge(double[] x, double[] y, double[] z, float[] level, @Nullable boolean[] lamp, int n, double link, float cap,
                                     @Nullable Sight sight, boolean lampsCountAlone) {
        List<Merged> out = new ArrayList<>();
        n = Math.min(MAX_LIGHTS, n);
        if (n < 2 || link <= 0) return out;
        int[] group = groups(x, y, z, n, link, null, 0, sight);
        for (int[] part : parts(x, y, z, group, n, link, null, 0)) {
            int m = part.length;
            double[] w = weights(x, y, z, part, link, sight);
            boolean[] partLamp = null;
            if (lamp != null) {
                partLamp = new boolean[m];
                for (int p = 0; p < m; p++) partLamp[p] = lamp[part[p]];
            }
            double effective = effective(w, partLamp, lampsCountAlone);
            if (effective < MIN_EFFECTIVE) continue;
            float[] partLevel = new float[m];
            for (int p = 0; p < m; p++) partLevel[p] = level[part[p]];
            float peak = peak(placeLevels(x, y, z, part, partLevel), w, partLamp, lampsCountAlone, cap);
            double[] share = placeShares(x, y, z, part, w);
            double cx = 0, cy = 0, cz = 0;
            for (int p = 0; p < m; p++) {
                int i = part[p];
                cx += x[i] * share[p];
                cy += y[i] * share[p];
                cz += z[i] * share[p];
            }
            float base = 0;
            for (int i : part) base = Math.max(base, level[i] - (float) (Math.abs(cx - x[i]) + Math.abs(cy - y[i]) + Math.abs(cz - z[i])));
            float target = target(base, peak, effective);
            if (target <= base + 0.01f) continue;
            out.add(new Merged(cx, cy, cz, target, base, effective, part));
        }
        return out;
    }
}
