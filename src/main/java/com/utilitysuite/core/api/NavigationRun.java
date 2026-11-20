package com.utilitysuite.core.api;

/**
 * One navigator command started with {@link INavigator#goToLeased} / {@link INavigator#followPathLeased}. The run owns
 * a {@link ControlLease} on {@link LeasedNavigation#CHANNELS} for as long as the navigator executes its command: the
 * lease is released when the command arrives, fails, is cancelled or is superseded by another navigator command, and
 * the navigator is cancelled when the lease is preempted, overridden by manual input, timed out or revoked by
 * Stop-All. Client thread only.
 */
public interface NavigationRun {

    /** True while the run holds its lease and its command is still the navigator's current command. */
    boolean isActive();

    /** Navigator status while active; the final status once the run ended ({@link NavigationStatus#IDLE} if cut short). */
    NavigationStatus status();

    /** Why the lease ended involuntarily (preempted, manual override, timeout, Stop-All), or null. */
    LeaseEndReason endReason();

    /** Cancels the navigator (if this run is still current) and releases the lease. Idempotent. */
    void cancel();
}
