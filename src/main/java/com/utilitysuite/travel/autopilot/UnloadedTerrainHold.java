package com.utilitysuite.travel.autopilot;

/**
 * Safety margin for elytra cruise while terrain ahead is not loaded yet, engine-free. Unloaded columns have no height
 * to follow, so while any lookahead sample is unloaded the target altitude is raised by {@link #MARGIN} (and never
 * below the altitude at the start of the hold plus the margin), and rockets are held back so the glider does not
 * accelerate into terrain it cannot see.
 * <p>
 * The glider is never left to stall: during the first {@link #GRACE_TICKS} of a hold a rocket is still allowed below
 * {@link #STALL_SPEED}, and after that (the chunks are not coming, e.g. a short server view distance) rockets are
 * allowed again for keeping cruise speed, never for gaining height. The driver passes its own altitude as the rocket
 * target while {@link #active()}, which makes {@link RocketPolicy} speed-only.
 * <p>
 * The hold ends after {@link #RELEASE_TICKS} loaded ticks in a row, so a sample flickering across a chunk border does
 * not restart it every few ticks.
 */
public final class UnloadedTerrainHold {

    public static final double MARGIN = 8.0;
    public static final int GRACE_TICKS = 100;
    public static final double STALL_SPEED = 0.6;
    public static final int RELEASE_TICKS = 10;
    public static final int ANNOUNCE_TICKS = 40;

    private int ticks;
    private int loadedTicks;
    private double floorAltitude;

    public void reset() {
        ticks = 0;
        loadedTicks = 0;
        floorAltitude = 0.0;
    }

    /** Once per cruise tick. {@code altitude} is the glider's current y. */
    public void update(boolean unloadedAhead, double altitude) {
        if (unloadedAhead) {
            if (ticks == 0) {
                floorAltitude = altitude + MARGIN;
            }
            ticks++;
            loadedTicks = 0;
        } else if (ticks > 0 && ++loadedTicks >= RELEASE_TICKS) {
            ticks = 0;
            loadedTicks = 0;
        }
    }

    public boolean active() {
        return ticks > 0;
    }

    /** True on the one tick a hold has lasted long enough to be worth a status line. */
    public boolean announce() {
        return ticks == ANNOUNCE_TICKS && loadedTicks == 0;
    }

    /** The cruise target with the margin applied while the hold is active. */
    public double targetAltitude(double baseTarget) {
        return ticks > 0 ? Math.max(baseTarget + MARGIN, floorAltitude) : baseTarget;
    }

    /** Whether a rocket may be considered at all this tick (the policy still decides). */
    public boolean rocketAllowed(double horizontalSpeed) {
        return ticks == 0 || ticks > GRACE_TICKS || horizontalSpeed < STALL_SPEED;
    }
}
