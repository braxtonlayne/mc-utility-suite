package com.utilitysuite.tools.player;

import java.util.function.IntSupplier;

/**
 * Engine-free step machine for the quick pearl key, driven by {@link QuickPearl} once per client tick with the current
 * facts. The returned {@link Action} says what to do through the core input APIs. No allocation.
 *
 * <pre>
 *  IDLE --start--> [SWAP_IN --pearl arrived-->] SELECTED --USE--> THROWN --RESTORE--> RESTORING --FINISH--> IDLE
 * </pre>
 * The throw only counts ({@link Outcome#THROWN}) when the use tap was really issued. When the pearl slot is no longer
 * the selected one at throw time (the selection is not ours alone: pick block, the server, a scroll the arbiter did not
 * see) or the pearl left it, the throw is {@link Outcome#CANCELLED}: the swapped stack goes back, and a selection
 * somebody else changed is left alone. When the lease is lost mid-way (the user scrolled or clicked: manual input wins)
 * nothing more is done under it; a stack that may be swapped in is remembered ({@link #owesSwapBack()}) and put
 * back under a new lease once the user's input pause has passed.
 */
public final class PearlThrow {

    public enum Phase { IDLE, SWAP_IN, SELECTED, THROWN, RESTORING }

    public enum Action {
        NONE,
        /** Tap use now, then report the result with {@link #useTapped}. */
        USE,
        /** Queue the swap back when {@link #owesSwapBack()} (report with {@link #swapBackQueued}) and reselect the
         *  previous slot when {@link #reselect()}. */
        RESTORE,
        /** The run is over: release the lease. */
        FINISH
    }

    public enum Outcome { NONE, THROWN, CANCELLED }

    /** Ticks one run may take before it is cut short. */
    public static final int TIMEOUT = 60;
    /** Ticks between the use tap and the restore. */
    public static final int SETTLE_TICKS = 4;
    /** Ticks a swap back may stay owed before it is given up. */
    public static final int DEBT_TIMEOUT = 200;

    private final PearlPlan plan;
    private final IntSupplier reactionTicks;
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private int wait;
    private int ticks;
    private int debtTicks;
    /** The pearl stack sits in the hotbar slot and the slot's own stack in the main inventory. */
    private boolean swappedIn;
    private boolean swapBackQueued;
    private boolean reselect;
    private boolean notice;

    public PearlThrow(PearlPlan plan, IntSupplier reactionTicks) {
        this.plan = plan;
        this.reactionTicks = reactionTicks;
    }

    public Phase phase() {
        return phase;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** The slot's own stack is still in the main inventory and has to be swapped back. */
    public boolean owesSwapBack() {
        return swappedIn;
    }

    /** With {@link Action#RESTORE}: reselect {@link PearlPlan#restoreSlot}. */
    public boolean reselect() {
        return reselect;
    }

    /** True once after a run ended without a throw (the caller tells the user). */
    public boolean takeNotice() {
        boolean n = notice;
        notice = false;
        return n;
    }

    /** A new run on a fresh plan: the swap-in click is queued, or the pearl slot was selected. */
    public void start() {
        outcome = Outcome.NONE;
        ticks = 0;
        debtTicks = 0;
        swappedIn = false;
        swapBackQueued = false;
        reselect = false;
        notice = false;
        if (plan.needsSwap()) {
            phase = Phase.SWAP_IN;
        } else {
            phase = Phase.SELECTED;
            wait = reactionTicks.getAsInt();
        }
    }

    /**
     * @param selected        the selected hotbar slot right now
     * @param pearlInUseSlot  {@link PearlPlan#useSlot} holds an ender pearl right now
     * @param clicksPending   container clicks still queued
     */
    public Action tick(int selected, boolean pearlInUseSlot, int clicksPending) {
        if (phase == Phase.IDLE) {
            return Action.NONE;
        }
        if (++ticks > TIMEOUT) {
            if (phase == Phase.RESTORING) {
                return finish(clicksPending);
            }
            if (outcome == Outcome.THROWN) {
                reselect = plan.needsSelect();
                phase = Phase.RESTORING;
                return Action.RESTORE;
            }
            return cancel(selected);
        }
        switch (phase) {
            case SWAP_IN -> {
                if (clicksPending == 0 && pearlInUseSlot) {
                    swappedIn = true;
                    phase = Phase.SELECTED;
                    wait = reactionTicks.getAsInt();
                }
                return Action.NONE;
            }
            case SELECTED -> {
                if (--wait > 0) {
                    return Action.NONE;
                }
                if (selected == plan.useSlot && pearlInUseSlot) {
                    return Action.USE;
                }
                return cancel(selected);
            }
            case THROWN -> {
                if (--wait > 0) {
                    return Action.NONE;
                }
                reselect = plan.needsSelect();
                phase = Phase.RESTORING;
                return Action.RESTORE;
            }
            case RESTORING -> {
                if (clicksPending == 0) {
                    return finish(clicksPending);
                }
                return Action.NONE;
            }
            default -> {
                return Action.NONE;
            }
        }
    }

    /** Result of the use tap asked for by {@link Action#USE}. A refused tap cancels the throw. */
    public Action useTapped(boolean accepted, int selected) {
        if (phase != Phase.SELECTED) {
            return Action.NONE;
        }
        if (!accepted) {
            return cancel(selected);
        }
        outcome = Outcome.THROWN;
        phase = Phase.THROWN;
        wait = SETTLE_TICKS;
        return Action.NONE;
    }

    /** Result of the swap-back click asked for by {@link Action#RESTORE} (or by {@link #resumeRestore}). */
    public void swapBackQueued(boolean accepted) {
        swapBackQueued = accepted;
    }

    /**
     * The lease was lost mid-run (manual input, a higher priority, timeout, Stop-All). Nothing more happens under it.
     * The core drops the clicks still queued under a lease that ends, so whether a swap click ran is not known here:
     * a run that swaps stays owed and {@link #debtStillValid} checks it against the inventory before it is paid.
     */
    public void interrupted() {
        if (phase == Phase.IDLE) {
            return;
        }
        swappedIn = plan.needsSwap();
        if (outcome != Outcome.THROWN) {
            outcome = Outcome.CANCELLED;
            notice = true;
        }
        end();
    }

    /**
     * One idle tick with a swap back owed.
     *
     * @return false when the debt has just been given up (too old)
     */
    public boolean debtTick() {
        if (swappedIn && ++debtTicks > DEBT_TIMEOUT) {
            swappedIn = false;
            return false;
        }
        return true;
    }

    /**
     * The owed swap back still matches the inventory, i.e. the stacks really are swapped: the pearl is still in the
     * hotbar slot, or (the last pearl was thrown) that slot is empty and the slot's own stack waits in the main
     * inventory. Anything else means the swap back already ran or the user has moved things since: nothing to undo.
     *
     * @param pearlInFromSlot {@link PearlPlan#swapFromInv} holds an ender pearl
     * @param useSlotEmpty    {@link PearlPlan#useSlot} is empty
     * @param fromSlotEmpty   {@link PearlPlan#swapFromInv} is empty
     */
    public boolean debtStillValid(boolean pearlInUseSlot, boolean pearlInFromSlot, boolean useSlotEmpty,
                                  boolean fromSlotEmpty) {
        if (!swappedIn) {
            return false;
        }
        return pearlInUseSlot
                || (outcome == Outcome.THROWN && !pearlInFromSlot && useSlotEmpty && !fromSlotEmpty);
    }

    /** A new lease was granted for the owed swap back and its click was (or was not) queued. */
    public void resumeRestore(boolean clickQueued) {
        phase = Phase.RESTORING;
        ticks = 0;
        reselect = false;
        swapBackQueued = clickQueued;
    }

    /** Forgets everything (the player left the world, or the debt no longer matches the inventory). */
    public void reset() {
        phase = Phase.IDLE;
        outcome = Outcome.NONE;
        swappedIn = false;
        swapBackQueued = false;
        reselect = false;
        notice = false;
        debtTicks = 0;
    }

    private Action cancel(int selected) {
        outcome = Outcome.CANCELLED;
        notice = true;
        // a selection somebody else moved off the pearl slot is theirs: only undo our own
        reselect = plan.needsSelect() && selected == plan.useSlot;
        phase = Phase.RESTORING;
        return Action.RESTORE;
    }

    private Action finish(int clicksPending) {
        if (swapBackQueued && clicksPending == 0) {
            swappedIn = false;
        }
        end();
        return Action.FINISH;
    }

    private void end() {
        phase = Phase.IDLE;
        swapBackQueued = false;
        reselect = false; // debtTicks keeps counting across retried swap backs
    }
}
