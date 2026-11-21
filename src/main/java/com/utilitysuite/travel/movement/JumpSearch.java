package com.utilitysuite.travel.movement;

/**
 * Searches short futures of a {@link MovementPhysics} player to pick the best keys. Allocation free (two reused
 * simulators), deterministic, engine-free; the movement assist and any automated driver (combat, a hunter agent) use
 * the same calls.
 * <ul>
 *   <li>{@link #steer}: in the air, which forward/strafe keys (held for the rest of the flight) give the best landing.</li>
 *   <li>{@link #shouldEdgeJump}: on the ground at an edge, is jumping now better than walking off.</li>
 * </ul>
 * A landing scores {@code progress along the heading - FALL_WEIGHT * fall damage}; never landing within the horizon
 * (the void, a very deep drop) scores {@link #NO_LANDING}. Damage at or above the player's health is treated as no
 * landing at all.
 */
public final class JumpSearch {

    public static final int HORIZON_TICKS = 60;
    public static final double FALL_WEIGHT = 4.0;
    public static final double NO_LANDING = -1.0e9;

    /** The nine forward/strafe combinations, forward first. */
    private static final int[] FWD = {1, 1, 1, 0, 0, 0, -1, -1, -1};
    private static final int[] STR = {0, 1, -1, 0, 1, -1, 0, 1, -1};

    private final MovementPhysics sim = new MovementPhysics();

    // result of the last search
    private int forward;
    private int strafe;
    private double score;
    private double landX, landY, landZ;
    private float landDamage;
    private int landTicks;
    private double lastWalk = NO_LANDING;
    private double lastJump = NO_LANDING;

    /**
     * Mid-air steering.
     *
     * @param now    the current (airborne) state; not modified
     * @param yawDeg the camera yaw (kept; only keys change, so it looks like a player's air strafing)
     * @param health current health: landings that would kill count as no landing
     * @return true when some combination lands; the keys are in {@link #forward()} / {@link #strafe()}
     */
    public boolean steer(MovementPhysics now, float yawDeg, boolean sprint, float health, MovementPhysics.Voxels w) {
        double hx = -Math.sin(Math.toRadians(yawDeg));
        double hz = Math.cos(Math.toRadians(yawDeg));
        double best = NO_LANDING;
        int bf = 1;
        int bs = 0;
        double bx = 0, by = 0, bz = 0;
        float bd = 0;
        int bt = 0;
        for (int i = 0; i < FWD.length; i++) {
            double sc = simulate(now, FWD[i], STR[i], yawDeg, sprint, false, health, hx, hz, w);
            if (sc > best + 1.0e-9) {
                best = sc;
                bf = FWD[i];
                bs = STR[i];
                bx = sim.x;
                by = sim.y;
                bz = sim.z;
                bd = MovementPhysics.fallDamage(sim.fallDistance);
                bt = lastTicks;
            }
        }
        store(bf, bs, best, bx, by, bz, bd, bt);
        return best > NO_LANDING;
    }

    /**
     * Edge decision while running: compares jumping this tick against stepping off without a jump (both holding
     * forward, sprinting as given).
     *
     * @return true when jumping now lands strictly better
     */
    public boolean shouldEdgeJump(MovementPhysics now, float yawDeg, boolean sprint, float health,
                                  MovementPhysics.Voxels w) {
        lastWalk = Double.NaN;
        lastJump = Double.NaN;
        if (!now.onGround) {
            return false;
        }
        // only at an edge: walking on for one more tick must leave the ground. On the ground vy is about -0.078, so the
        // Y-first collision keeps onGround true for the step-off tick itself: check what is under the feet after it.
        sim.copyFrom(now).tick(1, 0, yawDeg, sprint, false, w);
        if (sim.supported(w)) {
            return false;
        }
        double hx = -Math.sin(Math.toRadians(yawDeg));
        double hz = Math.cos(Math.toRadians(yawDeg));
        double walk = simulate(now, 1, 0, yawDeg, sprint, false, health, hx, hz, w);
        double jump = simulate(now, 1, 0, yawDeg, sprint, true, health, hx, hz, w);
        lastWalk = walk;
        lastJump = jump;
        store(1, 0, jump, sim.x, sim.y, sim.z, MovementPhysics.fallDamage(sim.fallDistance), lastTicks);
        return jump > walk + 0.05;
    }

    /**
     * Scores holding the given keys (optionally jumping on the first tick) until landing; the landing is in
     * {@link #landX()} etc. Used to judge the user's own keys and to draw the predicted landing.
     */
    public double evaluate(MovementPhysics now, int f, int s, float yawDeg, boolean sprint, boolean jumpFirst,
                           float health, MovementPhysics.Voxels w) {
        double hx = -Math.sin(Math.toRadians(yawDeg));
        double hz = Math.cos(Math.toRadians(yawDeg));
        double sc = simulate(now, f, s, yawDeg, sprint, jumpFirst, health, hx, hz, w);
        store(f, s, sc, sim.x, sim.y, sim.z, sc <= NO_LANDING ? 0f : MovementPhysics.fallDamage(sim.fallDistance),
                lastTicks);
        return sc;
    }

    private int lastTicks;

    /**
     * Holds the keys until landing (after having been airborne) or the horizon. With {@code jumpFirst} the jump key is
     * pressed on the first tick only. Leaves the end state in {@link #sim}.
     */
    private double simulate(MovementPhysics start, int f, int s, float yaw, boolean sprint, boolean jumpFirst,
                            float health, double hx, double hz, MovementPhysics.Voxels w) {
        sim.copyFrom(start);
        double ox = start.x;
        double oz = start.z;
        boolean wasAirborne = !start.onGround;
        double maxFall = 0;
        for (int t = 1; t <= HORIZON_TICKS; t++) {
            double fallBefore = sim.fallDistance;
            sim.tick(f, s, yaw, sprint, jumpFirst && t == 1, w);
            maxFall = Math.max(maxFall, fallBefore);
            if (!sim.onGround) {
                wasAirborne = true;
            } else if (wasAirborne) {
                lastTicks = t;
                // fallDistance resets on landing: use the distance accumulated just before touching down
                float dmg = MovementPhysics.fallDamage(Math.max(maxFall, fallBefore + Math.max(0, -sim.vy)));
                sim.fallDistance = Math.max(maxFall, fallBefore);
                if (dmg >= health) {
                    return NO_LANDING;
                }
                double progress = (sim.x - ox) * hx + (sim.z - oz) * hz;
                return progress - FALL_WEIGHT * dmg;
            } else if (t > 3) {
                // never left the ground (blocked or walking on): progress only, no landing bonus
                lastTicks = t;
                return (sim.x - ox) * hx + (sim.z - oz) * hz - 0.5;
            }
        }
        lastTicks = HORIZON_TICKS;
        return NO_LANDING;
    }

    private void store(int f, int s, double sc, double x, double y, double z, float dmg, int ticks) {
        forward = f;
        strafe = s;
        score = sc;
        landX = x;
        landY = y;
        landZ = z;
        landDamage = dmg;
        landTicks = ticks;
    }

    public int forward() {
        return forward;
    }

    public int strafe() {
        return strafe;
    }

    public double score() {
        return score;
    }

    public double landX() {
        return landX;
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return landZ;
    }

    public float landDamage() {
        return landDamage;
    }

    public int landTicks() {
        return landTicks;
    }

    /** Scores of the last edge check (diagnostics): walking off vs jumping now. */
    public double lastWalkScore() {
        return lastWalk;
    }

    public double lastJumpScore() {
        return lastJump;
    }
}
