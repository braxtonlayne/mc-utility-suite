package com.utilitysuite.core.api;

import java.util.Set;

/**
 * Proof of ownership over one or more {@link ControlChannel}s, granted by {@link IActionArbiter}. Every
 * {@link IInputSmoother} call that changes keys, camera or hotbar takes a lease and is ignored when the lease is no
 * longer active (preempted, expired, released or revoked by Stop-All). Closing a lease releases it.
 */
public interface ControlLease extends AutoCloseable {

    /** Unique, monotonically increasing lease id. */
    long id();

    String moduleId();

    ControlPriority priority();

    /** Channels covered by this lease (unmodifiable). */
    Set<ControlChannel> channels();

    boolean covers(ControlChannel channel);

    /** False once the lease was released, preempted, timed out, or revoked by Stop-All. */
    boolean isActive();

    /** Releases the lease. Idempotent. */
    @Override
    void close();
}
