package com.utilitysuite.core.arbiter;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;

import java.util.Set;

/** The arbiter's lease implementation. Channel coverage is a bit mask so {@link #covers} is allocation free. */
final class Lease implements ControlLease {

    private final ActionArbiter owner;
    private final long id;
    private final String moduleId;
    private final ControlPriority priority;
    private final Set<ControlChannel> channels;
    final int mask;
    final LeaseListener listener;
    final int timeoutTicks;

    int ticksLeft;
    /** Arbiter tick stamp of the last timeout decrement, so a multi-channel lease is counted once per tick. */
    long lastTickStamp = -1;
    boolean active = true;
    LeaseEndReason endReason;

    Lease(ActionArbiter owner, long id, String moduleId, ControlPriority priority, Set<ControlChannel> channels,
          int timeoutTicks, LeaseListener listener) {
        this.owner = owner;
        this.id = id;
        this.moduleId = moduleId;
        this.priority = priority;
        this.channels = channels;
        this.timeoutTicks = timeoutTicks;
        this.ticksLeft = timeoutTicks;
        this.listener = listener;
        int m = 0;
        for (ControlChannel c : channels) {
            m |= 1 << c.ordinal();
        }
        this.mask = m;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public String moduleId() {
        return moduleId;
    }

    @Override
    public ControlPriority priority() {
        return priority;
    }

    @Override
    public Set<ControlChannel> channels() {
        return channels;
    }

    @Override
    public boolean covers(ControlChannel channel) {
        return (mask & (1 << channel.ordinal())) != 0;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /** Why the lease ended, or null while active. */
    public LeaseEndReason endReason() {
        return endReason;
    }

    @Override
    public void close() {
        owner.release(this);
    }

    @Override
    public String toString() {
        return "Lease#" + id + "[" + moduleId + " " + priority + " " + channels + (active ? "" : " ended " + endReason)
                + "]";
    }
}
