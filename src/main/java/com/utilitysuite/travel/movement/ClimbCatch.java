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

    private final MovementPhysics body = new MovementPhysics();
    private final MovementPhysics walker = new MovementPhysics();
    private boolean active;
    private boolean jump;
    private boolean withhold;
    private boolean held;
    private boolean airborne;
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
     * @param sprint    the body sprints (or will this tick)
     * @param userJump  the user's jump key is down
     * @param autoPress the assist may press jump on its own (Takeover)
     */
    public void decide(MovementPhysics now, float keyYaw, float moveYaw, int forward, int strafe, boolean sprint,
                       boolean userJump, boolean autoPress, MovementPhysics.Voxels w) {
        jump = false;
        withhold = false;
        simTicks = 0;
        if (!now.onGround) {
            // a catching flight keeps the catch (no placement mid-air); a fall that did not catch ends it at the ground
            active &= airborne;
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
        // one walk along the ground; a takeoff is flown only from ticks within a jump's reach of the face
        boolean nowCatches = false;
        boolean nextCatches = false;
        boolean later = false;
        walker.copyFrom(now);
        for (int k = 0; k <= MAX_WAIT && !later; k++) {
            if (k > 0) {
                walker.tick(forward, strafe, keyYaw, spr, false, w);
                simTicks++;
                if (!walker.onGround) {
                    break; // walked off: no takeoff from here on
                }
            }
            double left = dist - ((walker.x - now.x) * dx + (walker.z - now.z) * dz);
            if (left <= FLIGHT_REACH && catchesFrom(walker, keyYaw, forward, strafe, spr, w)) {
                if (k == 0) {
                    nowCatches = true;
                } else {
                    later = true;
                    nextCatches = k == 1;
                }
            }
        }
        active = nowCatches || later;
        if (!active) {
            held = false;
            return;
        }
        boolean want = userJump || held;
        if (nowCatches && (want || (autoPress && !nextCatches))) {
            jump = true;
            held = false;
            airborne = true;
        } else if (want) {
            // too early: the press waits for the first tick that catches
            withhold = true;
            held = true;
        }
    }

    /**
     * Distance along the heading to the first climbable cell (feet level -1 to +2) within {@link #SCAN_RANGE}, before
     * a wall at head height; -1 when none.
     */
    double climbableAhead(MovementPhysics now, float moveYaw, MovementPhysics.Voxels w) {
        double rad = Math.toRadians(moveYaw);
        double dx = -Math.sin(rad);
        double dz = Math.cos(rad);
        int fy = (int) Math.floor(now.y + 1.0e-6);
        for (double d = SCAN_FROM; d <= SCAN_RANGE; d += SCAN_STEP) {
            int bx = (int) Math.floor(now.x + dx * d);
            int bz = (int) Math.floor(now.z + dz * d);
            for (int cy = fy - 1; cy <= fy + 2; cy++) {
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
        for (int t = 0; t < SIM_TICKS; t++) {
            if (w.surface((int) Math.floor(body.x), (int) Math.floor(body.y), (int) Math.floor(body.z))
                    == MovementPhysics.SURFACE_CLIMBABLE) {
                return true;
            }
            if (body.onGround || body.y < from.y - MAX_DROP) {
                return false;
            }
            body.tick(forward, strafe, yaw, sprint, false, w);
            simTicks++;
        }
        return false;
    }
}
