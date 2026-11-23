package com.utilitysuite.travel.movement;

/**
 * Approach and takeoff control (design 2026-11-22, sections 4 and 6.2 "takeoff line"): while a commitment is ARMED on
 * the ground with a locked, safe target, choose the least intervention on the run-up and the takeoff tick that makes
 * the jump land with a stable stop on the target's real top. A full sprint jump onto a near, narrow top cannot be
 * braked enough in the air; the speed has to come off before the takeoff.
 * <p>
 * The family of takeoff plans, cheapest first (each rolled in the exact-shape replica from the current body and
 * verified with the {@link FlightController} arrival family, accelerate then brake or release):
 * <ol>
 *   <li>the user's own keys and their own jump tick (or, when the assist takes the jump, its default edge tick):
 *       output nothing beyond that;</li>
 *   <li>another takeoff tick: the user's press is held back (at most {@link #MAX_WITHHOLD_TICKS}, and only while the
 *       later takeoff is still supported), or the assist's own jump moves within the last supported ticks;</li>
 *   <li>ease W: forward 0 for {@code k} ticks up to and including the takeoff. In vanilla a tick with no forward
 *       impulse ends the sprint on that tick (LocalPlayer.aiStep), so the jump gets no sprint boost; with the sprint
 *       key still held the sprint comes back with W, also in the air. The replica does exactly that;</li>
 *   <li>sprint withheld with W kept (a walking run-up), only when the module can withhold sprint.</li>
 * </ol>
 * The search is spread over ticks: candidates are anchored on absolute ticks (the tick the user pressed jump, or the
 * tick the edge came within {@link #WINDOW_TICKS}), sorted by the tick on which they must start (deadline) and then by
 * cost, and a cursor walks them under {@link #SIM_BUDGET} replica ticks per call. The chosen plan is re-verified from
 * the real body every tick; anything that changes the run-up (keys, yaw, an unexpected body state) restarts the scan.
 * Letting go of W ends everything at once. Engine-free, allocation free (tested).
 */
public final class ApproachController {

    /** The scan for the assist's own jump opens when the edge (user keys) is this many ground ticks away. */
    public static final int WINDOW_TICKS = 8;
    /** The user's jump press is held back at most this many ticks. */
    public static final int MAX_WITHHOLD_TICKS = 4;
    /** Assist jump: takeoff ticks tried after the default edge tick (an eased run-up reaches the edge later). */
    static final int LATE_TICKS = 4;
    /** Eased ticks (forward 0) up to and including the takeoff. */
    static final int[] EASE_TICKS = {1, 2, 3, 4};
    /** Walking ticks (sprint withheld, W kept) up to and including the takeoff. */
    static final int[] WALK_TICKS = {2, 4, 6, 9};
    /** Replica ticks one {@link #update} may roll (scan and re-verify together). */
    public static final int SIM_BUDGET = 1000;
    /**
     * Hard cap on the replica ticks of one {@link #update}: a candidate that would cross it is cut off and rolled again
     * on the next tick. Leaves the rest of {@link MovementAssistBrain#MAX_COMMIT_SIM_TICKS} to the hold's checks.
     */
    public static final int HARD_CAP = 1120;
    private boolean lastTruncated;
    /** A plan's no-key stop point must keep at least this margin inside the target's support region. */
    public static final double MIN_MARGIN = 0.02;
    /** A body this far (blocks) from where the last tick's keys should have put it restarts the scan. */
    static final double DRIFT = 0.01;
    /** A view turned this much (degrees) since the anchor restarts the scan. */
    static final float YAW_DRIFT = 1.0f;

    public static final int KIND_NONE = 0;
    public static final int KIND_EASE = 1;
    public static final int KIND_WALK = 2;

    static final int MAX_CANDIDATES = 96;

    // candidate table (anchor-relative ticks)
    private final int[] cT = new int[MAX_CANDIDATES];
    private final int[] cE = new int[MAX_CANDIDATES];
    private final int[] cKind = new int[MAX_CANDIDATES];
    private final int[] cCost = new int[MAX_CANDIDATES];
    private final int[] cDeadline = new int[MAX_CANDIDATES];
    private int count;
    private int cursor;

    private final MovementPhysics sim = new MovementPhysics();
    private final MovementPhysics shadow = new MovementPhysics();
    private final FlightController flight = new FlightController();

    private long tick;
    private boolean anchored;
    private boolean userCase;
    private long anchorTick;
    private float anchorYaw;
    private int anchorF;
    private int anchorS;
    private boolean anchorSprint;
    private boolean shadowValid;
    private long pressTick = -1;

    // active plan (absolute ticks)
    private boolean active;
    private long planT;
    private long planE;
    private int planKind;
    private int planCost;
    private double planMargin;

    // outputs
    private int forwardOut = MovementAssistBrain.KEEP;
    private boolean sprintOffOut;
    private boolean jumpOut;
    private boolean withholdOut;
    private boolean ownsOut;
    private int simTicks;
    private int evaluated;

    /** Forget everything (commitment left ARMED, W released, target changed, body airborne). */
    public void reset() {
        anchored = false;
        active = false;
        count = 0;
        cursor = 0;
        shadowValid = false;
        pressTick = -1;
        clearOutputs();
    }

    private void clearOutputs() {
        ownsOut = false;
        forwardOut = MovementAssistBrain.KEEP;
        sprintOffOut = false;
        jumpOut = false;
        withholdOut = false;
        simTicks = 0;
        evaluated = 0;
    }

    /**
     * One ground tick while ARMED (GUIDE or CARRY) with a locked, safe target. The caller checks those conditions and
     * calls {@link #reset()} otherwise.
     *
     * @param sprintKey      the user means to sprint (sprint key held / sprinting before an eased tick)
     * @param userJump       the user's jump key is down this tick
     * @param assistMayJump  the assist may take the jump itself (edge jump on)
     * @param allowBack      the flight brake may hold back (CARRY, or GUIDE with the forward axis free)
     * @param walkAllowed    sprint may be withheld with W kept (the module's sprint withhold works)
     * @param reactionTicks  the level's reaction delay (0 = none): no brake earlier in the flight, no ease earlier
     *                       after the user's press
     * @param maxChanges     the level's key-change limit per jump (0 = unlimited): ease and brake together must fit
     */
    public void update(MovementPhysics now, float yaw, boolean sprintKey, int userF, int userS, boolean userJump,
                       boolean assistMayJump, boolean allowBack, boolean walkAllowed, int reactionTicks,
                       int maxChanges, int edgeTicks, SupportRegion region, MovementPhysics.Voxels w) {
        tick++;
        clearOutputs();
        if (!now.onGround || userF <= 0 || region.count() == 0) {
            reset(); // letting go of W always stops; nothing to plan in the air
            return;
        }
        // anything that changed the run-up restarts the scan (the active plan is re-verified below anyway)
        if (anchored) {
            boolean drift = shadowValid && (Math.abs(shadow.x - now.x) > DRIFT || Math.abs(shadow.z - now.z) > DRIFT
                    || Math.abs(shadow.y - now.y) > DRIFT);
            if (drift || userF != anchorF || userS != anchorS || sprintKey != anchorSprint
                    || Math.abs(wrap(yaw - anchorYaw)) > YAW_DRIFT) {
                boolean keepPress = pressTick >= 0;
                long press = pressTick;
                reset();
                if (keepPress && tick - press <= MAX_WITHHOLD_TICKS) {
                    pressTick = press;
                }
            }
        }
        boolean newPress = userJump && pressTick < 0;
        if (newPress) {
            pressTick = tick;
        }
        boolean pressPending = pressTick >= 0 && tick - pressTick <= MAX_WITHHOLD_TICKS;
        if (pressTick >= 0 && !pressPending) {
            pressTick = -1;
        }
        // anchor: the user's press (their jump, moved by at most MAX_WITHHOLD_TICKS), or the coming edge
        if (pressPending && (!anchored || !userCase)) {
            boolean absorbed = active && planT >= tick && planT - pressTick <= MAX_WITHHOLD_TICKS;
            if (!absorbed) {
                anchor(tick - Math.min(MAX_WITHHOLD_TICKS, tick - pressTick), true, 0, yaw, userF, userS, sprintKey,
                        walkAllowed, reactionTicks);
            }
        } else if (!anchored && !pressPending && assistMayJump && edgeTicks >= 0 && edgeTicks <= WINDOW_TICKS) {
            anchor(tick, false, edgeTicks, yaw, userF, userS, sprintKey, walkAllowed, 0);
        }
        if (!anchored) {
            return;
        }
        flight.minBrakeTick = reactionTicks;
        // re-verify the active plan from the real body
        if (active) {
            if (planT < tick || evaluate(now, yaw, sprintKey, userF, userS, allowBack, maxChanges, planT, planE,
                    planKind, region, w) < MIN_MARGIN) {
                active = false;
            }
        }
        // scan: cheaper than the active plan, deadline not passed; a candidate cut off by the hard cap is rolled again
        // next tick (its deadline permitting)
        int scanned = 0;
        while (cursor < count && simTicks < SIM_BUDGET) {
            int i = cursor++;
            long T = anchorTick + cT[i];
            long E = anchorTick + cE[i];
            long deadline = anchorTick + cDeadline[i];
            if (deadline < tick || T < tick || (active && cCost[i] >= planCost)) {
                continue;
            }
            if (userCase && T - pressTick > MAX_WITHHOLD_TICKS) {
                continue;
            }
            double m = evaluate(now, yaw, sprintKey, userF, userS, allowBack, maxChanges, T, E, cKind[i], region, w);
            if (lastTruncated) {
                if (scanned > 0) {
                    cursor = i; // cut off by this tick's cap: roll it again next tick
                }
                break;
            }
            scanned++;
            if (m >= MIN_MARGIN) {
                active = true;
                planT = T;
                planE = E;
                planKind = cKind[i];
                planCost = cCost[i];
                planMargin = m;
            }
        }
        // outputs for this tick
        if (active) {
            boolean eased = planKind != KIND_NONE && tick >= planE;
            if (planKind == KIND_EASE && eased) {
                forwardOut = 0;
            }
            sprintOffOut = planKind == KIND_WALK && eased;
            if (tick == planT) {
                jumpOut = !(userCase && userJump && planKind == KIND_NONE); // the user's own press passes through
                pressTick = -1;
            } else if (userJump) {
                withholdOut = true; // the takeoff is later: hold the press back until then
            }
        } else if (pressPending) {
            // no plan (yet): hold the press only while candidates are left and the takeoff is still supported next
            // tick; otherwise the user's jump goes now, as they pressed it
            boolean waiting = cursor < count && tick - pressTick < MAX_WITHHOLD_TICKS
                    && stillSupportedNextTick(now, yaw, sprintKey, userF, userS, w);
            if (waiting) {
                withholdOut = userJump;
            } else {
                jumpOut = !userJump;
                pressTick = -1;
            }
        }
        ownsOut = active || jumpOut || withholdOut;
        // where the keys of this tick should put the body (drift check next tick)
        int af = forwardOut == MovementAssistBrain.KEEP ? userF : forwardOut;
        boolean aj = jumpOut || (userJump && !withholdOut);
        shadow.copyFrom(now);
        shadow.tick(af, userS, yaw, sprintKey && !sprintOffOut, aj, w);
        simTicks++;
        shadowValid = !aj;
        if (aj) {
            anchored = false; // the takeoff happens on this tick: the flight controller has it from here
            active = false;
        }
    }

    private boolean stillSupportedNextTick(MovementPhysics now, float yaw, boolean sprintKey, int f, int s,
                                           MovementPhysics.Voxels w) {
        sim.copyFrom(now);
        sim.tick(f, s, yaw, sprintKey, false, w);
        simTicks++;
        return sim.onGround;
    }

    private void anchor(long at, boolean user, int edgeTicks, float yaw, int f, int s, boolean sprintKey,
                        boolean walkAllowed, int reactionTicks) {
        anchored = true;
        userCase = user;
        anchorTick = at;
        anchorYaw = yaw;
        anchorF = f;
        anchorS = s;
        anchorSprint = sprintKey;
        active = false;
        count = 0;
        cursor = 0;
        int dDefault = user ? 0 : edgeTicks;
        int dMax = user ? MAX_WITHHOLD_TICKS : edgeTicks + LATE_TICKS;
        int minStart = user ? (int) (tick - at) + reactionTicks : (int) (tick - at);
        for (int dt = 0; dt <= dMax; dt++) {
            int shift = Math.abs(dt - dDefault);
            add(dt, dt + 1, KIND_NONE, shift, dt);
            for (int e : EASE_TICKS) {
                int start = dt - e + 1;
                if (start >= minStart) {
                    add(dt, start, KIND_EASE, 20 + 4 * e + shift, start);
                }
            }
            if (walkAllowed) {
                for (int e : WALK_TICKS) {
                    int start = dt - e + 1;
                    if (start >= minStart) {
                        add(dt, start, KIND_WALK, 60 + 2 * e + shift, start);
                    }
                }
            }
        }
        // insertion sort by (deadline, cost)
        for (int i = 1; i < count; i++) {
            int t = cT[i], e = cE[i], k = cKind[i], c = cCost[i], d = cDeadline[i];
            int j = i - 1;
            while (j >= 0 && (cDeadline[j] > d || (cDeadline[j] == d && cCost[j] > c))) {
                cT[j + 1] = cT[j];
                cE[j + 1] = cE[j];
                cKind[j + 1] = cKind[j];
                cCost[j + 1] = cCost[j];
                cDeadline[j + 1] = cDeadline[j];
                j--;
            }
            cT[j + 1] = t;
            cE[j + 1] = e;
            cKind[j + 1] = k;
            cCost[j + 1] = c;
            cDeadline[j + 1] = d;
        }
    }

    private void add(int t, int e, int kind, int cost, int deadline) {
        if (count >= MAX_CANDIDATES) {
            return;
        }
        cT[count] = t;
        cE[count] = e;
        cKind[count] = kind;
        cCost[count] = cost;
        cDeadline[count] = deadline;
        count++;
    }

    /**
     * Rolls one plan from the real body: the run-up with the plan's keys to the takeoff tick {@code T} (the body must
     * still stand there), the jump, then the flight with the user's keys verified by the arrival family.
     *
     * @return the margin of the best arrival's stop point, or -MAX when the plan does not land
     */
    private double evaluate(MovementPhysics now, float yaw, boolean sprintKey, int userF, int userS, boolean allowBack,
                            int maxChanges, long T, long E, int kind, SupportRegion region, MovementPhysics.Voxels w) {
        evaluated++;
        lastTruncated = false;
        sim.copyFrom(now);
        for (long t = tick; t <= T; t++) {
            if (!sim.onGround) {
                return -Double.MAX_VALUE; // walked off before the takeoff tick
            }
            boolean eased = kind != KIND_NONE && t >= E;
            int f = kind == KIND_EASE && eased ? 0 : userF;
            boolean spr = sprintKey && !(kind == KIND_WALK && eased);
            sim.tick(f, userS, yaw, spr, t == T, w);
            simTicks++;
            if (sim.horizontalCollision) {
                return -Double.MAX_VALUE;
            }
        }
        if (sim.onGround) {
            return -Double.MAX_VALUE; // the jump did not leave the ground (jump delay, a ceiling)
        }
        flight.simCap = Math.max(1, HARD_CAP - simTicks);
        boolean found = flight.plan(sim, userF, userS, yaw, sprintKey, allowBack, region, w);
        lastTruncated = flight.truncated();
        simTicks += flight.simTicks();
        if (!found) {
            return -Double.MAX_VALUE;
        }
        if (maxChanges > 0 && (kind != KIND_NONE ? 1 : 0) + (flight.brakeTick() >= 0 ? 1 : 0) > maxChanges) {
            return -Double.MAX_VALUE;
        }
        return flight.bestMargin();
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    // ---- outputs ----------------------------------------------------------------------------------------------------

    /** Forward axis to apply this tick (0 while easing), or {@link MovementAssistBrain#KEEP}. */
    public int forward() {
        return forwardOut;
    }

    /** Withhold sprint this tick (a walking run-up). */
    public boolean sprintOff() {
        return sprintOffOut;
    }

    /** Take off this tick (the assist's jump, or the user's held-back press). */
    public boolean jump() {
        return jumpOut;
    }

    /** Hold the user's jump key back this tick (the planned takeoff is later). */
    /** The user's press is pending here (held back for the planned takeoff, or not yet placed). */
    public boolean holdsPress() {
        return pressTick >= 0;
    }

    public boolean withholdJump() {
        return withholdOut;
    }

    /** The controller decides this tick's takeoff keys (a plan, a held-back press or a released one). */
    public boolean owns() {
        return ownsOut;
    }

    /** The scan is anchored (a press or the edge window): the controller owns the takeoff decision. */
    public boolean engaged() {
        return anchored;
    }

    /** A verified plan lands on the target. */
    public boolean hasPlan() {
        return active;
    }

    /** Every candidate was rolled and none lands. */
    public boolean exhausted() {
        return anchored && !active && cursor >= count;
    }

    /** Kind of the active plan ({@link #KIND_NONE}, {@link #KIND_EASE}, {@link #KIND_WALK}). */
    public int planKind() {
        return planKind;
    }

    /** Ticks until the planned takeoff (0 = this tick), or -1 without a plan. */
    public int ticksToTakeoff() {
        return active ? (int) (planT - tick) : -1;
    }

    /** Cost of the active plan (0 = the user's or the default takeoff as is). */
    public int planCost() {
        return active ? planCost : -1;
    }

    public double planMargin() {
        return active ? planMargin : -Double.MAX_VALUE;
    }

    /** Replica ticks the last update rolled (at most {@link #SIM_BUDGET} plus one candidate). */
    public int simTicks() {
        return simTicks;
    }

    /** Candidates rolled by the last update. */
    public int evaluated() {
        return evaluated;
    }
}
