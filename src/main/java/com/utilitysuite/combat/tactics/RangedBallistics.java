package com.utilitysuite.combat.tactics;

/**
 * Vanilla 1.21.1 arrow / trident flight and the launch angle that hits a point. Engine free, allocation free (one
 * instance per caller, results in fields).
 * <p>
 * Per tick ({@code AbstractArrow.tick}): {@code pos += vel; vel *= drag; vel.y -= 0.05} with drag 0.99 in air and, in
 * water, 0.6 for arrows and 0.99 for tridents. Launch speeds: bow {@code getPowerForTime(useTicks) * 3.0}, crossbow
 * 3.15, trident 2.5. Shooter motion and the random inaccuracy are not modelled (the inaccuracy is zero-mean).
 * <p>
 * The solver uses the low arc (the flatter, faster shot): bisection on the elevation between straight down-ish and the
 * elevation that reaches the farthest height at the target's distance. Lead on a moving target iterates the flight
 * time: aim at {@code target + vel * T}, re-solve, repeat.
 */
public final class RangedBallistics {

    public static final double GRAVITY = 0.05;
    public static final double AIR_DRAG = 0.99;
    public static final double ARROW_WATER_DRAG = 0.6;
    public static final double TRIDENT_WATER_DRAG = 0.99;
    public static final double CROSSBOW_SPEED = 3.15;
    public static final double TRIDENT_SPEED = 2.5;
    public static final double BOW_FULL_SPEED = 3.0;
    /** Ticks of bow draw for full power ({@code getPowerForTime} reaches 1.0 at 20). */
    public static final int BOW_FULL_CHARGE_TICKS = 20;
    /** Flights longer than this are treated as out of range. */
    public static final int MAX_TICKS = 120;

    private static final double MIN_ELEV = -80.0;
    private static final double MAX_ELEV = 80.0;
    private static final int LEAD_ITERATIONS = 5;

    private double elevationDeg;
    private double flightTicks;

    /** {@code BowItem.getPowerForTime}: {@code f = t/20; (f*f + 2f) / 3}, capped at 1. */
    public static double bowPower(int useTicks) {
        double f = useTicks / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        return Math.min(f, 1.0);
    }

    /** Launch speed of a bow arrow after {@code useTicks} of draw (blocks per tick). */
    public static double bowSpeed(int useTicks) {
        return bowPower(useTicks) * BOW_FULL_SPEED;
    }

    /**
     * Height the projectile has when it has travelled {@code dist} horizontally, launched at {@code elevDeg} (up
     * positive) with {@code speed}; NaN when it never gets that far within {@link #MAX_TICKS}. The fractional tick of
     * arrival is left in {@link #flightTicks()}.
     */
    public double heightAt(double elevDeg, double speed, double dist, double waterDrag, boolean inWater) {
        double r = Math.toRadians(elevDeg);
        double vh = Math.cos(r) * speed;
        double vy = Math.sin(r) * speed;
        double h = 0.0, y = 0.0;
        double drag = inWater ? waterDrag : AIR_DRAG;
        if (dist <= 0.0) {
            flightTicks = 0.0;
            return 0.0;
        }
        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double nh = h + vh;
            double ny = y + vy;
            if (nh >= dist) {
                double f = vh > 1.0e-9 ? (dist - h) / vh : 0.0;
                flightTicks = tick - 1 + f;
                return y + vy * f;
            }
            h = nh;
            y = ny;
            vh *= drag;
            vy = vy * drag - GRAVITY;
            if (vh < 1.0e-4) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * Elevation (degrees, up positive) that puts the projectile through the point {@code dist} blocks away
     * horizontally and {@code dy} blocks above the launch point.
     *
     * @return false when no low-arc shot reaches it (out of range)
     */
    public boolean solve(double dist, double dy, double speed, double waterDrag, boolean inWater) {
        // Coarse scan for the elevation that gets highest at this distance: the top of the reachable band.
        double bestElev = Double.NaN, bestH = Double.NEGATIVE_INFINITY;
        for (double e = MIN_ELEV; e <= MAX_ELEV; e += 5.0) {
            double h = heightAt(e, speed, dist, waterDrag, inWater);
            if (!Double.isNaN(h) && h > bestH) {
                bestH = h;
                bestElev = e;
            }
        }
        if (Double.isNaN(bestElev) || bestH < dy) {
            return false;
        }
        double lo = MIN_ELEV;
        double hLo = heightAt(lo, speed, dist, waterDrag, inWater);
        if (!Double.isNaN(hLo) && hLo > dy) {
            return false; // even straight down passes above it (not a shot geometry we take)
        }
        double hi = bestElev;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) * 0.5;
            double h = heightAt(mid, speed, dist, waterDrag, inWater);
            if (Double.isNaN(h) || h < dy) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        elevationDeg = hi;
        heightAt(hi, speed, dist, waterDrag, inWater); // leaves flightTicks for the chosen elevation
        return flightTicks <= MAX_TICKS;
    }

    /**
     * Solves a shot from {@code (ox, oy, oz)} at a target point moving with {@code (tvx, tvy, tvz)} per tick, leading
     * by the flight time. On success {@link #elevationDeg()}/{@link #flightTicks()} hold the shot and
     * {@link #aimX()}.. the led point it was solved for.
     */
    public boolean solveLead(double ox, double oy, double oz, double tx, double ty, double tz,
                             double tvx, double tvy, double tvz, double speed, double waterDrag, boolean inWater) {
        double t = 0.0;
        boolean ok = false;
        for (int i = 0; i < LEAD_ITERATIONS; i++) {
            double ax = tx + tvx * t, ay = ty + tvy * t, az = tz + tvz * t;
            double dx = ax - ox, dz = az - oz;
            ok = solve(Math.sqrt(dx * dx + dz * dz), ay - oy, speed, waterDrag, inWater);
            if (!ok) {
                return false;
            }
            aimX = ax;
            aimY = ay;
            aimZ = az;
            if (Math.abs(flightTicks - t) < 0.05) {
                break;
            }
            t = flightTicks;
        }
        return ok;
    }

    private double aimX, aimY, aimZ;

    /** Elevation of the last solution, degrees, up positive. Minecraft pitch is the negative of it. */
    public double elevationDeg() {
        return elevationDeg;
    }

    /** Minecraft pitch (down positive) of the last solution. */
    public float mcPitch() {
        return (float) -elevationDeg;
    }

    /** Flight time of the last solution (or {@link #heightAt} call), ticks. */
    public double flightTicks() {
        return flightTicks;
    }

    public double aimX() {
        return aimX;
    }

    public double aimY() {
        return aimY;
    }

    public double aimZ() {
        return aimZ;
    }
}
