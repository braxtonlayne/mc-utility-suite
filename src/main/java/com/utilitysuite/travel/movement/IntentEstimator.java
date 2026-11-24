package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Estimates which block the player means to land on (design 2026-11-22, section 9, items 1-5 and 8). Candidate
 * landing tops around the player are scored by the gaze, the held keys, the current velocity and the line of the
 * recent jumps; the winner is the cell the jump search and the turn gain aim for.
 * <ul>
 *   <li><b>Real tops.</b> A candidate is a standable top read from the exact collision boxes (full block 1.0, slab
 *       0.5, fence and wall 1.5, pane and bars 1.0 on a thin post) with a body-sized clearance (1.8) above it. HARM
 *       tops are not candidates; UNMODELLED tops stay candidates with confidence capped below every commit
 *       threshold.</li>
 *   <li><b>Simulated reach.</b> The sprint-jump reach table is a pre-filter (beyond table reach plus a soft band a top
 *       is not a candidate). The best {@link #ROLL_POOL} by score get one feasibility roll each in the replica (run
 *       up toward the top, jump at the coming edge or when the coast would already carry the body there, air keys
 *       aimed at the top's centre), at most {@link #MAX_ROLLS_PER_ESTIMATE} rolls per call, results cached per cell
 *       until the stance moves. Infeasible tops drop out.</li>
 *   <li><b>Gaze over time.</b> The look term uses an exponential average (about 6 ticks) of the look ray's hit point,
 *       so a glance elsewhere does not flip the target.</li>
 *   <li><b>Course line.</b> A ring of the last {@link #PLATFORM_RING} landing points; the platform just left and the
 *       ones before it are penalised. The jump-vector line bonus is kept.</li>
 *   <li><b>Not a dead end.</b> When two finalists are within {@link #DEAD_END_BAND}, one more roll each checks
 *       whether a further jump is possible from them; a dead end loses.</li>
 *   <li><b>Commit threshold by mode</b> ({@link #setCommitThreshold}); hysteresis keeps the incumbent unless a
 *       challenger leads by {@link #HYSTERESIS} on {@link #CHALLENGE_TICKS} consecutive ticks.</li>
 * </ul>
 * Work per call is bounded by {@link #MAX_CANDIDATES}, {@link #MAX_LOOKUPS} block reads and {@link #MAX_SIM_TICKS}
 * simulated ticks. Engine-free and allocation free after construction.
 */
public final class IntentEstimator {

    /** Default commit threshold (GUIDE). Below the threshold the assist keeps its heading-based behaviour. */
    public static final double MIN_CONFIDENCE = 0.5;
    /** Commit threshold for GUIDE (the default). */
    public static final double GUIDE_THRESHOLD = 0.5;
    /** Commit threshold for CARRY: the HUD shows the lock and looking elsewhere changes it. */
    public static final double CARRY_THRESHOLD = 0.35;
    /** Confidence cap for a top whose shape or effect the replica does not model (below every commit threshold). */
    public static final double UNMODELLED_CONFIDENCE = 0.3;

    public static final double W_LOOK = 0.40;
    public static final double W_KEYS = 0.20;
    public static final double W_VELOCITY = 0.15;
    public static final double W_LINE = 0.15;
    public static final double W_REACH = 0.10;

    /** Hard caps per estimate() call. */
    public static final int MAX_CANDIDATES = 96;
    public static final int MAX_LOOKUPS = 1200;
    /** Candidates (best by score) that get a feasibility roll. */
    public static final int ROLL_POOL = 6;
    public static final int MAX_ROLLS_PER_ESTIMATE = 2;
    /** Simulated ticks one roll may use (run-up, the edge probe and the flight). */
    public static final int ROLL_TICKS = 48;
    /** Simulated ticks per estimate() call, all rolls together. */
    public static final int MAX_SIM_TICKS = MAX_ROLLS_PER_ESTIMATE * ROLL_TICKS;

    /** Horizontal range (top-centre distance from the player) and vertical window (support-block relative). */
    public static final double MAX_RANGE = 6.0;
    public static final double MIN_RANGE = 1.2;
    /** A noted "jump" longer than this (blocks, horizontal) is a teleport or a respawn, not a jump. */
    public static final double MAX_JUMP_NOTE = 8.0;
    /**
     * Confidence floor for the only candidate in the movement cone when the movement points at it: with one place to
     * go, where the camera points says little (a player sprinting at a low post looks ahead, not down at it).
     */
    public static final double SOLE_CONFIDENCE = 0.6;
    /** Movement alignment (the key term) the sole-candidate floor needs: within about 30 degrees of the top. */
    public static final double SOLE_ALIGN = 0.8;
    /**
     * Score factor of a top whose straight roll was stopped by a wall, not by distance: it may still be reachable
     * around the corner, which only the brain's technique search can tell. It stays a candidate at a discount.
     */
    public static final double BLOCKED_FACTOR = 0.9;
    public static final int MAX_UP = 1;
    public static final int MAX_DOWN = 4;
    /** A top higher than this above the feet cannot be jumped onto (jump apex 1.2522). */
    public static final double MAX_JUMP_UP = 1.26;

    public static final int PLATFORM_RING = 8;
    /** Score factor for a top on the platform just left (the latest landing and the last takeoff). */
    public static final double LEFT_PENALTY = 0.5;
    /** Score factor for a top on an older platform of the ring. */
    public static final double OLD_PENALTY = 0.75;
    /** Score band within which two finalists get the dead-end check. */
    public static final double DEAD_END_BAND = 0.06;
    /** Score factor for a finalist from which no further jump was found. */
    public static final double DEAD_END_FACTOR = 0.85;
    /** A previous target survives unless a challenger beats it by this much ... */
    public static final double HYSTERESIS = 0.05;
    /** ... on this many consecutive ticks. */
    public static final int CHALLENGE_TICKS = 2;
    /** Weight of the newest look hit in the gaze average (about a 6-tick average). */
    public static final double GAZE_ALPHA = 0.25;

    /** Half-angle of the candidate cone around the movement direction, as a cosine (60 degrees). */
    private static final double CONE_COS = 0.5;
    private static final double LOOK_ZERO_DEG = 30.0;
    private static final double LOOK_BLOCK_MARGIN = 0.1;
    private static final double LOOK_TOP_DY = 0.3;
    private static final double MARGIN_FULL = 0.25;
    private static final double STILL_SPEED = 0.03;
    private static final double LINE_ZERO_DIST = 2.5;
    private static final double REACH_SOFT = 1.5;
    private static final double WALK_STEP = 0.5;
    private static final int JUMP_RING = 4;
    private static final double EYE_HEIGHT = 1.62;
    private static final double BODY = MovementPhysics.HEIGHT;
    private static final double HALF = MovementPhysics.HALF_WIDTH;
    private static final double TOP_EPS = 1.0e-3;
    private static final double GAZE_RANGE = 12.0;
    private static final double UNMODELLED_FACTOR = 0.8;
    /** A cached roll is re-done when the player moved more than this (blocks) or the speed changed by more. */
    private static final double STANCE_POS = 1.0;
    private static final double STANCE_SPEED = 0.1;
    private static final int CACHE = 24;
    private static final int RUNUP_MAX = 30;
    private static final double MAX_SAFE_FALL = 3.0;

    /** Sprint-jump reach (horizontal, top centre) by height difference: up 1, flat, down 1..4. */
    private static final double[] REACH_BY_DY = {3.6, 4.3, 4.6, 4.9, 5.1, 5.3};
    private static final double NO_SPRINT_REACH = 0.8;

    // column cells read per scanned column: yBot .. yTop + 3 (clearance over a fence top one above the window)
    private static final int COL_CELLS = MAX_DOWN + MAX_UP + 4;
    /** Worst-case lookups one column can take (cells, a few boxes each, the walk line); reserved before scanning. */
    private static final int COLUMN_RESERVE = 72;

    private static final int[] COL_DX;
    private static final int[] COL_DZ;

    static {
        int r = (int) Math.ceil(MAX_RANGE) + 1;
        int n = 0;
        int[] sx = new int[(2 * r + 1) * (2 * r + 1)];
        int[] sz = new int[sx.length];
        double[] sd = new double[sx.length];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > MAX_RANGE + 1.0) {
                    continue;
                }
                int i = n++;
                while (i > 0 && sd[i - 1] > d) {
                    sx[i] = sx[i - 1];
                    sz[i] = sz[i - 1];
                    sd[i] = sd[i - 1];
                    i--;
                }
                sx[i] = dx;
                sz[i] = dz;
                sd[i] = d;
            }
        }
        COL_DX = java.util.Arrays.copyOf(sx, n);
        COL_DZ = java.util.Arrays.copyOf(sz, n);
    }

    // ---- published state ----------------------------------------------------------------------------------------
    private boolean hasTarget;
    private int tx, ty, tz;
    private double tTop;
    private byte tSurface;
    private double confidence;
    private float idealYaw;
    private boolean prevValid;
    private double commitThreshold = MIN_CONFIDENCE;

    // ---- jump line and course ring ------------------------------------------------------------------------------
    private final double[] jumpDx = new double[JUMP_RING];
    private final double[] jumpDz = new double[JUMP_RING];
    private int jumpCount;
    private int jumpHead;
    private final double[] ringX = new double[PLATFORM_RING];
    private final double[] ringY = new double[PLATFORM_RING];
    private final double[] ringZ = new double[PLATFORM_RING];
    private int ringCount;
    private int ringHead;
    private double leftX, leftY, leftZ;
    /** Feet height of the ground the last jump left from (NaN when not seen). */
    private double takeoffY = Double.NaN;
    private boolean wasGround;
    /** The newest ring entry has no height yet: filled from the next ground estimate near it. */
    private boolean ringPendingY;
    private boolean haveLeft;

    // ---- gaze ---------------------------------------------------------------------------------------------------
    private boolean gazeValid;
    private double gazeX, gazeY, gazeZ;

    // ---- reference ground ---------------------------------------------------------------------------------------
    private boolean groundValid;
    private double groundY;

    // ---- candidates ---------------------------------------------------------------------------------------------
    private final int[] candX = new int[MAX_CANDIDATES];
    private final int[] candY = new int[MAX_CANDIDATES];
    private final int[] candZ = new int[MAX_CANDIDATES];
    private final double[] candTop = new double[MAX_CANDIDATES];
    private final double[] candMinX = new double[MAX_CANDIDATES];
    private final double[] candMinZ = new double[MAX_CANDIDATES];
    private final double[] candMaxX = new double[MAX_CANDIDATES];
    private final double[] candMaxZ = new double[MAX_CANDIDATES];
    private final byte[] candSurface = new byte[MAX_CANDIDATES];
    private final double[] candBase = new double[MAX_CANDIDATES];
    private final double[] candScore = new double[MAX_CANDIDATES];
    private final int[] candCache = new int[MAX_CANDIDATES];
    private int candCount;
    private int lookups;
    private int simTicks;
    private int rolls;
    private final int[] pool = new int[ROLL_POOL];
    private final int[] colCount = new int[COL_CELLS];
    private final float[] box = new float[6];

    // hysteresis
    private int chX, chY, chZ;
    private int chTicks;

    // ---- roll cache (per candidate cell) ------------------------------------------------------------------------
    private static final byte UNKNOWN = 0;
    private static final byte YES = 1;
    private static final byte NO = 2;
    /** The straight roll ran into a wall: not reachable on the line, maybe around it. */
    private static final byte BLOCKED = 3;
    private boolean rollBlocked;
    private boolean tBlocked;

    /**
     * The target's straight roll was stopped by a wall that is not the target: it is kept as a candidate, but only a
     * technique (a way around) can land it. Valid with {@link #hasTarget()}.
     */
    public boolean targetBlocked() {
        return hasTarget && tBlocked;
    }
    private final double[] candKey = new double[MAX_CANDIDATES];
    private final long[] cacheCell = new long[CACHE];
    private final byte[] cacheFeasible = new byte[CACHE];
    private final byte[] cacheContinues = new byte[CACHE];
    private final double[] cacheSX = new double[CACHE];
    private final double[] cacheSY = new double[CACHE];
    private final double[] cacheSZ = new double[CACHE];
    private final double[] cacheSpeed = new double[CACHE];
    private final boolean[] cacheSprint = new boolean[CACHE];
    private final boolean[] cacheGround = new boolean[CACHE];
    private final boolean[] cacheUsed = new boolean[CACHE];
    private int cacheNext;

    private final MovementPhysics roll = new MovementPhysics();
    private final MovementPhysics save = new MovementPhysics();

    // per-call scratch (fields so the helpers stay allocation free)
    private double px, py, pz, eyeY, refTop, speedNow, mx, mz;
    private boolean sprintNow, groundNow;

    // ---- inputs from the caller ---------------------------------------------------------------------------------

    /** A jump the player made (takeoff and landing feet positions), kept as the "line of recent jumps". */
    public void noteJump(double takeoffX, double takeoffZ, double landX, double landZ) {
        noteJump(takeoffX, takeoffZ, landX, Double.NaN, landZ);
    }

    /**
     * A jump with the landing feet height, so the course ring tells stacked platforms apart (a spiral tower). With
     * the four-argument form the ring matches any height.
     */
    public void noteJump(double takeoffX, double takeoffZ, double landX, double landY, double landZ) {
        double dx = landX - takeoffX;
        double dz = landZ - takeoffZ;
        double d2 = dx * dx + dz * dz;
        if (d2 < 1.0 || d2 > MAX_JUMP_NOTE * MAX_JUMP_NOTE) {
            return; // not a real jump: a step, or a teleport / respawn between a takeoff and the next ground tick
        }
        jumpDx[jumpHead] = dx;
        jumpDz[jumpHead] = dz;
        jumpHead = (jumpHead + 1) % JUMP_RING;
        if (jumpCount < JUMP_RING) {
            jumpCount++;
        }
        ringX[ringHead] = landX;
        ringY[ringHead] = landY;
        ringZ[ringHead] = landZ;
        ringHead = (ringHead + 1) % PLATFORM_RING;
        if (ringCount < PLATFORM_RING) {
            ringCount++;
        }
        leftX = takeoffX;
        leftZ = takeoffZ;
        leftY = takeoffY;
        ringPendingY = Double.isNaN(landY);
        haveLeft = true;
    }

    /** Score factor of the candidate that is the next link of the {@link ChainLookahead} path (sets up the chain). */
    public static final double CHAIN_BONUS = 1.12;
    private boolean hintSet;
    private int hintX, hintZ;
    private double hintTop;

    /** The next jump link of the chain lookahead from where the body stands (block column and feet height). */
    public void setChainHint(int x, double top, int z) {
        hintSet = true;
        hintX = x;
        hintTop = top;
        hintZ = z;
    }

    public void clearChainHint() {
        hintSet = false;
    }

    /** Commit threshold: {@link #GUIDE_THRESHOLD} (default) or {@link #CARRY_THRESHOLD}; clamped to 0.05..1. */
    public void setCommitThreshold(double threshold) {
        commitThreshold = Math.max(0.05, Math.min(1.0, threshold));
    }

    public double commitThreshold() {
        return commitThreshold;
    }

    /** Forgets the jump line, the course ring, the gaze and the roll cache (teleport, world change, long stop). */
    public void reset() {
        hasTarget = false;
        confidence = 0.0;
        lookConfidence = 0.0;
        prevValid = false;
        jumpCount = 0;
        jumpHead = 0;
        ringCount = 0;
        ringHead = 0;
        haveLeft = false;
        ringPendingY = false;
        takeoffY = Double.NaN;
        wasGround = false;
        gazeValid = false;
        groundValid = false;
        candCount = 0;
        lookups = 0;
        simTicks = 0;
        rolls = 0;
        chTicks = 0;
        for (int i = 0; i < CACHE; i++) {
            cacheUsed[i] = false;
        }
    }

    /**
     * One estimate per tick, budgeted.
     *
     * @param userForward the user's forward axis (-1, 0, 1)
     * @param userStrafe  the user's strafe axis (+1 left, -1 right, 0)
     * @return true when a target with at least the commit threshold was found
     */
    public boolean estimate(MovementPhysics now, float yaw, float pitch, int userForward, int userStrafe,
                            boolean sprinting, MovementPhysics.Voxels w) {
        hasTarget = false;
        confidence = 0.0;
        lookConfidence = 0.0;
        idealYaw = yaw;
        candCount = 0;
        lookups = 0;
        simTicks = 0;
        rolls = 0;

        px = now.x;
        py = now.y;
        pz = now.z;
        eyeY = py + EYE_HEIGHT;
        sprintNow = sprinting;
        groundNow = now.onGround;
        if (now.onGround) {
            groundValid = true;
            groundY = now.y;
            if (ringPendingY && ringCount > 0) {
                int r = (ringHead + PLATFORM_RING - 1) % PLATFORM_RING;
                if (Math.abs(ringX[r] - px) < 1.5 && Math.abs(ringZ[r] - pz) < 1.5) {
                    ringY[r] = py; // the landing platform's height, seen standing on it
                }
                ringPendingY = false;
            }
        } else {
            if (wasGround && groundValid) {
                takeoffY = groundY; // the ground this flight left
            }
            if (groundValid && (py < groundY - 2.0 || py > groundY + 2.0)) {
                groundValid = false;
            }
        }
        wasGround = now.onGround;
        refTop = groundValid ? groundY : floor(py + 1.0e-6);

        double yawRad = Math.toRadians(yaw);
        double sinY = Math.sin(yawRad);
        double cosY = Math.cos(yawRad);
        double pitchRad = Math.toRadians(pitch);
        double cosP = Math.cos(pitchRad);
        double lx = -sinY * cosP;
        double ly = -Math.sin(pitchRad);
        double lz = cosY * cosP;
        updateGaze(lx, ly, lz, w);

        double speed = Math.sqrt(now.vx * now.vx + now.vz * now.vz);
        speedNow = speed;
        boolean keys = userForward != 0 || userStrafe != 0;
        if (!keys && speed < STILL_SPEED) {
            prevValid = false;
            return false;
        }

        if (keys) {
            mx = userForward * -sinY + userStrafe * cosY;
            mz = userForward * cosY + userStrafe * sinY;
        } else if (speed >= 0.05) {
            mx = now.vx;
            mz = now.vz;
        } else {
            mx = -sinY;
            mz = cosY;
        }
        double mlen = Math.sqrt(mx * mx + mz * mz);
        if (mlen < 1.0e-9) {
            mx = -sinY;
            mz = cosY;
            mlen = 1.0;
        }
        mx /= mlen;
        mz /= mlen;
        double vux = 0.0;
        double vuz = 0.0;
        if (speed >= 1.0e-6) {
            vux = now.vx / speed;
            vuz = now.vz / speed;
        }

        double meanX = 0.0;
        double meanZ = 0.0;
        if (jumpCount > 0) {
            for (int i = 0; i < jumpCount; i++) {
                meanX += jumpDx[i];
                meanZ += jumpDz[i];
            }
            meanX /= jumpCount;
            meanZ /= jumpCount;
        }
        double predX = px + meanX;
        double predZ = pz + meanZ;

        // gaze direction from the eye (used for the angular look term)
        double gdx = gazeX - px;
        double gdy = gazeY - eyeY;
        double gdz = gazeZ - pz;
        double glen = Math.sqrt(gdx * gdx + gdy * gdy + gdz * gdz);
        if (glen < 1.0e-6) {
            gdx = lx;
            gdy = ly;
            gdz = lz;
        } else {
            gdx /= glen;
            gdy /= glen;
            gdz /= glen;
        }

        scanColumns(w, keys, vux, vuz, speed, predX, predZ, gdx, gdy, gdz);
        if (candCount == 0) {
            prevValid = false;
            return false;
        }

        // feasibility rolls for the best few (cached per cell)
        for (int i = 0; i < candCount; i++) {
            candCache[i] = findCache(candX[i], candY[i], candZ[i]);
        }
        rollPool(now, w);
        applyScores();
        int best = bestIndex(-1);
        if (best < 0) {
            prevValid = false;
            return false;
        }
        int second = bestIndex(best);
        if (second >= 0 && candScore[best] - candScore[second] <= DEAD_END_BAND) {
            boolean rolled = deadEndRoll(best, w) | deadEndRoll(second, w);
            if (rolled) {
                applyScores();
                best = bestIndex(-1);
            }
        }

        // hysteresis: the incumbent stays unless a challenger leads by the margin on consecutive ticks
        int chosen = best;
        if (prevValid) {
            int inc = -1;
            for (int i = 0; i < candCount; i++) {
                if (candX[i] == tx && candY[i] == ty && candZ[i] == tz) {
                    inc = i;
                    break;
                }
            }
            if (inc >= 0 && candScore[inc] > 0.0 && inc != best) {
                if (candScore[best] > candScore[inc] + HYSTERESIS) {
                    if (chTicks > 0 && candX[best] == chX && candY[best] == chY && candZ[best] == chZ) {
                        chTicks++;
                    } else {
                        chX = candX[best];
                        chY = candY[best];
                        chZ = candZ[best];
                        chTicks = 1;
                    }
                    chosen = chTicks >= CHALLENGE_TICKS ? best : inc;
                } else {
                    chosen = inc;
                    chTicks = 0;
                }
            } else {
                chTicks = 0;
            }
        }
        if (chosen == best) {
            chTicks = 0;
        }

        int bx = candX[chosen];
        int bz = candZ[chosen];
        double bs = candScore[chosen];
        double rival = 0.0;
        for (int i = 0; i < candCount; i++) {
            if (i == chosen) {
                continue;
            }
            // neighbours of the winner are the same landing area, not a rival
            if (Math.abs(candX[i] - bx) <= 1 && Math.abs(candZ[i] - bz) <= 1) {
                continue;
            }
            if (candScore[i] > rival) {
                rival = candScore[i];
            }
        }
        double margin = Math.max(0.0, bs - rival);
        double conf = clamp01(Math.min(1.0, margin / MARGIN_FULL) * bs);
        double plain = conf; // before the sole-candidate floor
        if (rival <= 0.0 && candKey[chosen] >= SOLE_ALIGN && candCache[chosen] >= 0) {
            // the only place to go, and the keys point at it: the look term must not hold the confidence down
            byte fz = cacheFeasible[candCache[chosen]];
            if (fz == YES) {
                conf = Math.max(conf, SOLE_CONFIDENCE);
            } else if (fz == BLOCKED) {
                conf = Math.max(conf, SOLE_CONFIDENCE * BLOCKED_FACTOR);
            }
        }
        if (candSurface[chosen] == MovementPhysics.SURFACE_UNMODELLED) {
            conf = Math.min(conf, UNMODELLED_CONFIDENCE);
            plain = Math.min(plain, UNMODELLED_CONFIDENCE);
        }

        tx = bx;
        ty = candY[chosen];
        tz = bz;
        tTop = candTop[chosen];
        tSurface = candSurface[chosen];
        tBlocked = candCache[chosen] >= 0 && cacheFeasible[candCache[chosen]] == BLOCKED;
        prevValid = true;
        confidence = conf;
        lookConfidence = plain;
        double cx = 0.5 * (candMinX[chosen] + candMaxX[chosen]);
        double cz = 0.5 * (candMinZ[chosen] + candMaxZ[chosen]);
        idealYaw = (float) Math.toDegrees(Math.atan2(-(cx - px), cz - pz));
        hasTarget = confidence >= commitThreshold;
        return hasTarget;
    }

    // ---- gaze ---------------------------------------------------------------------------------------------------

    /** Marches the look ray through the cells (exact boxes) and folds the hit point into the gaze average. */
    private void updateGaze(double lx, double ly, double lz, MovementPhysics.Voxels w) {
        double ox = px;
        double oy = eyeY;
        double oz = pz;
        int cx = floor(ox);
        int cy = floor(oy);
        int cz = floor(oz);
        int stepX = lx > 0 ? 1 : -1;
        int stepY = ly > 0 ? 1 : -1;
        int stepZ = lz > 0 ? 1 : -1;
        double tdx = Math.abs(lx) < 1.0e-9 ? Double.MAX_VALUE : Math.abs(1.0 / lx);
        double tdy = Math.abs(ly) < 1.0e-9 ? Double.MAX_VALUE : Math.abs(1.0 / ly);
        double tdz = Math.abs(lz) < 1.0e-9 ? Double.MAX_VALUE : Math.abs(1.0 / lz);
        double tmx = Math.abs(lx) < 1.0e-9 ? Double.MAX_VALUE : ((lx > 0 ? cx + 1 - ox : ox - cx) * tdx);
        double tmy = Math.abs(ly) < 1.0e-9 ? Double.MAX_VALUE : ((ly > 0 ? cy + 1 - oy : oy - cy) * tdy);
        double tmz = Math.abs(lz) < 1.0e-9 ? Double.MAX_VALUE : ((lz > 0 ? cz + 1 - oz : oz - cz) * tdz);
        boolean shaped = w.shaped();
        double hitT = -1.0;
        for (int steps = 0; steps < 40; steps++) {
            double t = rayCell(w, cx, cy, cz, ox, oy, oz, lx, ly, lz);
            if (shaped) {
                double t2 = rayCell(w, cx, cy - 1, cz, ox, oy, oz, lx, ly, lz); // fences and walls rise into here
                if (t2 >= 0.0 && (t < 0.0 || t2 < t)) {
                    t = t2;
                }
            }
            if (t >= 0.0) {
                hitT = t;
                break;
            }
            double tNext = Math.min(tmx, Math.min(tmy, tmz));
            if (tNext > GAZE_RANGE) {
                break;
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
        double hx;
        double hy;
        double hz;
        if (hitT >= 0.0) {
            hx = ox + lx * hitT;
            hy = oy + ly * hitT;
            hz = oz + lz * hitT;
        } else if (ly < -1.0e-6 && (refTop - oy) / ly <= GAZE_RANGE * 1.5) {
            double t = (refTop - oy) / ly; // nothing hit: where the ray crosses the feet level
            hx = ox + lx * t;
            hy = refTop;
            hz = oz + lz * t;
        } else {
            hx = ox + lx * GAZE_RANGE;
            hy = oy + ly * GAZE_RANGE;
            hz = oz + lz * GAZE_RANGE;
        }
        if (!gazeValid) {
            gazeX = hx;
            gazeY = hy;
            gazeZ = hz;
            gazeValid = true;
        } else {
            gazeX += GAZE_ALPHA * (hx - gazeX);
            gazeY += GAZE_ALPHA * (hy - gazeY);
            gazeZ += GAZE_ALPHA * (hz - gazeZ);
        }
    }

    /** Nearest ray parameter at which the ray enters a box of the cell, or -1. */
    private double rayCell(MovementPhysics.Voxels w, int cx, int cy, int cz, double ox, double oy, double oz,
                           double lx, double ly, double lz) {
        int n = w.boxCount(cx, cy, cz);
        lookups++;
        double best = -1.0;
        for (int i = 0; i < n; i++) {
            w.box(cx, cy, cz, i, box, 0);
            lookups++;
            double t = slab(ox, lx, cx + box[0], cx + box[3], 0.0, Double.MAX_VALUE);
            t = t < 0 ? -1 : slab2(t, oy, ly, cy + box[1], cy + box[4], oz, lz, cz + box[2], cz + box[5]);
            if (t >= 0.0 && (best < 0.0 || t < best)) {
                best = t;
            }
        }
        return best;
    }

    private double slabHi;

    /** Clips [lo, hi] by one axis; returns the new lo (or -1 when empty) and keeps hi in {@link #slabHi}. */
    private double slab(double o, double d, double min, double max, double lo, double hi) {
        if (Math.abs(d) < 1.0e-12) {
            if (o < min || o > max) {
                return -1.0;
            }
            slabHi = hi;
            return lo;
        }
        double t1 = (min - o) / d;
        double t2 = (max - o) / d;
        if (t1 > t2) {
            double s = t1;
            t1 = t2;
            t2 = s;
        }
        lo = Math.max(lo, t1);
        hi = Math.min(hi, t2);
        slabHi = hi;
        return lo <= hi ? lo : -1.0;
    }

    private double slab2(double lo, double oy, double ly, double minY, double maxY, double oz, double lz, double minZ,
                         double maxZ) {
        double hi = slabHi;
        lo = slab(oy, ly, minY, maxY, lo, hi);
        if (lo < 0.0) {
            return -1.0;
        }
        return slab(oz, lz, minZ, maxZ, lo, slabHi);
    }

    // ---- candidates ---------------------------------------------------------------------------------------------

    private void scanColumns(MovementPhysics.Voxels w, boolean keys, double vux, double vuz, double speed,
                             double predX, double predZ, double gdx, double gdy, double gdz) {
        int fx = floor(px);
        int fz = floor(pz);
        int sy = floor(refTop - TOP_EPS); // the cell whose top the player stands on
        int yBot = sy - MAX_DOWN;
        int yTop = sy + MAX_UP;
        for (int c = 0; c < COL_DX.length && candCount < MAX_CANDIDATES; c++) {
            if (lookups + COLUMN_RESERVE > MAX_LOOKUPS) {
                break;
            }
            int bx = fx + COL_DX[c];
            int bz = fz + COL_DZ[c];
            double ex0 = bx + 0.5 - px;
            double ez0 = bz + 0.5 - pz;
            double hd0 = Math.sqrt(ex0 * ex0 + ez0 * ez0);
            if (hd0 < MIN_RANGE - 0.5 || hd0 > MAX_RANGE + 0.5) {
                continue;
            }
            if (ex0 * mx + ez0 * mz < CONE_COS * hd0) {
                continue;
            }
            for (int k = 0; k < COL_CELLS; k++) {
                colCount[k] = w.boxCount(bx, yBot + k, bz);
                lookups++;
            }
            for (int y = yTop; y >= yBot && candCount < MAX_CANDIDATES; y--) {
                int k = y - yBot;
                if (colCount[k] == 0) {
                    continue;
                }
                double top = topOfCell(w, bx, y, bz, colCount[k]);
                if (Double.isNaN(top) || top - refTop > MAX_JUMP_UP) {
                    continue;
                }
                if (!clear(w, bx, y, bz, top, yBot)) {
                    continue;
                }
                double t0x = topMinX; // jumpTarget below reuses the top scratch
                double t0z = topMinZ;
                double t1x = topMaxX;
                double t1z = topMaxZ;
                double cxm = 0.5 * (t0x + t1x);
                double czm = 0.5 * (t0z + t1z);
                double ex = cxm - px;
                double ez = czm - pz;
                double hd = Math.sqrt(ex * ex + ez * ez);
                if (hd < MIN_RANGE || hd > MAX_RANGE || ex * mx + ez * mz < CONE_COS * hd) {
                    continue;
                }
                double reach = tableReach(top - refTop);
                if (hd > reach + REACH_SOFT) {
                    continue; // the table pre-filter
                }
                byte surf = w.surface(bx, y, bz);
                lookups++;
                if (surf == MovementPhysics.SURFACE_HARM || surf == MovementPhysics.SURFACE_NONE
                        || surf == MovementPhysics.SURFACE_STUCK) {
                    continue;
                }
                int above = floor(top + TOP_EPS);
                if (above != y) {
                    byte up = w.surface(bx, above, bz);
                    lookups++;
                    if (up == MovementPhysics.SURFACE_HARM || up == MovementPhysics.SURFACE_STUCK) {
                        continue;
                    }
                    if (up == MovementPhysics.SURFACE_UNMODELLED) {
                        surf = up;
                    }
                }
                if (Math.abs(top - refTop) < 0.01 && !jumpTarget(w, cxm, czm, hd)) {
                    continue;
                }
                int i = candCount++;
                candX[i] = bx;
                candY[i] = y;
                candZ[i] = bz;
                candTop[i] = top;
                candMinX[i] = t0x;
                candMinZ[i] = t0z;
                candMaxX[i] = t1x;
                candMaxZ[i] = t1z;
                candSurface[i] = surf;
                candBase[i] = score(i, ex, ez, hd, reach, keys, vux, vuz, speed, predX, predZ, gdx, gdy, gdz);
            }
        }
    }

    private double topMinX, topMinZ, topMaxX, topMaxZ;

    /** Highest top face of the cell's boxes and its extent (world coordinates), or NaN. */
    private double topOfCell(MovementPhysics.Voxels w, int x, int y, int z, int n) {
        double best = Double.NaN;
        for (int i = 0; i < n; i++) {
            w.box(x, y, z, i, box, 0);
            lookups++;
            double t = y + box[4];
            if (!(t <= best + TOP_EPS)) {
                best = t;
                topMinX = x + box[0];
                topMinZ = z + box[2];
                topMaxX = x + box[3];
                topMaxZ = z + box[5];
            } else if (Math.abs(t - best) <= TOP_EPS) {
                topMinX = Math.min(topMinX, x + box[0]);
                topMinZ = Math.min(topMinZ, z + box[2]);
                topMaxX = Math.max(topMaxX, x + box[3]);
                topMaxZ = Math.max(topMaxZ, z + box[5]);
            }
        }
        return best;
    }

    /** No box of the cells above overlaps the body height over the top. */
    private boolean clear(MovementPhysics.Voxels w, int x, int y, int z, double top, int yBot) {
        int last = floor(top + BODY - TOP_EPS);
        for (int c = y + 1; c <= last; c++) {
            int k = c - yBot;
            int n;
            if (k >= 0 && k < COL_CELLS) {
                n = colCount[k];
            } else {
                n = w.boxCount(x, c, z);
                lookups++;
            }
            for (int i = 0; i < n; i++) {
                w.box(x, c, z, i, box, 0);
                lookups++;
                if (c + box[1] < top + BODY - TOP_EPS && c + box[4] > top + TOP_EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Excludes tops reachable by walking along the same floor (every sampled column has a top at the feet level). */
    private boolean jumpTarget(MovementPhysics.Voxels w, double cx, double cz, double hd) {
        double dx = (cx - px) / hd;
        double dz = (cz - pz) / hd;
        int cy = floor(refTop - TOP_EPS);
        for (double d = WALK_STEP; d < hd; d += WALK_STEP) {
            if (lookups + 8 > MAX_LOOKUPS) {
                return false; // out of budget: leave it out rather than guess
            }
            int sx = floor(px + dx * d);
            int sz = floor(pz + dz * d);
            int n = w.boxCount(sx, cy, sz);
            lookups++;
            if (n == 0) {
                return true;
            }
            double t = topOfCell(w, sx, cy, sz, n);
            if (!(Math.abs(t - refTop) < 0.01)) {
                return true;
            }
            // a floor-level block with more blocks on it is a wall, not floor: the course J36 pillar stands in the
            // gap cell, and the strip behind it was dropped as "walkable" (live trace, 2026-11-23)
            if (!clear(w, sx, cy, sz, t, cy + COL_CELLS + 1)) {
                return true;
            }
        }
        return false;
    }

    private double tableReach(double dy) {
        double f = 1.0 - dy; // up 1 -> 0, flat -> 1, down 4 -> 5
        double reach;
        if (f <= 0.0) {
            reach = REACH_BY_DY[0];
        } else if (f >= REACH_BY_DY.length - 1) {
            reach = REACH_BY_DY[REACH_BY_DY.length - 1];
        } else {
            int i = (int) f;
            double a = f - i;
            reach = REACH_BY_DY[i] * (1.0 - a) + REACH_BY_DY[i + 1] * a;
        }
        reach += 0.4;
        return sprintNow ? reach : reach * NO_SPRINT_REACH;
    }

    private double score(int i, double ex, double ez, double hd, double reach, boolean keys, double vux, double vuz,
                         double speed, double predX, double predZ, double gdx, double gdy, double gdz) {
        double top = candTop[i];
        // LOOK: the gaze average on the top face scores 1, otherwise the angle to the top's centre
        double lookHit = 0.0;
        if (gazeX >= candMinX[i] - LOOK_BLOCK_MARGIN && gazeX <= candMaxX[i] + LOOK_BLOCK_MARGIN
                && gazeZ >= candMinZ[i] - LOOK_BLOCK_MARGIN && gazeZ <= candMaxZ[i] + LOOK_BLOCK_MARGIN
                && Math.abs(gazeY - top) <= LOOK_TOP_DY) {
            lookHit = 1.0;
        }
        double dy = top - eyeY;
        double dist = Math.sqrt(ex * ex + ez * ez + dy * dy);
        double dot = (ex * gdx + dy * gdy + ez * gdz) / dist;
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        double look = clamp01(Math.max(lookHit, 1.0 - angle / LOOK_ZERO_DEG));

        double keyTerm = 0.5;
        if (keys) {
            keyTerm = align(ex * mx + ez * mz, hd);
        }
        candKey[i] = keys ? keyTerm : 0.0;
        double velTerm = 0.5;
        if (speed > 1.0e-6) {
            double a = align(ex * vux + ez * vuz, hd);
            double wgt = Math.min(1.0, speed / 0.2);
            velTerm = 0.5 + (a - 0.5) * wgt;
        }
        double lineTerm = 0.5;
        if (jumpCount > 0) {
            double ddx = px + ex - predX;
            double ddz = pz + ez - predZ;
            lineTerm = clamp01(1.0 - Math.sqrt(ddx * ddx + ddz * ddz) / LINE_ZERO_DIST);
        }
        double reachTerm = hd <= reach ? 1.0 : clamp01(1.0 - (hd - reach) / REACH_SOFT);

        double sum = W_LOOK * look + W_KEYS * keyTerm + W_VELOCITY * velTerm + W_LINE * lineTerm + W_REACH * reachTerm;
        double s = sum * (0.5 + 0.5 * reachTerm);
        double course = coursePenalty(i);
        s *= course;
        if (course < 1.0) {
            candKey[i] = 0.0; // a platform already visited never gets the sole-candidate floor
        }
        if (candSurface[i] == MovementPhysics.SURFACE_UNMODELLED) {
            s *= UNMODELLED_FACTOR;
        }
        if (hintSet && candX[i] == hintX && candZ[i] == hintZ && Math.abs(top - hintTop) < 0.05) {
            s *= CHAIN_BONUS; // of two plausible tops, the one the chain goes on from
        }
        return s;
    }

    /** The platform just left (latest landing, last takeoff) and older ring platforms are penalised. */
    private double coursePenalty(int i) {
        double f = 1.0;
        int newest = (ringHead + PLATFORM_RING - 1) % PLATFORM_RING;
        for (int k = 0; k < ringCount; k++) {
            int r = (ringHead + PLATFORM_RING - 1 - k) % PLATFORM_RING;
            if (onPlatform(i, ringX[r], ringY[r], ringZ[r])) {
                f = Math.min(f, r == newest ? LEFT_PENALTY : OLD_PENALTY);
            }
        }
        if (haveLeft && onPlatform(i, leftX, leftY, leftZ)) {
            f = Math.min(f, LEFT_PENALTY);
        }
        return f;
    }

    private boolean onPlatform(int i, double x, double y, double z) {
        if (!Double.isNaN(y) && Math.abs(y - candTop[i]) > 0.6) {
            return false;
        }
        // within one cell of the point counts as the same platform
        return Math.abs(floor(x) - candX[i]) <= 1 && Math.abs(floor(z) - candZ[i]) <= 1
                && Math.abs(x - 0.5 * (candMinX[i] + candMaxX[i])) < 1.5
                && Math.abs(z - 0.5 * (candMinZ[i] + candMaxZ[i])) < 1.5;
    }

    // ---- selection ------------------------------------------------------------------------------------------------

    private void applyScores() {
        for (int i = 0; i < candCount; i++) {
            double s = candBase[i];
            int c = candCache[i];
            if (c >= 0) {
                if (cacheFeasible[c] == NO) {
                    s = -1.0;
                } else if (cacheFeasible[c] == BLOCKED) {
                    s *= BLOCKED_FACTOR;
                } else if (cacheContinues[c] == NO) {
                    s *= DEAD_END_FACTOR;
                }
            }
            candScore[i] = s;
        }
    }

    /** Best candidate with a positive score, leaving out {@code exclude} and its 1-cell neighbourhood. */
    private int bestIndex(int exclude) {
        int best = -1;
        for (int i = 0; i < candCount; i++) {
            if (candScore[i] <= 0.0) {
                continue;
            }
            if (exclude >= 0 && (i == exclude || (Math.abs(candX[i] - candX[exclude]) <= 1
                    && Math.abs(candZ[i] - candZ[exclude]) <= 1))) {
                continue;
            }
            if (best < 0 || candScore[i] > candScore[best]) {
                best = i;
            }
        }
        return best;
    }

    // ---- rolls ----------------------------------------------------------------------------------------------------

    /** The best {@link #ROLL_POOL} by base score, not known infeasible, get a roll when none is cached or fresh. */
    private void rollPool(MovementPhysics now, MovementPhysics.Voxels w) {
        int n = 0;
        for (int i = 0; i < candCount; i++) {
            int c = candCache[i];
            if (c >= 0 && cacheFeasible[c] == NO && fresh(c)) {
                continue;
            }
            // insertion into the small ranked pool
            int j = n < ROLL_POOL ? n++ : ROLL_POOL;
            if (j == ROLL_POOL) {
                if (candBase[i] <= candBase[pool[ROLL_POOL - 1]]) {
                    continue;
                }
                j = ROLL_POOL - 1;
            }
            while (j > 0 && candBase[pool[j - 1]] < candBase[i]) {
                pool[j] = pool[j - 1];
                j--;
            }
            pool[j] = i;
        }
        for (int k = 0; k < n && rolls < MAX_ROLLS_PER_ESTIMATE; k++) {
            int i = pool[k];
            int c = candCache[i];
            if (c >= 0 && cacheFeasible[c] != UNKNOWN && fresh(c)) {
                continue;
            }
            boolean wasBlocked = c >= 0 && cacheFeasible[c] == BLOCKED;
            boolean ok = feasibilityRoll(now, i, w);
            rolls++;
            if (c < 0) {
                c = newCache(candX[i], candY[i], candZ[i]);
                candCache[i] = c;
                for (int o = 0; o < candCount; o++) {
                    if (o != i && candCache[o] == c) {
                        candCache[o] = -1; // the slot was recycled
                    }
                }
            }
            // a top found behind a wall stays "needs a way around" for later stances: nearer the wall the straight roll
            // may pass it and fall short instead, which says nothing new about the way around
            cacheFeasible[c] = ok ? YES : (rollBlocked || wasBlocked ? BLOCKED : NO);
            cacheContinues[c] = UNKNOWN;
            stamp(c);
        }
    }

    /**
     * One roll toward candidate {@code i}: run toward its top centre, jump at the coming edge (or a wall), or as soon
     * as the coast alone would carry the body there; in the air hold forward, release or hold back to aim at the
     * centre. Feasible when the body lands on the top's height inside the top (grown by the body half-width) with a
     * fall of at most 3 blocks.
     */
    private boolean feasibilityRoll(MovementPhysics now, int i, MovementPhysics.Voxels w) {
        MovementPhysics s = roll.copyFrom(now);
        rollBlocked = false;
        s.jumpDelay = 0; // a held jump key of the user's does not delay the planned takeoff
        double top = candTop[i];
        double tcx = 0.5 * (candMinX[i] + candMaxX[i]);
        double tcz = 0.5 * (candMinZ[i] + candMaxZ[i]);
        boolean airborne = !s.onGround;
        double peak = s.y;
        int used = 0;
        int ground = 0;
        while (used < ROLL_TICKS && simTicks < MAX_SIM_TICKS) {
            double dx = tcx - s.x;
            double dz = tcz - s.z;
            double dc = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            if (s.onGround && !airborne) {
                if (++ground > RUNUP_MAX) {
                    return false;
                }
                double ux = dc > 1.0e-9 ? dx / dc : 0.0;
                double uz = dc > 1.0e-9 ? dz / dc : 0.0;
                double vt = s.vx * ux + s.vz * uz;
                boolean jumpNow = coast(vt + (sprintNow ? MovementPhysics.SPRINT_JUMP_BOOST + 0.13 : 0.1),
                        airTicksTo(top - s.y, MovementPhysics.JUMP_VELOCITY + s.jumpBoost)) >= dc - 0.3;
                if (!jumpNow) {
                    save.copyFrom(s);
                    s.tick(1, 0, yaw, sprintNow, false, w);
                    simTicks++;
                    used++;
                    if (s.onGround && !s.horizontalCollision) {
                        continue;
                    }
                    s.copyFrom(save); // the edge (or a wall): the jump goes on this tick
                }
                s.tick(1, 0, yaw, sprintNow, true, w);
                simTicks++;
                used++;
                airborne = true;
                peak = Math.max(peak, s.y);
                continue;
            }
            airborne = true;
            int n = airTicksTo(top - s.y, s.vy);
            double ux = dc > 1.0e-9 ? dx / dc : 0.0;
            double uz = dc > 1.0e-9 ? dz / dc : 0.0;
            double drift = coast(s.vx * ux + s.vz * uz, n);
            int f = drift > dc + 0.05 ? -1 : (drift > dc - 0.25 ? 0 : 1);
            s.tick(f, 0, yaw, sprintNow, false, w);
            simTicks++;
            used++;
            peak = Math.max(peak, s.y);
            if (s.horizontalCollision && !rollBlocked && wallBeside(w, s, dx, dz, top)) {
                rollBlocked = true; // stopped by something taller than the target, not by the target's own side
            }
            if (s.onGround) {
                return Math.abs(s.y - top) < 0.01 && peak - s.y <= MAX_SAFE_FALL
                        && s.x >= candMinX[i] - HALF && s.x <= candMaxX[i] + HALF
                        && s.z >= candMinZ[i] - HALF && s.z <= candMaxZ[i] + HALF;
            }
        }
        return false;
    }

    /** A block next to the body must stand this far above the target's top to count as a wall in the way. */
    private static final double WALL_OVER_TOP = 0.6;

    /**
     * The body just touched something. A wall in the way (BLOCKED) is a block beside the body, on the side the target
     * lies, that rises more than {@link #WALL_OVER_TOP} above the target's top. A roll that fell short and ran into the
     * side of the target's own platform touches nothing that tall: that top is out of reach, not blocked.
     */
    private boolean wallBeside(MovementPhysics.Voxels w, MovementPhysics s, double dx, double dz, double top) {
        int cx = floor(s.x);
        int cz = floor(s.z);
        int nx = floor(s.x + (dx > 0.0 ? HALF + 0.05 : -HALF - 0.05));
        int nz = floor(s.z + (dz > 0.0 ? HALF + 0.05 : -HALF - 0.05));
        int y0 = floor(top + WALL_OVER_TOP);
        int y1 = floor(top + BODY - TOP_EPS);
        for (int y = y0; y <= y1; y++) {
            lookups += 3;
            if ((nx != cx && w.boxCount(nx, y, cz) > 0) || (nz != cz && w.boxCount(cx, y, nz) > 0)
                    || (nx != cx && nz != cz && w.boxCount(nx, y, nz) > 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dead-end check for a finalist: from standing on its top centre, carrying the current speed (0.1..0.28) along the
     * movement direction, a sprint jump at the edge must land on another top with a safe fall.
     *
     * @return true when a roll was made
     */
    private boolean deadEndRoll(int i, MovementPhysics.Voxels w) {
        int c = candCache[i];
        if (c < 0 || cacheFeasible[c] != YES || cacheContinues[c] != UNKNOWN || rolls >= MAX_ROLLS_PER_ESTIMATE) {
            return false;
        }
        rolls++;
        MovementPhysics s = roll;
        double v = Math.max(0.1, Math.min(0.28, speedNow));
        s.set(0.5 * (candMinX[i] + candMaxX[i]), candTop[i], 0.5 * (candMinZ[i] + candMaxZ[i]), mx * v, 0.0, mz * v,
                true);
        s.jumpDelay = 0;
        s.crouching = false;
        s.sprintBlocked = false;
        s.sprinting = true;
        float yaw = (float) Math.toDegrees(Math.atan2(-mx, mz));
        boolean airborne = false;
        double peak = s.y;
        boolean ok = false;
        int used = 0;
        while (used < ROLL_TICKS && simTicks < MAX_SIM_TICKS) {
            if (!airborne) {
                save.copyFrom(s);
                s.tick(1, 0, yaw, true, false, w);
                simTicks++;
                used++;
                if (s.onGround && !s.horizontalCollision) {
                    continue;
                }
                s.copyFrom(save);
                s.tick(1, 0, yaw, true, true, w);
                simTicks++;
                used++;
                airborne = true;
                peak = Math.max(peak, s.y);
                continue;
            }
            s.tick(1, 0, yaw, true, false, w);
            simTicks++;
            used++;
            peak = Math.max(peak, s.y);
            if (s.onGround) {
                boolean other = floor(s.x) != candX[i] || floor(s.z) != candZ[i]
                        || Math.abs(s.y - candTop[i]) > 0.01;
                ok = other && peak - s.y <= MAX_SAFE_FALL;
                break;
            }
        }
        cacheContinues[c] = ok ? YES : NO;
        return true;
    }

    /** Horizontal distance a coast covers in {@code n} air ticks from speed {@code v} (air friction 0.91). */
    private static double coast(double v, int n) {
        double d = 0.0;
        for (int k = 0; k < n; k++) {
            d += v;
            v *= MovementPhysics.AIR_FRICTION;
        }
        return d;
    }

    /** Air ticks until a body with vertical speed {@code vy} comes down through height {@code dy} (capped at 60). */
    private static int airTicksTo(double dy, double vy) {
        double y = 0.0;
        for (int k = 1; k <= 60; k++) {
            y += vy;
            vy = (vy - MovementPhysics.GRAVITY) * MovementPhysics.DRAG;
            if (vy < 0 && y + vy < dy) {
                return k;
            }
        }
        return 60;
    }

    // ---- cache ----------------------------------------------------------------------------------------------------

    private int findCache(int x, int y, int z) {
        long key = Packed.pack(x, y, z);
        for (int c = 0; c < CACHE; c++) {
            if (cacheUsed[c] && cacheCell[c] == key) {
                return c;
            }
        }
        return -1;
    }

    private int newCache(int x, int y, int z) {
        int c = cacheNext;
        cacheNext = (cacheNext + 1) % CACHE;
        cacheUsed[c] = true;
        cacheCell[c] = Packed.pack(x, y, z);
        cacheFeasible[c] = UNKNOWN;
        cacheContinues[c] = UNKNOWN;
        return c;
    }

    private void stamp(int c) {
        cacheSX[c] = px;
        cacheSY[c] = py;
        cacheSZ[c] = pz;
        cacheSpeed[c] = speedNow;
        cacheSprint[c] = sprintNow;
        cacheGround[c] = groundNow;
    }

    /** The cached roll was made from about this stance. A stale result is kept until its re-roll (no flicker). */
    private boolean fresh(int c) {
        return Math.abs(cacheSX[c] - px) <= STANCE_POS && Math.abs(cacheSY[c] - py) <= STANCE_POS
                && Math.abs(cacheSZ[c] - pz) <= STANCE_POS && Math.abs(cacheSpeed[c] - speedNow) <= STANCE_SPEED
                && cacheSprint[c] == sprintNow && cacheGround[c] == groundNow;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static double align(double dot, double hd) {
        return clamp01((dot / hd - 0.3) / 0.7);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---- results --------------------------------------------------------------------------------------------------

    public boolean hasTarget() {
        return hasTarget;
    }

    /** The block to stand on (the cell holding the top box; feet land at {@link #targetTopY()}). */
    public int targetX() {
        return tx;
    }

    public int targetY() {
        return ty;
    }

    public int targetZ() {
        return tz;
    }

    /** True landing (feet) height on the target: y + 1 for a full block, + 1.5 for a fence or wall, + 0.5 slab. */
    public double targetTopY() {
        return tTop;
    }

    /** Surface kind of the target top (a {@code MovementPhysics.SURFACE_*} constant). */
    public byte targetSurface() {
        return tSurface;
    }

    /**
     * The confidence without the sole-candidate floor: what the look, the keys and the course line say by
     * themselves. The landing hold's "the user is going on" reads this one: with the floor, W alone would count as
     * going on wherever a single top lies ahead, and holding W must never chain a series of jumps.
     */
    /** Diagnostics for tests (allocates): every candidate of the last estimate. */
    String debugCandidates() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < candCount; i++) {
            int c = candCache[i];
            s.append(String.format(" [%d,%d,%d top%.2f sc%.3f key%.2f f%d]", candX[i], candY[i], candZ[i], candTop[i],
                    candScore[i], candKey[i], c >= 0 ? cacheFeasible[c] : -1));
        }
        return s.toString();
    }

    public double lookConfidence() {
        return lookConfidence;
    }

    private double lookConfidence;

    /** 0..1: how clearly the best candidate beats the runner-up (with the sole-candidate floor). */
    public double confidence() {
        return confidence;
    }

    /** Yaw from the player's position to the centre of the target's top face. */
    public float idealYaw() {
        return idealYaw;
    }

    /** Candidates scored by the last estimate() call (diagnostics, tests). */
    public int lastCandidateCount() {
        return candCount;
    }

    /** Block lookups (cell box counts, box reads, surfaces) made by the last estimate() call. */
    public int lastLookups() {
        return lookups;
    }

    /** Simulated ticks used by the rolls of the last estimate() call (at most {@link #MAX_SIM_TICKS}). */
    public int lastSimTicks() {
        return simTicks;
    }

    /** Rolls (feasibility and dead-end) made by the last estimate() call. */
    public int lastRolls() {
        return rolls;
    }

    /**
     * Cached feasibility of a candidate cell: 1 feasible, 0 infeasible, -1 not rolled (diagnostics, tests).
     */
    public int feasibility(int x, int y, int z) {
        int c = findCache(x, y, z);
        return c < 0 || cacheFeasible[c] == UNKNOWN ? -1 : (cacheFeasible[c] == YES ? 1 : 0);
    }

    /** Cached dead-end check of a candidate cell: 1 continues, 0 dead end, -1 not checked. */
    public int continues(int x, int y, int z) {
        int c = findCache(x, y, z);
        return c < 0 || cacheContinues[c] == UNKNOWN ? -1 : (cacheContinues[c] == YES ? 1 : 0);
    }
}
