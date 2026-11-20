package com.utilitysuite.core.api;

public enum ObservationKind {
    /** Seen in a loaded chunk. */
    VERIFIED,
    /** Computed from the seed, not yet seen. */
    PREDICTED,
    /** A prediction that was checked and found wrong. */
    VERIFIED_MISS
}
