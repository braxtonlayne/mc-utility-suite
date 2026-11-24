package com.utilitysuite.travel.movement;

/**
 * Ladder / vine catch: a jump whose goal is a climbable face, not a top to land on (course J49, J50, J85).
 * <p>
 * The landing planners only know tops, so in front of a ladder tower they used to pick a lower block (or nothing) and
 * the block assist bridged the gap. This check runs first: when a climbable cell lies ahead along the heading and some
 * jump from the next few ground ticks puts the feet in a climbable cell (vanilla {@code onClimbable} reads the block at
 * the feet position), the assist leaves the jump to the catch: no landing target, no placement, the user's early press
 * held back until the jump catches, and in Takeover the press made on the last ground tick that still catches.
 * <p>
 * Engine free and allocation free: two scratch bodies, a ray of cell lookups, one walk of up to
 * {@code MAX_WAIT} ground ticks plus flights only from takeoffs within {@link #FLIGHT_REACH} of the face, and only while a climbable is ahead.
 */
public final class ClimbCatch {
    /** How far ahead (blocks, horizontal) a climbable face is looked for. */
    static final double SCAN_RANGE = 12.0;
    private static final double SCAN_FROM = 0.3;
    private static final double SCAN_STEP = 0.25;
    /** Ground ticks walked ahead looking for a catching takeoff (12 blocks at sprint speed). */
    static final int MAX_WAIT = 48;
    /** A takeoff further than this (blocks) from the face cannot catch it: no flight is simulated. */
    static final double FLIGHT_REACH = 5.0;
    /** Flight ticks simulated per takeoff. */
    static final int SIM_TICKS = 30;
    private static final double MAX_DROP = 3.0;
    /** Ground ticks before its first catching takeoff the catch starts to own the jump (an early press is held). */
    static final int HOLD_WINDOW = 6;
    /** The takeoff comes this many ticks before the last catching one. */
    static final int LATE_SLACK = 1;
    /** Replica ticks one decision may spend. */
    static final int MAX_SIM_TICKS = 600;
    /** A body already in the air looks this far (blocks, horizontal) for a face to steer to. */
    static final double AIR_RANGE = 5.0;
    private static final double AIR_STEP = 0.5;
    /** "No steering" for {@link #steerForward()} / {@link #steerStrafe()}. */
    public static final int KEEP = Integer.MIN_VALUE;
    // key sets tried in the air (forward, strafe)
    private static final int[] TRY_F = {1, 1, 1, 0, 0, -1, -1, -1, 0};
    private static final int[] TRY_S = {0, 1, -1, 1, -1, 0, 1, -1, 0};

    private final MovementPhysics body = new MovementPhysics();
    private final MovementPhysics walker = new MovementPhysics();
    private boolean active;
    private boolean jump;
    private boolean withhold;
    private boolean held;
    private boolean airborne;
    private double airY; // the takeoff height of the catching jump (or where the in-air catch armed)
    private boolean steering;
    private int steerF;
    private int steerS;
    /** Replica ticks the last decision used (budget check in tests). */
    int simTicks;

    /** Ends everything (a skip, Stop-All, a climb reached). */
    public void reset() {
        active = false;
        jump = false;
        withhold = false;
        held = false;
        airborne = false;
        steering = false;
    }

    /** A catch owns this jump: no landing target, no block placement. */
    public boolean active() {
        return active;
    }

    /** Press jump this tick (Takeover's last catching ground tick, or the user's held-back press). */
    public boolean jump() {
        return jump;
    }

    /** Hold the user's jump key off this tick: a jump now falls short, a later one catches. */
    public boolean withholdJump() {
        return withhold;
    }

    /** The in-air catch needs keys other than the user's: write {@link #steerForward()} / {@link #steerStrafe()}. */
    public boolean steering() {
        return active && steering;
    }

    /** Forward key (-1, 0, 1) the in-air catch wants, {@link #KEEP} when it does not steer. */
    public int steerForward() {
        return steering() ? steerF : KEEP;
    }

    /** Strafe key (-1, 0, 1) the in-air catch wants, {@link #KEEP} when it does not steer. */
    public int steerStrafe() {
        return steering() ? steerS : KEEP;
    }

    /**
     * One tick.
     *
     * @param now       the body this tick (before the input is applied)
     * @param keyYaw    the yaw the movement keys are relative to
     * @param moveYaw   the heading the keys move along
     * @param forward   forward key (-1, 0, 1)
     * @param strafe    strafe key (-1, 0, 1)
     * @param sprint    the body sprints, or will (vanilla starts it with the key on the ground): the run-up's speed
     * @param userJump  the user's jump key is down
     * @param autoPress the assist may press jump on its own (Takeover); in the air it may also override the user's keys
     *                  to steer (otherwise it only fills axes the user leaves at 0). Never yaw
     */
    public void decide(MovementPhysics now, float keyYaw, float moveYaw, int forward, int strafe, boolean sprint,
                       boolean userJump, boolean autoPress, MovementPhysics.Voxels w) {
        jump = false;
        withhold = false;
        simTicks = 0;
        if (!now.onGround) {
            decideInAir(now, keyYaw, forward, strafe, sprint, autoPress, w);
            return;
        }
        airborne = false;
        steering = false;
        double dist = forward > 0 ? climbableAhead(now, moveYaw, w) : -1.0;
        if (dist < 0.0) {
            active = false;
            held = false;
            return;
        }
        double rad = Math.toRadians(moveYaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        boolean spr = sprint && forward > 0;
        // one walk along the ground; a takeoff is flown only from ticks within a jump's reach of the face. The catch
        // owns the jump only within HOLD_WINDOW ground ticks of its first catching takeoff: further out the user's own
        // jumps (a sprint hop, a block in between) are theirs
        int first = -1;
        int last = -1;
        walker.copyFrom(now);
        for (int k = 0; k <= MAX_WAIT && simTicks < MAX_SIM_TICKS; k++) {
            if (k > 0) {
                walker.tick(forward, strafe, keyYaw, spr, false, w);
                simTicks++;
                if (!walker.onGround || walker.horizontalCollision) {
                    break; // walked off, or into a wall: no takeoff from further on
                }
            }
            double left = dist - ((walker.x - now.x) * dx + (walker.z - now.z) * dz);
            if (left > FLIGHT_REACH) {
                continue;
            }
            if (first < 0 && k > HOLD_WINDOW) {
                break; // the first catching takeoff is too far off for the catch to own this run-up yet
            }
            boolean c = catchesFrom(walker, keyYaw, forward, strafe, spr, w);
            if (c) {
                if (first < 0) {
                    first = k;
                }
                last = k;
            } else if (first >= 0) {
                break; // the catching run of ticks ended
            }
        }
        active = first >= 0;
        if (!active) {
            held = false;
            return;
        }
        boolean want = userJump || held;
        // the takeoff one tick before the last that catches: nearer the face, so the catch is higher on it, with a tick
        // of slack against the replica's difference from the live body
        boolean takeoffNow = first == 0 && last <= LATE_SLACK;
        if (takeoffNow && (want || autoPress)) {
            jump = true;
            held = false;
            airborne = true;
            airY = now.y;
        } else if (want) {
            // early: the press waits for the takeoff tick
            withhold = true;
            held = true;
        }
    }

    /**
     * Distance along the heading to the first climbable cell within {@link #SCAN_RANGE}, from {@code MAX_DROP} below
     * the feet to two above, before a wall at head height; -1 when none.
     */
    double climbableAhead(MovementPhysics now, float moveYaw, MovementPhysics.Voxels w) {
        double rad = Math.toRadians(moveYaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        int fy = (int) Math.floor(now.y + 1.0e-6);
        int below = (int) Math.ceil(MAX_DROP);
        for (double d = SCAN_FROM; d <= SCAN_RANGE; d += SCAN_STEP) {
            int bx = (int) Math.floor(now.x + dx * d);
            int bz = (int) Math.floor(now.z + dz * d);
            for (int cy = fy - below; cy <= fy + 2; cy++) {
                if (w.surface(bx, cy, bz) == MovementPhysics.SURFACE_CLIMBABLE) {
                    return d;
                }
            }
            if (w.solid(bx, fy + 1, bz)) {
                return -1.0; // a wall at head height before any climbable
            }
        }
        return -1.0;
    }

    /** Jump from {@code from} and fly: do the feet enter a climbable cell before touching ground? */
    boolean catchesFrom(MovementPhysics from, float yaw, int forward, int strafe, boolean sprint,
                        MovementPhysics.Voxels w) {
        body.copyFrom(from);
        body.tick(forward, strafe, yaw, sprint, true, w);
        simTicks++;
        return flies(from.y, yaw, forward, strafe, sprint, w);
    }

    /**
     * In the air. A catching flight (the jump above, or an in-air catch armed earlier) keeps the catch while it still
     * catches from here, steering with keys only when the user's do not; a flight that no longer does lets go at once,
     * so the block assist and the fall clutch act. A body that was not catching (knocked off, walked off a platform)
     * is checked for a climbable face on its fall path: the user's keys first, then other key sets.
     */
    private void decideInAir(MovementPhysics now, float keyYaw, int forward, int strafe, boolean sprint,
                             boolean autoPress, MovementPhysics.Voxels w) {
        if (active && airborne) {
            if (chooseKeys(now, keyYaw, forward, strafe, sprint, autoPress, w)) {
                return;
            }
            active = false; // let go
            airborne = false;
            steering = false;
            return;
        }
        steering = false;
        if (!faceNear(now, keyYaw, w)) {
            active = false;
            return;
        }
        airY = now.y;
        active = chooseKeys(now, keyYaw, forward, strafe, sprint, autoPress, w);
        airborne = active;
    }

    /**
     * Picks the key set whose flight from here ends in a climbable cell: the last steering first (no flicker), the
     * user's own, then the others the user's keys leave free.
     */
    private boolean chooseKeys(MovementPhysics now, float yaw, int forward, int strafe, boolean sprint,
                               boolean autoPress, MovementPhysics.Voxels w) {
        if (steering && simFrom(now, yaw, steerF, steerS, sprint && steerF > 0, w)) {
            return true;
        }
        steering = false;
        if (simFrom(now, yaw, forward, strafe, sprint && forward > 0, w)) {
            return true;
        }
        for (int i = 0; i < TRY_F.length && simTicks < MAX_SIM_TICKS; i++) {
            int f = TRY_F[i];
            int s = TRY_S[i];
            if (f == forward && s == strafe) {
                continue;
            }
            if (!autoPress && ((forward != 0 && f != forward) || (strafe != 0 && s != strafe))) {
                continue; // manual keys win: only the axes the user leaves at 0
            }
            if (simFrom(now, yaw, f, s, sprint && f > 0, w)) {
                steering = true;
                steerF = f;
                steerS = s;
                return true;
            }
        }
        return false;
    }

    private boolean simFrom(MovementPhysics now, float yaw, int f, int s, boolean sprint, MovementPhysics.Voxels w) {
        body.copyFrom(now);
        return flies(airY, yaw, f, s, sprint, w);
    }

    /**
     * Cheap pre-filter for an in-air catch: a climbable cell in the column under the body, or within {@link #AIR_RANGE}
     * along the horizontal velocity or the four key directions. The flight simulation decides; this only spares it.
     */
    private boolean faceNear(MovementPhysics now, float keyYaw, MovementPhysics.Voxels w) {
        if (columnHasClimbable(now.x, now.y, now.z, w)) {
            return true;
        }
        double sq = now.vx * now.vx + now.vz * now.vz;
        if (sq > 1.0e-4) {
            double len = Math.sqrt(sq);
            if (rayHasClimbable(now, now.vx / len, now.vz / len, w)) {
                return true;
            }
        }
        for (int q = 0; q < 4; q++) {
            double rad = Math.toRadians(keyYaw + 90.0 * q);
            if (rayHasClimbable(now, -Math.sin(rad), Math.cos(rad), w)) {
                return true;
            }
        }
        return false;
    }

    private boolean rayHasClimbable(MovementPhysics now, double dx, double dz, MovementPhysics.Voxels w) {
        for (double d = AIR_STEP; d <= AIR_RANGE; d += AIR_STEP) {
            if (columnHasClimbable(now.x + dx * d, now.y, now.z + dz * d, w)) {
                return true;
            }
        }
        return false;
    }

    private boolean columnHasClimbable(double x, double y, double z, MovementPhysics.Voxels w) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int fy = (int) Math.floor(y + 1.0e-6);
        int below = (int) Math.ceil(MAX_DROP);
        for (int cy = fy - below; cy <= fy + 2; cy++) {
            if (w.surface(bx, cy, bz) == MovementPhysics.SURFACE_CLIMBABLE) {
                return true;
            }
        }
        return false;
    }

    private boolean flies(double fromY, float yaw, int forward, int strafe, boolean sprint, MovementPhysics.Voxels w) {
        for (int t = 0; t < SIM_TICKS; t++) {
            if (w.surface((int) Math.floor(body.x), (int) Math.floor(body.y), (int) Math.floor(body.z))
                    == MovementPhysics.SURFACE_CLIMBABLE) {
                return true;
            }
            if (body.onGround || body.y < fromY - MAX_DROP) {
                return false;
            }
            body.tick(forward, strafe, yaw, sprint, false, w);
            simTicks++;
        }
        return false;
    }
}
