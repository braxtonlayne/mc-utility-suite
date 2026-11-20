package com.utilitysuite.travel;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.SimKey;

/**
 * Remembers which keys this module asked the smoother to hold so per-tick driving only calls the smoother on changes.
 * Tracking resets automatically when the lease changes (the core releases a lease's keys when it ends).
 */
public final class HeldKeys {

    private final boolean[] held = new boolean[SimKey.all().length];
    private long leaseId = -1;

    public void set(IInputSmoother input, ControlLease lease, SimKey key, boolean down) {
        if (lease.id() != leaseId) {
            java.util.Arrays.fill(held, false);
            leaseId = lease.id();
        }
        if (held[key.ordinal()] == down) {
            return;
        }
        if (input.setKeyHeld(lease, key, down)) {
            held[key.ordinal()] = down;
        }
    }

    /** Releases every key this module holds under the lease. */
    public void releaseAll(IInputSmoother input, ControlLease lease) {
        if (lease == null) {
            reset();
            return;
        }
        for (SimKey k : SimKey.all()) {
            if (held[k.ordinal()] && lease.id() == leaseId) {
                input.setKeyHeld(lease, k, false);
            }
        }
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(held, false);
        leaseId = -1;
    }
}
