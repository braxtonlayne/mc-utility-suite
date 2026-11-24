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

    private final MovementPhysics body = new MovementPhysics();
    private final MovementPhysics walker = new MovementPhysics();
    private boolean active;
    private boolean jump;
    private boolean withhold;
    private boolean held;
    private boolean airborne;
    private double airY; // the takeoff height of the catching jump
    /** Replica ticks the last decision used (budget check in tests). */
    int simTicks;

    /** Ends everything (a skip, Stop-All, a climb reached). */
    public void reset() {
        active = false;
        jump = false;
        withhold = false;
        held = false;
        airborne = false;
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
     * @param autoPress the assist may press jump on its own (Takeover)
     */
    public void decide(MovementPhysics now, float keyYaw, float moveYaw, int forward, int strafe, boolean sprint,
                       boolean userJump, boolean autoPress, MovementPhysics.Voxels w) {
        jump = false;
        withhold = false;
        simTicks = 0;
        if (!now.onGround) {
            // a catching flight keeps the catch (no placement mid-air) while it still catches from here; a flight that
            // no longer does hands the fall to the block assist and the clutch at once
            active &= airborne && flyCatches(now, keyYaw, forward, strafe, sprint, w);
            return;
        }
        airborne = false;
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

    /** Already in the air: does the rest of this flight still catch? */
    private boolean flyCatches(MovementPhysics now, float yaw, int forward, int strafe, boolean sprint,
                               MovementPhysics.Voxels w) {
        body.copyFrom(now);
        return flies(airY, yaw, forward, strafe, sprint, w);
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
