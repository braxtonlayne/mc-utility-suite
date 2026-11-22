package com.utilitysuite.travel.movement;

/**
 * Jump buffering (opt-in, it delays a user key): a jump press that arrives 1..{@link #maxTicks()} ticks before the
 * last supported tick at an edge is withheld and fired on that last ground tick, so an early press still gets the
 * full run-up. Never withheld longer than the setting, never when no edge is that close, and a jump held on flat
 * ground passes through untouched. Off by default. Engine-free, allocation free.
 */
public final class JumpBuffer {

    public static final int DEFAULT_TICKS = 2;
    public static final int MAX_TICKS = 3;

    private boolean enabled;
    private int maxTicks = DEFAULT_TICKS;
    private boolean prevHeld;
    private boolean pending;
    private int withheld;
    private boolean withholding;
    private boolean fired;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            pending = false;
        }
    }

    public int maxTicks() {
        return maxTicks;
    }

    public void setMaxTicks(int ticks) {
        maxTicks = Math.max(1, Math.min(MAX_TICKS, ticks));
    }

    /**
     * One tick.
     *
     * @param held        the user's jump key this tick
     * @param onGround    the body is on the ground
     * @param ticksToEdge from {@link JumpSearch#ticksToEdge}: 0 = last supported tick, -1 = no edge close
     * @return whether the jump input should be on this tick
     */
    public boolean update(boolean held, boolean onGround, int ticksToEdge) {
        boolean press = held && !prevHeld;
        prevHeld = held;
        withholding = false;
        fired = false;
        if (!enabled) {
            pending = false;
            return held;
        }
        if (pending) {
            if (!onGround || ticksToEdge <= 0 || withheld >= maxTicks) {
                pending = false;
                fired = true;
                return onGround || held;
            }
            withheld++;
            withholding = true;
            return false;
        }
        if (press && onGround && ticksToEdge >= 1 && ticksToEdge <= maxTicks) {
            pending = true;
            withheld = 1;
            withholding = true;
            return false;
        }
        return held;
    }

    /** True on a tick the user's jump key is being held back. */
    public boolean withholding() {
        return withholding;
    }

    /** True on the tick a buffered press was released. */
    public boolean fired() {
        return fired;
    }

    public boolean pending() {
        return pending;
    }

    public void reset() {
        pending = false;
        prevHeld = false;
        withheld = 0;
        withholding = false;
        fired = false;
    }
}
