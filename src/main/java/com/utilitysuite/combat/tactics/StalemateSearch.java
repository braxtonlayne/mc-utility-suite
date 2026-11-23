package com.utilitysuite.combat.tactics;

/**
 * Budgeted walking search for the stalemate breaker: a breadth-first flood over {@link CombatVoxels} inside a fixed box
 * around the player, a few hundred cells per tick ({@link #step}), that finds a walkable route to one of three goals:
 * <ul>
 *   <li>{@link #GOAL_APPROACH}: a standable cell at the target's level (feet within one block) and within
 *       {@link #APPROACH_REACH} blocks of it horizontally: somewhere it can be hit from.</li>
 *   <li>{@link #GOAL_MEET}: open, flat, hazard-free ground the target itself can walk to (a second flood from the
 *       target with its own, stricter path limits: drops of at most {@link #OPP_MAX_DROP}), at least
 *       {@link #MEET_MIN_DISTANCE} from it. Not offered when the player already stands on ground the target can
 *       reach (waiting there changes nothing).</li>
 *   <li>{@link #GOAL_BAIT}: a cell {@link #BAIT_MIN_DISTANCE}..{@link #BAIT_MAX_DISTANCE} blocks from the target at a
 *       bearing (seen from the target) at least {@link #BAIT_MIN_TURN_DEG} degrees away from the current one, near the
 *       player's height: a new angle that makes a stuck opponent re-path.</li>
 * </ul>
 * Moves are what a walking player does without risk: flat steps, one-block steps up (with headroom for the jump) and
 * drops of at most {@link #OUR_MAX_DROP} blocks (no fall damage). Feet and head cells must be open (air, or shallow
 * water at the feet only); lava, fire, magma, cactus, cobwebs and unloaded blocks are never entered, and a cell beside
 * lava is never used. Breadth-first order makes the first goal found the one with the fewest steps.
 * <p>
 * Engine free; the arrays are allocated on the first {@link #begin}, never per tick; {@link #step} does not allocate.
 * Positions in the result use the {@code BlockPos.asLong} layout the route runner takes. Client thread only.
 */
public final class StalemateSearch {

    /** Horizontal half-size of the search box (blocks around the player). */
    public static final int RADIUS = 20;
    /** Blocks below and above the player's feet the box covers. */
    public static final int DOWN = 10;
    public static final int UP = 13;
    static final int SX = RADIUS * 2 + 1;
    static final int SY = DOWN + UP + 1;
    static final int SZ = SX;
    static final int CELLS = SX * SY * SZ;

    /** Our own drop limit: three blocks fall without damage. */
    public static final int OUR_MAX_DROP = 3;
    /** The target's assumed drop limit (path-finding bots refuse deeper steps; players rarely jump into a pit). */
    public static final int OPP_MAX_DROP = 2;
    /** The deepest deliberate fall any search takes (9 damage before armor and Feather Falling). */
    public static final int MAX_FALL_DROP = 12;
    /** Cells expanded per search at most (both floods together). */
    public static final int MAX_EXPANSIONS = 16_000;
    /** Longest route returned (the route follower's limit); a longer one is cut and re-planned on the way. */
    public static final int MAX_ROUTE = 256;

    public static final double APPROACH_REACH = 2.5;
    public static final double MEET_MIN_DISTANCE = 3.5;
    public static final double BAIT_MIN_DISTANCE = 4.0;
    public static final double BAIT_MAX_DISTANCE = 12.0;
    public static final double BAIT_MIN_TURN_DEG = 60.0;
    /** A bait cell stays within this many blocks of the player's feet height. */
    public static final int BAIT_MAX_DY = 2;

    public static final int GOAL_APPROACH = 0;
    public static final int GOAL_MEET = 1;
    public static final int GOAL_BAIT = 2;

    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int FOUND = 2;
    public static final int NOT_FOUND = 3;

    private static final byte OURS = 1;
    private static final byte OPP = 2;
    private static final int PHASE_OPP = 0;
    private static final int PHASE_OURS = 1;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int NO_PARENT = -1;

    private byte[] mark;
    private int[] parent;
    private int[] queue;
    private final long[] route = new long[MAX_ROUTE];
    private int routeLength;

    private int status = IDLE;
    private int goal;
    private int phase;
    private int head;
    private int tail;
    private int expansions;
    private int ox, oy, oz;
    private int startIdx;
    private int sx, sy, sz;
    private int tcx, tcy, tcz;
    private double tx, tz;
    private double bearingX, bearingZ;
    private boolean startOppReachable;
    private boolean seeded;
    private int ourMaxDrop = OUR_MAX_DROP;
    private int found = -1;

    /** The voxels of the running search (set by {@link #step}; never kept beyond it). */
    private CombatVoxels w;

    /**
     * Starts a search (replacing any running one). Allocation free after the first call.
     *
     * @param goalKind {@link #GOAL_APPROACH}, {@link #GOAL_MEET} or {@link #GOAL_BAIT}
     * @param px       the player's feet position
     * @param tx       the target's feet position
     */
    public void begin(int goalKind, double px, double py, double pz, double tx, double ty, double tz) {
        begin(goalKind, px, py, pz, tx, ty, tz, OUR_MAX_DROP);
    }

    /**
     * As {@link #begin(int, double, double, double, double, double, double)} with a larger drop limit for our own
     * moves: a fall the caller has judged survivable at the current health ({@code maxDrop - 3} damage). Never below
     * {@link #OUR_MAX_DROP}, never above {@link #MAX_FALL_DROP}.
     */
    public void begin(int goalKind, double px, double py, double pz, double tx, double ty, double tz, int maxDrop) {
        ourMaxDrop = Math.max(OUR_MAX_DROP, Math.min(MAX_FALL_DROP, maxDrop));
        if (mark == null) {
            mark = new byte[CELLS];
            parent = new int[CELLS];
            queue = new int[CELLS];
        }
        java.util.Arrays.fill(mark, (byte) 0);
        goal = goalKind;
        sx = floor(px);
        sy = floor(py + 0.01);
        sz = floor(pz);
        ox = sx - RADIUS;
        oy = sy - DOWN;
        oz = sz - RADIUS;
        tcx = floor(tx);
        tcy = floor(ty + 0.01);
        tcz = floor(tz);
        this.tx = tx;
        this.tz = tz;
        double bx = px - tx;
        double bz = pz - tz;
        double len = Math.sqrt(bx * bx + bz * bz);
        bearingX = len > 1.0e-6 ? bx / len : 1.0;
        bearingZ = len > 1.0e-6 ? bz / len : 0.0;
        head = 0;
        tail = 0;
        expansions = 0;
        found = -1;
        routeLength = 0;
        startIdx = -1;
        startOppReachable = false;
        phase = goalKind == GOAL_MEET ? PHASE_OPP : PHASE_OURS;
        status = RUNNING;
        seeded = false;
    }

    /**
     * Runs up to {@code budget} cell expansions.
     *
     * @return {@link #RUNNING}, {@link #FOUND} (the route is ready) or {@link #NOT_FOUND}
     */
    public int step(CombatVoxels voxels, int budget) {
        if (status != RUNNING) {
            return status;
        }
        if (voxels == null) {
            status = NOT_FOUND;
            return status;
        }
        w = voxels;
        try {
            int left = budget;
            while (left > 0 && status == RUNNING) {
                if (!seeded) {
                    seed();
                    continue;
                }
                if (head >= tail) {
                    endPhase();
                    continue;
                }
                if (++expansions > MAX_EXPANSIONS) {
                    status = NOT_FOUND;
                    break;
                }
                left--;
                int idx = queue[head++];
                expandFrom(idx);
            }
        } finally {
            w = null;
        }
        return status;
    }

    private void seed() {
        seeded = true;
        head = 0;
        tail = 0;
        if (phase == PHASE_OPP) {
            int y = groundBelow(tcx, tcy, tcz);
            int idx = index(tcx, y, tcz);
            if (y == Integer.MIN_VALUE || idx < 0) {
                endPhase(); // the target is outside the box or in the air over nothing: no meet ground
                return;
            }
            mark[idx] |= OPP;
            queue[tail++] = idx;
        } else {
            int y = groundBelow(sx, sy, sz);
            int idx = y == Integer.MIN_VALUE ? -1 : index(sx, y, sz);
            if (idx < 0) {
                status = NOT_FOUND;
                return;
            }
            startIdx = idx;
            startOppReachable = (mark[idx] & OPP) != 0;
            if (goal == GOAL_MEET && startOppReachable) {
                status = NOT_FOUND; // the target can already walk to us: waiting changes nothing
                return;
            }
            mark[idx] |= OURS;
            parent[idx] = NO_PARENT;
            queue[tail++] = idx;
        }
    }

    private void endPhase() {
        if (phase == PHASE_OPP) {
            phase = PHASE_OURS;
            seeded = false;
        } else {
            status = NOT_FOUND;
        }
    }

    private void expandFrom(int idx) {
        int x = cellX(idx);
        int y = cellY(idx);
        int z = cellZ(idx);
        boolean opp = phase == PHASE_OPP;
        int maxDrop = opp ? OPP_MAX_DROP : ourMaxDrop;
        boolean jumpRoom = open(kind(x, y + 2, z));
        for (int d = 0; d < 4 && status == RUNNING; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            int ny = Integer.MIN_VALUE;
            if (body(nx, y, nz)) {
                if (CombatVoxels.standable(kind(nx, y - 1, nz))) {
                    ny = y;
                } else {
                    for (int k = 1; k <= maxDrop; k++) {
                        if (!open(kind(nx, y - k, nz))) {
                            break;
                        }
                        if (CombatVoxels.standable(kind(nx, y - k - 1, nz))) {
                            ny = body(nx, y - k, nz) ? y - k : Integer.MIN_VALUE;
                            break;
                        }
                    }
                }
            } else if (jumpRoom && CombatVoxels.standable(kind(nx, y, nz)) && body(nx, y + 1, nz)) {
                ny = y + 1;
            }
            if (ny == Integer.MIN_VALUE) {
                continue;
            }
            int n = index(nx, ny, nz);
            if (n < 0) {
                continue;
            }
            byte bit = opp ? OPP : OURS;
            if ((mark[n] & bit) != 0 || besideLava(nx, ny, nz)) {
                continue;
            }
            mark[n] |= bit;
            queue[tail++] = n;
            if (!opp) {
                parent[n] = idx;
                if (isGoal(n, nx, ny, nz)) {
                    found = n;
                    buildRoute();
                    status = FOUND;
                }
            }
        }
    }

    private boolean isGoal(int n, int x, int y, int z) {
        double cx = x + 0.5 - tx;
        double cz = z + 0.5 - tz;
        double h2 = cx * cx + cz * cz;
        switch (goal) {
            case GOAL_APPROACH:
                return Math.abs(y - tcy) <= 1 && h2 <= APPROACH_REACH * APPROACH_REACH;
            case GOAL_MEET:
                return (mark[n] & OPP) != 0 && h2 >= MEET_MIN_DISTANCE * MEET_MIN_DISTANCE && flatAround(x, y, z);
            case GOAL_BAIT: {
                if (Math.abs(y - sy) > BAIT_MAX_DY || h2 < BAIT_MIN_DISTANCE * BAIT_MIN_DISTANCE
                        || h2 > BAIT_MAX_DISTANCE * BAIT_MAX_DISTANCE) {
                    return false;
                }
                double h = Math.sqrt(h2);
                double cos = (cx * bearingX + cz * bearingZ) / h;
                return cos <= Math.cos(Math.toRadians(BAIT_MIN_TURN_DEG));
            }
            default:
                return false;
        }
    }

    /** Open, flat ground: every neighbour is a standable cell at the same height. */
    private boolean flatAround(int x, int y, int z) {
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            if (!CombatVoxels.standable(kind(nx, y - 1, nz)) || !body(nx, y, nz)) {
                return false;
            }
        }
        return true;
    }

    private boolean besideLava(int x, int y, int z) {
        for (int d = 0; d < 4; d++) {
            if (kind(x + DX[d], y, z + DZ[d]) == CombatVoxels.LAVA
                    || kind(x + DX[d], y - 1, z + DZ[d]) == CombatVoxels.LAVA) {
                return true;
            }
        }
        return false;
    }

    private void buildRoute() {
        int len = 0;
        for (int i = found; i != NO_PARENT; i = parent[i]) {
            len++;
        }
        int skip = Math.max(0, len - MAX_ROUTE); // too long: keep the part from the start
        int n = len - skip;
        int i = found;
        for (int k = 0; k < skip; k++) {
            i = parent[i];
        }
        for (int k = n - 1; k >= 0; k--) {
            route[k] = pack(cellX(i), cellY(i), cellZ(i));
            i = parent[i];
        }
        routeLength = n;
    }

    /** The nearest feet cell at or below {@code y} (within the drop limit + 1) that stands on something, or MIN_VALUE. */
    private int groundBelow(int x, int y, int z) {
        if (CombatVoxels.standable(kind(x, y, z)) && body(x, y + 1, z)) {
            return y + 1; // feet inside a partial block (slab, path, farmland): stand on top of it
        }
        for (int k = 0; k <= OUR_MAX_DROP + 1; k++) {
            if (CombatVoxels.standable(kind(x, y - k - 1, z)) && body(x, y - k, z)) {
                return y - k;
            }
            if (!open(kind(x, y - k, z))) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** A body fits: the feet cell is open (air or shallow water) and the head cell is air. */
    private boolean body(int x, int y, int z) {
        return open(kind(x, y, z)) && kind(x, y + 1, z) == CombatVoxels.AIR;
    }

    private static boolean open(int k) {
        return k == CombatVoxels.AIR || k == CombatVoxels.WATER;
    }

    private int kind(int x, int y, int z) {
        return w.kind(x, y, z);
    }

    private int index(int x, int y, int z) {
        int ix = x - ox;
        int iy = y - oy;
        int iz = z - oz;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= SX || iy >= SY || iz >= SZ) {
            return -1;
        }
        return (ix * SY + iy) * SZ + iz;
    }

    private int cellX(int idx) {
        return idx / (SY * SZ) + ox;
    }

    private int cellY(int idx) {
        return (idx / SZ) % SY + oy;
    }

    private int cellZ(int idx) {
        return idx % SZ + oz;
    }

    // ---- results ---------------------------------------------------------------------------------------------------

    public int status() {
        return status;
    }

    public int goal() {
        return goal;
    }

    /** Route cells ({@code BlockPos.asLong} layout), start first; valid while {@link #status()} is FOUND. */
    public long[] route() {
        return route;
    }

    public int routeLength() {
        return status == FOUND ? routeLength : 0;
    }

    /** Cells expanded by this search so far (diagnostics, tests). */
    public int expansions() {
        return expansions;
    }

    /** Drops the search (the result too). */
    public void cancel() {
        status = IDLE;
        routeLength = 0;
    }

    // ---- packing (bit-compatible with BlockPos.asLong: x 26 bits at 38, z 26 bits at 12, y 12 bits at 0) -----------

    private static final long MASK_XZ = (1L << 26) - 1L;
    private static final long MASK_Y = (1L << 12) - 1L;

    public static long pack(int x, int y, int z) {
        return ((x & MASK_XZ) << 38) | ((z & MASK_XZ) << 12) | (y & MASK_Y);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
