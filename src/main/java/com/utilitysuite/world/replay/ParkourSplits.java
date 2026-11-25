package com.utilitysuite.world.replay;

/**
 * Parkour split timing between two markers (start, finish), and the ghost of a saved run (F2 "save as ghost").
 * <ul>
 *   <li><b>Live clock</b> ({@link #tick}): IDLE until the player stands within the start radius (ARMED); leaving it
 *       starts the run; reaching the finish radius ends it. A finished run faster than the personal best becomes the
 *       new best (and its track the ghost when the ghost came from it).</li>
 *   <li><b>Progress</b> is the projection of the position onto the start-to-finish line, 0..1. The split delta at a
 *       moment is the live elapsed time minus the ghost's time to the same progress, so it is meaningful mid-run.</li>
 *   <li><b>Ghost</b>: a track of positions, one per tick from the start exit to the finish, kept in preallocated
 *       arrays of {@link #MAX_TICKS}.</li>
 * </ul>
 * Allocation free after construction. Engine free.
 */
public final class ParkourSplits {

    public static final int MAX_TICKS = ReplayRing.FRAMES;

    public enum State { IDLE, ARMED, RUNNING, FINISHED }

    private double sx;
    private double sy;
    private double sz;
    private double fx;
    private double fy;
    private double fz;
    private boolean hasStart;
    private boolean hasFinish;
    private double radius = 1.5;

    private State state = State.IDLE;
    private int elapsed;
    private int lastRunTicks = -1;
    private int bestTicks = -1;

    private final double[] gx = new double[MAX_TICKS];
    private final double[] gy = new double[MAX_TICKS];
    private final double[] gz = new double[MAX_TICKS];
    private final float[] gProgress = new float[MAX_TICKS];
    private int ghostLen;

    // live track (so a new best can become the ghost)
    private final double[] lx = new double[MAX_TICKS];
    private final double[] ly = new double[MAX_TICKS];
    private final double[] lz = new double[MAX_TICKS];
    private int liveLen;
    private boolean autoGhost = true;
    private boolean newBest;

    public void setStart(double x, double y, double z) {
        sx = x;
        sy = y;
        sz = z;
        hasStart = true;
        resetClock();
    }

    public void setFinish(double x, double y, double z) {
        fx = x;
        fy = y;
        fz = z;
        hasFinish = true;
        resetClock();
    }

    public void setRadius(double r) {
        radius = Math.max(0.5, r);
    }

    public void setAutoGhost(boolean on) {
        autoGhost = on;
    }

    public boolean ready() {
        return hasStart && hasFinish;
    }

    public double startX() {
        return sx;
    }

    public double startY() {
        return sy;
    }

    public double startZ() {
        return sz;
    }

    public double finishX() {
        return fx;
    }

    public double finishY() {
        return fy;
    }

    public double finishZ() {
        return fz;
    }

    public State state() {
        return state;
    }

    public int elapsed() {
        return elapsed;
    }

    public int lastRunTicks() {
        return lastRunTicks;
    }

    public int bestTicks() {
        return bestTicks;
    }

    public void setBestTicks(int t) {
        bestTicks = t;
    }

    public void resetClock() {
        state = State.IDLE;
        elapsed = 0;
        liveLen = 0;
    }

    private boolean near(double x, double y, double z, double cx, double cy, double cz) {
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= 2.0;
    }

    /** One client tick of the player's feet position. Returns true on the tick a run finishes. */
    public boolean tick(double x, double y, double z) {
        if (!ready()) {
            return false;
        }
        boolean inStart = near(x, y, z, sx, sy, sz);
        switch (state) {
            case IDLE, FINISHED -> {
                if (inStart) {
                    state = State.ARMED;
                }
            }
            case ARMED -> {
                if (!inStart) {
                    state = State.RUNNING;
                    elapsed = 0;
                    liveLen = 0;
                    pushLive(x, y, z);
                }
            }
            case RUNNING -> {
                if (inStart) {
                    state = State.ARMED; // went back: re-arm
                    return false;
                }
                elapsed++;
                pushLive(x, y, z);
                if (near(x, y, z, fx, fy, fz)) {
                    state = State.FINISHED;
                    lastRunTicks = elapsed;
                    if (bestTicks < 0 || elapsed < bestTicks) {
                        bestTicks = elapsed;
                        newBest = true;
                        if (autoGhost) {
                            setGhost(lx, ly, lz, liveLen);
                        }
                    }
                    return true;
                }
                if (elapsed >= MAX_TICKS - 1) {
                    state = State.IDLE; // too long to keep: abandon
                }
            }
        }
        return false;
    }

    private void pushLive(double x, double y, double z) {
        if (liveLen < MAX_TICKS) {
            lx[liveLen] = x;
            ly[liveLen] = y;
            lz[liveLen] = z;
            liveLen++;
        }
    }

    /** Progress 0..1 of a point along the start-to-finish line. */
    public double progress(double x, double y, double z) {
        double dx = fx - sx;
        double dy = fy - sy;
        double dz = fz - sz;
        double len2 = dx * dx + dy * dy + dz * dz;
        if (len2 < 1e-9) {
            return 0;
        }
        double p = ((x - sx) * dx + (y - sy) * dy + (z - sz) * dz) / len2;
        return Math.max(0, Math.min(1, p));
    }

    /** Sets the ghost from a track (copied); progress is made monotonic so lookups are well defined. */
    public void setGhost(double[] xs, double[] ys, double[] zs, int n) {
        int m = Math.min(n, MAX_TICKS);
        float best = 0f;
        for (int i = 0; i < m; i++) {
            gx[i] = xs[i];
            gy[i] = ys[i];
            gz[i] = zs[i];
            best = Math.max(best, (float) progress(xs[i], ys[i], zs[i]));
            gProgress[i] = best;
        }
        ghostLen = m;
    }

    public int ghostLength() {
        return ghostLen;
    }

    /** Copies the ghost track (absolute positions) into the arrays; returns the samples copied. */
    public int copyGhost(double[] xs, double[] ys, double[] zs) {
        int n = Math.min(ghostLen, xs.length);
        System.arraycopy(gx, 0, xs, 0, n);
        System.arraycopy(gy, 0, ys, 0, n);
        System.arraycopy(gz, 0, zs, 0, n);
        return n;
    }

    /** Drops the ghost and the personal best (the markers stay). */
    public void clearGhost() {
        ghostLen = 0;
        bestTicks = -1;
        lastRunTicks = -1;
        newBest = false;
    }

    /** True once after a finished run set a new personal best (so it can be saved). */
    public boolean consumeNewBest() {
        boolean b = newBest;
        newBest = false;
        return b;
    }

    /** Ghost position at {@code tick} (clamped) into out[0..2]; false with no ghost. */
    public boolean ghostAt(double tick, double[] out) {
        if (ghostLen == 0) {
            return false;
        }
        double t = Math.max(0, Math.min(ghostLen - 1, tick));
        int a = (int) Math.floor(t);
        int b = Math.min(ghostLen - 1, a + 1);
        double f = t - a;
        out[0] = gx[a] + (gx[b] - gx[a]) * f;
        out[1] = gy[a] + (gy[b] - gy[a]) * f;
        out[2] = gz[a] + (gz[b] - gz[a]) * f;
        return true;
    }

    /** Ghost ticks to reach {@code p} (fractional, interpolated), or -1 if it never did. */
    public double ghostTicksTo(double p) {
        for (int i = 0; i < ghostLen; i++) {
            if (gProgress[i] >= p) {
                if (i == 0) {
                    return 0;
                }
                float p0 = gProgress[i - 1];
                float p1 = gProgress[i];
                double f = p1 > p0 ? (p - p0) / (p1 - p0) : 1.0;
                return i - 1 + f;
            }
        }
        return -1;
    }

    /**
     * Split delta in ticks for the live run at its current position: elapsed minus the ghost's time to the same
     * progress (negative = ahead). {@code Double.NaN} when not running or no ghost.
     */
    public double liveDelta(double x, double y, double z) {
        if (state != State.RUNNING || ghostLen == 0) {
            return Double.NaN;
        }
        double g = ghostTicksTo(progress(x, y, z));
        return g < 0 ? Double.NaN : elapsed - g;
    }

    /** Final delta of the last finished run vs the best before it was set is not kept; vs the current best. */
    public int lastDeltaVsBest() {
        return lastRunTicks < 0 || bestTicks < 0 ? 0 : lastRunTicks - bestTicks;
    }
}
