package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ManualFacts;
import com.utilitysuite.core.api.SimKey;

/**
 * Facts about the user's own hardware input ({@link ManualFacts}), fed only by {@link ManualInputHooks} from
 * {@code InputEvent.Key} / {@code InputEvent.MouseButton.Pre} and the raw mouse turn. Automation changes key states
 * through {@code KeyMapping.setDown}, which fires none of those events, so simulated presses never show up here.
 * Engine free (the hooks map events to {@link SimKey}s) and allocation free. Client thread only.
 *
 * <h2>Tick counting</h2>
 * {@link #beginTick} (ClientTickEvent.Pre) advances the counter. Hardware events arrive between ticks, so a press
 * stamped with tick {@code t} is first seen by the game's movement input in tick {@code t + 1}, where
 * {@link #heldTicks} reads 1. {@code heldTicks = tick - pressTick} (at least 1 while down), so the contract's
 * {@code heldSince(key, s)} means "pressed before tick s".
 * <p>
 * A screen takes the keys away from the game: presses are not counted while one is open, releases always are, and
 * {@link #releaseAll} (called at every tick start while a screen is open) marks every key up. A key still physically
 * held when the screen closes counts as up until it is pressed again (vanilla re-reads it, but no hardware event fires).
 */
public final class ManualFactsTracker implements ManualFacts {

    private static final SimKey[] KEYS = SimKey.all();

    private final boolean[] down = new boolean[KEYS.length];
    private final long[] pressTick = new long[KEYS.length];
    private final long[] releaseTick = new long[KEYS.length];

    private long tick;
    private double handYawThisTick;
    private double handYawLastTick;

    public ManualFactsTracker() {
        for (int i = 0; i < KEYS.length; i++) {
            pressTick[i] = -1L;
            releaseTick[i] = -1L;
        }
    }

    // ---- feed -------------------------------------------------------------------------------------------------------

    /** ClientTickEvent.Pre: advances the tick counter and rolls the hand yaw over. */
    public void beginTick() {
        tick++;
        handYawLastTick = handYawThisTick;
        handYawThisTick = 0.0;
    }

    /** A hardware press (not a repeat). A press of a key that is already down restarts it (a lost release). */
    public void onPress(SimKey key) {
        int i = key.ordinal();
        down[i] = true;
        pressTick[i] = tick;
    }

    /** A hardware release. Ignored for a key that is not down. */
    public void onRelease(SimKey key) {
        int i = key.ordinal();
        if (down[i]) {
            down[i] = false;
            releaseTick[i] = tick;
        }
    }

    /** Every held key counts as released now (screen open, world left). */
    public void releaseAll() {
        for (int i = 0; i < KEYS.length; i++) {
            if (down[i]) {
                down[i] = false;
                releaseTick[i] = tick;
            }
        }
    }

    /** Yaw degrees the hand produced in one mouse frame, before turn gain or co-steer. */
    public void addHandYaw(double deg) {
        if (Double.isFinite(deg)) {
            handYawThisTick += deg;
        }
    }

    /** Yaw degrees the hand produced so far in the current tick. */
    public double handYawDegThisTick() {
        return handYawThisTick;
    }

    /** True while the hardware key is down. */
    public boolean isDown(SimKey key) {
        return down[key.ordinal()];
    }

    // ---- contract ---------------------------------------------------------------------------------------------------

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public int heldTicks(SimKey key) {
        int i = key.ordinal();
        if (!down[i]) {
            return 0;
        }
        long held = tick - pressTick[i];
        return held < 1L ? 1 : (held > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) held);
    }

    @Override
    public long lastPressTick(SimKey key) {
        return pressTick[key.ordinal()];
    }

    @Override
    public long lastReleaseTick(SimKey key) {
        return releaseTick[key.ordinal()];
    }

    @Override
    public double handYawDegLastTick() {
        return handYawLastTick;
    }
}
