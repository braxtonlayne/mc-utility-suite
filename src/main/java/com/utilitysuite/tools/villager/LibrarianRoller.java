package com.utilitysuite.tools.villager;

/**
 * Engine-free state machine of the librarian enchantment roller. The module feeds it {@link Facts} each tick and
 * performs the returned {@link Action}; this class decides nothing about the world beyond those facts, so it is unit
 * tested without the game. Allocation free.
 *
 * <p>Cycle: OPEN (interact with the villager) → CHECK (offers read: success, traded-with guard, cycle cap) → BREAK
 * (break the one remembered lectern) → WAIT_UNEMPLOYED (villager drops the profession) → PLACE (lectern back at the
 * same spot) → WAIT_EMPLOYED (villager takes it again) → OPEN ... Each waiting phase has a timeout that stops the run.
 */
public final class LibrarianRoller {

    public enum Phase { IDLE, OPEN, CHECK, BREAK, WAIT_UNEMPLOYED, PLACE, WAIT_EMPLOYED, DONE }

    public enum Action { NONE, INTERACT_VILLAGER, CLOSE_SCREEN, BREAK_LECTERN, PLACE_LECTERN }

    public enum Stop {
        NONE, SUCCESS, TRADED_WITH, MAX_CYCLES, VILLAGER_LOST, NO_LECTERN_ITEM, BREAK_TIMEOUT, PLACE_TIMEOUT,
        PROFESSION_TIMEOUT, OPEN_TIMEOUT, MANUAL
    }

    /** Facts gathered by the module each tick (one reused instance). */
    public static final class Facts {
        public boolean villagerPresent;
        /** The villager currently has the librarian profession. */
        public boolean librarian;
        /** The villager has no profession (unemployed). */
        public boolean unemployed;
        public boolean lecternAtPos;
        public boolean lecternInHotbar;
        /** A trade screen with this villager's offers is open. */
        public boolean offersShown;
        /** Trader xp / level from the open trade screen (valid when offersShown). */
        public int traderXp;
        public int traderLevel;
        /** The wanted book is among the shown offers (valid when offersShown). */
        public boolean wantedOffered;

        public void clear() {
            villagerPresent = librarian = unemployed = lecternAtPos = lecternInHotbar = false;
            offersShown = wantedOffered = false;
            traderXp = 0;
            traderLevel = 0;
        }
    }

    public static final int OPEN_TIMEOUT = 100;
    public static final int BREAK_TIMEOUT = 200;
    public static final int UNEMPLOYED_TIMEOUT = 400;
    public static final int PLACE_TIMEOUT = 100;
    public static final int EMPLOYED_TIMEOUT = 600;

    private Phase phase = Phase.IDLE;
    private Stop stop = Stop.NONE;
    private int cycles;
    private int maxCycles = 50;
    private int ticksInPhase;

    public void start(int maxCycles) {
        this.maxCycles = Math.max(1, maxCycles);
        cycles = 0;
        stop = Stop.NONE;
        enter(Phase.OPEN);
    }

    public void stop(Stop reason) {
        if (phase != Phase.IDLE && phase != Phase.DONE) {
            stop = reason;
            phase = Phase.DONE;
        }
    }

    public Action tick(Facts f) {
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            return Action.NONE;
        }
        ticksInPhase++;
        if (!f.villagerPresent) {
            stop(Stop.VILLAGER_LOST);
            return Action.NONE;
        }
        switch (phase) {
            case OPEN -> {
                if (f.offersShown) {
                    enter(Phase.CHECK);
                    return tick(f);
                }
                if (ticksInPhase > OPEN_TIMEOUT) {
                    stop(Stop.OPEN_TIMEOUT);
                    return Action.NONE;
                }
                return f.librarian ? Action.INTERACT_VILLAGER : Action.NONE;
            }
            case CHECK -> {
                if (!f.offersShown) {
                    enter(Phase.OPEN);
                    return Action.NONE;
                }
                if (f.traderXp > 0 || f.traderLevel > 1) {
                    stop(Stop.TRADED_WITH);
                    return Action.NONE;
                }
                if (f.wantedOffered) {
                    stop(Stop.SUCCESS);
                    return Action.NONE;
                }
                cycles++;
                if (cycles >= maxCycles) {
                    stop(Stop.MAX_CYCLES);
                    return Action.CLOSE_SCREEN;
                }
                enter(Phase.BREAK);
                return Action.CLOSE_SCREEN;
            }
            case BREAK -> {
                if (f.offersShown) {
                    return Action.CLOSE_SCREEN;
                }
                if (!f.lecternAtPos) {
                    enter(Phase.WAIT_UNEMPLOYED);
                    return Action.NONE;
                }
                if (ticksInPhase > BREAK_TIMEOUT) {
                    stop(Stop.BREAK_TIMEOUT);
                    return Action.NONE;
                }
                return Action.BREAK_LECTERN;
            }
            case WAIT_UNEMPLOYED -> {
                if (f.unemployed) {
                    enter(Phase.PLACE);
                    return Action.NONE;
                }
                if (ticksInPhase > UNEMPLOYED_TIMEOUT) {
                    stop(Stop.PROFESSION_TIMEOUT);
                }
                return Action.NONE;
            }
            case PLACE -> {
                if (f.lecternAtPos) {
                    enter(Phase.WAIT_EMPLOYED);
                    return Action.NONE;
                }
                if (!f.lecternInHotbar) {
                    stop(Stop.NO_LECTERN_ITEM);
                    return Action.NONE;
                }
                if (ticksInPhase > PLACE_TIMEOUT) {
                    stop(Stop.PLACE_TIMEOUT);
                    return Action.NONE;
                }
                return Action.PLACE_LECTERN;
            }
            case WAIT_EMPLOYED -> {
                if (f.librarian) {
                    enter(Phase.OPEN);
                    return Action.NONE;
                }
                if (ticksInPhase > EMPLOYED_TIMEOUT) {
                    stop(Stop.PROFESSION_TIMEOUT);
                }
                return Action.NONE;
            }
            default -> {
                return Action.NONE;
            }
        }
    }

    private void enter(Phase p) {
        phase = p;
        ticksInPhase = 0;
    }

    public Phase phase() {
        return phase;
    }

    public boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    public Stop stopReason() {
        return stop;
    }

    public int cycles() {
        return cycles;
    }

    /** Back to IDLE after the stop was announced. */
    public void reset() {
        phase = Phase.IDLE;
    }
}
