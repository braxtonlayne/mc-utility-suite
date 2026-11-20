package com.utilitysuite.core.api;

import java.util.Optional;

/**
 * Single-owner arbitration over {@link ControlChannel}s. All methods are called on the client thread.
 * <p>
 * Rules: a request is granted when every requested channel is free or held by a strictly lower priority; lower holders
 * are preempted (their whole lease ends, {@link LeaseListener#onPreempted} is called). Manual user input on a channel
 * held below {@link ControlPriority#EMERGENCY} ends that lease with {@link LeaseEndReason#MANUAL_OVERRIDE} and blocks new
 * non-emergency leases on that channel for the configured override pause. {@link #stopAll} revokes everything.
 */
public interface IActionArbiter {

    /** Returns a lease, or empty when refused (higher holder, manual-override pause, or automation stopped). */
    Optional<ControlLease> request(LeaseRequest request);

    /** Releases a lease. Same as {@link ControlLease#close()}. */
    void release(ControlLease lease);

    /** Current holder of a channel, if any. */
    Optional<ControlLease> holder(ControlChannel channel);

    /** True when the module currently owns this channel. */
    boolean isHeldBy(ControlChannel channel, String moduleId);

    /** Reported by input hooks whenever the user produces real input. */
    void onManualInput(ManualInput input);

    /** True while the manual-override pause blocks automation on this channel. */
    boolean isManualOverrideActive(ControlChannel channel);

    /**
     * Emergency stop: revokes every lease ({@link LeaseEndReason#STOP_ALL}), releases all simulated keys, clears
     * rotation targets and puts automation into the stopped state until {@link #resumeAutomation()}.
     */
    void stopAll(String reason);

    boolean isAutomationStopped();

    /** Clears the stopped state so modules may request leases again. */
    void resumeAutomation();

    /** Advances timeouts and override pauses. Called once per client tick by the core. */
    void tick();
}
