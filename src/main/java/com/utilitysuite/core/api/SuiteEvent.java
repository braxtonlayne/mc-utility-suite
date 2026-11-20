package com.utilitysuite.core.api;

/** Cross-module event records posted on the {@link ISuiteEventBus}. Posting is rare (state changes, not per tick). */
public sealed interface SuiteEvent {

    /** Wall-clock millis, for the diagnostics timeline. */
    long timeMillis();

    record StopAll(long timeMillis, String reason) implements SuiteEvent {
    }

    record LeaseGranted(long timeMillis, long leaseId, String moduleId, ControlPriority priority) implements SuiteEvent {
    }

    record LeaseEnded(long timeMillis, long leaseId, String moduleId, LeaseEndReason reason, String byModuleId)
            implements SuiteEvent {
    }

    record JobChanged(long timeMillis, JobSnapshot snapshot) implements SuiteEvent {
    }

    record PlayerDied(long timeMillis, long packedPos, String dimension) implements SuiteEvent {
    }

    record VerifiedMiss(long timeMillis, long packedPos, String dimension, String expectedBlockId) implements SuiteEvent {
    }

    /** A server position correction was received. */
    record PositionCorrection(long timeMillis, double dx, double dy, double dz) implements SuiteEvent {
    }

    /** Free-form diagnostics line for the timeline. */
    record Diagnostic(long timeMillis, String source, String message) implements SuiteEvent {
    }
}
