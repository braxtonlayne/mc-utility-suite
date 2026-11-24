package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.api.SimKey;

/**
 * Key-only evasion planning, pure and allocation free (a later hunter bot reuses it without client classes).
 * <ul>
 *   <li>{@link #planDodge}: a projectile on a hit course. Picks the movement key that carries the player sideways out of
 *       the projectile's path (away from the side it will pass on) and when to start the tap, from the arrival tick and
 *       the lateral miss distance. No dodge when the projectile already misses.</li>
 *   <li>{@link #planEscape}: a creeper fuse or primed TNT. Picks up to two movement keys that carry the player directly
 *       away from the blast, sprinting only when the move is forward (Minecraft cannot sprint backward). The camera is
 *       never turned: the look stays where the player is moving.</li>
 * </ul>
 * Directions use Minecraft's convention: view yaw {@code y} faces {@code (-sin y, cos y)} and the strafe-left key moves
 * along {@code (cos y, sin y)}. Keys whose step is a hazard (ledge, lava) are never chosen.
 */
public final class DodgePlanner {

    /** Projectile passing closer than this (blocks) to the body centre hits (half the body width plus margin). */
    public static final double HIT_RADIUS = 1.2;
    /** Extra lateral clearance aimed for beyond {@link #HIT_RADIUS}. */
    public static final double MARGIN = 0.15;
    /** Conservative lateral speed in the first ticks of a strafe from standing (blocks/tick). */
    public static final double STRAFE_SPEED = 0.12;
    public static final double WALK_SPEED = 0.2;
    public static final double SPRINT_SPEED = 0.26;
    public static final int MIN_TAP = 2;
    public static final int MAX_TAP = 8;
    /** Projectiles arriving later than this are re-planned next tick instead of acted on. */
    public static final int HORIZON_TICKS = 30;
    /** The strafe tap is only started this close to impact (ticks), so the shooter cannot track and re-aim. */
    public static final int TRIGGER_TICKS = 8;
    /** ...and only when the arrow's ray passes this close to the player's box (blocks). */
    public static final double BOX_TRIGGER = 0.55;
    /** Player box half extents around the body centre. */
    private static final double BOX_HALF_W = 0.3;
    private static final double BOX_HALF_H = 0.9;
    /** A key is used when its direction is within ~65 degrees of the wanted direction. */
    private static final double KEY_ALIGN = 0.42;

    // ---- outputs of the last plan
    private SimKey key;
    private SimKey key2;
    private boolean sprint;
    private int tapTicks;
    private int startInTicks;
    private int arrivalTick;
    private double missDistance;
    private boolean reachable;
    private boolean imminent;
    private double boxMiss;

    /**
     * Closest approach (blocks) of the ray {@code r + v t}, t from 0 to its pass of the body centre and a little
     * beyond, to the player's box (half width 0.3, half height 0.9) centred on the origin. The distance to a box is
     * convex along a line, so a ternary search finds it. Pure, allocation free.
     */
    public static double boxMissDistance(double rx, double ry, double rz, double vx, double vy, double vz) {
        double vv = vx * vx + vy * vy + vz * vz;
        if (vv < 1.0e-9) {
            return boxDistance(rx, ry, rz);
        }
        double tc = -(rx * vx + ry * vy + rz * vz) / vv;
        double hi = Math.max(0.0, tc) + 2.0 / Math.sqrt(vv);
        double lo = 0.0;
        for (int i = 0; i < 30; i++) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;
            if (boxDistance(rx + vx * m1, ry + vy * m1, rz + vz * m1)
                    <= boxDistance(rx + vx * m2, ry + vy * m2, rz + vz * m2)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double t = (lo + hi) * 0.5;
        return boxDistance(rx + vx * t, ry + vy * t, rz + vz * t);
    }

    private static double boxDistance(double x, double y, double z) {
        double dx = Math.max(Math.abs(x) - BOX_HALF_W, 0.0);
        double dy = Math.max(Math.abs(y) - BOX_HALF_H, 0.0);
        double dz = Math.max(Math.abs(z) - BOX_HALF_W, 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Plans a dodge against one projectile.
     *
     * @param rx,ry,rz projectile position relative to the player's body centre
     * @param vx,vy,vz projectile velocity (blocks/tick)
     * @param yawDeg   the player's view yaw
     * @return true when a dodge is needed and possible; {@link #startInTicks()} 0 means tap now
     */
    public boolean planDodge(double rx, double ry, double rz, double vx, double vy, double vz, float yawDeg,
                             boolean hazForward, boolean hazBack, boolean hazLeft, boolean hazRight) {
        clear();
        double vv = vx * vx + vy * vy + vz * vz;
        double vh = Math.sqrt(vx * vx + vz * vz);
        if (vv < 0.01 || vh < 0.05) {
            return false; // resting or dropping straight down: nothing to strafe out of
        }
        double t = -(rx * vx + ry * vy + rz * vz) / vv;
        if (t <= 0.0 || t > HORIZON_TICKS) {
            return false; // moving away, or too far out to plan yet
        }
        double mx = rx + vx * t;
        double my = ry + vy * t;
        double mz = rz + vz * t;
        missDistance = Math.sqrt(mx * mx + my * my + mz * mz);
        arrivalTick = (int) Math.ceil(t);
        boxMiss = boxMissDistance(rx, ry, rz, vx, vy, vz);
        imminent = arrivalTick <= TRIGGER_TICKS && boxMiss <= BOX_TRIGGER;
        if (missDistance > HIT_RADIUS) {
            return false; // already misses
        }
        // lateral axis: horizontal perpendicular of the projectile's path
        double px = vz / vh;
        double pz = -vx / vh;
        double side = mx * px + mz * pz; // where the projectile passes, along p
        // move against the side it passes on (the projectile ends up further away)
        double sign = side > 0.02 ? -1.0 : (side < -0.02 ? 1.0 : 0.0);
        double need;
        if (sign != 0.0) {
            need = HIT_RADIUS + MARGIN - Math.abs(side);
            if (pick(sign * px, sign * pz, yawDeg, hazForward, hazBack, hazLeft, hazRight)) {
                return finishDodge(need);
            }
            // that side is blocked: cross the path instead (longer)
            need = HIT_RADIUS + MARGIN + Math.abs(side);
            sign = -sign;
            return pick(sign * px, sign * pz, yawDeg, hazForward, hazBack, hazLeft, hazRight) && finishDodge(need);
        }
        need = HIT_RADIUS + MARGIN;
        if (pick(px, pz, yawDeg, hazForward, hazBack, hazLeft, hazRight)
                || pick(-px, -pz, yawDeg, hazForward, hazBack, hazLeft, hazRight)) {
            return finishDodge(need);
        }
        return false;
    }

    private boolean finishDodge(double need) {
        int ticks = (int) Math.ceil(need / STRAFE_SPEED);
        reachable = ticks <= arrivalTick;
        tapTicks = Math.max(MIN_TAP, Math.min(MAX_TAP, ticks));
        // start so the move completes one tick before arrival; never later than now
        startInTicks = Math.max(0, arrivalTick - ticks - 1);
        key2 = null;
        return true;
    }

    /** Picks the single key best aligned with (dx, dz); false when none is aligned or the aligned one is a hazard. */
    private boolean pick(double dx, double dz, float yawDeg, boolean hazF, boolean hazB, boolean hazL, boolean hazR) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double df = dx * fx + dz * fz;
        double dl = dx * fz - dz * fx; // left = (fz, -fx) = (cos y, sin y)
        SimKey best;
        double score;
        if (Math.abs(df) >= Math.abs(dl)) {
            best = df > 0 ? SimKey.FORWARD : SimKey.BACK;
            score = Math.abs(df);
        } else {
            best = dl > 0 ? SimKey.LEFT : SimKey.RIGHT;
            score = Math.abs(dl);
        }
        if (score < KEY_ALIGN || blocked(best, hazF, hazB, hazL, hazR)) {
            return false;
        }
        key = best;
        return true;
    }

    /**
     * Plans a back-off from a blast centre.
     *
     * @param rx,rz       blast source position relative to the player (horizontal)
     * @param fuseTicks   ticks until it goes off
     * @param blastRadius distance beyond which the blast does no damage
     * @return true when the player is inside the radius and some key moves it away
     */
    public boolean planEscape(double rx, double rz, int fuseTicks, double blastRadius, float yawDeg,
                              boolean hazForward, boolean hazBack, boolean hazLeft, boolean hazRight) {
        clear();
        double d = Math.sqrt(rx * rx + rz * rz);
        if (d >= blastRadius) {
            return false;
        }
        double ax;
        double az;
        if (d < 1.0e-3) {
            double yaw = Math.toRadians(yawDeg);
            ax = Math.sin(yaw); // on top of it: back away from where the player looks
            az = -Math.cos(yaw);
        } else {
            ax = -rx / d;
            az = -rz / d;
        }
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double df = ax * fx + az * fz;
        double dl = ax * fz - az * fx;
        SimKey fb = df > KEY_ALIGN ? SimKey.FORWARD : (df < -KEY_ALIGN ? SimKey.BACK : null);
        SimKey lr = dl > KEY_ALIGN ? SimKey.LEFT : (dl < -KEY_ALIGN ? SimKey.RIGHT : null);
        if (fb != null && blocked(fb, hazForward, hazBack, hazLeft, hazRight)) {
            fb = null;
        }
        if (lr != null && blocked(lr, hazForward, hazBack, hazLeft, hazRight)) {
            lr = null;
        }
        if (fb == null && lr == null) {
            return false;
        }
        key = fb != null ? fb : lr;
        key2 = fb != null ? lr : null;
        sprint = fb == SimKey.FORWARD;
        double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
        double need = blastRadius - d;
        int ticks = (int) Math.ceil(need / speed);
        tapTicks = Math.max(MIN_TAP, ticks);
        startInTicks = 0;
        arrivalTick = fuseTicks;
        reachable = ticks <= fuseTicks;
        missDistance = d;
        return true;
    }

    private static boolean blocked(SimKey k, boolean hazF, boolean hazB, boolean hazL, boolean hazR) {
        return switch (k) {
            case FORWARD -> hazF;
            case BACK -> hazB;
            case LEFT -> hazL;
            case RIGHT -> hazR;
            default -> true;
        };
    }

    private void clear() {
        key = null;
        key2 = null;
        sprint = false;
        tapTicks = 0;
        startInTicks = 0;
        arrivalTick = 0;
        missDistance = 0;
        reachable = false;
        imminent = false;
        boxMiss = Double.MAX_VALUE;
    }

    /**
     * The last dodge plan is due now: the arrow is {@value #TRIGGER_TICKS} ticks or less out and its ray passes within
     * {@value #BOX_TRIGGER} blocks of the player's box. Tapping earlier lets the shooter track the strafe and re-aim.
     */
    public boolean imminent() {
        return imminent;
    }

    /** Closest approach of the arrow's ray to the player's box in the last dodge plan. */
    public double boxMiss() {
        return boxMiss;
    }

    /** Main movement key of the last plan, or null. */
    public SimKey key() {
        return key;
    }

    /** Second (diagonal) key of an escape, or null. */
    public SimKey key2() {
        return key2;
    }

    public boolean sprint() {
        return sprint;
    }

    public int tapTicks() {
        return tapTicks;
    }

    public int startInTicks() {
        return startInTicks;
    }

    public int arrivalTick() {
        return arrivalTick;
    }

    /** Closest approach of the projectile (dodge) or distance to the blast (escape). */
    public double missDistance() {
        return missDistance;
    }

    /** The move completes before arrival / the fuse. */
    public boolean reachable() {
        return reachable;
    }
}
