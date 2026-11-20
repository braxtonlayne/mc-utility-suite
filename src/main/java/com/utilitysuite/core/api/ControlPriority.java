package com.utilitysuite.core.api;

/**
 * Lease priority. A request with a strictly higher {@link #level()} preempts the current holder of a channel;
 * equal or lower requests are refused. {@link #MANUAL} is never requested by modules: it represents the user's own
 * hardware input and is used by the arbiter's manual-override detection.
 */
public enum ControlPriority {
    MANUAL(100),
    EMERGENCY(90),
    COMBAT(70),
    SURVIVAL(50),
    TRAVEL(40),
    GOAL(30),
    IDLE(0);

    private final int level;

    ControlPriority(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean outranks(ControlPriority other) {
        return level > other.level;
    }
}
