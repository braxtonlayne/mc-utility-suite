package com.utilitysuite.travel.movement;

/**
 * Air brake (design 2026-11-22, section 6.1 steps 2, 4 and 5) for a committed jump onto a narrow top: the flight
 * arrives slow, so the touchdown speed leaves the stop point inside the top's {@link SupportRegion}.
 * <p>
 * Family of plans: keep the current keys until air tick {@code k}, then brake (release forward, or hold back when
 * allowed) with {@code k} in {n, n-2, n-4, n-6}, where {@code n} is the air ticks the current keys need to land. Each
 * candidate is verified with one roll in the replica: it must land on the region's top height inside the region, with
 * no wall contact on any tick and no cell the replica does not model. The candidate whose no-key stop point (the latch
 * and the sneak guard take every key away on the top) keeps the most margin wins. When none lands, the caller falls
 * back to {@link JumpSearch#steer}.
 * <p>
 * Engine-free, allocation free; at most {@link #MAX_SIM_TICKS} simulated ticks per {@link #plan} (tested).
 */
public final class FlightController {

    /** Commitment budget per client tick, on top of the brain's own {@link MovementAssistBrain#MAX_SIM_TICKS}. */
    public static final int MAX_SIM_TICKS = 1200;
    static final int HORIZON = JumpSearch.HORIZON_TICKS;
    static final int[] BRAKE_OFFSETS = {0, 2, 4, 6};
    private static final double TOP_EPS = 1.0e-3;

    private final MovementPhysics sim = new MovementPhysics();
    private int simTicks;
    private boolean found;
    private int brakeTick;
    private int brakeForward;
    private double bestMargin;
    private int airTicks;
    private boolean userLands;
    private double userMargin;

    /**
     * Plans the arrival.
     *
     * @param f         forward axis the flight holds now (the user's or the plan's)
     * @param s         strafe axis
     * @param allowBack the brake may hold back (CARRY; GUIDE only when the forward axis is free)
     * @param region    the locked target's support region (built at its true top)
     * @return true when some candidate lands on the region
     */
    public boolean plan(MovementPhysics now, int f, int s, float yaw, boolean sprint, boolean allowBack,
                        SupportRegion region, MovementPhysics.Voxels w) {
        simTicks = 0;
        found = false;
        brakeTick = -1;
        brakeForward = f;
        bestMargin = -Double.MAX_VALUE;
        userLands = false;
        userMargin = -SupportRegion.MARGIN_CAP;
        if (now.onGround || region.count() == 0) {
            return false;
        }
        // the current keys held to the landing: n and the reference
        crossAir = 0;
        double m = roll(now, f, s, yaw, sprint, HORIZON, f, region, w);
        airTicks = lastAir;
        if (m > -Double.MAX_VALUE) {
            userLands = true;
            userMargin = m;
            found = true;
            bestMargin = m;
            brakeTick = -1;
            brakeForward = f;
        }
        // Anchor the brake family on the tick the body comes down through the top's height. Air time to a height does
        // not depend on the horizontal keys, so this is the touchdown tick of every candidate that reaches the top,
        // including when the current keys overfly it (their own landing, far below, says nothing about this one).
        int n = crossAir > 0 ? crossAir : lastAir > 0 ? lastAir : HORIZON;
        for (int oi = 0; oi < BRAKE_OFFSETS.length; oi++) {
            int k = n - 1 - BRAKE_OFFSETS[oi]; // brake from air tick k (0 = this tick) to the touchdown
            if (k < 0) {
                break;
            }
            for (int b = 0; b <= 1; b++) {
                int bf = b == 0 ? 0 : -1;
                if (bf == f || (bf < 0 && !allowBack)) {
                    continue;
                }
                double cm = roll(now, f, s, yaw, sprint, k, bf, region, w);
                if (cm > bestMargin + 1.0e-9) {
                    bestMargin = cm;
                    brakeTick = k;
                    brakeForward = bf;
                    found = true;
                }
            }
        }
        return found;
    }

    private int lastAir;
    /** Air ticks until the reference roll first descends to the region's top height (0 = never within the horizon). */
    private int crossAir;

    /** Keys (f, s) for {@code keepTicks} ticks, then (bf, s); margin of the no-key stop point, or -MAX when it fails. */
    private double roll(MovementPhysics now, int f, int s, float yaw, boolean sprint, int keepTicks, int bf,
                        SupportRegion region, MovementPhysics.Voxels w) {
        sim.copyFrom(now);
        lastAir = 0;
        for (int t = 0; t < HORIZON; t++) {
            int ff = t < keepTicks ? f : bf;
            sim.tick(ff, s, yaw, sprint && ff > 0, false, w);
            simTicks++;
            if (crossAir == 0 && sim.vy <= 0.0 && sim.y <= region.top() + TOP_EPS) {
                crossAir = t + 1;
            }
            if (sim.horizontalCollision || unmodelled(sim, w)) {
                return -Double.MAX_VALUE;
            }
            if (sim.onGround) {
                lastAir = t + 1;
                if (Math.abs(sim.y - region.top()) > TOP_EPS || region.margin(sim.x, sim.z) < 0.0) {
                    return -Double.MAX_VALUE;
                }
                int c = MovementPhysics.floor(sim.y - 0.01);
                if (w.surface(MovementPhysics.floor(sim.x), c, MovementPhysics.floor(sim.z))
                        == MovementPhysics.SURFACE_HARM) {
                    return -Double.MAX_VALUE;
                }
                region.computeStop(sim.x, sim.z, sim.vx, sim.vz, 0, 0, yaw, false, sim.speed, sim.slipperiness,
                        true);
                return region.margin(region.stopX(), region.stopZ());
            }
        }
        return -Double.MAX_VALUE;
    }

    /** The body's cell or the one under it is one the replica does not model (fail-safe rule, design 7.2). */
    static boolean unmodelled(MovementPhysics b, MovementPhysics.Voxels w) {
        int x = MovementPhysics.floor(b.x);
        int y = MovementPhysics.floor(b.y);
        int z = MovementPhysics.floor(b.z);
        byte s0 = w.surface(x, y, z);
        byte s1 = w.surface(x, y - 1, z);
        return s0 == MovementPhysics.SURFACE_UNMODELLED || s0 == MovementPhysics.SURFACE_STUCK
                || s1 == MovementPhysics.SURFACE_UNMODELLED || s1 == MovementPhysics.SURFACE_STUCK;
    }

    /** Some candidate lands on the region. */
    public boolean found() {
        return found;
    }

    /** Brake this tick: the plan's air tick for the brake has come. */
    public boolean brakeNow() {
        return found && brakeTick == 0;
    }

    /** Forward axis of the brake (0 release, -1 back); the flight's own forward when no brake is planned. */
    public int brakeForward() {
        return brakeForward;
    }

    /** Air tick the brake starts on (0 = now), or -1 when the current keys are the best plan. */
    public int brakeTick() {
        return brakeTick;
    }

    /** Margin of the chosen plan's stop point. */
    public double bestMargin() {
        return bestMargin;
    }

    /** The current keys already land on the region (minimum intervention reference). */
    public boolean userLands() {
        return userLands;
    }

    public double userMargin() {
        return userMargin;
    }

    /** Air ticks to the touchdown with the current keys (0 when they land nowhere). */
    public int airTicks() {
        return airTicks;
    }

    /** Simulated ticks of the last plan. */
    public int simTicks() {
        return simTicks;
    }
}
