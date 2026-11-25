package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.BlockQuery;

/**
 * Hands-free elytra takeoff, engine-free. Before leaving the ground (or water) a cheap column check makes sure at least
 * {@link #REQUIRED_CLEARANCE} blocks of air are above the head; then: jump (hold jump while in water until out of it),
 * open the elytra with a second jump press in the air, wait for the camera (smoothed by the core) to reach the climb
 * pitch, fire one rocket and hand over to cruise. Aborts cleanly when the column is blocked, no rockets are carried, or
 * the elytra refuses to open after a few tries. No allocation.
 */
final class TakeoffSequence {

    /** Blocks of clear air needed above the head before a ground or water takeoff. */
    static final int REQUIRED_CLEARANCE = 6;
    /** Camera pitch error (degrees) that counts as "pitched up" for the first rocket. */
    static final double PITCH_TOLERANCE = 8.0;
    static final int TAP_COOLDOWN = 6;
    static final int MAX_DEPLOY_TRIES = 3;
    /** Ticks to wait for the climb pitch before firing anyway (the glide must not stall). */
    static final int PITCH_TIMEOUT = 12;
    /** Ticks after the rocket request before cruise takes over. */
    static final int BOOST_TICKS = 10;
    /** Cruise takes over after this many ticks even when the rocket was never fired (crosshair on a block). */
    static final int BOOST_TIMEOUT = 40;

    enum Phase { CHECK, LIFT, DEPLOY, PITCH_UP, BOOST, DONE, ABORTED }

    enum Action {
        NONE, JUMP, HOLD_JUMP, DEPLOY_JUMP, FIRE, CRUISE, ABORT_BLOCKED, ABORT_NO_ROCKETS, ABORT_NO_DEPLOY
    }

    private Phase phase = Phase.CHECK;
    private int phaseTicks;
    private int cooldown;
    private int deployTries;

    void reset() {
        to(Phase.CHECK);
        cooldown = 0;
        deployTries = 0;
    }

    Phase phase() {
        return phase;
    }

    /**
     * Consecutive non-blocking blocks above the head (from {@code feetY + 2} up), counted up to {@code need}.
     *
     * @param solid blocks that stop the climb (air and water do not)
     */
    static int clearAbove(BlockQuery solid, int x, int feetY, int z, int need) {
        int n = 0;
        while (n < need && !solid.blocks(x, feetY + 2 + n, z)) {
            n++;
        }
        return n;
    }

    static boolean clearForTakeoff(BlockQuery solid, int x, int feetY, int z) {
        return clearAbove(solid, x, feetY, z, REQUIRED_CLEARANCE) >= REQUIRED_CLEARANCE;
    }

    /**
     * One tick.
     *
     * @param clear         result of {@link #clearForTakeoff} (only read in the CHECK phase)
     * @param onGround      standing on the ground
     * @param inWater       in water
     * @param airborneTicks ticks since leaving the ground
     * @param vy            vertical speed
     * @param gliding       the elytra is open
     * @param pitchError    |camera pitch - climb pitch| in degrees
     * @param rockets       hotbar rockets
     * @param rocketFired   a rocket was fired since the FIRE action
     */
    Action step(boolean clear, boolean onGround, boolean inWater, int airborneTicks, double vy, boolean gliding,
                double pitchError, int rockets, boolean rocketFired) {
        phaseTicks++;
        if (cooldown > 0) {
            cooldown--;
        }
        switch (phase) {
            case CHECK -> {
                if (gliding) {
                    to(Phase.PITCH_UP);
                    return Action.NONE;
                }
                boolean grounded = onGround || inWater;
                if (grounded && !clear) {
                    to(Phase.ABORTED);
                    return Action.ABORT_BLOCKED;
                }
                if (grounded && rockets <= 0) {
                    to(Phase.ABORTED);
                    return Action.ABORT_NO_ROCKETS;
                }
                to(grounded ? Phase.LIFT : Phase.DEPLOY);
                return Action.NONE;
            }
            case LIFT -> {
                if (gliding) {
                    to(Phase.PITCH_UP);
                    return Action.NONE;
                }
                if (inWater) {
                    return Action.HOLD_JUMP; // swim up until the feet leave the water
                }
                if (onGround) {
                    if (cooldown == 0) {
                        cooldown = TAP_COOLDOWN;
                        return Action.JUMP;
                    }
                    return Action.NONE;
                }
                if (airborneTicks >= 3 && vy < 0.1) {
                    to(Phase.DEPLOY);
                    return deployTap();
                }
                return Action.NONE;
            }
            case DEPLOY -> {
                if (gliding) {
                    to(Phase.PITCH_UP);
                    return Action.NONE;
                }
                if (onGround || inWater) {
                    if (deployTries >= MAX_DEPLOY_TRIES) {
                        to(Phase.ABORTED);
                        return Action.ABORT_NO_DEPLOY;
                    }
                    to(Phase.LIFT);
                    return Action.NONE;
                }
                return cooldown == 0 ? deployTap() : Action.NONE;
            }
            case PITCH_UP -> {
                if (!gliding && (onGround || inWater)) {
                    to(Phase.LIFT);
                    return Action.NONE;
                }
                if (pitchError <= PITCH_TOLERANCE || phaseTicks > PITCH_TIMEOUT) {
                    if (rockets <= 0) {
                        to(Phase.DONE);
                        return Action.CRUISE;
                    }
                    to(Phase.BOOST);
                    return Action.FIRE;
                }
                return Action.NONE;
            }
            case BOOST -> {
                if ((rocketFired && phaseTicks >= BOOST_TICKS) || phaseTicks >= BOOST_TIMEOUT) {
                    to(Phase.DONE);
                    return Action.CRUISE;
                }
                return Action.NONE;
            }
            default -> {
                return Action.NONE;
            }
        }
    }

    private Action deployTap() {
        deployTries++;
        cooldown = TAP_COOLDOWN;
        return Action.DEPLOY_JUMP;
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }
}
