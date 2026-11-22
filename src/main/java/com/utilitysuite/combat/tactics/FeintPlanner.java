package com.utilitysuite.combat.tactics;

/**
 * Conditioning and feints. Watches how the opponent aims at us (does its facing lead our strafe, i.e. is it
 * pre-aiming the pattern we show?) and how it answers a backpedal (does it sprint in?), then proposes small breaks:
 * reverse the strafe early once the opponent pre-aims it, or backpedal to draw a sprint-in and step back in on its
 * cooldown. Keys only. Engine free, allocation free after construction, up to {@value #MAX_OPPONENTS} opponents.
 * <p>
 * Reads (per opponent, kept across fights until {@link #clear}):
 * <ul>
 *   <li><b>aim lead</b>: the signed angle between the opponent's facing and the bearing to us, positive in the
 *       direction we move around it, divided by our angular speed as seen from it (relative velocity). EMA,
 *       clamped to +-{@value #LEAD_CLAMP} ticks per sample.</li>
 *   <li><b>pre-aim</b>: EMA of "lead within [{@value #PRE_AIM_MIN}, {@value #PRE_AIM_MAX}] ticks" over the samples
 *       taken while we held one strafe direction for {@value #HOLD_TICKS}+ ticks.</li>
 *   <li><b>bait rate</b>: after {@value #BACKPEDAL_TICKS} ticks of moving away from it, did it sprint toward us within
 *       {@value #BAIT_WINDOW} ticks? Mean of the first trials, then an EMA.</li>
 * </ul>
 * Plan state machine (one feint at a time, at least {@value #MIN_GAP} + up to {@value #GAP_JITTER} random ticks between
 * the end of one feint and the start of the next, and an eligible tick only starts a feint with probability
 * {@value #START_CHANCE}, so feints are never periodic): NONE -> BREAK_PATTERN -> NONE, NONE -> BAIT_RETREAT -> (PUNISH)
 * -> NONE. See {@code integration/opponent-reads.notes.md}.
 */
public final class FeintPlanner {

    public static final int MAX_OPPONENTS = 8;
    /** Returned by the override getters when the planner leaves that axis to the caller. */
    public static final int KEEP = Integer.MIN_VALUE;

    /** Strafe held this long before the pattern counts as "shown". */
    public static final int HOLD_TICKS = 8;
    /** Aim lead range (ticks) counted as pre-aiming. */
    public static final double PRE_AIM_MIN = 0.5;
    public static final double PRE_AIM_MAX = 3.0;
    /** Per-sample clamp on the measured lead. */
    public static final double LEAD_CLAMP = 6.0;
    /** Below this angular speed (deg/tick, as seen from the opponent) the lead is not measurable. */
    public static final double MIN_ANGULAR_SPEED = 0.5;
    public static final double LEAD_ALPHA = 0.15;
    public static final double PRE_AIM_ALPHA = 0.1;
    public static final double PRE_AIM_TRIGGER = 0.6;

    /** Ticks of moving away before a backpedal counts, and the window for the sprint-in answer. */
    public static final int BACKPEDAL_TICKS = 4;
    public static final int BAIT_WINDOW = 10;
    /** Our speed away from it (blocks/tick) that counts as a backpedal; its closing speed that counts as a run-in. */
    public static final double AWAY_SPEED = 0.06;
    public static final double CLOSING_SPEED = 0.1;
    public static final double BAIT_ALPHA = 0.25;
    public static final double BAIT_TRIGGER = 0.5;

    /** Feint pacing. */
    public static final int MIN_GAP = 40;
    public static final int GAP_JITTER = 40;
    public static final double START_CHANCE = 0.25;
    /** Chance per eligible tick to test an opponent whose bait answer is still unknown. */
    public static final double UNTESTED_BAIT_CHANCE = 0.02;
    public static final int BREAK_MIN = 6;
    public static final int BREAK_SPAN = 5; // 6..10
    public static final int BAIT_MIN = 5;
    public static final int BAIT_SPAN = 4; // 5..8
    public static final int PUNISH_TICKS = 5;
    public static final double BAIT_MIN_DIST = 2.5;
    public static final double BAIT_MAX_DIST = 4.0;
    public static final double COMMIT_DIST = 3.2;

    public enum Move {
        NONE,
        /** Reverse our strafe now: the opponent is aiming where the shown pattern would put us. */
        BREAK_PATTERN,
        /** Backpedal (no sprint) to draw a sprint-in. */
        BAIT_RETREAT,
        /** The opponent took the bait and swung or over-committed: step in now. */
        PUNISH
    }

    private static final byte TRIAL_NONE = 0;
    private static final byte TRIAL_OPEN = 1;
    private static final byte TRIAL_DONE = 2;

    // per opponent: learned habits
    private final int[] ids = new int[MAX_OPPONENTS];
    private final long[] lastSeen = new long[MAX_OPPONENTS];
    private final double[] aimLead = new double[MAX_OPPONENTS];
    private final int[] leadSamples = new int[MAX_OPPONENTS];
    private final double[] preAim = new double[MAX_OPPONENTS];
    private final double[] baitRate = new double[MAX_OPPONENTS];
    private final int[] baitTrials = new int[MAX_OPPONENTS];
    // per opponent: fight-scoped
    private final int[] backpedal = new int[MAX_OPPONENTS];
    private final byte[] trial = new byte[MAX_OPPONENTS];
    private final long[] trialEnd = new long[MAX_OPPONENTS];
    private final long[] sprintInTick = new long[MAX_OPPONENTS];

    // our strafe run (shared by all opponents, counted once per tick)
    private int strafeDir;
    private int strafeHeld;
    private long strafeTick;

    // running feint
    private Move state = Move.NONE;
    private int stateId;
    private long stateEnd;
    private int breakDir;
    private long nextAllowed;
    private int feintCount;

    private int strafeOv = KEEP;
    private int forwardOv = KEEP;
    private boolean sprintOk = true;

    public FeintPlanner() {
        clear();
    }

    // ------------------------------------------------------------------ slots

    private int slotOf(int id) {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private int slotFor(int id, long tick) {
        int s = slotOf(id);
        if (s >= 0) {
            return s;
        }
        int oldest = 0;
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            if (ids[i] == -1) {
                oldest = i;
                break;
            }
            if (lastSeen[i] < lastSeen[oldest]) {
                oldest = i;
            }
        }
        resetSlot(oldest);
        ids[oldest] = id;
        lastSeen[oldest] = tick;
        return oldest;
    }

    private void resetSlot(int s) {
        ids[s] = -1;
        lastSeen[s] = Long.MIN_VALUE;
        aimLead[s] = 0.0;
        leadSamples[s] = 0;
        preAim[s] = 0.0;
        baitRate[s] = 0.0;
        baitTrials[s] = 0;
        resetFightSlot(s);
    }

    private void resetFightSlot(int s) {
        backpedal[s] = 0;
        trial[s] = TRIAL_NONE;
        trialEnd[s] = Long.MIN_VALUE;
        sprintInTick[s] = Long.MIN_VALUE;
    }

    private void trackStrafe(long tick, int ourStrafe) {
        if (tick == strafeTick) {
            return;
        }
        strafeTick = tick;
        if (ourStrafe != 0 && ourStrafe == strafeDir) {
            if (strafeHeld < Integer.MAX_VALUE) {
                strafeHeld++;
            }
        } else {
            strafeDir = ourStrafe;
            strafeHeld = ourStrafe == 0 ? 0 : 1;
        }
    }

    // ------------------------------------------------------------------ observe

    /**
     * One sample per engaged tick.
     *
     * @param oppYawDeg the opponent's facing (Minecraft yaw)
     * @param ourStrafe our strafe this tick: +1 = left key, -1 = right key, 0 = none
     */
    public void observe(int id, long tick, double ox, double oz, float oppYawDeg, double ovx, double ovz,
                        boolean oppSprinting, double px, double pz, double pvx, double pvz, int ourStrafe) {
        trackStrafe(tick, ourStrafe);
        int s = slotFor(id, tick);
        lastSeen[s] = tick;
        double dx = px - ox;
        double dz = pz - oz;
        double r2 = dx * dx + dz * dz;
        if (r2 < 0.04) {
            return;
        }
        double r = Math.sqrt(r2);

        // --- aim lead: facing minus bearing, over the bearing's rate (both in yaw degrees)
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = wrapDeg(oppYawDeg - bearing);
        double rvx = pvx - ovx;
        double rvz = pvz - ovz;
        double rate = Math.toDegrees((dx * rvz - dz * rvx) / r2); // d(bearing)/dt
        if (Math.abs(rate) >= MIN_ANGULAR_SPEED) {
            double lead = delta / rate;
            lead = lead < -LEAD_CLAMP ? -LEAD_CLAMP : Math.min(lead, LEAD_CLAMP);
            int n = ++leadSamples[s];
            double a = n < 6 ? 1.0 / n : LEAD_ALPHA;
            aimLead[s] += a * (lead - aimLead[s]);
            if (strafeDir != 0 && strafeHeld >= HOLD_TICKS) {
                double hit = lead >= PRE_AIM_MIN && lead <= PRE_AIM_MAX ? 1.0 : 0.0;
                preAim[s] += PRE_AIM_ALPHA * (hit - preAim[s]);
            }
        }

        // --- bait statistics
        double away = (pvx * dx + pvz * dz) / r;
        double closing = (ovx * dx + ovz * dz) / r; // its speed toward us (d points from it to us)
        boolean runIn = oppSprinting && closing > CLOSING_SPEED;
        if (runIn) {
            sprintInTick[s] = tick;
        }
        if (away > AWAY_SPEED) {
            backpedal[s]++;
            if (backpedal[s] >= BACKPEDAL_TICKS && trial[s] == TRIAL_NONE) {
                trial[s] = TRIAL_OPEN;
                trialEnd[s] = tick + BAIT_WINDOW;
            }
        } else {
            backpedal[s] = 0;
            if (trial[s] == TRIAL_DONE) {
                trial[s] = TRIAL_NONE;
            }
        }
        if (trial[s] == TRIAL_OPEN) {
            if (runIn) {
                recordBait(s, 1.0);
            } else if (tick > trialEnd[s]) {
                recordBait(s, 0.0);
            }
        }
    }

    private void recordBait(int s, double v) {
        int n = ++baitTrials[s];
        double a = Math.max(BAIT_ALPHA, 1.0 / n);
        baitRate[s] += a * (v - baitRate[s]);
        trial[s] = backpedal[s] > 0 ? TRIAL_DONE : TRIAL_NONE;
    }

    private static double wrapDeg(double d) {
        d %= 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // ------------------------------------------------------------------ plan

    /**
     * Decides this tick's move.
     *
     * @param ourStrafe  our current strafe (+1 left, -1 right, 0)
     * @param oppReadyIn ticks until the opponent's attack is charged
     * @param ownReadyIn ticks until ours is
     * @param canBackUp  false with a hazard or wall behind us (no bait)
     * @param unit       a uniform random in [0, 1) from the shared humanizer stream (feints must not be periodic)
     */
    public Move plan(int id, long tick, int ourStrafe, double distance, int oppReadyIn, int ownReadyIn,
                     boolean canBackUp, double unit) {
        trackStrafe(tick, ourStrafe);
        strafeOv = KEEP;
        forwardOv = KEEP;
        sprintOk = true;
        int s = slotOf(id);

        switch (state) {
            case BREAK_PATTERN -> {
                if (tick < stateEnd) {
                    strafeOv = breakDir;
                    return Move.BREAK_PATTERN;
                }
                endFeint(tick, unit);
            }
            case BAIT_RETREAT -> {
                if (!canBackUp) {
                    endFeint(tick, unit);
                    return Move.NONE; // abort at once: no backing into a hazard
                }
                int bs = slotOf(stateId);
                boolean ranIn = bs >= 0 && sprintInTick[bs] != Long.MIN_VALUE && tick - sprintInTick[bs] <= 1;
                if (ranIn && (oppReadyIn > 3 || (distance < COMMIT_DIST && ownReadyIn == 0))) {
                    state = Move.PUNISH;
                    stateEnd = tick + PUNISH_TICKS;
                    forwardOv = 1;
                    return Move.PUNISH;
                }
                if (tick < stateEnd) {
                    forwardOv = -1;
                    sprintOk = false;
                    return Move.BAIT_RETREAT;
                }
                endFeint(tick, unit);
            }
            case PUNISH -> {
                if (tick < stateEnd) {
                    forwardOv = 1;
                    return Move.PUNISH;
                }
                endFeint(tick, unit);
            }
            default -> {
            }
        }
        if (state != Move.NONE || s < 0 || tick < nextAllowed) {
            return Move.NONE;
        }

        // BREAK_PATTERN: the opponent pre-aims the strafe we have been showing
        if (ourStrafe != 0 && strafeDir == ourStrafe && strafeHeld >= HOLD_TICKS && preAim[s] > PRE_AIM_TRIGGER) {
            if (unit < START_CHANCE) {
                double u = unit / START_CHANCE;
                state = Move.BREAK_PATTERN;
                stateId = id;
                stateEnd = tick + BREAK_MIN + (int) (u * BREAK_SPAN);
                breakDir = -ourStrafe;
                strafeOv = breakDir;
                preAim[s] *= 0.5; // the next break needs fresh evidence
                feintCount++;
                return Move.BREAK_PATTERN;
            }
            return Move.NONE;
        }

        // BAIT_RETREAT: it runs in on our backpedals, its swing is ready and ours will be by the end of the bait
        if (canBackUp && distance >= BAIT_MIN_DIST && distance <= BAIT_MAX_DIST && oppReadyIn == 0) {
            double chance = baitTrials[s] == 0 ? UNTESTED_BAIT_CHANCE
                    : baitRate[s] > BAIT_TRIGGER ? START_CHANCE : 0.0;
            if (unit < chance) {
                double u = unit / chance;
                int len = BAIT_MIN + (int) (u * BAIT_SPAN);
                if (ownReadyIn <= len) {
                    state = Move.BAIT_RETREAT;
                    stateId = id;
                    stateEnd = tick + len;
                    forwardOv = -1;
                    sprintOk = false;
                    feintCount++;
                    return Move.BAIT_RETREAT;
                }
            }
        }
        return Move.NONE;
    }

    private void endFeint(long tick, double unit) {
        state = Move.NONE;
        nextAllowed = tick + MIN_GAP + (long) (unit * GAP_JITTER);
    }

    /** Strafe for this tick (+1 left, -1 right, 0 none) or {@link #KEEP}. Valid after {@link #plan}. */
    public int strafeOverride() {
        return strafeOv;
    }

    /** Forward for this tick (+1, 0, -1) or {@link #KEEP}. */
    public int forwardOverride() {
        return forwardOv;
    }

    /** False while a bait wants us walking, not sprinting. */
    public boolean allowSprint() {
        return sprintOk;
    }

    /** The opponent's measured aim lead on our strafe in ticks (0 = aims at where we are; negative = lags). */
    public double aimLeadTicks(int id) {
        int s = slotOf(id);
        return s < 0 ? 0.0 : aimLead[s];
    }

    /** 0..1: how consistently the opponent pre-aims our shown pattern. */
    public double preAim(int id) {
        int s = slotOf(id);
        return s < 0 ? 0.0 : preAim[s];
    }

    /** 0..1: share of our backpedals the opponent answered by sprinting in. */
    public double baitRate(int id) {
        int s = slotOf(id);
        return s < 0 ? 0.0 : baitRate[s];
    }

    /** Feints started this fight (diagnostics, tests). */
    public int feints() {
        return feintCount;
    }

    /** Ends any running feint and forgets the fight-scoped counters (the learned habits per opponent stay). */
    public void reset() {
        state = Move.NONE;
        stateId = -1;
        stateEnd = Long.MIN_VALUE;
        breakDir = 0;
        nextAllowed = Long.MIN_VALUE;
        feintCount = 0;
        strafeDir = 0;
        strafeHeld = 0;
        strafeTick = Long.MIN_VALUE;
        strafeOv = KEEP;
        forwardOv = KEEP;
        sprintOk = true;
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            resetFightSlot(i);
        }
    }

    public void clear() {
        for (int i = 0; i < MAX_OPPONENTS; i++) {
            resetSlot(i);
        }
        reset();
    }
}
