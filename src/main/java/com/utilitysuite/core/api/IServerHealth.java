package com.utilitysuite.core.api;

/**
 * Server health signal (lag spikes, TPS drops, stalled server ticks) for high-risk automation: elytra dives and cruise,
 * bridging, parkour leaps and takeover, and anything else that is dangerous when the server stops answering. While
 * {@link #pauseHighRisk()} is true such drivers hold a safe state (safe pitch, no forward, no jump) instead of
 * pressing on; it turns false again only after the server has been stable for a while (hysteresis in the detector).
 * Client thread only, allocation free. {@link Services#serverHealth()} never returns null.
 */
public interface IServerHealth {

    /** No detector registered (or the guard is off): always healthy. */
    IServerHealth NONE = new IServerHealth() {
        @Override
        public boolean pauseHighRisk() {
            return false;
        }

        @Override
        public double tps() {
            return 20.0;
        }

        @Override
        public long msSinceServerTick() {
            return 0L;
        }
    };

    /** High-risk automation should pause now. */
    boolean pauseHighRisk();

    /** Estimated server ticks per second (20 when unknown). */
    double tps();

    /** Milliseconds since the last server time packet (0 when unknown). */
    long msSinceServerTick();
}
