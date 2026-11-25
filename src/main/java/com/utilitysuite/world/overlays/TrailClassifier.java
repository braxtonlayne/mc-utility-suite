package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Trail forensics: groups new-chunk trail cells ({@link ChunkAgeTracker#NEW}) into 8-connected segments and classifies
 * each by shape. Heuristics, all on chunk coordinates:
 * <ul>
 *   <li><b>Search zig-zag</b>: three or more parallel lanes (gaps in the cross-axis offset histogram).</li>
 *   <li><b>Nether tunnel bore</b>: long, very straight, in the Nether (overworld distance is 8x).</li>
 *   <li><b>Boat run</b>: most cells have water or ice at the surface.</li>
 *   <li><b>Elytra highway</b>: long and straight with gaps along its length (fast flight loads chunks sparsely).</li>
 *   <li><b>Travel</b>: straight but none of the above; <b>Wander</b>: no dominant axis.</li>
 * </ul>
 * Direction points toward the cells observed most recently when observation ticks differ, otherwise away from the
 * viewer (the near end is taken as the start). When the cells carry timestamps (game ticks, else wall time) the trail's
 * real speed is measured ({@link TrailRecency#speed}) and decides flight vs boat vs ground travel in place of the
 * sparseness guess. Runs off-thread on a copied cell array; engine-free.
 */
public final class TrailClassifier {

    public enum Kind { ELYTRA_HIGHWAY, BOAT_RUN, NETHER_TUNNEL, SEARCH_ZIGZAG, TRAVEL, WANDER }

    public static final int MAX_SPINE = 16;

    /**
     * Classification of one segment. Spine points are chunk-centre coordinates (chunk units), start to end.
     * {@code speedBps} is the measured speed in blocks per second (local blocks, not Overworld-scaled), NaN when the
     * cells carry too few timestamps; {@code latestWallMs}/{@code oldestWallMs} are the newest and oldest wall-clock
     * stamps (0 = unknown) and {@code spineWallMs} the newest stamp per spine point (0 = unknown).
     */
    public record Segment(Kind kind, double dirX, double dirZ, double lengthChunks, double widthChunks, int cells,
                          long latestTick, double straightness, int lanes, float[] spineX, float[] spineZ,
                          double speedBps, long latestWallMs, long oldestWallMs, long[] spineWallMs) {

        public boolean hasSpeed() {
            return !Double.isNaN(speedBps);
        }
    }

    /** Tunable thresholds (defaults act like a careful human reading the map). */
    public double minStraightness = 0.85;
    public int minLength = 10;
    public int elytraMinLength = 20;
    public double sparseFill = 0.75;
    public double boatWaterFraction = 0.6;
    public int zigzagMinLanes = 3;
    /** Measured speed from which a dry trail is flight (sprint-jumping tops out near 7 b/s, horses near 14). */
    public double elytraMinSpeed = 18.0;
    /** Measured speed over water/ice above which it is flight rather than a boat (blue-ice boats top out near 72). */
    public double boatMaxSpeed = 75.0;
    /** Least game-tick span for a speed estimate (2 s) and the wall-clock fallback span (3 s). */
    public long minTickSpan = 40;
    public long minWallSpan = 3000;

    /**
     * Labels the 8-connected components of {@code n} packed chunk keys (packed as
     * {@code BlockPosPacking.chunk}). @return component id per cell
     */
    public static int[] cluster(long[] keys, int n) {
        Long2IntOpenHashMap index = new Long2IntOpenHashMap(n * 2);
        index.defaultReturnValue(-1);
        for (int i = 0; i < n; i++) {
            index.put(keys[i], i);
        }
        int[] label = new int[n];
        java.util.Arrays.fill(label, -1);
        int[] stack = new int[n];
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (label[i] >= 0) {
                continue;
            }
            int sp = 0;
            stack[sp++] = i;
            label[i] = next;
            while (sp > 0) {
                int c = stack[--sp];
                int cx = BlockPosPacking.chunkX(keys[c]);
                int cz = BlockPosPacking.chunkZ(keys[c]);
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oz == 0) {
                            continue;
                        }
                        int j = index.get(key(cx + ox, cz + oz));
                        if (j >= 0 && label[j] < 0) {
                            label[j] = next;
                            stack[sp++] = j;
                        }
                    }
                }
            }
            next++;
        }
        return label;
    }

    static long key(int cx, int cz) {
        return BlockPosPacking.chunk(cx, cz);
    }

    /**
     * Classifies one segment.
     *
     * @param cx,cz      chunk coordinates of the segment's cells
     * @param water      per cell: water or ice at the surface (may be null)
     * @param ticks      per cell: observation tick (may be null)
     * @param nether     the segment is in the Nether
     * @param viewerCx   viewer chunk position (used to orient when ticks do not)
     */
    public Segment classify(int[] cx, int[] cz, int n, boolean[] water, long[] ticks, boolean nether,
                            double viewerCx, double viewerCz) {
        return classify(cx, cz, n, water, ticks, null, nether, viewerCx, viewerCz);
    }

    /**
     * Classifies one segment with per-cell timestamps.
     *
     * @param ticks  per cell: game time it was first seen (0 or null = unknown); orients the trail and gives its speed
     * @param wallMs per cell: wall clock of that (0 or null = unknown); recency, and the speed fallback without ticks
     */
    public Segment classify(int[] cx, int[] cz, int n, boolean[] water, long[] ticks, long[] wallMs, boolean nether,
                            double viewerCx, double viewerCz) {
        if (n <= 0) {
            return null;
        }
        double mx = 0;
        double mz = 0;
        for (int i = 0; i < n; i++) {
            mx += cx[i];
            mz += cz[i];
        }
        mx /= n;
        mz /= n;
        double cxx = 0;
        double czz = 0;
        double cxz = 0;
        for (int i = 0; i < n; i++) {
            double ux = cx[i] - mx;
            double uz = cz[i] - mz;
            cxx += ux * ux;
            czz += uz * uz;
            cxz += ux * uz;
        }
        double tr = cxx + czz;
        double disc = Math.sqrt((cxx - czz) * (cxx - czz) + 4 * cxz * cxz);
        double l1 = 0.5 * (tr + disc);
        double l2 = 0.5 * (tr - disc);
        double straightness = l1 > 1e-9 ? (l1 - l2) / (l1 + l2) : 0.0;
        double vx;
        double vz;
        if (Math.abs(cxz) > 1e-9) {
            vx = l1 - czz;
            vz = cxz;
        } else if (cxx >= czz) {
            vx = 1;
            vz = 0;
        } else {
            vx = 0;
            vz = 1;
        }
        double len = Math.hypot(vx, vz);
        vx /= len;
        vz /= len;

        double minA = Double.MAX_VALUE;
        double maxA = -Double.MAX_VALUE;
        double minP = Double.MAX_VALUE;
        double maxP = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double ux = cx[i] - mx;
            double uz = cz[i] - mz;
            double a = ux * vx + uz * vz;
            double p = -ux * vz + uz * vx;
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minP = Math.min(minP, p);
            maxP = Math.max(maxP, p);
        }
        double length = maxA - minA + 1;
        double width = maxP - minP + 1;

        // Along-axis fill (sparseness) and cross-axis lanes.
        int aBins = Math.max(1, (int) Math.round(length));
        int pBins = Math.max(1, (int) Math.round(width));
        boolean[] aOcc = new boolean[aBins + 1];
        int[] pCount = new int[pBins + 1];
        for (int i = 0; i < n; i++) {
            double ux = cx[i] - mx;
            double uz = cz[i] - mz;
            int a = (int) Math.round(ux * vx + uz * vz - minA);
            int p = (int) Math.round(-ux * vz + uz * vx - minP);
            aOcc[Math.min(aBins, Math.max(0, a))] = true;
            pCount[Math.min(pBins, Math.max(0, p))]++;
        }
        int filled = 0;
        for (boolean b : aOcc) {
            if (b) {
                filled++;
            }
        }
        // Sparse = gaps along the axis or a patchy band (fast flight outruns chunk loading).
        double fill = Math.min((double) filled / aOcc.length, Math.min(1.0, n / (length * width)) * 1.25);
        // A lane is a run of cross-axis bins holding a real share of the cells; the short turn connectors at the ends
        // of a zig-zag only add one or two cells per bin, so they do not merge lanes.
        int maxBin = 0;
        for (int c : pCount) {
            maxBin = Math.max(maxBin, c);
        }
        int laneMin = Math.max(2, maxBin / 4);
        int lanes = 0;
        boolean inRun = false;
        for (int c : pCount) {
            boolean lane = c >= laneMin;
            if (lane && !inRun) {
                lanes++;
            }
            inRun = lane;
        }
        int wet = 0;
        if (water != null) {
            for (int i = 0; i < n; i++) {
                if (water[i]) {
                    wet++;
                }
            }
        }
        double wetFrac = (double) wet / n;

        // Orientation: toward newer observations (game ticks, else wall time; unknown stamps skipped), else away from
        // the viewer.
        long latest = Long.MIN_VALUE;
        if (ticks != null) {
            for (int i = 0; i < n; i++) {
                latest = Math.max(latest, ticks[i]);
            }
        }
        long latestWall = 0;
        long oldestWall = 0;
        if (wallMs != null) {
            for (int i = 0; i < n; i++) {
                long w = wallMs[i];
                if (w > 0) {
                    latestWall = Math.max(latestWall, w);
                    oldestWall = oldestWall == 0 ? w : Math.min(oldestWall, w);
                }
            }
        }
        double sign = orientation(cx, cz, n, mx, mz, vx, vz, ticks);
        if (sign == 0) {
            sign = orientation(cx, cz, n, mx, mz, vx, vz, wallMs);
        }
        // Real speed from the stamps: blocks along the axis against time.
        double[] along = new double[n];
        for (int i = 0; i < n; i++) {
            along[i] = ((cx[i] - mx) * vx + (cz[i] - mz) * vz) * 16.0;
        }
        double speed = TrailRecency.speed(along, ticks, n, 20.0, minTickSpan);
        if (Double.isNaN(speed)) {
            speed = TrailRecency.speed(along, wallMs, n, 1000.0, minWallSpan);
        }
        if (sign == 0) {
            double toViewer = (viewerCx - mx) * vx + (viewerCz - mz) * vz;
            sign = toViewer > 0 ? -1 : 1;
        }
        vx *= sign;
        vz *= sign;

        Kind kind;
        if (lanes >= zigzagMinLanes && length >= 4) {
            kind = Kind.SEARCH_ZIGZAG;
        } else if (straightness < minStraightness || length < minLength) {
            kind = Kind.WANDER;
        } else if (nether) {
            kind = Kind.NETHER_TUNNEL;
        } else if (!Double.isNaN(speed)) {
            // measured speed decides between flight, boats and ground travel
            if (wetFrac >= boatWaterFraction) {
                kind = speed > boatMaxSpeed ? Kind.ELYTRA_HIGHWAY : Kind.BOAT_RUN;
            } else {
                kind = speed >= elytraMinSpeed ? Kind.ELYTRA_HIGHWAY : Kind.TRAVEL;
            }
        } else if (wetFrac >= boatWaterFraction) {
            kind = Kind.BOAT_RUN;
        } else if (length >= elytraMinLength && fill < sparseFill) {
            kind = Kind.ELYTRA_HIGHWAY;
        } else {
            kind = Kind.TRAVEL;
        }

        // Spine: mean cross-axis offset per along-axis bin, ordered start to end.
        int bins = Math.max(2, Math.min(MAX_SPINE, aBins));
        double[] sumP = new double[bins];
        int[] cnt = new int[bins];
        long[] binWall = new long[bins];
        for (int i = 0; i < n; i++) {
            double ux = cx[i] - mx;
            double uz = cz[i] - mz;
            double a = ux * vx + uz * vz; // signed axis
            double p = -ux * vz + uz * vx;
            double lo = sign > 0 ? minA : -maxA;
            int b = (int) Math.min(bins - 1, Math.max(0, (a - lo) / length * bins));
            sumP[b] += p;
            cnt[b]++;
            if (wallMs != null && wallMs[i] > binWall[b]) {
                binWall[b] = wallMs[i]; // newest stamp in the bin: how recently this stretch was made
            }
        }
        float[] sx = new float[bins];
        float[] sz = new float[bins];
        long[] sw = new long[bins];
        int k = 0;
        double lo = sign > 0 ? minA : -maxA;
        for (int b = 0; b < bins; b++) {
            if (cnt[b] == 0) {
                continue;
            }
            double a = lo + (b + 0.5) * length / bins;
            double p = sumP[b] / cnt[b];
            sx[k] = (float) (mx + a * vx - p * vz);
            sz[k] = (float) (mz + a * vz + p * vx);
            sw[k] = binWall[b];
            k++;
        }
        return new Segment(kind, vx, vz, nether ? length * 8 : length, width, n, latest, straightness, lanes,
                java.util.Arrays.copyOf(sx, k), java.util.Arrays.copyOf(sz, k), speed, latestWall, oldestWall,
                java.util.Arrays.copyOf(sw, k));
    }

    /** Sign (+1/-1) of the covariance of along-axis position with time over cells with a stamp; 0 = undecided. */
    private static double orientation(int[] cx, int[] cz, int n, double mx, double mz, double vx, double vz,
                                      long[] times) {
        if (times == null) {
            return 0;
        }
        int m = 0;
        long base = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (times[i] != 0) {
                m++;
                base = Math.min(base, times[i]);
            }
        }
        if (m < 2) {
            return 0;
        }
        double sa = 0;
        double st = 0;
        double sat = 0;
        for (int i = 0; i < n; i++) {
            if (times[i] == 0) {
                continue;
            }
            double a = (cx[i] - mx) * vx + (cz[i] - mz) * vz;
            double t = times[i] - base; // relative: epoch milliseconds would swamp the doubles
            sa += a;
            st += t;
            sat += a * t;
        }
        double cov = sat / m - (sa / m) * (st / m);
        return Math.abs(cov) > 1e-6 ? Math.signum(cov) : 0;
    }

    /**
     * Samples a centripetal-free (uniform) Catmull-Rom spline through {@code n} points, {@code perSeg} samples per
     * segment, into {@code outX/outZ}. Allocates nothing. @return samples written
     */
    public static int catmullRom(float[] px, float[] pz, int n, int perSeg, float[] outX, float[] outZ) {
        if (n <= 0) {
            return 0;
        }
        if (n == 1) {
            outX[0] = px[0];
            outZ[0] = pz[0];
            return 1;
        }
        int w = 0;
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1);
            int i3 = Math.min(n - 1, i + 2);
            for (int s = 0; s < perSeg && w < outX.length; s++) {
                float t = (float) s / perSeg;
                outX[w] = cr(px[i0], px[i], px[i + 1], px[i3], t);
                outZ[w] = cr(pz[i0], pz[i], pz[i + 1], pz[i3], t);
                w++;
            }
        }
        if (w < outX.length) {
            outX[w] = px[n - 1];
            outZ[w] = pz[n - 1];
            w++;
        }
        return w;
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    public static int color(Kind k) {
        return switch (k) {
            case ELYTRA_HIGHWAY -> 0xFF80C0FF;
            case BOAT_RUN -> 0xFF4080FF;
            case NETHER_TUNNEL -> 0xFFFF6030;
            case SEARCH_ZIGZAG -> 0xFFFFE040;
            case TRAVEL -> 0xFFE0E0E0;
            case WANDER -> 0xFF909090;
        };
    }
}
