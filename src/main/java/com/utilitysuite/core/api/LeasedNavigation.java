package com.utilitysuite.core.api;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link NavigationRun}: requests a lease on MOVEMENT + CAMERA + WORLD_INTERACT (Baritone may break and place
 * blocks while pathing), issues the navigator command, and ties the two together. Used by the default
 * {@link INavigator#goToLeased} / {@link INavigator#followPathLeased} and by navigator implementations that override
 * them to track their current run (see {@code BaritoneAdapter}).
 * <p>
 * Release on completion: {@link #update()} (called once per tick by the navigator implementation, and lazily from
 * {@link #status()} / {@link #isActive()}) releases the lease as soon as the navigator stops pathing. No allocation
 * after {@link #begin}.
 */
public final class LeasedNavigation implements NavigationRun, LeaseListener {

    /** Channels a navigator run needs: Baritone walks, looks around and may break or place blocks. */
    public static final Set<ControlChannel> CHANNELS =
            Set.copyOf(EnumSet.of(ControlChannel.MOVEMENT, ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT));

    private final INavigator navigator;
    private ControlLease lease;
    private boolean ended;
    private NavigationStatus endStatus = NavigationStatus.IDLE;
    private LeaseEndReason endReason;

    private LeasedNavigation(INavigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Requests the lease from {@code arbiter} and, when granted, runs {@code command} (which must start the navigator).
     * Empty when the navigator is unavailable or the lease is refused (higher holder, manual-override pause, Stop-All).
     */
    public static Optional<LeasedNavigation> begin(IActionArbiter arbiter, INavigator navigator, String moduleId,
                                                   ControlPriority priority, Runnable command) {
        if (arbiter == null || navigator == null || !navigator.isAvailable()) {
            return Optional.empty();
        }
        LeasedNavigation run = new LeasedNavigation(navigator);
        Optional<ControlLease> lease = arbiter.request(new LeaseRequest(moduleId, priority, CHANNELS, 0, run));
        if (lease.isEmpty()) {
            return Optional.empty();
        }
        run.lease = lease.get();
        command.run();
        if (navigator.status() != NavigationStatus.PATHING) {
            // Rejected or finished immediately: don't keep the lease.
            run.finish(navigator.status());
        }
        return Optional.of(run);
    }

    /** Releases the lease once the navigator stopped pathing. Cheap and allocation-free; safe to call every tick. */
    public void update() {
        if (ended) {
            return;
        }
        if (!lease.isActive()) {
            // Ended without a callback (e.g. world unload reset): make sure the navigator stops too.
            finish(NavigationStatus.IDLE);
            navigator.cancel();
            return;
        }
        NavigationStatus s = navigator.status();
        if (s != NavigationStatus.PATHING) {
            finish(s);
        }
    }

    /**
     * The navigator started a different command: release this run's lease without cancelling the navigator. Called by
     * navigator implementations that track their current run.
     */
    public void detach() {
        if (!ended) {
            finish(NavigationStatus.IDLE);
        }
    }

    /** True once the run ended (completed, cancelled, superseded or lost its lease). Does not poll the navigator. */
    public boolean isEnded() {
        return ended;
    }

    @Override
    public boolean isActive() {
        update();
        return !ended;
    }

    @Override
    public NavigationStatus status() {
        update();
        return ended ? endStatus : navigator.status();
    }

    @Override
    public LeaseEndReason endReason() {
        return endReason;
    }

    @Override
    public void cancel() {
        if (ended) {
            return;
        }
        finish(NavigationStatus.IDLE);
        navigator.cancel();
    }

    /** The lease this run holds (inactive once the run ended). */
    public ControlLease lease() {
        return lease;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (ended) {
            return;
        }
        ended = true;
        endStatus = NavigationStatus.IDLE;
        endReason = reason;
        navigator.cancel();
    }

    private void finish(NavigationStatus status) {
        ended = true;
        endStatus = status;
        if (lease != null) {
            lease.close();
        }
    }
}
