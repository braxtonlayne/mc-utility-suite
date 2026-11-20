package com.utilitysuite.travel.autopilot;

/**
 * Elytra pitch controller, engine-free. Minecraft pitch is positive nose-down. A PD law on altitude error and vertical
 * speed around a slightly nose-down cruise pitch:
 * <pre>
 *   pitch = cruise + kP * (altitude - target) + kD * verticalSpeed
 * </pre>
 * Above the target or climbing, the nose goes down; below the target or sinking, up. Pulling up trades speed for
 * height, so when the glider is slower than {@code stallSpeed} and no rocket is pushing, the pitch is floored at a
 * nose-down recovery value that grows as speed drops. The result is clamped to [minPitch, maxPitch].
 */
public final class ElytraPitchController {

    private double cruisePitch = 3.0;
    private double kP = 1.1;
    private double kD = 30.0;
    private double minPitch = -35.0;
    private double maxPitch = 40.0;
    private double stallSpeed = 0.75;
    private double recoverPitch = 25.0;

    public ElytraPitchController tune(double cruisePitch, double kP, double kD, double minPitch, double maxPitch) {
        this.cruisePitch = cruisePitch;
        this.kP = kP;
        this.kD = kD;
        this.minPitch = minPitch;
        this.maxPitch = maxPitch;
        return this;
    }

    public ElytraPitchController stall(double stallSpeed, double recoverPitch) {
        this.stallSpeed = stallSpeed;
        this.recoverPitch = recoverPitch;
        return this;
    }

    /**
     * @param altitude       current y
     * @param targetAltitude desired y
     * @param verticalSpeed  blocks per tick, positive up
     * @param horizontalSpeed blocks per tick
     * @param boosting       a firework rocket is currently pushing
     * @return pitch in degrees (positive down)
     */
    public double pitch(double altitude, double targetAltitude, double verticalSpeed, double horizontalSpeed,
                        boolean boosting) {
        double p = cruisePitch + kP * (altitude - targetAltitude) + kD * verticalSpeed;
        if (!boosting && horizontalSpeed < stallSpeed) {
            double deficit = 1.0 - Math.max(0.0, horizontalSpeed) / stallSpeed;
            p = Math.max(p, recoverPitch * deficit);
        }
        return Math.max(minPitch, Math.min(maxPitch, p));
    }

    public double minPitch() {
        return minPitch;
    }

    public double maxPitch() {
        return maxPitch;
    }
}
