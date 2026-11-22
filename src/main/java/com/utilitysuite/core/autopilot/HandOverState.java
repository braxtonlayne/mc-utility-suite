package com.utilitysuite.core.autopilot;

/**
 * The hand-over key's state (feature proposal 1, 2026-11-22): while the key is held the autopilot has the whole
 * body (parkour Takeover, combat on the threat in front, every reflex armed). Engine free; fed once per client tick
 * with the key's state by {@link Autopilot}. It raises authority only: nothing here touches a rotation cap, a reach
 * or the legit level.
 * <ul>
 *     <li>Hold: held from the tick the key goes down to the tick it comes up.</li>
 *     <li>Stop-All ({@link #revoke}): ends it at once; the key must be let go and pressed again.</li>
 *     <li>Optional latch: two presses within {@value #DOUBLE_TAP_TICKS} ticks keep it on without holding; the next
 *     press (or Stop-All) ends it.</li>
 * </ul>
 * All methods are allocation free.
 */
public final class HandOverState {

    public static final long NEVER = -1L;
    public static final int DOUBLE_TAP_TICKS = 6;

    /** {@link #update} results. */
    public static final int NO_CHANGE = 0;
    public static final int STARTED = 1;
    public static final int ENDED = 2;

    private boolean held;
    private boolean latched;
    private boolean revoked;
    private boolean keyWasDown;
    private boolean latchOnDoubleTap;
    private long lastPressTick = NEVER;
    private long startTick = NEVER;
    private long endTick = NEVER;

    /** One client tick. Returns {@link #STARTED} or {@link #ENDED} on the tick the hand-over changes. */
    public int update(boolean keyDown, long now) {
        if (!keyDown) {
            revoked = false; // letting go clears a Stop-All (or an unlatching press)
        } else if (!keyWasDown) {
            if (latched) {
                latched = false;
                revoked = true; // this press only ends the latch
                lastPressTick = NEVER;
            } else {
                if (latchOnDoubleTap && !revoked && lastPressTick != NEVER && now - lastPressTick <= DOUBLE_TAP_TICKS) {
                    latched = true;
                }
                lastPressTick = now;
            }
        }
        keyWasDown = keyDown;
        return set((keyDown && !revoked) || latched, now);
    }

    /** Stop-All: ends the hand-over now. A key that is still down does nothing until it was let go. */
    public int revoke(long now) {
        latched = false;
        revoked = true;
        lastPressTick = NEVER;
        return set(false, now);
    }

    private int set(boolean want, long now) {
        if (want == held) {
            return NO_CHANGE;
        }
        held = want;
        if (want) {
            startTick = now;
            return STARTED;
        }
        endTick = now;
        return ENDED;
    }

    public boolean held() {
        return held;
    }

    /** True while held by the latch (the key itself may be up). */
    public boolean latched() {
        return latched;
    }

    /** The tick the current (or last) hand-over started, {@link #NEVER} before the first. */
    public long startTick() {
        return startTick;
    }

    /** The tick the last hand-over ended, {@link #NEVER} before the first. */
    public long endTick() {
        return endTick;
    }

    public void setLatchOnDoubleTap(boolean on) {
        latchOnDoubleTap = on;
        if (!on && latched) {
            latched = false; // the next update ends it unless the key is down
        }
    }
}
