package com.utilitysuite.core.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A request for ownership of channels. All requested channels are granted atomically or not at all.
 *
 * @param timeoutTicks lease auto-expires after this many client ticks; {@code 0} means no timeout
 */
public record LeaseRequest(String moduleId, ControlPriority priority, Set<ControlChannel> channels, int timeoutTicks,
                           LeaseListener listener) {

    public LeaseRequest {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(priority, "priority");
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("A lease needs at least one channel");
        }
        if (priority == ControlPriority.MANUAL) {
            throw new IllegalArgumentException("MANUAL priority is reserved for user input");
        }
        if (timeoutTicks < 0) {
            throw new IllegalArgumentException("timeoutTicks < 0");
        }
        channels = Set.copyOf(EnumSet.copyOf(channels));
        listener = listener == null ? LeaseListener.NONE : listener;
    }

    public static LeaseRequest of(String moduleId, ControlPriority priority, LeaseListener listener,
                                  ControlChannel first, ControlChannel... rest) {
        return new LeaseRequest(moduleId, priority, EnumSet.of(first, rest), 0, listener);
    }

    public LeaseRequest withTimeout(int ticks) {
        return new LeaseRequest(moduleId, priority, channels, ticks, listener);
    }
}
