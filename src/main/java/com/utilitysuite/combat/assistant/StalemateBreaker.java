package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.StalemateSearch;
import com.utilitysuite.core.api.IRouteRunner;

/**
 * Breaks a fight in which neither side can or will move in (setting "Break stalemates"): we are in a ravine and the
 * target paces at the top, it stands on a ledge we cannot climb, or it stares at us across water. The
 * {@link StalemateDetector} decides that the fight stalled; then the breaker escalates through what is actually
 * possible, each option with a timeout before the next:
 * <ol>
 *   <li><b>Take the fight to it</b> ({@link Phase#APPROACH}): a walking route ({@link StalemateSearch}, budgeted over a
 *       few ticks) to a cell at the target's level within {@link StalemateSearch#APPROACH_REACH} of it, e.g. along the
 *       ravine floor and up the stairs at its end. The route runner walks it (corners cut, steps jumped, looking where
 *       it runs) under the combat lease; the route is re-planned when the target moves
 *       {@value #REPLAN_DRIFT} blocks. Arriving hands back to the normal fight.</li>
 *   <li><b>Make it come</b> ({@link Phase#MEET}): no route up, so walk to open, flat, hazard-free ground the target
 *       can walk to under its own path limits, at least {@link StalemateSearch#MEET_MIN_DISTANCE} from it, and wait
 *       there facing it until it walks in.</li>
 *   <li><b>A new angle</b> ({@link Phase#BAIT}): a short, bounded reposition to a bearing at least
 *       {@link StalemateSearch#BAIT_MIN_TURN_DEG} degrees round the target, so a stuck opponent re-paths.</li>
 * </ol>
 * Pillaring up is not an option: the core API has no block-placement path for combat (only travel's bridge assist
 * places blocks, behind its own module), so it is never improvised here. When nothing is possible the breaker holds
 * off for {@value #HOLD_OFF_TICKS} ticks and the normal fight runs. A stall that comes back within
 * {@value #RECUR_TICKS} ticks without a hit in between starts at the next option, so one option never loops.
 * <p>
 * The breaker never walks into hazards (the search refuses them), never drops more than three blocks, and moves only
 * through {@link Runner} (the assistant hands its lease to the route runner). Any hit either way, the target coming
 * into reach, or the assistant becoming busy (eating, retreating) ends it at once. Engine free; allocation free per
 * tick. Client thread only.
 */
public final class StalemateBreaker {

    public enum Phase { IDLE, SEARCH, APPROACH, MEET, BAIT, HOLD_OFF }

    /** {@link #drive} answers: the normal fight runs / the route runner drives this tick / hold still facing it. */
    public static final int ACT_NONE = 0;
    public static final int ACT_ROUTE = 1;
    public static final int ACT_HOLD = 2;

    /** Escalation order (search goals). */
    static final int OPTION_APPROACH = StalemateSearch.GOAL_APPROACH;
    static final int OPTION_MEET = StalemateSearch.GOAL_MEET;
    static final int OPTION_BAIT = StalemateSearch.GOAL_BAIT;
    static final int OPTIONS = 3;

    /** Cells the search expands per tick. */
    public static final int SEARCH_BUDGET = 192;
    /** A search that has not finished after this many ticks counts as failed. */
    public static final int SEARCH_TIMEOUT_TICKS = 100;
    /** Approach timeout: a base plus a few ticks per route cell, capped. */
    public static final int APPROACH_BASE_TICKS = 100;
    public static final int APPROACH_TICKS_PER_CELL = 6;
    public static final int APPROACH_MAX_TICKS = 400;
    /** Travel to the meeting ground plus the wait there. */
    public static final int MEET_TIMEOUT_TICKS = 240;
    /** Travel to the new angle plus a short wait there. */
    public static final int BAIT_TIMEOUT_TICKS = 120;
    /** Nothing possible: let the normal fight run this long before looking again. */
    public static final int HOLD_OFF_TICKS = 100;
    /** A stall within this many ticks of the last breaker run, with no hit in between, starts at the next option. */
    public static final int RECUR_TICKS = 300;
    /** Re-plan the approach when the target moved this far from where the route was planned for. */
    public static final double REPLAN_DRIFT = 3.0;
    public static final int REPLAN_MIN_TICKS = 20;
    /** Back to the normal fight once this close (feet to feet) and reachable. */
    public static final double HANDBACK_REACH = 3.6;
    /** Route failures (knocked off, blocked) tolerated per option before the next one. */
    public static final int MAX_ROUTE_FAILS = 2;
    /** Health (half-hearts, absorption included) a deliberate fall must leave; below it only safe drops are used. */
    public static final float FALL_RESERVE_HP = 10f;

    /**
     * How the breaker moves: the assistant's lease handed to the route runner. Implementations release their own keys
     * before the hand-over and take them back on {@link #stop}.
     */
    public interface Runner {
        /** Starts (or replaces) a run along the given route; false when no runner can take it. */
        boolean follow(long[] cells, int count);

        /** The run's state; IDLE when no run of ours is active. */
        IRouteRunner.State state();

        /** Ends our run (no-op when none is active). */
        void stop();
    }

    private final StalemateDetector detector = new StalemateDetector();
    private final StalemateSearch search = new StalemateSearch();

    private Phase phase = Phase.IDLE;
    private int option;
    private int optionTicks;
    private int optionLimit;
    private int searchTicks;
    private int fails;
    /** The approach search already retried with a survivable fall allowed. */
    private boolean fallTried;
    private boolean atSpot;
    private boolean replanning;
    private int sinceSearch;
    private double plannedTx, plannedTy, plannedTz;
    private int holdOff;
    private long clock;
    private long lastEndClock = Long.MIN_VALUE / 2;
    private int lastOption = -1;
    private boolean exchangeSinceEnd;

    // notes for the status line (the assistant formats them on a change of serial)
    private int noteSerial;
    private Phase notePhase = Phase.IDLE;
    private boolean noteFresh;
    private double noteDy;
    private boolean noteFall;
    private int breaks;

    public StalemateDetector detector() {
        return detector;
    }

    /** Feeds the detector; call every engaged tick before {@link #drive}. */
    public void observe(CombatSnapshot s, boolean busy) {
        detector.observe(s, busy);
        if (detector.exchanged()) {
            exchangeSinceEnd = true;
        }
    }

    /**
     * One tick of the breaker.
     *
     * @param enabled    the setting
     * @param stallTicks ticks of stall before acting
     * @return {@link #ACT_NONE}, {@link #ACT_ROUTE} or {@link #ACT_HOLD}
     */
    public int drive(CombatSnapshot s, CombatVoxels w, Runner r, boolean enabled, int stallTicks) {
        clock++;
        if (!enabled || w == null || r == null) {
            if (phase != Phase.IDLE) {
                abort(r);
            }
            return ACT_NONE;
        }
        switch (phase) {
            case IDLE: {
                if (detector.stallTicks() < stallTicks) {
                    return ACT_NONE;
                }
                boolean recur = clock - lastEndClock <= RECUR_TICKS && !exchangeSinceEnd && lastOption >= 0;
                note(Phase.SEARCH, s, true);
                startOption(recur ? lastOption + 1 : OPTION_APPROACH, s);
                return ACT_NONE;
            }
            case HOLD_OFF:
                if (--holdOff <= 0) {
                    phase = Phase.IDLE;
                    lastOption = -1;
                    detector.restart();
                }
                return ACT_NONE;
            default:
                break;
        }
        // an active option: a hit either way or the target in reach ends it
        if (detector.exchanged()) {
            finish(r, true);
            return ACT_NONE;
        }
        if (detector.distance() <= HANDBACK_REACH && !detector.unreachableNow()) {
            finish(r, false);
            return ACT_NONE;
        }
        if (phase == Phase.SEARCH) {
            return searchTick(s, w, r);
        }
        optionTicks++;
        if (optionTicks > optionLimit) {
            r.stop();
            startOption(option + 1, s);
            return ACT_NONE;
        }
        if (phase == Phase.APPROACH) {
            replanTick(s, w, r);
        } else if (phase == Phase.MEET && atSpot && detector.approaching()) {
            finish(r, false); // it walks in: the normal fight meets it
            return ACT_NONE;
        }
        if (atSpot) {
            return ACT_HOLD;
        }
        IRouteRunner.State st = r.state();
        if (st == IRouteRunner.State.ARRIVED) {
            if (phase == Phase.APPROACH) {
                finish(r, false); // at its level within reach: the normal fight takes over
                return ACT_NONE;
            }
            r.stop();
            atSpot = true; // wait there facing it
            return ACT_HOLD;
        }
        if (st == IRouteRunner.State.FAILED || st == IRouteRunner.State.IDLE) {
            r.stop();
            fails++;
            startOption(fails <= MAX_ROUTE_FAILS ? option : option + 1, s); // knocked off: plan again from here
            return ACT_NONE;
        }
        return ACT_ROUTE;
    }

    private int searchTick(CombatSnapshot s, CombatVoxels w, Runner r) {
        if (detector.approaching()) {
            finish(r, false); // it comes on its own while we look: nothing to break
            return ACT_NONE;
        }
        int st = search.step(w, SEARCH_BUDGET);
        if (st == StalemateSearch.RUNNING) {
            if (++searchTicks > SEARCH_TIMEOUT_TICKS) {
                search.cancel();
                startOption(option + 1, s);
            }
            return ACT_NONE;
        }
        if (st == StalemateSearch.NOT_FOUND && option == OPTION_APPROACH && !fallTried) {
            int drop = survivableDrop(s);
            if (drop > StalemateSearch.OUR_MAX_DROP && s.ty - s.py < -StalemateDetector.SAFE_DROP) {
                fallTried = true; // no walking way down: a fall the health can take (stairs are always tried first)
                searchTicks = 0;
                search.begin(OPTION_APPROACH, s.px, s.py, s.pz, s.tx, s.ty, s.tz, drop);
                return ACT_NONE;
            }
        }
        if (st != StalemateSearch.FOUND || search.routeLength() < 2 || !r.follow(search.route(), search.routeLength())) {
            search.cancel();
            startOption(option + 1, s);
            return ACT_NONE;
        }
        phase = option == OPTION_APPROACH ? Phase.APPROACH : (option == OPTION_MEET ? Phase.MEET : Phase.BAIT);
        optionTicks = 0;
        atSpot = false;
        replanning = false;
        sinceSearch = 0;
        int len = search.routeLength();
        optionLimit = option == OPTION_APPROACH
                ? Math.min(APPROACH_MAX_TICKS, APPROACH_BASE_TICKS + APPROACH_TICKS_PER_CELL * len)
                : (option == OPTION_MEET ? MEET_TIMEOUT_TICKS : BAIT_TIMEOUT_TICKS);
        noteFall = option == OPTION_APPROACH && fallTried;
        note(phase, s, fails == 0);
        return ACT_ROUTE;
    }

    /** Approach: re-plan in the background when the target moved; the held route runs until the new one is ready. */
    private void replanTick(CombatSnapshot s, CombatVoxels w, Runner r) {
        sinceSearch++;
        if (!replanning) {
            double dx = s.tx - plannedTx;
            double dy = s.ty - plannedTy;
            double dz = s.tz - plannedTz;
            if (sinceSearch >= REPLAN_MIN_TICKS && dx * dx + dy * dy + dz * dz > REPLAN_DRIFT * REPLAN_DRIFT) {
                beginSearch(OPTION_APPROACH, s);
                replanning = true;
            }
            return;
        }
        int st = search.step(w, SEARCH_BUDGET);
        if (st == StalemateSearch.RUNNING) {
            return;
        }
        replanning = false;
        sinceSearch = 0;
        if (st == StalemateSearch.FOUND && search.routeLength() >= 2) {
            r.follow(search.route(), search.routeLength()); // a refusal keeps the held route
            optionLimit = Math.min(APPROACH_MAX_TICKS,
                    optionTicks + APPROACH_BASE_TICKS + APPROACH_TICKS_PER_CELL * search.routeLength());
        }
    }

    private void startOption(int k, CombatSnapshot s) {
        if (k >= OPTIONS) {
            search.cancel();
            phase = Phase.HOLD_OFF;
            holdOff = HOLD_OFF_TICKS;
            lastEndClock = clock;
            lastOption = OPTIONS - 1;
            exchangeSinceEnd = false;
            note(Phase.HOLD_OFF, s, true);
            return;
        }
        if (k != option) {
            fails = 0;
        }
        option = k;
        phase = Phase.SEARCH;
        searchTicks = 0;
        atSpot = false;
        replanning = false;
        beginSearch(k, s);
    }

    /**
     * Deepest fall (blocks) the current health can take while keeping {@value #FALL_RESERVE_HP} health: fall damage
     * is one point per block past three (armor does not reduce it, so this is the worst case).
     */
    static int survivableDrop(CombatSnapshot s) {
        double spare = s.effectiveHealth() - FALL_RESERVE_HP;
        return spare <= 0 ? StalemateSearch.OUR_MAX_DROP
                : Math.min(StalemateSearch.MAX_FALL_DROP, StalemateSearch.OUR_MAX_DROP + (int) Math.floor(spare));
    }

    private void beginSearch(int goal, CombatSnapshot s) {
        fallTried = false;
        plannedTx = s.tx;
        plannedTy = s.ty;
        plannedTz = s.tz;
        search.begin(goal, s.px, s.py, s.pz, s.tx, s.ty, s.tz);
    }

    private void finish(Runner r, boolean exchanged) {
        if (r != null) {
            r.stop();
        }
        search.cancel();
        if (phase != Phase.SEARCH || exchanged) {
            breaks++;
        }
        lastEndClock = clock;
        lastOption = exchanged ? -1 : option;
        exchangeSinceEnd = false;
        phase = Phase.IDLE;
        atSpot = false;
        replanning = false;
        fails = 0;
        detector.restart();
    }

    /** Ends any running option at once (busy, setting off, target change, disengage). */
    public void abort(Runner r) {
        if (phase != Phase.IDLE && phase != Phase.HOLD_OFF && r != null) {
            r.stop();
        }
        search.cancel();
        phase = Phase.IDLE;
        atSpot = false;
        replanning = false;
        fails = 0;
        detector.restart();
    }

    /** New engagement or new target: forget the stall, the escalation memory and any run. */
    public void reset(Runner r) {
        abort(r);
        detector.reset();
        lastOption = -1;
        lastEndClock = Long.MIN_VALUE / 2;
        exchangeSinceEnd = false;
        holdOff = 0;
    }

    private void note(Phase p, CombatSnapshot s, boolean fresh) {
        notePhase = p;
        noteFresh = fresh;
        noteDy = s.ty - s.py;
        noteSerial++;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** True while an option runs or is being searched (not IDLE, not holding off). */
    public boolean active() {
        return phase != Phase.IDLE && phase != Phase.HOLD_OFF;
    }

    /** Bumped on every status-line worthy change ({@link #notePhase}, {@link #noteDy}). */
    public int noteSerial() {
        return noteSerial;
    }

    /** The approach of the last note drops down a survivable fall (no walking way down was found). */
    public boolean noteFall() {
        return noteFall;
    }

    /** Phase the last note is about (SEARCH = a stall was just detected or the next option is being looked for). */
    public Phase notePhase() {
        return notePhase;
    }

    /** False when the note repeats an option after a route failure (re-planned from a new spot). */
    public boolean noteFresh() {
        return noteFresh;
    }

    /** Target feet minus ours when the note was made. */
    public double noteDy() {
        return noteDy;
    }

    /** Index of the running (or last searched) option: 0 approach, 1 meet, 2 bait. */
    public int option() {
        return option;
    }

    /** Ticks the running option has run. */
    public int optionTicks() {
        return optionTicks;
    }

    /** Options that ended with the stall broken (diagnostics, tests). */
    public int breaks() {
        return breaks;
    }

    /** The search (tests read the route). */
    public StalemateSearch search() {
        return search;
    }
}
