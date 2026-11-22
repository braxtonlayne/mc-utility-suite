package com.utilitysuite.combat.tactics;

/**
 * Where a thrown ender pearl comes down: vanilla 1.21.1 thrown-projectile motion per tick
 * ({@code pos += vel; vel *= 0.99 (0.8 in water); vel.y -= 0.03}) stepped until the segment enters a block that is not
 * {@link CombatVoxels#AIR} or water. Engine free, allocation free.
 * <p>
 * CONTRACT STUB: signatures are fixed for the volley; the bodies are filled in by the pearl-intercept task. Until then
 * {@link #simulate} answers false.
 */
public final class PearlLandingSim {

    /** A pearl still flying after this many ticks is treated as lost (out of loaded chunks). */
    public static final int MAX_TICKS = 200;

    private double landX, landY, landZ;
    private int flightTicks;

    /**
     * @return true when the pearl hits a block within {@code maxTicks}; the landing point (where the thrower will
     *         stand: on top of or in front of the block hit) is then in {@link #landX()}..
     */
    public boolean simulate(double x, double y, double z, double vx, double vy, double vz, CombatVoxels w,
                            int maxTicks) {
        return false;
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

    /** Ticks from now until the impact. */
    public int flightTicks() {
        return flightTicks;
    }
}
