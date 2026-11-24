package com.utilitysuite.travel.movement;

/**
 * Jump-chain lookahead (owner, 2026-11-23: "look ahead a few dozen blocks and like a dozen wide"). The intent estimator
 * scores one jump ahead within {@link IntentEstimator#MAX_RANGE}; a fast player looks two or three jumps further, so the
 * next landing often had no confident target and the landing hold latched W on every link of a chain. This maps the
 * standable tops of a corridor {@link #LENGTH} blocks along the heading and {@link #HALF_WIDTH} to each side, links them
 * with a reach table (walk steps and jumps, rises of at most one block, drops of at most {@link #MAX_DROP}), and keeps
 * the cheapest chain to the furthest point: the path the course most likely takes.
 * <p>
 * Work is budgeted: the column scan spends at most {@link #LOOKUPS_PER_TICK} voxel lookups a tick and the search at
 * most {@link #POPS_PER_TICK} settled nodes, so a fresh corridor is ready a handful of ticks after a landing. The plan
 * from the previous takeoff stays readable meanwhile (that is the one a touchdown asks about). Engine-free and
 * allocation-free after construction: every array is sized for the largest corridor.
 */
public final class ChainLookahead {

    /** Corridor length along the heading (blocks). */
    public static final int LENGTH = 48;
    /** Corridor half width across the heading (blocks). */
    public static final int HALF_WIDTH = 12;
    /** Cells scanned above and below the start top for standable tops. */
    public static final int SCAN_UP = 6;
    public static final int SCAN_DOWN = 8;
    /** Tops kept per column (highest first). */
    public static final int TOPS = 3;
    public static final int LOOKUPS_PER_TICK = 4096;
    public static final int POPS_PER_TICK = 512;
    /** Most a link may drop (the estimator's {@link IntentEstimator#MAX_DOWN}); deeper is never part of the course. */
    public static final double MAX_DROP = 4.5;
    /** Most a link may rise (a jump onto a block one up; fence posts from a slab included). */
    public static final double MAX_RISE = 1.26;
    /**
     * Centre-to-centre reach of a sprint jump by rise: index 0 = one block up, 1 = level, then each block down (like
     * the estimator's table, which measures the same thing).
     */
    static final double[] REACH = {3.6, 4.3, 4.6, 4.9, 5.1, 5.3};
    /** Neighbouring tops at most this far apart and this much apart in height are a walk, not a jump. */
    static final double WALK_DIST = 1.01;
    static final double WALK_STEP = 0.6;
    static final double JUMP_COST = 1.0;
    static final double WALK_COST = 0.25;
    /** Cost per block of drop beyond the first (owner: drops are penalised unless they are the way on). */
    static final double DROP_COST = 2.0;
    static final double FREE_DROP = 1.0;
    /**
     * Weight of a route's cost when the end of the chain is picked: progress decides how far the chain reaches, the
     * cost (jumps, drops) decides which route gets there; a legal step down must not make the chain stop short.
     */
    static final double END_COST = 0.3;
    /** Cost per block of sideways offset from the heading line at the end of the chain. */
    static final double SIDE_COST = 0.15;
    /** A heading change beyond this (degrees) rebuilds the corridor on the ground. */
    static final double REBUILD_TURN = 25.0;
    /** Blocks walked on one top before the corridor starts from the body again. */
    static final double REBUILD_WALK = 3.0;
    /** Longest kept path. */
    public static final int MAX_PATH = 64;

    private static final int DIM = LENGTH + 2 * HALF_WIDTH + 3;
    private static final int COLS = DIM * DIM;
    private static final int NODES = COLS * TOPS;
    private static final double TOP_EPS = 1.0e-3;
    private static final double BODY = MovementPhysics.HEIGHT;
    private static final int RING = 6; // neighbour columns per axis for the search (reach 5.3 → 6)
    /** Lookups left for one node's edge checks when the search stops for the tick (the most a tick overshoots). */
    static final int RELAX_RESERVE = 256;

    // ---- corridor ---------------------------------------------------------------------------------------------------
    private boolean building;
    private boolean solving;
    private boolean ready;
    private int ox, oz; // start column
    private double oTop;
    private double bodyStartX, bodyStartZ;
    private boolean wasOnGround = true;
    private boolean started;
    private int oy; // start block y (the top's cell)
    private double hx, hz; // heading unit vector
    private float builtYaw;
    private int minX, minZ;
    /** Columns inside the corridor, ordered by distance along the heading (scan order). */
    private final int[] order = new int[COLS];
    private int orderCount;
    private int scanCursor;
    private final byte[] colState = new byte[COLS]; // 0 outside, 1 pending, 2 scanned
    private final byte[] topCount = new byte[COLS];
    private final double[] tops = new double[NODES];
    private final int[] topCell = new int[NODES];
    private final int[] bucket = new int[LENGTH + 3];

    // ---- search -----------------------------------------------------------------------------------------------------
    private final double[] cost = new double[NODES];
    private final int[] prev = new int[NODES];
    private final boolean[] viaJump = new boolean[NODES];
    private final boolean[] settled = new boolean[NODES];
    private final int[] heap = new int[NODES];
    private final int[] heapPos = new int[NODES];
    private int heapSize;
    private int startNode;

    // ---- result (the last finished plan) ----------------------------------------------------------------------------
    private int pathLen;
    private final int[] pathX = new int[MAX_PATH];
    private final int[] pathBlockY = new int[MAX_PATH];
    private final int[] pathZ = new int[MAX_PATH];
    private final double[] pathTop = new double[MAX_PATH];
    private final boolean[] pathJump = new boolean[MAX_PATH];
    private final int[] rev = new int[MAX_PATH];

    private int lookups;
    private int lastLookups;
    private final float[] box = new float[6];

    // ---- gaze (owner, 2026-11-23: "where the player is looking should guide its longer distance predictions") ----
    /** A looked-at block at least this far away (blocks) steers the end of the chain; nearer is the estimator's. */
    static final double GAZE_MIN = 6.0;
    /** Score per block between a chain end and the looked-at block (the furthest-along term is small beside it). */
    static final double GAZE_COST = 1.0;
    /** Score per block of height between a chain end and the looked-at top. */
    static final double GAZE_RISE_COST = 0.5;
    /** Score per block of progress along the heading while a gaze steers (ties go to the further top). */
    static final double GAZE_ALONG = 0.05;
    /** The looked-at block moved this far (blocks): the end of a finished chain is picked again. */
    static final double GAZE_REPICK = 1.0;
    private static final double EYE_HEIGHT = 1.62;
    private boolean gazeSet;
    private double gazeX, gazeTop, gazeZ;
    private boolean pickedGaze;
    private double pickedX, pickedZ;
    private int gazeCells;

    /**
     * Walks the view ray from the eyes cell by cell (an exact grid traversal) up to the corridor's length and keeps
     * the first block whose boxes it crosses as the looked-at point, when it is at least {@link #GAZE_MIN} away. The
     * boxes are intersected exactly, so a fence post, a pane or a bottom slab is hit wherever the ray enters its cell.
     * Allocation-free; at most two cell lookups per visited cell.
     */
    public void look(MovementPhysics now, float yaw, float pitch, MovementPhysics.Voxels w) {
        gazeSet = false;
        gazeCells = 0;
        double r = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        double dx = -Math.sin(r) * cp;
        double dy = -Math.sin(p);
        double dz = Math.cos(r) * cp;
        double ex = now.x;
        double ey = now.y + EYE_HEIGHT;
        double ez = now.z;
        int cx = MovementPhysics.floor(ex);
        int cy = MovementPhysics.floor(ey);
        int cz = MovementPhysics.floor(ez);
        boolean ax = Math.abs(dx) >= 1.0e-9;
        boolean ay = Math.abs(dy) >= 1.0e-9;
        boolean az = Math.abs(dz) >= 1.0e-9;
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tdx = ax ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tdy = ay ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tdz = az ? Math.abs(1.0 / dz) : Double.MAX_VALUE;
        double tmx = ax ? (dx > 0 ? cx + 1 - ex : ex - cx) * tdx : Double.MAX_VALUE;
        double tmy = ay ? (dy > 0 ? cy + 1 - ey : ey - cy) * tdy : Double.MAX_VALUE;
        double tmz = az ? (dz > 0 ? cz + 1 - ez : ez - cz) * tdz : Double.MAX_VALUE;
        boolean shaped = w.shaped();
        double range = LENGTH + RING;
        int maxCells = 3 * (LENGTH + RING) + 3;
        while (gazeCells < maxCells) {
            gazeCells++;
            int hy = cy;
            double t = gazeCell(w, cx, cy, cz, ex, ey, ez, dx, dy, dz);
            if (shaped) {
                // fences and walls rise half a block into the cell above theirs
                double t2 = gazeCell(w, cx, cy - 1, cz, ex, ey, ez, dx, dy, dz);
                if (t2 >= 0.0 && (t < 0.0 || t2 < t)) {
                    t = t2;
                    hy = cy - 1;
                }
            }
            if (t >= 0.0) {
                if (t > range) {
                    return;
                }
                double hx0 = cx + 0.5 - ex;
                double hz0 = cz + 0.5 - ez;
                if (hx0 * hx0 + hz0 * hz0 >= GAZE_MIN * GAZE_MIN) {
                    gazeSet = true;
                    gazeX = cx + 0.5;
                    gazeTop = gazeCellTop(w, cx, hy, cz);
                    gazeZ = cz + 0.5;
                }
                return;
            }
            if (Math.min(tmx, Math.min(tmy, tmz)) > range) {
                return;
            }
            if (tmx <= tmy && tmx <= tmz) {
                cx += stepX;
                tmx += tdx;
            } else if (tmy <= tmz) {
                cy += stepY;
                tmy += tdy;
            } else {
                cz += stepZ;
                tmz += tdz;
            }
        }
    }

    /** Nearest ray parameter at which the ray enters a box of the cell, or -1. */
    private double gazeCell(MovementPhysics.Voxels w, int cx, int cy, int cz, double ex, double ey, double ez,
                            double dx, double dy, double dz) {
        int n = w.boxCount(cx, cy, cz);
        double best = -1.0;
        for (int k = 0; k < n; k++) {
            w.box(cx, cy, cz, k, box, 0);
            double t = RayBox.enter(ex, ey, ez, dx, dy, dz, cx + box[0], cy + box[1], cz + box[2], cx + box[3],
                    cy + box[4], cz + box[5]);
            if (t >= 0.0 && (best < 0.0 || t < best)) {
                best = t;
            }
        }
        return best;
    }

    private double gazeCellTop(MovementPhysics.Voxels w, int cx, int cy, int cz) {
        int n = w.boxCount(cx, cy, cz);
        double top = -Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            w.box(cx, cy, cz, k, box, 0);
            top = Math.max(top, cy + box[4]);
        }
        return top;
    }

    /** A looked-at block steers the chain now. */
    public boolean gazing() {
        return gazeSet;
    }

    /** Cells the last {@link #look} ray visited. */
    int gazeCells() {
        return gazeCells;
    }

    /** Centre x of the looked-at block (meaningful while {@link #gazing}). */
    double gazeX() {
        return gazeX;
    }

    /** Top of the looked-at block. */
    double gazeTop() {
        return gazeTop;
    }

    double gazeZ() {
        return gazeZ;
    }

    private boolean gazeMoved() {
        if (gazeSet != pickedGaze) {
            return true;
        }
        if (!gazeSet) {
            return false;
        }
        double ddx = gazeX - pickedX;
        double ddz = gazeZ - pickedZ;
        return ddx * ddx + ddz * ddz > GAZE_REPICK * GAZE_REPICK;
    }

    /** One tick. {@code moveYaw}: the direction the user is heading (view yaw turned by the held keys). */
    public void update(MovementPhysics now, float moveYaw, MovementPhysics.Voxels w) {
        lookups = 0;
        if (now.onGround) {
            int sx = MovementPhysics.floor(now.x);
            int sz = MovementPhysics.floor(now.z);
            int by = MovementPhysics.floor(now.y - 0.5);
            double mx = now.x - (ox + 0.5);
            double mz = now.z - (oz + 0.5);
            // a new top (a landing, a step) or a few blocks walked: the corridor starts from here again
            boolean moved = !wasOnGround || mx * mx + mz * mz >= REBUILD_WALK * REBUILD_WALK
                    || Math.abs(now.y - oTop) > 0.05;
            boolean turned = Math.abs(wrap(moveYaw - builtYaw)) > REBUILD_TURN;
            if (!started || moved || turned) {
                begin(sx, by, sz, now.y, moveYaw, now.x, now.z);
            }
        }
        wasOnGround = now.onGround;
        if (building) {
            scan(w);
        }
        if (solving) {
            solve(w);
        }
        if (ready && !building && !solving && gazeMoved()) {
            finish(); // the corridor still holds the search: only the end and its path change
        }
        lastLookups = lookups;
    }

    /** Forget everything (teleport, world change). */
    public void reset() {
        started = false;
        building = false;
        solving = false;
        ready = false;
        pathLen = 0;
    }

    private void begin(int sx, int by, int sz, double feetY, float yaw, double bodyX, double bodyZ) {
        ox = sx;
        oz = sz;
        oy = by;
        oTop = feetY;
        bodyStartX = bodyX;
        bodyStartZ = bodyZ;
        started = true;
        builtYaw = yaw;
        double r = Math.toRadians(yaw);
        hx = -Math.sin(r);
        hz = Math.cos(r);
        // the grid is centred on the middle of the corridor, not on the body
        minX = MovementPhysics.floor(sx + 0.5 + hx * LENGTH * 0.5) - DIM / 2;
        minZ = MovementPhysics.floor(sz + 0.5 + hz * LENGTH * 0.5) - DIM / 2;
        // counting sort of the corridor's columns by distance along the heading (near columns are scanned first)
        for (int i = 0; i < bucket.length; i++) {
            bucket[i] = 0;
        }
        for (int c = 0; c < COLS; c++) {
            topCount[c] = 0;
            int b = corridorBucket(c);
            colState[c] = (byte) (b < 0 ? 0 : 1);
            if (b >= 0) {
                bucket[b]++;
            }
        }
        int sum = 0;
        for (int i = 0; i < bucket.length; i++) {
            int n = bucket[i];
            bucket[i] = sum;
            sum += n;
        }
        orderCount = sum;
        for (int c = 0; c < COLS; c++) {
            int b = corridorBucket(c);
            if (b >= 0) {
                order[bucket[b]++] = c;
            }
        }
        scanCursor = 0;
        building = true;
        solving = false;
    }

    /** Bucket (floor of the distance along the heading, from -1) of a column inside the corridor, or -1. */
    private int corridorBucket(int c) {
        int x = minX + c / DIM;
        int z = minZ + c % DIM;
        double dx = x - ox;
        double dz = z - oz;
        double along = dx * hx + dz * hz;
        double across = -dx * hz + dz * hx;
        if (along < -1.5 || along > LENGTH + 0.5 || Math.abs(across) > HALF_WIDTH + 0.5) {
            return -1;
        }
        int b = (int) Math.floor(along) + 2;
        return Math.max(0, Math.min(bucket.length - 1, b));
    }

    private void scan(MovementPhysics.Voxels w) {
        while (scanCursor < orderCount && lookups < LOOKUPS_PER_TICK) {
            scanColumn(order[scanCursor++], w);
        }
        if (scanCursor >= orderCount) {
            building = false;
            startSolve();
        }
    }

    private void scanColumn(int c, MovementPhysics.Voxels w) {
        int x = minX + c / DIM;
        int z = minZ + c % DIM;
        int n = 0;
        int base = c * TOPS;
        for (int y = oy + SCAN_UP; y >= oy - SCAN_DOWN && n < TOPS; y--) {
            int boxes = w.boxCount(x, y, z);
            lookups++;
            if (boxes == 0) {
                continue;
            }
            double t = topOf(w, x, y, z, boxes);
            if (t != t || !clear(w, x, y, z, t)) {
                continue;
            }
            lookups++;
            // a top that hurts, traps or is not modelled is never part of the course; the one under the feet stays
            // (the chain still has to start somewhere)
            if (unsafe(w.surface(x, y, z)) && !(x == ox && z == oz && Math.abs(t - oTop) <= 0.05)) {
                continue;
            }
            tops[base + n] = t;
            topCell[base + n] = y;
            n++;
        }
        topCount[c] = (byte) n;
        colState[c] = 2;
    }

    private double topOf(MovementPhysics.Voxels w, int x, int y, int z, int n) {
        double best = Double.NaN;
        for (int i = 0; i < n; i++) {
            w.box(x, y, z, i, box, 0);
            lookups++;
            double t = y + box[4];
            if (!(t <= best + TOP_EPS)) {
                best = t;
            }
        }
        return best;
    }

    /**
     * Room for the body over a top: no box in the cells the body would occupy (the top's own cell excepted), and
     * nothing there that hurts or traps without a box (fire, cobweb, powder snow, a berry bush).
     */
    private boolean clear(MovementPhysics.Voxels w, int x, int y, int z, double top) {
        int from = Math.max(y + 1, MovementPhysics.floor(top + TOP_EPS));
        int to = MovementPhysics.floor(top + BODY - TOP_EPS);
        for (int cy = from; cy <= to; cy++) {
            lookups += 2;
            if (w.boxCount(x, cy, z) != 0 || unsafe(w.surface(x, cy, z))) {
                return false;
            }
        }
        return true;
    }

    /** Surfaces a chain never lands on: harmful, trapping, or not modelled by the replica. */
    private static boolean unsafe(byte surface) {
        return surface == MovementPhysics.SURFACE_HARM || surface == MovementPhysics.SURFACE_STUCK
                || surface == MovementPhysics.SURFACE_UNMODELLED;
    }

    // ---- search -----------------------------------------------------------------------------------------------------

    private void startSolve() {
        heapSize = 0;
        for (int i = 0; i < NODES; i++) {
            cost[i] = Double.POSITIVE_INFINITY;
            settled[i] = false;
            prev[i] = -1;
            heapPos[i] = -1;
        }
        // the top under the feet: the body's own column, else a neighbour whose rim the hitbox overhangs
        startNode = -1;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int sc = col(ox + dx, oz + dz);
                if (sc < 0 || colState[sc] != 2) {
                    continue;
                }
                double ex = Math.max(0.0, Math.abs(ox + dx + 0.5 - bodyStartX) - 0.5);
                double ez = Math.max(0.0, Math.abs(oz + dz + 0.5 - bodyStartZ) - 0.5);
                if (ex >= MovementPhysics.HALF_WIDTH || ez >= MovementPhysics.HALF_WIDTH) {
                    continue;
                }
                for (int k = 0; k < topCount[sc]; k++) {
                    if (Math.abs(tops[sc * TOPS + k] - oTop) > 0.05) {
                        continue;
                    }
                    double d = (dx == 0 && dz == 0 ? 0.0 : 1.0) + ex + ez;
                    if (d < bestD) {
                        bestD = d;
                        startNode = sc * TOPS + k;
                    }
                }
            }
        }
        if (startNode < 0) {
            solving = false; // nothing to stand on here (water, a ladder): the previous plan stays readable
            return;
        }
        cost[startNode] = 0.0;
        viaJump[startNode] = false;
        push(startNode);
        solving = true;
    }

    private void solve(MovementPhysics.Voxels w) {
        int pops = 0;
        while (heapSize > 0 && pops < POPS_PER_TICK && lookups < LOOKUPS_PER_TICK - RELAX_RESERVE) {
            int a = pop();
            pops++;
            settled[a] = true;
            relax(a, w);
        }
        if (heapSize == 0) {
            solving = false;
            finish();
        }
    }

    private void relax(int a, MovementPhysics.Voxels w) {
        int ca = a / TOPS;
        int ax = ca / DIM;
        int az = ca % DIM;
        double ta = tops[a];
        for (int dx = -RING; dx <= RING; dx++) {
            int bx = ax + dx;
            if (bx < 0 || bx >= DIM) {
                continue;
            }
            for (int dz = -RING; dz <= RING; dz++) {
                int bz = az + dz;
                if (bz < 0 || bz >= DIM || (dx == 0 && dz == 0)) {
                    continue;
                }
                int cb = bx * DIM + bz;
                if (colState[cb] != 2) {
                    continue;
                }
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > REACH[REACH.length - 1]) {
                    continue;
                }
                for (int k = 0; k < topCount[cb]; k++) {
                    int b = cb * TOPS + k;
                    if (settled[b]) {
                        continue;
                    }
                    double dy = tops[b] - ta;
                    if (dy > MAX_RISE || -dy > MAX_DROP) {
                        continue;
                    }
                    boolean walk = d <= WALK_DIST && Math.abs(dy) <= WALK_STEP;
                    if (!walk && d > reach(dy)) {
                        continue;
                    }
                    double drop = -dy - FREE_DROP;
                    double c = cost[a] + (walk ? WALK_COST * d : JUMP_COST) + (drop > 0 ? drop * DROP_COST : 0.0);
                    if (c >= cost[b]) {
                        continue;
                    }
                    // checked only for an improving edge: most candidate edges never get this far
                    if (!walk && blocked(w, minX + ax, minZ + az, minX + bx, minZ + bz, Math.max(ta, tops[b]))) {
                        continue;
                    }
                    cost[b] = c;
                    prev[b] = a;
                    viaJump[b] = !walk;
                    if (heapPos[b] < 0) {
                        push(b);
                    } else {
                        up(heapPos[b]);
                    }
                }
            }
        }
    }

    static double reach(double dy) {
        if (dy > 0.6) {
            return REACH[0];
        }
        int i = 1 + (int) Math.floor(Math.max(0.0, -dy - 0.01));
        return REACH[Math.min(REACH.length - 1, i)];
    }

    /**
     * A wall in the flight: a box at the body's height over the higher top at the quarter, half and three-quarter
     * points of the straight line (a neo round it is the technique library's business, not a chain link).
     */
    private boolean blocked(MovementPhysics.Voxels w, int x0, int z0, int x1, int z1, double top) {
        int y0 = MovementPhysics.floor(top + TOP_EPS);
        int y1 = MovementPhysics.floor(top + BODY - TOP_EPS);
        for (int q = 1; q <= 3; q++) {
            double fx = x0 + 0.5 + (x1 - x0) * q * 0.25;
            double fz = z0 + 0.5 + (z1 - z0) * q * 0.25;
            int cx = MovementPhysics.floor(fx);
            int cz = MovementPhysics.floor(fz);
            if ((cx == x0 && cz == z0) || (cx == x1 && cz == z1)) {
                continue;
            }
            for (int cy = y0; cy <= y1; cy++) {
                lookups++;
                if (w.boxCount(cx, cy, cz) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Picks the end node (nearest the looked-at block; otherwise furthest along, cheapest, nearest the heading line) and stores the path to it. */
    private void finish() {
        pickedGaze = gazeSet;
        pickedX = gazeX;
        pickedZ = gazeZ;
        int best = -1;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < orderCount; i++) {
            int c = order[i];
            for (int k = 0; k < topCount[c]; k++) {
                int n = c * TOPS + k;
                if (!settled[n]) {
                    continue;
                }
                double dx = minX + c / DIM - ox;
                double dz = minZ + c % DIM - oz;
                double along = dx * hx + dz * hz;
                double across = Math.abs(-dx * hz + dz * hx);
                double s;
                if (gazeSet) {
                    // the end nearest the looked-at block: the chain runs to where the user is looking
                    double gx = minX + c / DIM + 0.5 - gazeX;
                    double gz = minZ + c % DIM + 0.5 - gazeZ;
                    s = -GAZE_COST * Math.sqrt(gx * gx + gz * gz) - GAZE_RISE_COST * Math.abs(tops[n] - gazeTop)
                            - END_COST * cost[n] + GAZE_ALONG * along;
                } else {
                    s = along - END_COST * cost[n] - SIDE_COST * across;
                }
                if (s > bestScore) {
                    bestScore = s;
                    best = n;
                }
            }
        }
        int len = 0;
        for (int n = best; n >= 0 && len < MAX_PATH; n = prev[n]) {
            rev[len++] = n;
        }
        pathLen = len;
        for (int i = 0; i < len; i++) {
            int n = rev[len - 1 - i];
            int c = n / TOPS;
            pathX[i] = minX + c / DIM;
            pathZ[i] = minZ + c % DIM;
            pathBlockY[i] = topCell[n];
            pathTop[i] = tops[n];
            pathJump[i] = viaJump[n];
        }
        ready = len > 0;
    }

    private int col(int x, int z) {
        int gx = x - minX;
        int gz = z - minZ;
        return gx < 0 || gz < 0 || gx >= DIM || gz >= DIM ? -1 : gx * DIM + gz;
    }

    // ---- binary heap over cost[] -------------------------------------------------------------------------------------

    private void push(int n) {
        heap[heapSize] = n;
        heapPos[n] = heapSize;
        heapSize++;
        up(heapSize - 1);
    }

    private int pop() {
        int top = heap[0];
        heapSize--;
        heapPos[top] = -1;
        if (heapSize > 0) {
            heap[0] = heap[heapSize];
            heapPos[heap[0]] = 0;
            down(0);
        }
        return top;
    }

    private void up(int i) {
        int n = heap[i];
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (cost[heap[p]] <= cost[n]) {
                break;
            }
            heap[i] = heap[p];
            heapPos[heap[i]] = i;
            i = p;
        }
        heap[i] = n;
        heapPos[n] = i;
    }

    private void down(int i) {
        int n = heap[i];
        while (true) {
            int l = 2 * i + 1;
            if (l >= heapSize) {
                break;
            }
            int r = l + 1;
            int m = r < heapSize && cost[heap[r]] < cost[heap[l]] ? r : l;
            if (cost[heap[m]] >= cost[n]) {
                break;
            }
            heap[i] = heap[m];
            heapPos[heap[i]] = i;
            i = m;
        }
        heap[i] = n;
        heapPos[n] = i;
    }

    private static double wrap(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // ---- queries ----------------------------------------------------------------------------------------------------

    /** A finished plan is available (it may be the previous corridor's while a new one builds). */
    public boolean ready() {
        return ready;
    }

    /** The corridor or its search is still being worked on. */
    public boolean busy() {
        return building || solving;
    }

    public int length() {
        return pathLen;
    }

    public int linkX(int i) {
        return pathX[i];
    }

    /** The block of link {@code i} (feet at {@link #linkTop}). */
    public int linkBlockY(int i) {
        return pathBlockY[i];
    }

    public int linkZ(int i) {
        return pathZ[i];
    }

    public double linkTop(int i) {
        return pathTop[i];
    }

    /** Link {@code i} is reached by a jump (false: a walk step). */
    public boolean linkJump(int i) {
        return pathJump[i];
    }

    /** Index of the path link whose top the body stands on (or is landing on) at this position, or -1. */
    public int indexAt(double x, double feetY, double z) {
        if (!ready) {
            return -1;
        }
        int cx = MovementPhysics.floor(x);
        int cz = MovementPhysics.floor(z);
        int near = -1;
        for (int i = 0; i < pathLen; i++) {
            if (Math.abs(pathTop[i] - feetY) > 0.05) {
                continue;
            }
            if (pathX[i] == cx && pathZ[i] == cz) {
                return i;
            }
            // standing over the rim of the link's block (the hitbox overhangs up to its half width)
            if (Math.abs(pathX[i] + 0.5 - x) < 0.5 + MovementPhysics.HALF_WIDTH
                    && Math.abs(pathZ[i] + 0.5 - z) < 0.5 + MovementPhysics.HALF_WIDTH && near < 0) {
                near = i;
            }
        }
        return near;
    }

    /** The first link after {@code from} that is reached by a jump, or -1 (the chain ends, or only walks on). */
    public int nextJump(int from) {
        for (int i = from + 1; i < pathLen; i++) {
            if (pathJump[i]) {
                return i;
            }
        }
        return -1;
    }

    /** Voxel lookups of the last update (budget check). */
    public int lastLookups() {
        return lastLookups;
    }
}
