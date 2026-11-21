package com.utilitysuite.travel.movement;

/**
 * The per-tick decision of the movement assist, engine-free so tests (and automated drivers) can call it. It never
 * fights the user: it only adds a jump, and only fills a key axis (forward/back, left/right) the user is not pressing.
 * <ul>
 *   <li><b>Edge jump</b> (on the ground, moving forward): jump on the last tick before walking off when that lands
 *       better than dropping ({@link JumpSearch#shouldEdgeJump}).</li>
 *   <li><b>Sprint hop</b> (optional speed mode): on open flat ground a sprint jump covers more distance than
 *       sprinting; jump whenever the jump lands at the same height and gets farther.</li>
 *   <li><b>Air steer</b> (airborne): if the user's keys miss the landing, take damage or land much lower than the best
 *       keys would, switch to the best keys on the free axes.</li>
 * </ul>
 * Results also carry the predicted landing for the overlay.
 */
public final class MovementAssistBrain {

    /** Air steering corrects only when the best keys beat the user's by this much (blocks of score). */
    public static final double STEER_MARGIN = 0.75;
    /** Sprint hop jumps only when the jump gains at least this much progress over the same ticks. */
    public static final double HOP_GAIN = 0.15;
    public static final int KEEP = Integer.MIN_VALUE;

    public boolean edgeJump = true;
    public boolean airSteer = true;
    public boolean sprintHop = false;
    /**
     * SafeWalk is on: an assisted jump is valid only if it lands without costing health (otherwise SafeWalk holds the
     * edge). The user pressing jump themselves always goes through: that is their call.
     */
    public boolean safeOnly;

    private final JumpSearch search = new JumpSearch();
    private final MovementPhysics a = new MovementPhysics();
    private final MovementPhysics b = new MovementPhysics();

    // outputs
    private boolean jump;
    private int forward = KEEP;
    private int strafe = KEEP;
    private boolean corrected;
    private boolean hasLanding;
    private double landX, landY, landZ;
    private float landDamage;
    private boolean lethal;

    /**
     * @param userForward the user's forward axis this tick (-1, 0, 1)
     * @param userStrafe  the user's strafe axis (+1 left, -1 right, 0)
     */
    public void decide(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                       boolean userJump, float health, MovementPhysics.Voxels w) {
        jump = false;
        forward = KEEP;
        strafe = KEEP;
        corrected = false;
        if (now.onGround) {
            if (edgeJump && !userJump && userForward > 0 && search.shouldEdgeJump(now, yaw, sprinting, health, w)
                    && (!safeOnly || search.landDamage() <= 0f)) {
                jump = true;
            } else if (sprintHop && !userJump && sprinting && userForward > 0 && hopGains(now, yaw, w)) {
                jump = true;
            }
            // predicted landing of a jump from here (the overlay's "if you jump now")
            double sc = search.evaluate(now, Math.max(userForward, 0), userStrafe, yaw, sprinting, true, health, w);
            capture(sc);
            return;
        }
        double mine = search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
        capture(mine);
        if (!airSteer) {
            return;
        }
        boolean bad = mine <= JumpSearch.NO_LANDING || search.landDamage() > 0;
        double myLandY = search.landY();
        if (!search.steer(now, yaw, sprinting, health, w)) {
            return; // nothing lands: keep the user's keys
        }
        double best = search.score();
        boolean muchLower = mine > JumpSearch.NO_LANDING && search.landY() - myLandY >= 1.0;
        if (!(bad || muchLower) || best < mine + STEER_MARGIN) {
            search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
            capture(mine);
            return;
        }
        int bf = search.forward();
        int bs = search.strafe();
        // fill only the free axes; if the best needs an axis the user is holding differently, re-check the blend
        int f = userForward != 0 ? userForward : bf;
        int s = userStrafe != 0 ? userStrafe : bs;
        double blended = search.evaluate(now, f, s, yaw, sprinting, false, health, w);
        if (blended < mine + STEER_MARGIN * 0.5) {
            search.evaluate(now, userForward, userStrafe, yaw, sprinting, false, health, w);
            capture(mine);
            return;
        }
        capture(blended);
        forward = f != userForward ? f : KEEP;
        strafe = s != userStrafe ? s : KEEP;
        corrected = forward != KEEP || strafe != KEEP;
    }

    /** A sprint jump vs. sprinting on, over the jump's own airtime, on ground that stays level. */
    private boolean hopGains(MovementPhysics now, float yaw, MovementPhysics.Voxels w) {
        a.copyFrom(now);
        b.copyFrom(now);
        a.tick(1, 0, yaw, true, true, w);
        b.tick(1, 0, yaw, true, false, w);
        int t = 1;
        while (!a.onGround && t < 20) {
            a.tick(1, 0, yaw, true, false, w);
            b.tick(1, 0, yaw, true, false, w);
            t++;
        }
        if (!a.onGround || a.y != now.y || !b.onGround || b.y != now.y || a.horizontalCollision) {
            return false;
        }
        double yawRad = Math.toRadians(yaw);
        double hx = -Math.sin(yawRad);
        double hz = Math.cos(yawRad);
        double ja = (a.x - now.x) * hx + (a.z - now.z) * hz;
        double jb = (b.x - now.x) * hx + (b.z - now.z) * hz;
        return ja > jb + HOP_GAIN;
    }

    private void capture(double score) {
        hasLanding = score > JumpSearch.NO_LANDING;
        lethal = !hasLanding;
        landX = search.landX();
        landY = search.landY();
        landZ = search.landZ();
        landDamage = hasLanding ? search.landDamage() : 0f;
    }

    public boolean jump() {
        return jump;
    }

    /** Forward axis to apply, or {@link #KEEP}. */
    public int forward() {
        return forward;
    }

    /** Strafe axis to apply (+1 left, -1 right), or {@link #KEEP}. */
    public int strafe() {
        return strafe;
    }

    public boolean corrected() {
        return corrected;
    }

    public boolean hasLanding() {
        return hasLanding;
    }

    /** True when the trajectory lands nowhere survivable within the horizon. */
    public boolean lethal() {
        return lethal;
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

    /** The shared search, for callers that want more (reach maps, automated drivers). */
    public JumpSearch search() {
        return search;
    }
}
