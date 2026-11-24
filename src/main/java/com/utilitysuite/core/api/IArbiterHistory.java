package com.utilitysuite.core.api;

/**
 * Read-only view of the arbiter's recent lease events: grants, preemptions (manual overrides included), denials and
 * ends, newest first ({@code i = 0}). Backed by fixed parallel arrays in a ring buffer, so recording and reading
 * allocate nothing; owner and "by" ids are strings the arbiter already holds (module ids and its constant
 * {@code manual} / {@code stop_all} / {@code timeout} tags). Client thread only.
 * <p>
 * Consecutive identical events (a module retrying a refused request every tick) fold into one entry whose
 * {@link #repeats} counts them and whose {@link #tick} is the latest.
 */
public interface IArbiterHistory {

    /** Entries kept by the arbiter's history. */
    int CAPACITY = 16;

    /** No history (fakes, an arbiter that does not record). */
    IArbiterHistory EMPTY = new IArbiterHistory() {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public long revision() {
            return 0L;
        }

        @Override
        public long now() {
            return 0L;
        }

        @Override
        public long tick(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public ArbiterEventKind kind(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public String owner(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public int channelMask(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public LeaseEndReason endReason(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public LeaseDenial denial(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public String by(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public int repeats(int i) {
            throw new IndexOutOfBoundsException(i);
        }
    };

    /** Entries held: at most {@link #CAPACITY} for the main history, at most 1 for {@link #lastStop}. */
    int size();

    /** Increases with every recorded (or folded) event: a cheap "did anything change" check. */
    long revision();

    /** The arbiter's current tick, on the same clock as {@link #tick}. */
    long now();

    /** Arbiter tick of entry {@code i} (0 = newest). */
    long tick(int i);

    ArbiterEventKind kind(int i);

    /** Module id that owned (or asked for) the lease. */
    String owner(int i);

    /**
     * Channels involved, as bits {@code 1 << ControlChannel.ordinal()}: the lease's channels for grants, preemptions
     * and ends; the channel that blocked the request for a denial (every requested channel when automation was
     * stopped).
     */
    int channelMask(int i);

    /** The end reason for {@link ArbiterEventKind#PREEMPTED} and {@link ArbiterEventKind#ENDED}, else null. */
    LeaseEndReason endReason(int i);

    /** Why a {@link ArbiterEventKind#DENIED} request was refused, else null. */
    LeaseDenial denial(int i);

    /**
     * Who caused it: the winning module for a preemption, the holder for a denial, the arbiter's constant
     * {@code manual} / {@code stop_all} / {@code timeout} tag, or the owner itself for a release. Empty for a grant.
     */
    String by(int i);

    /** How many identical consecutive events this entry stands for (1 or more). */
    int repeats(int i);

    /**
     * The last event that stopped or blocked automation (a preemption, a manual override, a denial, a timeout or
     * Stop-All), kept apart so a burst of ordinary grants and releases cannot push it out. Size 0 or 1.
     */
    default IArbiterHistory lastStop() {
        return EMPTY;
    }

    /** Constant label for entry {@code i}: the denial's, the end reason's, or else the kind's. Allocation free. */
    default String reasonLabel(int i) {
        LeaseDenial d = denial(i);
        if (d != null) {
            return d.label();
        }
        LeaseEndReason r = endReason(i);
        if (r == null) {
            return kind(i).label();
        }
        if (r == LeaseEndReason.MANUAL_OVERRIDE) {
            return "manual override";
        }
        if (r == LeaseEndReason.TIMED_OUT) {
            return "timed out";
        }
        if (r == LeaseEndReason.STOP_ALL) {
            return "stop-all";
        }
        return r == LeaseEndReason.PREEMPTED ? "preempted" : "released";
    }
}
