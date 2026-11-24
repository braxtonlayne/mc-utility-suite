package com.utilitysuite.tools.player;

import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.PearlLandingSim;

/**
 * Engine-free check for the quick pearl key: would a pearl thrown now hit a block right in front of the eyes? Builds
 * the vanilla 1.21.1 throw (start 0.1 below the eyes, speed 1.5 along the look direction, plus the thrower's own
 * motion; the small random spread is left out) and flies it with {@link PearlLandingSim} for {@link #SIM_TICKS} ticks.
 * The throw counts as blocked when the simulated landing point is within {@link #BLOCKED_RANGE} of the start. Unknown
 * cells and lava end the simulation without an impact, so they never block a throw. No allocation.
 * <p>
 * The simulation works on whole cells, so a slab, pane or ladder cell counts as full: {@link QuickPearl} confirms a
 * blocked answer against the real collision shapes before it holds the throw. {@link #dirX()}.. give that segment.
 */
public final class PearlPathCheck {

    /** A throw that would hit a block within this distance of the start is held back. */
    public static final double BLOCKED_RANGE = 1.5;
    /** Vanilla ender pearl launch speed (blocks per tick). */
    static final double SPEED = 1.5;
    /** The pearl starts this far below the eyes. */
    static final double EYE_DROP = 0.1;
    /** Enough to cover {@link #BLOCKED_RANGE} even while backing away from the throw. */
    static final int SIM_TICKS = 2;

    private static final double DEG = Math.PI / 180.0;

    private final PearlLandingSim sim = new PearlLandingSim();
    private double startX, startY, startZ;
    private double dirX, dirY, dirZ;

    /**
     * @param yawDeg   the thrower's yaw (0 = +Z, 90 = -X), degrees
     * @param pitchDeg the thrower's pitch (-90 = straight up), degrees
     * @param mvx      the thrower's motion per tick; pass 0 for {@code mvy} while on the ground (vanilla does)
     */
    public boolean blocked(double eyeX, double eyeY, double eyeZ, float yawDeg, float pitchDeg, double mvx, double mvy,
                           double mvz, CombatVoxels w) {
        double yaw = yawDeg * DEG;
        double pitch = pitchDeg * DEG;
        double cosPitch = Math.cos(pitch);
        double vx = -Math.sin(yaw) * cosPitch * SPEED + mvx;
        double vy = -Math.sin(pitch) * SPEED + mvy;
        double vz = Math.cos(yaw) * cosPitch * SPEED + mvz;
        startX = eyeX;
        startY = eyeY - EYE_DROP;
        startZ = eyeZ;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0e-6) {
            dirX = 0.0;
            dirY = 0.0;
            dirZ = 0.0;
            return false;
        }
        dirX = vx / len;
        dirY = vy / len;
        dirZ = vz / len;
        if (!sim.simulate(startX, startY, startZ, vx, vy, vz, w, SIM_TICKS)) {
            return false;
        }
        double dx = sim.landX() - startX;
        double dy = sim.landY() - startY;
        double dz = sim.landZ() - startZ;
        return dx * dx + dy * dy + dz * dz <= BLOCKED_RANGE * BLOCKED_RANGE;
    }

    /** Start of the last checked throw. */
    public double startX() {
        return startX;
    }

    public double startY() {
        return startY;
    }

    public double startZ() {
        return startZ;
    }

    /** Unit direction of the last checked throw at launch (zero when it had no speed). */
    public double dirX() {
        return dirX;
    }

    public double dirY() {
        return dirY;
    }

    public double dirZ() {
        return dirZ;
    }
}
