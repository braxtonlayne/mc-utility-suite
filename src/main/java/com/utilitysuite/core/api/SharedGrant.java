package com.utilitysuite.core.api;

/**
 * Shared control of the MOVEMENT keys (and, when asked for, a bounded yaw co-steer) for one module, granted by
 * {@link ISharedControl}. Unlike a {@link ControlLease} it is not ended by the user's own key presses: the module
 * reads them through {@link ManualFacts} and decides what they mean. Stop-All, a world unload, a screen opening and a
 * higher-priority or EMERGENCY lease on MOVEMENT end it.
 * <p>
 * Every write applies to the next movement input only and lapses after one tick: a module that stops calling leaves
 * the user's input untouched. Primitive arguments, allocation free, client thread only.
 */
public interface SharedGrant {

    /** Leave the user's own state of that axis or key as it is. */
    int KEEP = Integer.MIN_VALUE;
    int ON = 1;
    int OFF = 0;

    boolean isActive();

    /** Forward and strafe for the next tick: {@link #KEEP}, or -1 / 0 / +1 (0 withholds the user's key). */
    void axes(int forward, int strafe);

    /** Jump for the next tick: {@link #KEEP}, {@link #ON} or {@link #OFF} (withhold). */
    void jump(int state);

    /** Sneak for the next tick. False keeps the user's own sneak key; it never lifts a sneak the user holds. */
    void sneak(boolean on);

    /** Starts sprinting on the next tick when vanilla's own conditions allow it. Fill only: it never stops a sprint. */
    void startSprint();

    /**
     * Asks the core to ease the yaw toward {@code idealYawDeg} on top of the user's own mouse motion. Rate and
     * acceleration limited, never faster than {@link IInputSmoother#MAX_TURN_DEG_PER_SEC}, never more than
     * {@code maxTotalDeg} added over the life of this grant, never on the LEGIT level, never with a screen open.
     * Pitch is not touched. Ignored unless the grant was begun with {@code wantsYaw}. Lapses after one tick.
     *
     * @param authority 0..1, scales rate and gain (the hand-back ramp)
     */
    void coSteer(float idealYawDeg, double maxDegPerSec, double maxTotalDeg, double authority);

    /** Degrees of the user's hand motion that opposed the co-steer during this grant (for override detection). */
    double opposingYawDeg();

    /** Ends the grant. Idempotent. */
    void end();
}
