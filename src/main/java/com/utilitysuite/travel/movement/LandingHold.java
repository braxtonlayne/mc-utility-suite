package com.utilitysuite.travel.movement;

/**
 * The landing hold (design 2026-11-22, sections 5.2 and 5.3), used in every control mode. It replaces the 4-tick sneak
 * tap of the earlier brain, which ended on a timer, on any key change and when the authorship budget was spent, and
 * then let the W still held from the flight walk the body off a fence post.
 * <ul>
 *   <li><b>Sneak guard</b>: on a predicted narrow landing sneak goes down on the last air tick and stays down on the
 *       ground; vanilla's edge back-off then keeps the body on the real top.</li>
 *   <li><b>Stale-key latch</b>: at the touchdown of a narrow landing every movement key that was already down on the
 *       last air tick is latched off, per key, until that key is released. There is no timer on the latch, no budget
 *       gate, nothing that skips it: a key that was down in the air cannot walk the body off a narrow top, and
 *       releasing and pressing it again always can.</li>
 *   <li><b>Release</b> ({@link Release#FRESH_INPUT}, default): the sneak lifts once the body is stable and a fresh
 *       movement key press or a jump arrived; after {@link #holdLimitTicks} stable ticks it lifts anyway (the latch
 *       stays). {@link Release#TIMER}: the sneak lifts {@link #holdLimitTicks} ticks after touchdown (latch stays).</li>
 *   <li>Never on wide landings, never when the user sneaks (that hands everything back) or holds jump for a chain.</li>
 * </ul>
 * Keys are bit masks by {@code SimKey.ordinal()}: {@link #FORWARD}, {@link #BACK}, {@link #LEFT}, {@link #RIGHT},
 * {@link #JUMP}. Engine-free, allocation free; one {@link #update} per client tick.
 */
public final class LandingHold {

    public enum Release { FRESH_INPUT, TIMER }

    public static final int FORWARD = 1;
    public static final int BACK = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int MOVE_MASK = FORWARD | BACK | LEFT | RIGHT;
    public static final int DEFAULT_HOLD_LIMIT = 12;
    /** Ground ticks after which the sneak lifts even when the body never became stable (ice, knockback). */
    public static final int MAX_HOLD_TICKS = 60;

    /** Setting {@code landing_hold}. */
    public boolean enabled = true;
    public Release release = Release.FRESH_INPUT;
    /** Setting {@code hold_limit_ticks}: stable ticks (FRESH_INPUT) or ground ticks (TIMER) before the sneak lifts. */
    public int holdLimitTicks = DEFAULT_HOLD_LIMIT;

    private boolean active;
    private int latched;
    private int prevKeys;
    private boolean prevOnGround = true;
    private boolean freshSeen;
    private int groundTicks;
    private int stableTicks;
    private boolean sneak;
    private boolean startedThisTick;

    /**
     * One tick.
     *
     * @param onGround     the body is on the ground this tick
     * @param keys         the user's raw keys this tick (bit mask, {@link #JUMP} included)
     * @param userSneak    the user holds sneak themselves
     * @param narrowAhead  airborne and the user's keys touch down next tick on a narrow landing (sneak goes down now)
     * @param narrowHere   on the touchdown tick: the landing is narrow (ignored on other ticks)
     * @param stable       on the ground: the body is stable on its top ({@link SupportRegion#stable})
     */
    public void update(boolean onGround, int keys, boolean userSneak, boolean narrowAhead, boolean narrowHere,
                       boolean stable) {
        sneak = false;
        startedThisTick = false;
        boolean touchedDown = onGround && !prevOnGround;
        boolean jumpHeld = (keys & JUMP) != 0;
        if (!enabled || userSneak) {
            // sneak hands everything back; a switched-off hold holds nothing
            active = false;
            latched = 0;
        } else if (!onGround) {
            active = false;
            latched = 0;
            sneak = narrowAhead && !jumpHeld;
        } else {
            if (touchedDown && narrowHere && !jumpHeld) {
                active = true;
                startedThisTick = true;
                latched = keys & prevKeys & MOVE_MASK;
                freshSeen = false;
                groundTicks = 0;
                stableTicks = 0;
            }
            latched &= keys; // a released key is free again; its next press is fresh
            int fresh = keys & ~prevKeys & MOVE_MASK;
            if (active) {
                groundTicks++;
                if (fresh != 0) {
                    freshSeen = true;
                }
                if (stable) {
                    stableTicks++;
                }
                if (jumpHeld && !startedThisTick) {
                    // the next jump starts: everything is the user's again
                    active = false;
                    latched = 0;
                } else if (release == Release.TIMER) {
                    active = groundTicks <= holdLimitTicks;
                } else if ((stable && freshSeen) || stableTicks > holdLimitTicks || groundTicks > MAX_HOLD_TICKS) {
                    active = false; // the latch stays until each latched key is released
                }
            } else if (jumpHeld && (keys & ~prevKeys & JUMP) != 0) {
                latched = 0;
            }
            sneak = active;
        }
        prevKeys = keys;
        prevOnGround = onGround;
    }

    /** Forget everything (teleport, world change, Stop-All, a screen). */
    public void reset() {
        active = false;
        latched = 0;
        prevKeys = 0;
        prevOnGround = true;
        freshSeen = false;
        groundTicks = 0;
        stableTicks = 0;
        sneak = false;
    }

    /** Sneak this tick (the guard). */
    public boolean sneak() {
        return sneak;
    }

    /** The hold (sneak guard) is on. */
    public boolean active() {
        return active;
    }

    /** Keys held back until released (bit mask by {@code SimKey.ordinal()}). */
    public int latched() {
        return latched;
    }

    /** The hold began on this tick's touchdown. */
    public boolean startedThisTick() {
        return startedThisTick;
    }

    /** Forward axis after the latch: -1, 0, 1. */
    public int forwardAxis(int keys) {
        int k = keys & ~latched;
        return ((k & FORWARD) != 0 ? 1 : 0) - ((k & BACK) != 0 ? 1 : 0);
    }

    /** Strafe axis after the latch: +1 left, -1 right. */
    public int strafeAxis(int keys) {
        int k = keys & ~latched;
        return ((k & LEFT) != 0 ? 1 : 0) - ((k & RIGHT) != 0 ? 1 : 0);
    }

    /** Key mask from axes and the jump flag (scripted drivers and the brain's int-axis callers). */
    public static int keys(int forward, int strafe, boolean jump) {
        return (forward > 0 ? FORWARD : 0) | (forward < 0 ? BACK : 0) | (strafe > 0 ? LEFT : 0)
                | (strafe < 0 ? RIGHT : 0) | (jump ? JUMP : 0);
    }
}
