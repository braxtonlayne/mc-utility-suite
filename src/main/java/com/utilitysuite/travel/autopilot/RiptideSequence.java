package com.utilitysuite.travel.autopilot;

/**
 * Riptide launch state machine (engine free, allocation free). AIM: the caller requests the planned yaw/pitch through
 * the core's InputSmoother (smoothed, speed-capped) and this waits until the camera is within
 * {@link #PITCH_TOLERANCE}; CHARGE: hold use for {@link RiptideLaunchPlanner#MIN_CHARGE_TICKS} plus a margin while still
 * wet; RELEASE: let go of use (the throw happens); RISE: wait for the apex (vertical speed at or below zero), then tap
 * jump to open the elytra if one is worn; GLIDE / DONE hand back to the elytra driver or end.
 */
public final class RiptideSequence {

    public static final double PITCH_TOLERANCE = 6.0;
    /** Extra charge ticks past the vanilla minimum (one tick of slack for the use-item timing). */
    public static final int CHARGE_MARGIN = 2;
    public static final int AIM_TIMEOUT = 30;
    public static final int RISE_TIMEOUT = 60;
    public static final int MAX_DEPLOY_TRIES = 3;

    public enum Phase { AIM, CHARGE, RELEASE, RISE, GLIDE, DONE, ABORTED }

    public enum Action { NONE, HOLD_USE, RELEASE_USE, DEPLOY_JUMP, GLIDE, LANDED, ABORT_DRY, ABORT_NO_TRIDENT, ABORT_AIM }

    private Phase phase = Phase.AIM;
    private int phaseTicks;
    private int deployTries;
    private int cooldown;

    public void reset() {
        to(Phase.AIM);
        deployTries = 0;
        cooldown = 0;
    }

    public Phase phase() {
        return phase;
    }

    /**
     * One tick.
     *
     * @param holdingRiptide main hand holds a Riptide trident
     * @param wet            in water or rain
     * @param pitchError     |camera pitch - planned pitch| + |yaw error| in degrees
     * @param vy             vertical speed
     * @param onGround       standing on ground
     * @param gliding        elytra open
     * @param elytra         an elytra is worn
     */
    public Action step(boolean holdingRiptide, boolean wet, double pitchError, double vy, boolean onGround,
                       boolean gliding, boolean elytra) {
        phaseTicks++;
        if (cooldown > 0) {
            cooldown--;
        }
        switch (phase) {
            case AIM -> {
                if (!holdingRiptide) {
                    return abort(Action.ABORT_NO_TRIDENT);
                }
                if (!wet) {
                    return abort(Action.ABORT_DRY);
                }
                if (pitchError <= PITCH_TOLERANCE) {
                    to(Phase.CHARGE);
                    return Action.HOLD_USE;
                }
                return phaseTicks > AIM_TIMEOUT ? abort(Action.ABORT_AIM) : Action.NONE;
            }
            case CHARGE -> {
                if (!holdingRiptide) {
                    return abort(Action.ABORT_NO_TRIDENT);
                }
                if (!wet) {
                    return abort(Action.ABORT_DRY);
                }
                if (phaseTicks >= RiptideLaunchPlanner.MIN_CHARGE_TICKS + CHARGE_MARGIN) {
                    to(Phase.RELEASE);
                    return Action.RELEASE_USE;
                }
                return Action.HOLD_USE;
            }
            case RELEASE -> {
                to(Phase.RISE);
                return Action.NONE;
            }
            case RISE -> {
                if (gliding) {
                    to(Phase.GLIDE);
                    return Action.GLIDE;
                }
                if (phaseTicks > 2 && onGround) {
                    to(Phase.DONE);
                    return Action.LANDED;
                }
                if (phaseTicks > RISE_TIMEOUT) {
                    to(Phase.DONE);
                    return Action.LANDED;
                }
                if (elytra && phaseTicks > 1 && vy <= 0 && cooldown == 0 && deployTries < MAX_DEPLOY_TRIES) {
                    deployTries++;
                    cooldown = 4;
                    return Action.DEPLOY_JUMP;
                }
                return Action.NONE;
            }
            default -> {
                return Action.NONE;
            }
        }
    }

    private Action abort(Action a) {
        to(Phase.ABORTED);
        return a;
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }
}
