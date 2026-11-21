package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Reflex chains (idea A3): one EMERGENCY lease, owned by "autopilot", shared by danger reflexes that follow each other
 * (clutch, face the threat, shield, step out). Because the lease stays the same, a reflex's
 * {@code IInputSmoother.setRotationTarget} retargets the running curve instead of the camera being handed back to the
 * run heading in between. The lease is released {@link #GRACE_TICKS} after the last member calls done (so the next
 * reflex can join), on manual input on one of its channels, on preemption, Stop-All or world unload.
 * <p>
 * The lease covers every channel (MOVEMENT, CAMERA, HOTBAR, WORLD_INTERACT): a lease cannot grow, and a chain may go
 * from a clutch (camera, hotbar, use) to a dodge (movement). Only danger reflexes chain; PARKOUR, PARKOUR_BLOCKS,
 * TOTEM and OFFHAND get null and use their own leases. Client thread; allocation free except the grant itself.
 */
public final class ReflexChain implements LeaseListener {

    public static final String OWNER = Autopilot.ID;
    public static final int GRACE_TICKS = 5;
    /** Safety net: a chain never holds the controls longer than this. */
    public static final int TIMEOUT_TICKS = 200;

    private final TickClock clock;
    private ControlLease lease;
    private int members;
    private long releaseAt = -1;
    private int pausedAtGrant;
    private LeaseRequest request;

    public ReflexChain(TickClock clock) {
        this.clock = clock;
    }

    /** True for the reflexes that may share a chain lease. */
    public static boolean chainable(Reflex r) {
        return switch (r) {
            case FALL_CLUTCH, SHIELD, DODGE, THREAT_ESCAPE, ELYTRA_SWAP -> true;
            case PARKOUR, PARKOUR_BLOCKS, TOTEM, OFFHAND -> false;
        };
    }

    /** The chain's lease for this reflex (granting one when none is held), or null. */
    public ControlLease join(Reflex reflex, IActionArbiter arbiter) {
        if (reflex == null || arbiter == null || !chainable(reflex)) {
            return null;
        }
        if (lease != null && !lease.isActive()) {
            drop();
        }
        if (lease == null) {
            if (arbiter.isAutomationStopped()) {
                return null;
            }
            Optional<ControlLease> granted = arbiter.request(request());
            if (granted.isEmpty()) {
                return null;
            }
            lease = granted.get();
            pausedAtGrant = pausedMask(arbiter);
        }
        members |= 1 << reflex.ordinal();
        releaseAt = -1;
        return lease;
    }

    private LeaseRequest request() {
        LeaseRequest r = request;
        if (r == null) {
            r = new LeaseRequest(OWNER, ControlPriority.EMERGENCY, EnumSet.allOf(ControlChannel.class),
                    TIMEOUT_TICKS, this);
            request = r;
        }
        return r;
    }

    /** A member finished; when it was the last one the lease goes after the grace. */
    public void done(Reflex reflex) {
        if (reflex == null || lease == null) {
            return;
        }
        members &= ~(1 << reflex.ordinal());
        if (members == 0) {
            releaseAt = clock.now() + GRACE_TICKS;
        }
    }

    /** Per tick: grace expiry and manual input. Allocation free. */
    public void tick(IActionArbiter arbiter) {
        if (lease == null) {
            return;
        }
        if (!lease.isActive()) {
            drop();
            return;
        }
        if (members == 0 && releaseAt >= 0 && clock.now() >= releaseAt) {
            release();
            return;
        }
        if (arbiter != null) {
            int paused = pausedMask(arbiter);
            if ((paused & ~pausedAtGrant) != 0) {
                release(); // the user touched a control the chain holds: manual input wins
                return;
            }
            pausedAtGrant &= paused; // a pause that lapsed may be noticed again
        }
    }

    private static int pausedMask(IActionArbiter arbiter) {
        int m = 0;
        ControlChannel[] all = ControlChannel.all();
        for (int i = 0; i < all.length; i++) {
            if (arbiter.isManualOverrideActive(all[i])) {
                m |= 1 << i;
            }
        }
        return m;
    }

    public ControlLease lease() {
        return lease != null && lease.isActive() ? lease : null;
    }

    public boolean isMember(Reflex r) {
        return r != null && (members & (1 << r.ordinal())) != 0;
    }

    /** Releases the lease now (Stop-All, world unload, autopilot off, manual input). */
    public void release() {
        ControlLease l = lease;
        drop();
        if (l != null) {
            l.close();
        }
    }

    private void drop() {
        lease = null;
        members = 0;
        releaseAt = -1;
        pausedAtGrant = 0;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            drop();
        }
    }
}
