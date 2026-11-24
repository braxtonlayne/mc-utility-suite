package com.utilitysuite.core.api;

/** What happened to a lease, as recorded in {@link IArbiterHistory}. */
public enum ArbiterEventKind {
    /** A request was granted. */
    GRANTED("granted"),
    /**
     * A lease was taken away: by a higher request ({@link LeaseEndReason#PREEMPTED}) or by the user's own input
     * ({@link LeaseEndReason#MANUAL_OVERRIDE}).
     */
    PREEMPTED("preempted"),
    /** A request was refused ({@link LeaseDenial} says why). */
    DENIED("denied"),
    /** A lease ended otherwise: released, timed out or revoked by Stop-All. */
    ENDED("ended");

    private final String label;

    ArbiterEventKind(String label) {
        this.label = label;
    }

    /** Constant lowercase label for the HUD (no text built per frame). */
    public String label() {
        return label;
    }
}
