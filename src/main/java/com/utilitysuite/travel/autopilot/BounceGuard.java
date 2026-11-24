package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.BlockQuery;
import com.utilitysuite.travel.GridRay;

/**
 * Forward obstacle check for the Nether highway bounce, engine-free and allocation-free. Rays are cast along the
 * travel direction at foot and head height above the floor the bounce last stood on (not above the body, which rises
 * and falls and shrinks to the glide pose during a bounce):
 * <ul>
 *   <li>head height blocked within the stop distance: a wall, pillar or lowered ceiling, {@link Verdict#BLOCKED};</li>
 *   <li>only foot height blocked: a one-block lip. The bounce's jump clears it when there is standing room on top
 *       (head row and the row above it free past the lip), so that is {@link Verdict#LIP} and the bounce goes on.
 *       Without that room, or when the lip stays in front for {@link #LIP_STALL_TICKS}, it is {@code BLOCKED}.</li>
 * </ul>
 * The stop distance is {@link #STOP_DISTANCE}, stretched with speed up to the ray range. The foot row takes its own
 * query so the caller can leave out blocks that are walked over without a jump (carpet, slabs).
 */
public final class BounceGuard {

    public enum Verdict { CLEAR, LIP, BLOCKED }

    public static final double RANGE = 8.0;
    public static final double STOP_DISTANCE = 3.0;
    /** Ticks of travel the stop distance must cover at speed. */
    public static final double REACTION_TICKS = 4.0;
    /** A lip the jump clears is passed within a few ticks; one still in front after this long is not being cleared. */
    public static final int LIP_STALL_TICKS = 40;
    /** Room past the lip's near face for the body to stand on it. */
    private static final double LIP_ROOM = 1.5;

    private boolean hasFloor;
    private double floorY;
    private int lipTicks;

    public void reset() {
        hasFloor = false;
        floorY = 0.0;
        lipTicks = 0;
    }

    /** Once per tick before {@link #check}: remembers the floor height while standing on it. */
    public void observe(double y, boolean onGround) {
        if (onGround) {
            hasFloor = true;
            floorY = y;
        }
    }

    /** The height the rays are measured from: the remembered floor while the body is within a bounce of it. */
    double baseY(double y) {
        return hasFloor && y >= floorY - 0.6 && y <= floorY + 2.0 ? floorY : y;
    }

    /**
     * @param dx              horizontal travel direction, x (need not be normalized)
     * @param dz              horizontal travel direction, z
     * @param horizontalSpeed blocks per tick
     * @param footQuery       what stops the body at foot height (taller than a step)
     * @param bodyQuery       what stops the body at head height and above
     */
    public Verdict check(double x, double y, double z, double dx, double dz, double horizontalSpeed,
                         BlockQuery footQuery, BlockQuery bodyQuery) {
        double base = baseY(y);
        double stop = Math.min(RANGE, Math.max(STOP_DISTANCE, horizontalSpeed * REACTION_TICKS));
        double head = GridRay.march(x, base + 1.5, z, dx, 0.0, dz, RANGE, bodyQuery);
        if (head <= 0.0) {
            // The head row starts inside a block: the body is not standing at this height (glide pose under a low
            // roof), so the rows say nothing. A real drop in the ceiling is seen ahead before it is overhead.
            lipTicks = 0;
            return Verdict.CLEAR;
        }
        if (head < stop) {
            return Verdict.BLOCKED;
        }
        double foot = GridRay.march(x, base + 0.5, z, dx, 0.0, dz, RANGE, footQuery);
        if (foot >= stop || foot <= 0.0) {
            lipTicks = 0;
            return Verdict.CLEAR;
        }
        double room = foot + LIP_ROOM;
        if (head < room || GridRay.march(x, base + 2.5, z, dx, 0.0, dz, room, bodyQuery) < room) {
            return Verdict.BLOCKED;
        }
        return ++lipTicks > LIP_STALL_TICKS ? Verdict.BLOCKED : Verdict.LIP;
    }
}
