package com.utilitysuite.travel.movement;

/**
 * Runner's Vision (roadmap M3), the planning half: while sprinting, the fastest no-stop line ahead along a heading,
 * found with the physics replica: sprint forward, and wherever running on would stop the run (an edge into a fall that
 * hurts or never lands, a wall or a step too high to walk up) take the sprint jump if it lands without damage; where
 * neither works the line ends there. Visual only: it never drives input.
 * <p>
 * The search is budgeted: {@link #begin} starts one, {@link #step} advances it by at most a budget of simulated ticks
 * (plus at most one jump/fall evaluation of {@link #EVAL_TICKS}), and when it completes the line is published to the
 * read side ({@link #count()}, {@link #x(int)}, ...) where it stays until the next search completes. Engine free,
 * allocation free after construction, deterministic.
 */
public final class RunnersVision {

    /** Horizontal reach of the line (blocks from the start). */
    public static final double RANGE = 24.0;
    /** Most points on a line (one per simulated tick). */
    public static final int MAX_POINTS = 160;
    /** Default simulated ticks per {@link #step}. */
    public static final int DEFAULT_BUDGET = 240;
    /** Ticks a jump or a fall is followed before it counts as never landing. */
    public static final int EVAL_TICKS = 50;
    /** Ticks walked into a wall before the line ends there. */
    static final int STUCK_TICKS = 2;

    /** How the line ends. */
    public static final int END_RANGE = 0, END_STOP = 1;

    private final MovementPhysics body = new MovementPhysics();
    private final MovementPhysics probe = new MovementPhysics();
    private final MovementPhysics jumper = new MovementPhysics();

    // work side
    private final float[] wx = new float[MAX_POINTS];
    private final float[] wy = new float[MAX_POINTS];
    private final float[] wz = new float[MAX_POINTS];
    private final boolean[] wAir = new boolean[MAX_POINTS];
    private int wn;
    private int wJumps;
    private float yaw;
    private float health;
    private double ox, oz;
    private boolean running;
    private int stuck;
    private int ticksDone;

    // published side
    private final float[] px = new float[MAX_POINTS];
    private final float[] py = new float[MAX_POINTS];
    private final float[] pz = new float[MAX_POINTS];
    private final boolean[] pAir = new boolean[MAX_POINTS];
    private int pn;
    private int pJumps;
    private int pEnd;
    private int lastStepTicks;

    /** Starts a new search from {@code start} (copied) heading along {@code yawDeg}; the published line stays. */
    public void begin(MovementPhysics start, float yawDeg, float health) {
        body.copyFrom(start);
        yaw = yawDeg;
        this.health = health;
        ox = start.x;
        oz = start.z;
        wn = 0;
        wJumps = 0;
        stuck = 0;
        ticksDone = 0;
        running = true;
        add(body, !body.onGround);
    }

    public boolean searching() {
        return running;
    }

    /**
     * Advances the search by about {@code budget} simulated ticks.
     *
     * @return true when this call completed the search (the new line is published)
     */
    public boolean step(int budget, MovementPhysics.Voxels w) {
        if (!running) {
            return false;
        }
        int used = 0;
        while (used < budget) {
            if (wn >= MAX_POINTS || ticksDone > MAX_POINTS * 2) {
                return finish(END_RANGE);
            }
            double dx = body.x - ox;
            double dz = body.z - oz;
            if (dx * dx + dz * dz >= RANGE * RANGE) {
                return finish(END_RANGE);
            }
            if (body.onGround) {
                probe.copyFrom(body).tick(1, 0, yaw, true, false, w);
                used++;
                boolean edge = !probe.supported(w);
                boolean blocked = probe.horizontalCollision && horizontalGain(probe) < 0.05;
                if (edge || blocked) {
                    // a sprint jump from here: take it when it lands without damage
                    int jt = land(body, true, w);
                    used += Math.abs(jt);
                    if (jt > 0 && progress(jumper) > progress(body) + 0.5) {
                        commitJump(w, jt);
                        used += jt;
                        stuck = 0;
                        continue;
                    }
                    if (edge) {
                        int ft = land(body, false, w); // walking off: only a harmless drop keeps the run going
                        used += Math.abs(ft);
                        if (ft <= 0) {
                            return finish(END_STOP);
                        }
                    } else if (++stuck > STUCK_TICKS) {
                        return finish(END_STOP);
                    }
                }
            }
            body.tick(1, 0, yaw, true, false, w);
            used++;
            ticksDone++;
            add(body, !body.onGround);
            if (!body.onGround && body.y < -128) {
                return finish(END_STOP);
            }
        }
        lastStepTicks = used;
        return false;
    }

    /** Re-runs the accepted jump from {@link #body}, adding every airborne tick, and leaves the body on the landing. */
    private void commitJump(MovementPhysics.Voxels w, int ticks) {
        for (int t = 1; t <= ticks && wn < MAX_POINTS; t++) {
            body.tick(1, 0, yaw, true, t == 1, w);
            ticksDone++;
            add(body, !body.onGround);
        }
        wJumps++;
    }

    /**
     * Follows a sprint (jumping first or not) from {@code from} until it lands: ticks to the landing when it lands
     * within {@link #EVAL_TICKS} without damage (the end state in {@link #jumper}), else minus the ticks spent.
     */
    private int land(MovementPhysics from, boolean jump, MovementPhysics.Voxels w) {
        jumper.copyFrom(from);
        boolean airborne = false;
        double fall = 0.0;
        for (int t = 1; t <= EVAL_TICKS; t++) {
            double y0 = jumper.y;
            jumper.tick(1, 0, yaw, true, jump && t == 1, w);
            fall += Math.max(0.0, y0 - jumper.y);
            if (!jumper.onGround) {
                airborne = true;
                if (jumper.vy > 0.0) {
                    fall = 0.0; // still rising: the fall counts from the apex
                }
            } else if (airborne) {
                float dmg = MovementPhysics.fallDamage(fall);
                return dmg > 0f || dmg >= health ? -t : t;
            } else if (t > 2) {
                return -t; // never left the ground (blocked)
            }
        }
        return -EVAL_TICKS;
    }

    private double progress(MovementPhysics m) {
        double hx = -Math.sin(Math.toRadians(yaw));
        double hz = Math.cos(Math.toRadians(yaw));
        return (m.x - ox) * hx + (m.z - oz) * hz;
    }

    private double horizontalGain(MovementPhysics after) {
        double dx = after.x - body.x;
        double dz = after.z - body.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void add(MovementPhysics m, boolean air) {
        if (wn >= MAX_POINTS) {
            return;
        }
        wx[wn] = (float) m.x;
        wy[wn] = (float) m.y;
        wz[wn] = (float) m.z;
        wAir[wn] = air;
        wn++;
    }

    private boolean finish(int end) {
        System.arraycopy(wx, 0, px, 0, wn);
        System.arraycopy(wy, 0, py, 0, wn);
        System.arraycopy(wz, 0, pz, 0, wn);
        System.arraycopy(wAir, 0, pAir, 0, wn);
        pn = wn;
        pJumps = wJumps;
        pEnd = end;
        running = false;
        return true;
    }

    /** Drops the published line and any search. */
    public void clear() {
        pn = 0;
        pJumps = 0;
        running = false;
    }

    // ---- published line -----------------------------------------------------------------------------------------------

    public int count() {
        return pn;
    }

    public float x(int i) {
        return px[i];
    }

    public float y(int i) {
        return py[i];
    }

    public float z(int i) {
        return pz[i];
    }

    /** The point is in the air (part of a jump arc or a drop). */
    public boolean air(int i) {
        return pAir[i];
    }

    public int jumps() {
        return pJumps;
    }

    /** {@link #END_RANGE} (clear to the range) or {@link #END_STOP} (the run must stop there). */
    public int end() {
        return pEnd;
    }

    /** Index of the published point nearest to (x, z), searching forward from {@code from}. */
    public int nearest(double x, double z, int from) {
        int best = Math.max(0, Math.min(from, pn - 1));
        double bd = Double.MAX_VALUE;
        for (int i = best; i < pn; i++) {
            double dx = px[i] - x;
            double dz = pz[i] - z;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    int lastStepTicks() {
        return lastStepTicks;
    }
}
