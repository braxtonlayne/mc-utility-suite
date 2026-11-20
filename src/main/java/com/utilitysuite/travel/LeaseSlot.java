package com.utilitysuite.travel;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;

import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holds the (at most one) lease of a travel module and implements the shared lease etiquette:
 * <ul>
 *   <li>{@link #ensure(LeaseRequest)} returns the active lease or requests it. Refused requests are retried at most every
 *       {@value #RETRY_TICKS} ticks so a busy channel does not cause a request (and its {@code Optional}) every tick.</li>
 *   <li>Preemption or manual override marks the slot <i>suspended</i>: no re-request until the arbiter reports the
 *       channels free again ({@code onResumable}), then the module continues on its next tick.</li>
 *   <li>Stop-All and timeouts are terminal: the owning module stops its run (it also gets {@code onStopAll}).</li>
 * </ul>
 * Requests are built once (see {@link #request}) so the steady state allocates nothing. Client thread only.
 */
public final class LeaseSlot implements LeaseListener {

    static final int RETRY_TICKS = 10;

    private final String moduleId;
    private final Supplier<IActionArbiter> arbiter;
    private ControlLease lease;
    private boolean suspended;
    private boolean resumeReady;
    private boolean terminated;
    private LeaseEndReason lastEndReason;
    private String lastEndBy = "";
    private int retryCooldown;

    public LeaseSlot(String moduleId, Supplier<IActionArbiter> arbiter) {
        this.moduleId = moduleId;
        this.arbiter = arbiter;
    }

    /** Builds a reusable request whose listener is this slot. Call once, at construction. */
    public LeaseRequest request(ControlPriority priority, int timeoutTicks, ControlChannel first, ControlChannel... rest) {
        return new LeaseRequest(moduleId, priority, EnumSet.of(first, rest), timeoutTicks, this);
    }

    /**
     * The active lease for {@code request}, requesting it when needed. Returns null while refused, suspended (waiting
     * for {@code onResumable}) or after a terminal end ({@link #terminated()}).
     */
    public ControlLease ensure(LeaseRequest request) {
        ControlLease l = lease;
        if (l != null && l.isActive()) {
            return l;
        }
        lease = null;
        if (terminated || (suspended && !resumeReady)) {
            return null;
        }
        if (retryCooldown > 0) {
            retryCooldown--;
            return null;
        }
        IActionArbiter a = arbiter.get();
        if (a == null || a.isAutomationStopped()) {
            retryCooldown = RETRY_TICKS;
            return null;
        }
        Optional<ControlLease> granted = a.request(request);
        if (granted.isPresent()) {
            lease = granted.get();
            suspended = false;
            resumeReady = false;
            return lease;
        }
        retryCooldown = RETRY_TICKS;
        return null;
    }

    /** The active lease or null (never requests). */
    public ControlLease active() {
        ControlLease l = lease;
        return l != null && l.isActive() ? l : null;
    }

    /** Releases the lease and forgets every suspension / terminal state (a fresh run may start). */
    public void reset() {
        ControlLease l = lease;
        lease = null;
        if (l != null) {
            l.close();
        }
        suspended = false;
        resumeReady = false;
        terminated = false;
        retryCooldown = 0;
    }

    /** True while the lease was taken by a higher priority or the user and is not free again yet. */
    public boolean isSuspended() {
        return suspended && !resumeReady;
    }

    /** True after Stop-All or a timeout ended the lease; the module should end its run. */
    public boolean terminated() {
        return terminated;
    }

    public LeaseEndReason lastEndReason() {
        return lastEndReason;
    }

    public String lastEndBy() {
        return lastEndBy;
    }

    @Override
    public void onPreempted(ControlLease ended, LeaseEndReason reason, String byModuleId) {
        if (ended == lease) {
            lease = null;
        }
        lastEndReason = reason;
        lastEndBy = byModuleId == null ? "" : byModuleId;
        if (reason == LeaseEndReason.STOP_ALL || reason == LeaseEndReason.TIMED_OUT) {
            terminated = true;
            suspended = false;
        } else {
            suspended = true;
            resumeReady = false;
        }
    }

    @Override
    public void onResumable(ControlLease formerLease) {
        resumeReady = true;
        retryCooldown = 0;
    }
}
