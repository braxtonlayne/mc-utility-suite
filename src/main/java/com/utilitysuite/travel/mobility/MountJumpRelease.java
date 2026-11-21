package com.utilitysuite.travel.mobility;

/**
 * Horse jump charge, engine free. The user taps jump on a jumpable mount: the automation holds the jump key itself and
 * releases it at a full bar (the jump-riding scale reaching {@code target}; vanilla's bar peaks at 1.0 after 10 ticks
 * and decays after). A real jump press while the automation holds it, or holding the real key past the tap, gives the
 * charge back to the user (manual input wins).
 */
public final class MountJumpRelease {

    public enum Action { NONE, HOLD, RELEASE }

    /** A real press shorter than this is a tap (start an automated charge); longer is a manual charge. */
    public static final int TAP_TICKS = 4;
    /** Give up holding after this many ticks (the bar decays past its peak). */
    public static final int MAX_HOLD_TICKS = 30;

    private boolean holding;
    private int heldTicks;
    private int realTicks;
    private boolean realWasDown;

    /**
     * @param mountedJumpable riding a mount that can jump, on the ground
     * @param realDown        the user's jump key is physically down
     * @param scale           the jump-riding scale (0..1)
     * @param target          release at or above this (0.9..1.0)
     */
    public Action tick(boolean mountedJumpable, boolean realDown, float scale, float target) {
        boolean pressed = realDown && !realWasDown;
        boolean released = !realDown && realWasDown;
        int pressLength = realTicks;
        realTicks = realDown ? realTicks + 1 : 0;
        realWasDown = realDown;
        if (!mountedJumpable) {
            return stop();
        }
        if (holding) {
            if (pressed) {
                return stop(); // the user pressed again: the charge is theirs
            }
            heldTicks++;
            if (scale >= target || heldTicks > MAX_HOLD_TICKS) {
                return stop();
            }
            return Action.NONE;
        }
        if (realDown && realTicks > TAP_TICKS) {
            return Action.NONE; // a manual charge: leave it alone
        }
        if (released && pressLength <= TAP_TICKS) {
            holding = true;
            heldTicks = 0;
            return Action.HOLD;
        }
        return Action.NONE;
    }

    private Action stop() {
        if (holding) {
            holding = false;
            heldTicks = 0;
            return Action.RELEASE;
        }
        return Action.NONE;
    }

    public boolean holding() {
        return holding;
    }

    public void reset() {
        holding = false;
        heldTicks = 0;
        realTicks = 0;
        realWasDown = false;
    }
}
