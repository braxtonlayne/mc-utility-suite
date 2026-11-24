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
 * <p>
 * With an intent target ({@link #setTarget}) the progress term is replaced by how close the landing gets to the target
 * block's top: {@code -TARGET_MISS_WEIGHT * miss - centre distance / 4}, where {@code miss} is 0 when the player's
 * footprint stands on the target block ({@link #missDistance}). Without a target nothing changes.
 */
public final class JumpSearch {

    public static final int HORIZON_TICKS = 60;
    public static final double FALL_WEIGHT = 4.0;
    public static final double NO_LANDING = -1.0e9;
    /** Score per block of target miss (a landing beside the target loses this much per block). */
    public static final double TARGET_MISS_WEIGHT = 3.0;
    /** Miss reported when nothing lands at all. */
    public static final double MISS_NO_LANDING = 4.0;
    /** Walk ticks an edge run ({@link #edgeRun}) may take to reach the edge before it gives up. */
    public static final int EDGE_WALK_MAX = 20;

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

    // optional intent target: the solid block to stand on
    private boolean hasTarget;
    private int tx, ty, tz;
    /** The target's top: feet height and extent (the cube's unless {@link #setTargetTop} gave the real one). */
    private double topY, topX0, topZ0, topX1, topZ1;
    /** Simulated ticks since {@link #resetTickCount()} (the per-decision bound is tested on this). */
    private int simTicks;
    private final MovementPhysics walker = new MovementPhysics();
    private final MovementPhysics probe = new MovementPhysics();

    /** Aim landings at the top of this solid block instead of progress along the heading. */
    public void setTarget(int x, int y, int z) {
        hasTarget = true;
        tx = x;
        ty = y;
        tz = z;
        topY = y + 1;
        topX0 = x;
        topZ0 = z;
        topX1 = x + 1;
        topZ1 = z + 1;
    }

    /**
     * The target's real top from its collision boxes (a fence post: 1.5 high, 0.25 wide): feet height and the top
     * face's horizontal extent in world coordinates. Call after {@link #setTarget}; without it the cube top stands.
     */
    public void setTargetTop(double top, double x0, double z0, double x1, double z1) {
        topY = top;
        topX0 = x0;
        topZ0 = z0;
        topX1 = x1;
        topZ1 = z1;
    }

    /** Feet height on the target's top. */
    public double targetTop() {
        return topY;
    }

    /** Horizontal extent of the target's top face, world coordinates (the cube's unless {@link #setTargetTop}). */
    public double targetTopMinX() {
        return topX0;
    }

    public double targetTopMinZ() {
        return topZ0;
    }

    public double targetTopMaxX() {
        return topX1;
    }

    public double targetTopMaxZ() {
        return topZ1;
    }

    public void clearTarget() {
        hasTarget = false;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public int targetX() {
        return tx;
    }

    public int targetY() {
        return ty;
    }

    public int targetZ() {
        return tz;
    }

    /**
     * Horizontal distance from a feet position to the nearest position whose footprint still stands on the target
     * block, plus the height difference to the target top. 0 = standing on it. Without a target: 0.
     */
    public double missDistance(double x, double y, double z) {
        if (!hasTarget) {
            return 0.0;
        }
        double lo = MovementPhysics.HALF_WIDTH - 1.0e-3;
        double dx = Math.max(0.0, Math.max((topX0 - lo) - x, x - (topX1 + lo)));
        double dz = Math.max(0.0, Math.max((topZ0 - lo) - z, z - (topZ1 + lo)));
        double dy = Math.abs(y - topY);
        return Math.sqrt(dx * dx + dz * dz) + (dy < 1.0e-3 ? 0.0 : dy);
    }

    /** Miss of the last stored landing ({@link #MISS_NO_LANDING} when it landed nowhere; 0 without a target). */
    public double lastMiss() {
        if (!hasTarget) {
            return 0.0;
        }
        return score <= NO_LANDING ? MISS_NO_LANDING : missDistance(landX, landY, landZ);
    }

    /** Target-aware landing score of a feet position (no damage term). */
    private double targetScore(double x, double y, double z) {
        double cx = x - (tx + 0.5);
        double cz = z - (tz + 0.5);
        return -TARGET_MISS_WEIGHT * missDistance(x, y, z) - 0.25 * Math.sqrt(cx * cx + cz * cz);
    }

    public void resetTickCount() {
        simTicks = 0;
    }

    /** Counts physics ticks a helper simulated on its own bodies (takeoff shaping) toward the per-decision bound. */
    public void addTicks(int n) {
        simTicks += n;
    }

    /** Physics ticks simulated since {@link #resetTickCount()}. */
    public int tickCount() {
        return simTicks;
    }

    /**
     * Runs forward at {@code yawDeg} to the last supported tick (at most {@link #EDGE_WALK_MAX} walk ticks) and jumps
     * there, like the edge jump would; scores the landing (target-aware). Airborne starts hold the given keys instead.
     * Used for the ideal takeoff yaw: "if I faced this way, would the jump from the edge land?".
     *
     * @return the landing score, {@link #NO_LANDING} when it lands nowhere or no edge is within reach
     */
    public double edgeRun(MovementPhysics now, int f, int s, float yawDeg, boolean sprint, float health,
                          MovementPhysics.Voxels w) {
        if (!now.onGround) {
            return evaluate(now, f, s, yawDeg, sprint, false, health, w);
        }
        walker.copyFrom(now);
        for (int t = 0; t < EDGE_WALK_MAX; t++) {
            probe.copyFrom(walker).tick(1, 0, yawDeg, sprint, false, w);
            simTicks++;
            if (!probe.supported(w)) {
                return evaluate(walker, 1, 0, yawDeg, sprint, true, health, w);
            }
            walker.copyFrom(probe);
        }
        store(1, 0, NO_LANDING, walker.x, walker.y, walker.z, 0f, 0);
        return NO_LANDING;
    }

    /**
     * Ticks until the last supported tick when walking on with these keys: 0 = this is the last tick before walking
     * off, k = k more supported ticks first, -1 = no edge within {@code maxTicks + 1} ticks (or airborne, or no keys).
     */
    public int ticksToEdge(MovementPhysics now, int f, int s, float yawDeg, boolean sprint, int maxTicks,
                           MovementPhysics.Voxels w) {
        if (!now.onGround || (f == 0 && s == 0)) {
            return -1;
        }
        walker.copyFrom(now);
        for (int t = 0; t <= maxTicks; t++) {
            walker.tick(f, s, yawDeg, sprint, false, w);
            simTicks++;
            if (!walker.supported(w)) {
                return t;
            }
        }
        return -1;
    }

    /**
     * Jumps on the first tick holding forward at {@code yawDeg}, scored against a fixed reference heading
     * {@code refYawDeg} so takeoffs at different yaws compare on one yardstick. Target-aware like the rest.
     */
    public double jumpScore(MovementPhysics now, float yawDeg, float refYawDeg, boolean sprint, float health,
                            MovementPhysics.Voxels w) {
        double hx = -Math.sin(Math.toRadians(refYawDeg));
        double hz = Math.cos(Math.toRadians(refYawDeg));
        double sc = simulate(now, 1, 0, yawDeg, sprint, true, health, hx, hz, w);
        store(1, 0, sc, sim.x, sim.y, sim.z, sc <= NO_LANDING ? 0f : MovementPhysics.fallDamage(sim.fallDistance),
                lastTicks);
        return sc;
    }

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
        simTicks++;
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
            simTicks++;
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
                if (hasTarget) {
                    return targetScore(sim.x, sim.y, sim.z) - FALL_WEIGHT * dmg;
                }
                double progress = (sim.x - ox) * hx + (sim.z - oz) * hz;
                return progress - FALL_WEIGHT * dmg;
            } else if (t > 3) {
                // never left the ground (blocked or walking on): progress only, no landing bonus
                lastTicks = t;
                if (hasTarget) {
                    return targetScore(sim.x, sim.y, sim.z) - 0.5;
                }
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
