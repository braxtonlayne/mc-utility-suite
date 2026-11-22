package com.utilitysuite.travel.movement;

/**
 * Estimates which block the player means to land on. Candidate landing cells around the player are scored by the look
 * ray, the held keys, the current velocity and the line of the recent jumps; the winner is the cell the jump search
 * and the turn gain aim for ("the block they were looking at", not just progress along the heading).
 * <p>
 * Candidates are standable cells (solid block with two clear cells above) in a cone around the movement direction,
 * within {@link #MAX_RANGE} horizontally and from {@link #MAX_UP} up to {@link #MAX_DOWN} down relative to the block
 * the player stands on. Cells reachable by walking along the same flat floor are not jump targets and are skipped.
 * Each candidate gets a weighted 0..1 score (look, keys, velocity, jump line, reach); the best one wins, with a
 * hysteresis margin so the target does not flip between neighbours. Work per call is bounded by
 * {@link #MAX_CANDIDATES} candidates and {@link #MAX_LOOKUPS} block lookups (nearest columns are scanned first).
 * <p>
 * Engine-free and allocation free after construction.
 */
public final class IntentEstimator {

    /** Below this confidence the assist treats the intent as unknown and keeps its heading-based behaviour. */
    public static final double MIN_CONFIDENCE = 0.5;

    public static final double W_LOOK = 0.40;
    public static final double W_KEYS = 0.20;
    public static final double W_VELOCITY = 0.15;
    public static final double W_LINE = 0.15;
    public static final double W_REACH = 0.10;

    /** Hard caps per estimate() call. */
    public static final int MAX_CANDIDATES = 96;
    public static final int MAX_LOOKUPS = 1200;

    /** Horizontal range (block-centre distance from the player) and vertical window (support-block relative). */
    public static final double MAX_RANGE = 6.0;
    public static final double MIN_RANGE = 1.2;
    public static final int MAX_UP = 1;
    public static final int MAX_DOWN = 4;

    /** Half-angle of the candidate cone around the movement direction, as a cosine (60 degrees). */
    private static final double CONE_COS = 0.5;
    /** Angular scale of the look term: a candidate this many degrees off the ray scores 0. */
    private static final double LOOK_ZERO_DEG = 30.0;
    private static final double LOOK_BLOCK_MARGIN = 0.1;
    /** Score margin between best and runner-up at which confidence stops growing. */
    private static final double MARGIN_FULL = 0.25;
    /** A previous target survives unless a challenger beats it by this much. */
    private static final double HYSTERESIS = 0.05;
    private static final double STILL_SPEED = 0.03;
    private static final double LINE_ZERO_DIST = 2.5;
    private static final double REACH_SOFT = 1.5;
    private static final double WALK_STEP = 0.5;
    private static final int JUMP_RING = 4;
    private static final double EYE_HEIGHT = 1.62;

    /** Sprint-jump reach (horizontal, block centre) by height difference: up 1, flat, down 1..4. */
    private static final double[] REACH_BY_DY = {3.6, 4.3, 4.6, 4.9, 5.1, 5.3};
    private static final double NO_SPRINT_REACH = 0.8;

    // Column offsets (dx, dz) around the feet block within range, nearest first.
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

    private boolean hasTarget;
    private int tx, ty, tz;
    private double confidence;
    private float idealYaw;
    private boolean prevValid;

    private final double[] jumpDx = new double[JUMP_RING];
    private final double[] jumpDz = new double[JUMP_RING];
    private int jumpCount;
    private int jumpHead;

    private final int[] candX = new int[MAX_CANDIDATES];
    private final int[] candY = new int[MAX_CANDIDATES];
    private final int[] candZ = new int[MAX_CANDIDATES];
    private final double[] candScore = new double[MAX_CANDIDATES];
    private int candCount;
    private int lookups;

    /** A jump the player made (takeoff and landing feet positions), kept as the "line of recent jumps". */
    public void noteJump(double takeoffX, double takeoffZ, double landX, double landZ) {
        double dx = landX - takeoffX;
        double dz = landZ - takeoffZ;
        if (dx * dx + dz * dz < 1.0) {
            return; // not a real jump
        }
        jumpDx[jumpHead] = dx;
        jumpDz[jumpHead] = dz;
        jumpHead = (jumpHead + 1) % JUMP_RING;
        if (jumpCount < JUMP_RING) {
            jumpCount++;
        }
    }

    /** Forgets the jump line (teleport, world change, long stop). */
    public void reset() {
        hasTarget = false;
        confidence = 0.0;
        prevValid = false;
        jumpCount = 0;
        jumpHead = 0;
        candCount = 0;
        lookups = 0;
    }

    /**
     * One estimate per tick, budgeted (a bounded number of candidate cells).
     *
     * @param userForward the user's forward axis (-1, 0, 1)
     * @param userStrafe  the user's strafe axis (+1 left, -1 right, 0)
     * @return true when a target with at least {@link #MIN_CONFIDENCE} was found
     */
    public boolean estimate(MovementPhysics now, float yaw, float pitch, int userForward, int userStrafe,
                            boolean sprinting, MovementPhysics.Voxels w) {
        hasTarget = false;
        confidence = 0.0;
        idealYaw = yaw;
        candCount = 0;
        lookups = 0;

        double speed = Math.sqrt(now.vx * now.vx + now.vz * now.vz);
        boolean keys = userForward != 0 || userStrafe != 0;
        if (!keys && speed < STILL_SPEED) {
            prevValid = false;
            return false;
        }

        double yawRad = Math.toRadians(yaw);
        double sinY = Math.sin(yawRad);
        double cosY = Math.cos(yawRad);
        double pitchRad = Math.toRadians(pitch);
        double cosP = Math.cos(pitchRad);
        // look direction (yaw 0 = +Z, 90 = -X, positive pitch looks down)
        double lx = -sinY * cosP;
        double ly = -Math.sin(pitchRad);
        double lz = cosY * cosP;

        // movement direction: held keys rotated by yaw; coasting uses velocity; else the look direction
        double mx;
        double mz;
        if (keys) {
            // forward = (-sin, cos); left = (cos, sin)
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

        // mean jump vector for the line term
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
        double predX = now.x + meanX;
        double predZ = now.z + meanZ;

        double px = now.x;
        double py = now.y;
        double pz = now.z;
        int fx = floor(px);
        int fz = floor(pz);
        int sy = floor(py + 1.0e-6) - 1; // block the player stands on
        double eyeY = py + EYE_HEIGHT;
        double feetY = floor(py + 1.0e-6);

        int yTop = sy + MAX_UP;
        int yBot = sy - MAX_DOWN;

        for (int c = 0; c < COL_DX.length && candCount < MAX_CANDIDATES && lookups < MAX_LOOKUPS; c++) {
            int bx = fx + COL_DX[c];
            int bz = fz + COL_DZ[c];
            double ex = bx + 0.5 - px;
            double ez = bz + 0.5 - pz;
            double hd = Math.sqrt(ex * ex + ez * ez);
            if (hd < MIN_RANGE || hd > MAX_RANGE) {
                continue;
            }
            if (ex * mx + ez * mz < CONE_COS * hd) {
                continue;
            }
            boolean above2 = w.solid(bx, yTop + 2, bz);
            boolean above1 = w.solid(bx, yTop + 1, bz);
            lookups += 2;
            for (int y = yTop; y >= yBot; y--) {
                boolean s = w.solid(bx, y, bz);
                lookups++;
                if (s && !above1 && !above2 && candCount < MAX_CANDIDATES
                        && jumpTarget(bx, bz, y == sy, px, pz, hd, sy, w)) {
                    int i = candCount++;
                    candX[i] = bx;
                    candY[i] = y;
                    candZ[i] = bz;
                    candScore[i] = score(bx, y, bz, ex, ez, hd, px, eyeY, pz, feetY, lx, ly, lz, mx, mz, keys, vux,
                            vuz, speed, predX, predZ, sprinting);
                }
                above2 = above1;
                above1 = s;
            }
        }

        if (candCount == 0) {
            prevValid = false;
            return false;
        }

        int best = 0;
        for (int i = 1; i < candCount; i++) {
            if (candScore[i] > candScore[best]) {
                best = i;
            }
        }
        if (prevValid) {
            for (int i = 0; i < candCount; i++) {
                if (candX[i] == tx && candY[i] == ty && candZ[i] == tz) {
                    if (candScore[i] + HYSTERESIS >= candScore[best]) {
                        best = i;
                    }
                    break;
                }
            }
        }
        int bx = candX[best];
        int by = candY[best];
        int bz = candZ[best];
        double bs = candScore[best];
        double second = 0.0;
        for (int i = 0; i < candCount; i++) {
            if (i == best) {
                continue;
            }
            // neighbours of the winner are the same landing area, not a rival
            if (Math.abs(candX[i] - bx) <= 1 && Math.abs(candZ[i] - bz) <= 1) {
                continue;
            }
            if (candScore[i] > second) {
                second = candScore[i];
            }
        }
        double margin = Math.max(0.0, bs - second);
        double conf = Math.min(1.0, margin / MARGIN_FULL) * bs;

        tx = bx;
        ty = by;
        tz = bz;
        prevValid = true;
        confidence = clamp01(conf);
        double dx = bx + 0.5 - px;
        double dz = bz + 0.5 - pz;
        idealYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        hasTarget = confidence >= MIN_CONFIDENCE;
        return hasTarget;
    }

    /** Excludes cells reachable by walking on the same flat floor (every sampled column along the line is solid). */
    private boolean jumpTarget(int bx, int bz, boolean sameLevel, double px, double pz, double hd, int sy,
                               MovementPhysics.Voxels w) {
        if (!sameLevel) {
            return true;
        }
        double dx = (bx + 0.5 - px) / hd;
        double dz = (bz + 0.5 - pz) / hd;
        for (double d = WALK_STEP; d < hd; d += WALK_STEP) {
            lookups++;
            if (!w.solid(floor(px + dx * d), sy, floor(pz + dz * d))) {
                return true;
            }
        }
        return false;
    }

    private double score(int bx, int by, int bz, double ex, double ez, double hd, double px, double eyeY, double pz,
                         double feetY, double lx, double ly, double lz, double mx, double mz, boolean keys,
                         double vux, double vuz, double speed, double predX, double predZ, boolean sprinting) {
        double topY = by + 1.0;
        // LOOK: ray through the block top face scores 1, otherwise angular distance to the face centre
        double lookHit = 0.0;
        if (ly < -1.0e-6) {
            double t = (topY - eyeY) / ly;
            if (t > 0.0) {
                double hx = px + lx * t;
                double hz = pz + lz * t;
                if (hx >= bx - LOOK_BLOCK_MARGIN && hx <= bx + 1.0 + LOOK_BLOCK_MARGIN
                        && hz >= bz - LOOK_BLOCK_MARGIN && hz <= bz + 1.0 + LOOK_BLOCK_MARGIN) {
                    lookHit = 1.0;
                }
            }
        }
        double dy = topY - eyeY;
        double dist = Math.sqrt(ex * ex + ez * ez + dy * dy);
        double dot = (ex * lx + dy * ly + ez * lz) / dist;
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        double look = clamp01(Math.max(lookHit, 1.0 - angle / LOOK_ZERO_DEG));

        // KEYS (neutral when none held)
        double keyTerm = 0.5;
        if (keys) {
            keyTerm = align(ex * mx + ez * mz, hd);
        }
        // VELOCITY (weighted by speed; neutral when slow)
        double velTerm = 0.5;
        if (speed > 1.0e-6) {
            double a = align(ex * vux + ez * vuz, hd);
            double wgt = Math.min(1.0, speed / 0.2);
            velTerm = 0.5 + (a - 0.5) * wgt;
        }
        // LINE: closeness to where the recent jump vectors would put the next landing
        double lineTerm = 0.5;
        if (jumpCount > 0) {
            double ddx = bx + 0.5 - predX;
            double ddz = bz + 0.5 - predZ;
            lineTerm = clamp01(1.0 - Math.sqrt(ddx * ddx + ddz * ddz) / LINE_ZERO_DIST);
        }
        // REACH
        double dyBlocks = topY - feetY;
        int ri = (int) Math.round(1.0 - dyBlocks); // up 1 -> 0, flat -> 1, down 4 -> 5
        ri = Math.max(0, Math.min(REACH_BY_DY.length - 1, ri));
        double reach = REACH_BY_DY[ri] + 0.4;
        if (!sprinting) {
            reach *= NO_SPRINT_REACH;
        }
        double reachTerm = hd <= reach ? 1.0 : clamp01(1.0 - (hd - reach) / REACH_SOFT);

        double sum = W_LOOK * look + W_KEYS * keyTerm + W_VELOCITY * velTerm + W_LINE * lineTerm + W_REACH * reachTerm;
        return sum * (0.5 + 0.5 * reachTerm);
    }

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

    public boolean hasTarget() {
        return hasTarget;
    }

    /** The block to stand on (the solid block; feet land at y + 1). */
    public int targetX() {
        return tx;
    }

    public int targetY() {
        return ty;
    }

    public int targetZ() {
        return tz;
    }

    /** 0..1: how clearly the best candidate beats the runner-up. */
    public double confidence() {
        return confidence;
    }

    /** Yaw from the player's position to the centre of the target block's top face. */
    public float idealYaw() {
        return idealYaw;
    }

    /** Candidates scored by the last estimate() call (diagnostics, tests). */
    public int lastCandidateCount() {
        return candCount;
    }

    /** Block lookups made by the last estimate() call (diagnostics, tests). */
    public int lastLookups() {
        return lookups;
    }
}
