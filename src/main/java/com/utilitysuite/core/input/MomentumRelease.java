package com.utilitysuite.core.input;

/**
 * Engine-free camera momentum after manual mouse input preempts an automated VISIBLE turn: instead of stopping dead,
 * the turn's last angular velocity keeps going and decays exponentially to zero within about {@link #DURATION_SEC}.
 * The decayed motion is added on top of the user's own mouse movement; it is capped at the no-snap limit and never
 * pushes against the user: an axis the user moves the other way loses its momentum at once. Allocation free.
 */
public final class MomentumRelease {

    /** Decay time constant (seconds): velocity falls to about 4% after {@link #DURATION_SEC}. */
    static final double TAU_SEC = 0.06;
    /** Hard end of the release (seconds); whatever is left is dropped. */
    static final double DURATION_SEC = 0.2;
    /** Below this speed (degrees per second) the release ends. */
    static final double STOP_DEG_PER_SEC = 2.0;

    private boolean active;
    private double vYaw;
    private double vPitch;
    private double elapsed;
    private double lastYawStep;
    private double lastPitchStep;

    /** Starts a release with the turn's current velocity (degrees per second), capped at the no-snap limit. */
    public void start(double yawDegPerSec, double pitchDegPerSec) {
        double speed = Math.sqrt(yawDegPerSec * yawDegPerSec + pitchDegPerSec * pitchDegPerSec);
        if (!(speed >= STOP_DEG_PER_SEC) || Double.isInfinite(speed)) {
            cancel();
            return;
        }
        double k = speed > RotationLimits.MAX_DEG_PER_SEC ? RotationLimits.MAX_DEG_PER_SEC / speed : 1.0;
        vYaw = yawDegPerSec * k;
        vPitch = pitchDegPerSec * k;
        elapsed = 0.0;
        active = true;
    }

    /** Stops at once (Stop-All, world unload, new automated turn). */
    public void cancel() {
        active = false;
        vYaw = 0.0;
        vPitch = 0.0;
        lastYawStep = 0.0;
        lastPitchStep = 0.0;
    }

    /**
     * Advances by {@code dt} seconds. {@code userYawSign}/{@code userPitchSign} are the signs of the user's own mouse
     * motion this frame (0 = none): an axis moved against the momentum is zeroed. Afterwards read
     * {@link #yawStep()}/{@link #pitchStep()} (degrees to add this frame).
     *
     * @return true while a step is produced
     */
    public boolean advance(double dt, int userYawSign, int userPitchSign) {
        lastYawStep = 0.0;
        lastPitchStep = 0.0;
        if (!active) {
            return false;
        }
        if (!(dt > 0.0)) {
            return true;
        }
        if (userYawSign != 0 && userYawSign * vYaw < 0.0) {
            vYaw = 0.0;
        }
        if (userPitchSign != 0 && userPitchSign * vPitch < 0.0) {
            vPitch = 0.0;
        }
        // exact integral of v * exp(-t / tau) over the step, so the distance is frame-rate independent
        double decay = Math.exp(-dt / TAU_SEC);
        double travel = TAU_SEC * (1.0 - decay);
        lastYawStep = vYaw * travel;
        lastPitchStep = vPitch * travel;
        vYaw *= decay;
        vPitch *= decay;
        elapsed += dt;
        if (elapsed >= DURATION_SEC || Math.sqrt(vYaw * vYaw + vPitch * vPitch) < STOP_DEG_PER_SEC) {
            active = false;
            vYaw = 0.0;
            vPitch = 0.0;
        }
        return lastYawStep != 0.0 || lastPitchStep != 0.0;
    }

    public boolean isActive() {
        return active;
    }

    public double yawStep() {
        return lastYawStep;
    }

    public double pitchStep() {
        return lastPitchStep;
    }

    /** Current speed (degrees per second). */
    public double speed() {
        return Math.sqrt(vYaw * vYaw + vPitch * vPitch);
    }
}
