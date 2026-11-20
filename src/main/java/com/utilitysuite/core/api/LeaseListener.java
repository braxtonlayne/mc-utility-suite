package com.utilitysuite.core.api;

/**
 * Callbacks for a lease holder. Called on the client thread. Implementations must be cheap and must not request new
 * leases from inside {@link #onPreempted}; schedule that for the next tick instead.
 */
public interface LeaseListener {

    /**
     * The lease ended involuntarily (preempted by a higher priority, manual override, timeout or Stop-All). The module
     * should pause and remember a checkpoint so it can resume later.
     *
     * @param byModuleId the preempting module id, {@code "manual"} for user input, or {@code "stop_all"}
     */
    void onPreempted(ControlLease lease, LeaseEndReason reason, String byModuleId);

    /**
     * The channels this module lost through preemption or manual override are free again (the higher-priority holder
     * released them, or the manual-override pause elapsed). Not called after Stop-All or timeout.
     */
    default void onResumable(ControlLease formerLease) {
    }

    LeaseListener NONE = (lease, reason, by) -> { };
}
