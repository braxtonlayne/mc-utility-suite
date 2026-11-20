package com.utilitysuite.travel.autopilot;

/**
 * When to use a firework rocket during elytra cruise, engine-free. Fires when no boost is active, the cooldown has
 * elapsed, rockets above the reserve are left, and the glider is either too slow or too far below the target altitude.
 */
public final class RocketPolicy {

    private double minSpeed = 0.9;
    private double altitudeBand = 6.0;
    private int cooldownTicks = 30;

    public RocketPolicy configure(double minSpeed, double altitudeBand, int cooldownTicks) {
        this.minSpeed = minSpeed;
        this.altitudeBand = altitudeBand;
        this.cooldownTicks = cooldownTicks;
        return this;
    }

    public boolean shouldFire(double altitude, double targetAltitude, double horizontalSpeed, boolean boosting,
                              int ticksSinceLastRocket, int rockets, int reserveRockets) {
        if (boosting || ticksSinceLastRocket < cooldownTicks || rockets <= reserveRockets) {
            return false;
        }
        return horizontalSpeed < minSpeed || altitude < targetAltitude - altitudeBand;
    }
}
