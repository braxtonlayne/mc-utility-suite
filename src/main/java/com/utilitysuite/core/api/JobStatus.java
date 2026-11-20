package com.utilitysuite.core.api;

public enum JobStatus {
    QUEUED,
    PLANNING,
    RUNNING,
    /** Paused by the user, by preemption, or after the replan budget ran out. */
    PAUSED,
    REPLANNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
