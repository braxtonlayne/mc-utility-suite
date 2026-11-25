package com.utilitysuite.world.spawn;

/**
 * Engine-free "where does my mob cap go" maths. Spawn zones around an AFK spot (vanilla 1.21): nothing spawns within
 * 24 blocks of a player, mobs farther than 32 blocks may despawn at random, farther than 128 they despawn at once.
 * <p>
 * The grid counts dark spawnable floor spots per 16x16x16 sub-chunk around an anchor, then finds the biggest cluster of
 * touching sub-chunks (26-neighbourhood) and its spot-weighted centroid. All storage is preallocated (the arrays grow
 * only when a taller world needs more sections), so {@link #add} and {@link #computeClusters} allocate nothing.
 */
public final class CaveHeatGrid {

    public static final float NO_SPAWN = 24f;
    public static final float RANDOM_DESPAWN = 32f;
    public static final float INSTANT_DESPAWN = 128f;

    public static final int ZONE_NO_SPAWN = 0;
    public static final int ZONE_SAFE = 1;
    public static final int ZONE_RANDOM_DESPAWN = 2;
    public static final int ZONE_INSTANT_DESPAWN = 3;

    /** Sections each side of the anchor section (128 blocks = 8 sections, plus one for partial overlap). */
    static final int RADIUS_SECTIONS = 9;
    static final int SIDE = RADIUS_SECTIONS * 2 + 1;

    private int originSX;
    private int originSY;
    private int originSZ;
    private int sectionsY;
    private int[] counts = new int[0];
    private int[] mark = new int[0];
    private int[] queue = new int[0];
    private int stamp;
    private int total;

    // Result of the last computeClusters().
    private int bestSpots;
    private int bestSections;
    private double bestX;
    private double bestY;
    private double bestZ;
    private int bestMinSX;
    private int bestMinSY;
    private int bestMinSZ;
    private int bestMaxSX;
    private int bestMaxSY;
    private int bestMaxSZ;

    /** Zone of a spot at squared distance {@code d2} from the player / AFK spot. */
    public static int zone(double d2) {
        if (d2 < NO_SPAWN * NO_SPAWN) {
            return ZONE_NO_SPAWN;
        }
        if (d2 <= RANDOM_DESPAWN * RANDOM_DESPAWN) {
            return ZONE_SAFE;
        }
        if (d2 <= INSTANT_DESPAWN * INSTANT_DESPAWN) {
            return ZONE_RANDOM_DESPAWN;
        }
        return ZONE_INSTANT_DESPAWN;
    }

    /** True for spots that can take a cap slot while you idle at the anchor: 24 to 128 blocks away. */
    public static boolean countsForCap(double d2) {
        return d2 >= NO_SPAWN * NO_SPAWN && d2 <= INSTANT_DESPAWN * INSTANT_DESPAWN;
    }

    /** Clears the grid around the anchor block; {@code minSectionY}..{@code minSectionY + sectionsY - 1} are kept. */
    public void reset(int anchorX, int anchorZ, int minSectionY, int sectionsY) {
        originSX = (anchorX >> 4) - RADIUS_SECTIONS;
        originSZ = (anchorZ >> 4) - RADIUS_SECTIONS;
        originSY = minSectionY;
        this.sectionsY = Math.max(1, sectionsY);
        int n = SIDE * SIDE * this.sectionsY;
        if (counts.length < n) { // only when the world gets taller
            counts = new int[n];
            mark = new int[n];
            queue = new int[n];
        } else {
            java.util.Arrays.fill(counts, 0, n, 0);
        }
        total = 0;
        bestSpots = 0;
        bestSections = 0;
    }

    /** Counts one spawnable spot at block (x, y, z). False when outside the grid. */
    public boolean add(int x, int y, int z) {
        int i = index((x >> 4) - originSX, (y >> 4) - originSY, (z >> 4) - originSZ);
        if (i < 0) {
            return false;
        }
        counts[i]++;
        total++;
        return true;
    }

    private int index(int ix, int iy, int iz) {
        if (ix < 0 || iz < 0 || iy < 0 || ix >= SIDE || iz >= SIDE || iy >= sectionsY) {
            return -1;
        }
        return (iy * SIDE + iz) * SIDE + ix;
    }

    /** Finds the cluster of touching sub-chunks holding the most spots. */
    public void computeClusters() {
        int n = SIDE * SIDE * sectionsY;
        stamp++;
        if (stamp == Integer.MAX_VALUE) {
            java.util.Arrays.fill(mark, 0);
            stamp = 1;
        }
        bestSpots = 0;
        bestSections = 0;
        for (int start = 0; start < n; start++) {
            if (counts[start] == 0 || mark[start] == stamp) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            mark[start] = stamp;
            int spots = 0;
            double sx = 0;
            double sy = 0;
            double sz = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            while (head < tail) {
                int c = queue[head++];
                int ix = c % SIDE;
                int iz = (c / SIDE) % SIDE;
                int iy = c / (SIDE * SIDE);
                int w = counts[c];
                spots += w;
                sx += (ix + 0.5) * w;
                sy += (iy + 0.5) * w;
                sz += (iz + 0.5) * w;
                minX = Math.min(minX, ix);
                minY = Math.min(minY, iy);
                minZ = Math.min(minZ, iz);
                maxX = Math.max(maxX, ix);
                maxY = Math.max(maxY, iy);
                maxZ = Math.max(maxZ, iz);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = index(ix + dx, iy + dy, iz + dz);
                            if (j >= 0 && counts[j] > 0 && mark[j] != stamp) {
                                mark[j] = stamp;
                                queue[tail++] = j;
                            }
                        }
                    }
                }
            }
            if (spots > bestSpots) {
                bestSpots = spots;
                bestSections = tail;
                bestX = (originSX + sx / spots) * 16.0;
                bestY = (originSY + sy / spots) * 16.0;
                bestZ = (originSZ + sz / spots) * 16.0;
                bestMinSX = originSX + minX;
                bestMinSY = originSY + minY;
                bestMinSZ = originSZ + minZ;
                bestMaxSX = originSX + maxX;
                bestMaxSY = originSY + maxY;
                bestMaxSZ = originSZ + maxZ;
            }
        }
    }

    public int total() {
        return total;
    }

    public int bestSpots() {
        return bestSpots;
    }

    public int bestSections() {
        return bestSections;
    }

    /** Spot-weighted centroid of the biggest cluster, block coordinates. */
    public double bestX() {
        return bestX;
    }

    public double bestY() {
        return bestY;
    }

    public double bestZ() {
        return bestZ;
    }

    /** Section bounds of the biggest cluster (inclusive, section coordinates). */
    public int bestMinSX() {
        return bestMinSX;
    }

    public int bestMinSY() {
        return bestMinSY;
    }

    public int bestMinSZ() {
        return bestMinSZ;
    }

    public int bestMaxSX() {
        return bestMaxSX;
    }

    public int bestMaxSY() {
        return bestMaxSY;
    }

    public int bestMaxSZ() {
        return bestMaxSZ;
    }
}
