package com.utilitysuite.core.api;

/**
 * Facts about the user's own hardware input, kept by the core from hardware events only (simulated presses never
 * show up here). Read by modules that share control with the user and must tell a key that was already held from a
 * key that was just pressed. Client thread only; every method is allocation free.
 */
public interface ManualFacts {

    /** Reports nothing held and a still mouse. */
    ManualFacts NONE = new ManualFacts() {
        @Override
        public long tick() {
            return 0L;
        }

        @Override
        public int heldTicks(SimKey key) {
            return 0;
        }

        @Override
        public long lastPressTick(SimKey key) {
            return -1L;
        }

        @Override
        public long lastReleaseTick(SimKey key) {
            return -1L;
        }

        @Override
        public double handYawDegLastTick() {
            return 0.0;
        }
    };

    /** The core's client tick counter that the press and release ticks below are measured on. */
    long tick();

    /** Ticks the hardware key has been down without interruption; 0 while it is up. */
    int heldTicks(SimKey key);

    /** Tick of the last hardware press of the key, or -1 when it was never pressed this session. */
    long lastPressTick(SimKey key);

    /** Tick of the last hardware release of the key, or -1. */
    long lastReleaseTick(SimKey key);

    /** Yaw degrees the user's hand produced during the last tick (+ = yaw increasing), before any gain or co-steer. */
    double handYawDegLastTick();

    /** True when the key is down and has been down since before {@code sinceTick} (a "stale" key). */
    default boolean heldSince(SimKey key, long sinceTick) {
        int held = heldTicks(key);
        return held > 0 && tick() - held < sinceTick;
    }
}
