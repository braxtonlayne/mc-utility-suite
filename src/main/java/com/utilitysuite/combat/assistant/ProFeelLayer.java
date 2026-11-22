package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaField;
import com.utilitysuite.combat.tactics.ArenaQuery;
import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.ComboKeeper;
import com.utilitysuite.combat.tactics.FeintPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.PearlSource;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.combat.tactics.SkillEstimator;
import com.utilitysuite.combat.tactics.StrafePhaseModel;
import com.utilitysuite.combat.tactics.TacticUtility;
import com.utilitysuite.combat.tactics.TradeInput;
import com.utilitysuite.core.api.IBodySimProvider;
import com.utilitysuite.core.api.Services;

/**
 * The "pro feel" units of the Combat Assistant behind one small, engine-free facade: combo keeping, the strafe-phase
 * read (anticipatory aim, strafing with the opponent), the arena read (knock vectoring, high ground, ceiling deny,
 * headroom), feints, the rank dial, the pearl intercept and the read state for the overlay. The assistant keeps its
 * own decision order and only asks this layer small questions at its call sites.
 * <p>
 * Every unit sits behind a seam interface so tests can script it (the units themselves are final). With a setting off
 * or a unit answering "nothing" the assistant behaves as it did before this layer existed.
 * <p>
 * Allocation free per tick after construction (the production adapters are built once; the combo keeper once a body
 * simulation provider exists). Client thread only.
 */
public final class ProFeelLayer {

    /** Columns of the arena grid scanned per engaged tick. */
    public static final int ARENA_COLUMNS_PER_TICK = 12;
    /** Knock vector and positional ratings are refreshed every this many engaged ticks. */
    public static final int ARENA_EVAL_TICKS = 5;
    /** Ticks ahead the strafe-phase read is asked for our own strafe and the overlay tilt. */
    public static final int STRAFE_WITH_AHEAD = 3;
    /** Rank dial: events needed before the estimate is used, and ticks between two requests. */
    public static final int RANK_MIN_SAMPLES = 6;
    public static final int RANK_REQUEST_TICKS = 20;
    public static final double RANK_LO = 0.3;
    public static final double RANK_HI = 0.98;
    /** A swing sent in reach that no hit confirms within this many ticks counts as a miss. */
    public static final int MISS_WINDOW = 10;
    /** A swing of the opponent this soon after our step-in counts as its reaction to it. */
    public static final int REACTION_WINDOW = 20;
    /** Opponent speed (blocks per tick) above which it is assumed to sprint. */
    static final double SPRINT_SPEED = 0.22;
    /** Headroom at or below which no jump fits (crit hops and solver hops are suppressed). */
    public static final int LOW_HEADROOM = 2;

    // =================================================================================================================
    // Seams (the shapes of the final units)
    // =================================================================================================================

    /** {@link ComboKeeper}. */
    public interface Combo {
        boolean begin(TradeInput in, boolean sprintHit, boolean allowBackTap);

        boolean step(TradeInput in);

        boolean active();

        void reset();

        int resetKind();

        int resetTicks();

        int forward();

        int strafe();

        boolean sprint();
    }

    /** {@link StrafePhaseModel}. */
    public interface Phase {
        void observe(int id, long tick, double ox, double oz, double ovx, double ovz, double px, double pz);

        double confidence(int id);

        int lateralSignAt(int id, long now, int ticksAhead);

        double predictOffset(int id, long now, double leadTicks, double ovx, double ovz, double[] outXZ);

        void clear();
    }

    /** The write side of {@link ArenaField}; {@link #query()} is its read side. */
    public interface Arena {
        void recenter(int cx, int cy, int cz);

        int scan(CombatVoxels w, int budgetColumns);

        void clear();

        ArenaQuery query();
    }

    /** {@link ArenaTactics}. */
    public interface Positional {
        void assess(CombatSnapshot s, ArenaQuery field);

        double highGround();

        double ceilingDeny();

        boolean plan(CombatSnapshot s, ArenaQuery field, TacticUtility.Tactic tactic);

        int forward();

        int side();

        boolean sprint();

        boolean jump();

        void reset();
    }

    /** {@link FeintPlanner}. */
    public interface Feints {
        void observe(int id, long tick, double ox, double oz, float oppYawDeg, double ovx, double ovz,
                     boolean oppSprinting, double px, double pz, double pvx, double pvz, int ourStrafe);

        FeintPlanner.Move plan(int id, long tick, int ourStrafe, double distance, int oppReadyIn, int ownReadyIn,
                               boolean canBackUp, double unit);

        int strafeOverride();

        int forwardOverride();

        boolean allowSprint();

        void reset();
    }

    /** {@link SkillEstimator}. */
    public interface Skill {
        void noteOpponentSwing(int id, long tick, boolean inReach);

        void noteOpponentHit(int id, long tick, float damage);

        void noteOwnHit(int id, long tick);

        void noteOwnMiss(int id, long tick);

        void noteReaction(int id, int ticks);

        double skill(int id);

        int samples(int id);

        void clear();
    }

    // =================================================================================================================
    // State
    // =================================================================================================================

    private static final TradeDriver.Step NO_STEP = new TradeDriver.Step() {
        @Override
        public void solve(TradeInput in) {
        }

        @Override
        public boolean valid() {
            return false;
        }

        @Override
        public int forward() {
            return 0;
        }

        @Override
        public int strafe() {
            return 0;
        }

        @Override
        public boolean jump() {
            return false;
        }

        @Override
        public boolean sprint() {
            return false;
        }

        @Override
        public double score() {
            return 0;
        }

        @Override
        public double scoreOf(int combo) {
            return 0;
        }
    };

    private final String moduleId;
    /** Fills the trade input for the combo keeper (and learns our own cooldown period); never solves. */
    private final TradeDriver filler = new TradeDriver(NO_STEP);
    private final PearlIntercept pearl = new PearlIntercept();

    private Combo combo;
    private boolean comboFixed;
    private Phase phase = of(new StrafePhaseModel());
    private Arena arena = of(new ArenaField());
    private Positional positional = of(new ArenaTactics());
    private Feints feints = of(new FeintPlanner());
    private Skill skill = of(new SkillEstimator());
    private CombatVoxels voxels;
    private ReadState read;

    private int targetId = -1;
    private boolean targetIsPlayer;

    // strafe-phase read of this tick
    private int withDir = StrafePlanner.NONE;
    private double withConf;

    // arena read
    private boolean arenaLive;
    private int arenaClock;
    private double knockValue, knockX, knockZ;
    private int knockKind;
    private int headroom = ArenaField.MAX_HEADROOM;
    private double highGround, ceilingDeny;

    // feints
    private FeintPlanner.Move feint = FeintPlanner.Move.NONE;
    private int feintsStarted;

    // rank dial
    private boolean skillRequested;
    private long lastRequestTick = Long.MIN_VALUE / 2;
    private int rankRequests;
    private long pendingSwingTick = -1;
    private long stepInTick = -1;
    private boolean lastForward;

    public ProFeelLayer(String moduleId) {
        this.moduleId = moduleId;
    }

    // ---- wiring (bootstrap, tests) --------------------------------------------------------------------------------

    /** Blocks for the arena read and the pearl landing; null keeps both features idle. */
    public void useVoxels(CombatVoxels voxels) {
        this.voxels = voxels;
        pearl.useVoxels(voxels);
        if (voxels == null) {
            arena.clear();
            arenaLive = false;
        }
    }

    public void usePearlSource(PearlSource source) {
        pearl.useSource(source);
    }

    public void useReadState(ReadState read) {
        this.read = read;
    }

    /** Scripted combo keeper (tests); null goes back to the production keeper (built once body sims exist). */
    public void useCombo(Combo c) {
        combo = c;
        comboFixed = c != null;
    }

    public void usePhase(Phase p) {
        phase = p;
    }

    public void useArena(Arena a) {
        arena = a;
    }

    public void usePositional(Positional p) {
        positional = p;
    }

    public void useFeints(Feints f) {
        feints = f;
    }

    public void useSkill(Skill s) {
        skill = s;
    }

    public void usePearlLanding(PearlIntercept.Landing landing) {
        pearl.useLanding(landing);
    }

    public PearlIntercept pearl() {
        return pearl;
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    /** A new target (or the first one). Fight-scoped plans end; learned habits per opponent stay. */
    void engage(int id, boolean player) {
        targetId = id;
        targetIsPlayer = player;
        Combo c = combo;
        if (c != null) {
            c.reset();
        }
        filler.resetAll();
        feints.reset();
        positional.reset();
        pearl.reset();
        feint = FeintPlanner.Move.NONE;
        arenaClock = ARENA_EVAL_TICKS - 1; // evaluate on the first engaged tick
        pendingSwingTick = -1;
        stepInTick = -1;
        lastForward = false;
    }

    /** Disengage (every reason): plans end, the arena and the read are cleared, any skill request is dropped. */
    void disengage(CombatSnapshot s) {
        targetId = -1;
        Combo c = combo;
        if (c != null) {
            c.reset();
        }
        feints.reset();
        positional.reset();
        pearl.reset();
        feint = FeintPlanner.Move.NONE;
        clearArena(s);
        if (read != null) {
            read.clear();
        }
        clearSkillRequest();
    }

    void worldUnload(CombatSnapshot s) {
        disengage(s);
        phase.clear();
        skill.clear();
    }

    private void clearArena(CombatSnapshot s) {
        if (arenaLive) {
            arena.clear();
            arenaLive = false;
        }
        knockValue = 0.0;
        knockX = 0.0;
        knockZ = 0.0;
        knockKind = ArenaField.KNOCK_NONE;
        headroom = ArenaField.MAX_HEADROOM;
        highGround = 0.0;
        ceilingDeny = 0.0;
        writeArena(s);
    }

    private void writeArena(CombatSnapshot s) {
        s.knockValue = knockValue;
        s.knockFieldX = knockX;
        s.knockFieldZ = knockZ;
        s.knockKind = knockKind;
        s.headroom = headroom;
    }

    // ---- per engaged tick ----------------------------------------------------------------------------------------

    /**
     * Pearl intercept, run before the disengage radius check so a teleporting target is not dropped.
     *
     * @return true while a run to the pearl's landing should go on
     */
    boolean pearlTick(boolean enabled, long tick, CombatSnapshot s, double spacingMax, double pursuitRange) {
        if (!enabled) {
            pearl.reset();
            return false;
        }
        return pearl.update(targetId, tick, s.px, s.py, s.pz, s.tx, s.tz, spacingMax, pursuitRange);
    }

    /**
     * Feeds every unit from this tick's snapshot and publishes the read. Called once per engaged tick after the
     * opponent model was fed.
     *
     * @param ourStrafe the strafe we held last tick (+1 left key, -1 right key, 0 none)
     */
    void observe(CombatSnapshot s, CombatSettings st, OpponentModel opponents, long tick, int ourStrafe,
                 boolean allowJump) {
        int id = targetId;
        phase.observe(id, tick, s.tx, s.tz, s.tvx, s.tvz, s.px, s.pz);
        if (st.strafeWith) {
            double conf = phase.confidence(id);
            withDir = conf > 0.0 ? StrafePlanner.mirrorOf(phase.lateralSignAt(id, tick, STRAFE_WITH_AHEAD))
                    : StrafePlanner.NONE;
            withConf = withDir == StrafePlanner.NONE ? 0.0 : conf;
        } else {
            withDir = StrafePlanner.NONE;
            withConf = 0.0;
        }

        // the trade input for the combo keeper (also our own cooldown estimate for the feints)
        filler.fill(s, opponents, id, tick, targetIsPlayer, st.cooldownRead, st.hitTradeHorizon,
                CombatAssistant.BASE_TAKEN_WEIGHT, allowJump);
        Combo c = combo;
        if (c != null && c.active() && !c.step(filler.input())) {
            c.reset();
        }

        observeArena(s, st);

        boolean oppSprinting = s.tvx * s.tvx + s.tvz * s.tvz > SPRINT_SPEED * SPRINT_SPEED;
        if (targetIsPlayer) {
            feints.observe(id, tick, s.tx, s.tz, s.targetYaw, s.tvx, s.tvz, oppSprinting, s.px, s.pz, s.pvx, s.pvz,
                    ourStrafe);
        }

        observeSkill(s, st, id, tick);
        publishRead(s, st, opponents, id, tick);
    }

    private void observeArena(CombatSnapshot s, CombatSettings st) {
        CombatVoxels w = voxels;
        if (!st.arenaRead || w == null) {
            if (arenaLive || knockValue != 0.0 || headroom != ArenaField.MAX_HEADROOM || highGround != 0.0
                    || ceilingDeny != 0.0) {
                clearArena(s);
            } else {
                writeArena(s);
            }
            return;
        }
        Arena a = arena;
        arenaLive = true;
        a.recenter(floor((s.px + s.tx) * 0.5), floor((s.py + s.ty) * 0.5), floor((s.pz + s.tz) * 0.5));
        a.scan(w, ARENA_COLUMNS_PER_TICK);
        ArenaQuery q = a.query();
        if (++arenaClock >= ARENA_EVAL_TICKS) {
            arenaClock = 0;
            if (q.evalKnock(s.tx, s.ty, s.tz)) {
                knockValue = q.knockValue();
                knockX = q.knockX();
                knockZ = q.knockZ();
                knockKind = q.knockKind();
            } else {
                knockValue = 0.0;
                knockX = 0.0;
                knockZ = 0.0;
                knockKind = ArenaField.KNOCK_NONE;
            }
            positional.assess(s, q);
            highGround = positional.highGround();
            ceilingDeny = positional.ceilingDeny();
        }
        headroom = q.headroom(floor(s.px), floor(s.pz));
        writeArena(s);
    }

    private void observeSkill(CombatSnapshot s, CombatSettings st, int id, long tick) {
        Skill k = skill;
        if (s.targetSwingStarted) {
            // its reach is about ours: our eye to its box approximates its eye to ours
            k.noteOpponentSwing(id, tick, s.reachDistanceSq() <= 9.0);
            if (stepInTick >= 0 && tick - stepInTick <= REACTION_WINDOW) {
                k.noteReaction(id, (int) (tick - stepInTick));
            }
            stepInTick = -1;
        }
        if (pendingSwingTick >= 0 && tick - pendingSwingTick > MISS_WINDOW) {
            k.noteOwnMiss(id, tick);
            pendingSwingTick = -1;
        }
        if (!st.rankDial) {
            clearSkillRequest();
            return;
        }
        if (k.samples(id) >= RANK_MIN_SAMPLES && tick - lastRequestTick >= RANK_REQUEST_TICKS) {
            lastRequestTick = tick;
            skillRequested = true;
            rankRequests++;
            Services.humanizer().requestSkill(moduleId,
                    SkillEstimator.oneNotchAbove(k.skill(id), SkillEstimator.NOTCH, RANK_LO, RANK_HI));
        }
    }

    private void publishRead(CombatSnapshot s, CombatSettings st, OpponentModel opponents, int id, long tick) {
        ReadState r = read;
        if (r == null) {
            return;
        }
        r.active = true;
        r.targetId = id;
        r.charge = st.cooldownRead ? opponents.attackCharge(id, tick) : 1f;
        r.readyIn = st.cooldownRead ? Math.max(0, opponents.attackReadyIn(id, tick)) : 0;
        int sign = phase.lateralSignAt(id, tick, STRAFE_WITH_AHEAD);
        double conf = phase.confidence(id);
        double fx = s.tx - s.px;
        double fz = s.tz - s.pz;
        double len = Math.sqrt(fx * fx + fz * fz);
        if (sign == 0 || conf <= 0.0 || len < 1.0e-6) {
            r.tiltX = 0f;
            r.tiltZ = 0f;
        } else {
            // + = to its own right while facing us = our left; our left of facing f is (fz, -fx)
            double k = sign * Math.min(1.0, conf) / len;
            r.tiltX = (float) (fz * k);
            r.tiltZ = (float) (-fx * k);
        }
        r.steppingIn = false;
    }

    // ---- combo keeper --------------------------------------------------------------------------------------------

    private Combo combo() {
        Combo c = combo;
        if (c == null && !comboFixed) {
            IBodySimProvider sims = Services.bodySimsOrNull();
            if (sims != null) {
                c = of(new ComboKeeper(sims));
                combo = c;
            }
        }
        return c;
    }

    /**
     * A hit was just sent. Plans the follow-up when the setting allows it and the target is in reach.
     *
     * @return true when the keeper planned the follow-up ({@link #comboResetKind} and {@link #comboResetTicks} replace
     *         the fixed sprint reset)
     */
    boolean onSwingSent(CombatSnapshot s, CombatSettings st, long tick) {
        if (!s.inReach()) {
            return false;
        }
        if (pendingSwingTick >= 0) {
            skill.noteOwnMiss(targetId, tick); // the previous swing in reach never landed
        }
        pendingSwingTick = tick;
        if (!st.comboKeeper || st.sprintReset == CombatSettings.SprintReset.OFF) {
            return false;
        }
        Combo c = combo();
        return c != null && c.begin(filler.input(), s.sprinting, !s.hazardBack);
    }

    /** True while the keeper's keys drive the movement (setting on, a plan running). */
    boolean comboDrives(CombatSettings st) {
        Combo c = combo;
        return st.comboKeeper && st.sprintReset != CombatSettings.SprintReset.OFF && c != null && c.active();
    }

    int comboResetKind() {
        Combo c = combo;
        return c == null ? ComboKeeper.RESET_NONE : c.resetKind();
    }

    int comboResetTicks() {
        Combo c = combo;
        return c == null ? 0 : Math.max(0, Math.min(3, c.resetTicks()));
    }

    int comboForward() {
        return combo.forward();
    }

    /** {@link StrafePlanner} convention. */
    int comboSide() {
        return sideOf(combo.strafe());
    }

    boolean comboSprint() {
        return combo.sprint();
    }

    // ---- strafe phase --------------------------------------------------------------------------------------------

    /**
     * Aim offset for this tick: the learned strafe phase when anticipatory aim is on (the stub answers the plain
     * velocity lead), written to {@code out[0]} (x) and {@code out[1]} (z).
     */
    void aimOffset(CombatSnapshot s, long tick, double leadTicks, double[] outXZ) {
        phase.predictOffset(targetId, tick, leadTicks, s.tvx, s.tvz, outXZ);
    }

    /** Our strafe that moves with the opponent ({@link StrafePlanner} convention), or NONE. */
    int withDir() {
        return withDir;
    }

    double withStrength() {
        return withConf;
    }

    // ---- arena -------------------------------------------------------------------------------------------------

    double highGround() {
        return highGround;
    }

    double ceilingDeny() {
        return ceilingDeny;
    }

    boolean planPositional(CombatSnapshot s, TacticUtility.Tactic t) {
        if (voxels == null) {
            return false;
        }
        return positional.plan(s, arena.query(), t);
    }

    int positionalForward() {
        return positional.forward();
    }

    int positionalSide() {
        return positional.side();
    }

    boolean positionalSprint() {
        return positional.sprint();
    }

    boolean positionalJump() {
        return positional.jump();
    }

    void resetPositional() {
        positional.reset();
    }

    // ---- feints --------------------------------------------------------------------------------------------------

    /**
     * Plans this tick's feint. Players only; never against a mob.
     *
     * @param side our strafe so far this tick ({@link StrafePlanner} convention)
     * @return the move (NONE leaves the keys alone); overrides in {@link #feintForward}, {@link #feintSide},
     *         {@link #feintSprint}
     */
    FeintPlanner.Move planFeint(CombatSnapshot s, CombatSettings st, OpponentModel opponents, long tick, int side,
                                double distance) {
        if (!st.feints || !targetIsPlayer) {
            feint = FeintPlanner.Move.NONE;
            return feint;
        }
        int oppReadyIn = oppReadyIn(st, opponents, tick);
        int ownReadyIn = TradeDriver.readyIn(s.attackStrength, filler.ownCooldown());
        FeintPlanner.Move m = feints.plan(targetId, tick, solverStrafe(side), distance, oppReadyIn, ownReadyIn,
                !s.hazardBack, Services.humanizer().nextUnit());
        if (m == null) {
            m = FeintPlanner.Move.NONE;
        }
        if (m != FeintPlanner.Move.NONE && m != feint) {
            feintsStarted++;
        }
        feint = m;
        return m;
    }

    /** Forward override or {@code current}. */
    int feintForward(int current) {
        int f = feints.forwardOverride();
        return f == FeintPlanner.KEEP ? current : Integer.signum(f);
    }

    /** Strafe override ({@link StrafePlanner} convention) or {@code current}. */
    int feintSide(int current) {
        int sd = feints.strafeOverride();
        return sd == FeintPlanner.KEEP ? current : sideOf(sd);
    }

    boolean feintSprint(boolean current) {
        return current && feints.allowSprint();
    }

    FeintPlanner.Move feint() {
        return feint;
    }

    void endFeint() {
        feint = FeintPlanner.Move.NONE;
    }

    /** Feints that started this session (diagnostics, tests). */
    public int feintsStarted() {
        return feintsStarted;
    }

    static int oppReadyIn(CombatSettings st, OpponentModel opponents, int id, long tick) {
        return st.cooldownRead ? Math.max(0, opponents.attackReadyIn(id, tick)) : 0;
    }

    private int oppReadyIn(CombatSettings st, OpponentModel opponents, long tick) {
        return oppReadyIn(st, opponents, targetId, tick);
    }

    // ---- movement outcome, skill and the read ---------------------------------------------------------------------

    /**
     * The movement keys chosen this tick. Marks a step-in (for the opponent's reaction time) and publishes
     * {@link ReadState#steppingIn} when the solver or the keeper steps in on a cooldown read.
     */
    void afterMovement(int forward, boolean planned, int oppReadyIn, long tick) {
        boolean fwd = forward > 0;
        if (fwd && !lastForward) {
            stepInTick = tick;
        }
        lastForward = fwd;
        ReadState r = read;
        if (r != null && r.active) {
            r.steppingIn = fwd && planned && oppReadyIn > 0;
        }
    }

    void noteOpponentHit(long tick, float damage) {
        if (targetId >= 0) {
            skill.noteOpponentHit(targetId, tick, damage);
        }
    }

    /** Our hit landed (a confirmed damage event or the target's health dropped). */
    void noteOwnHit(long tick) {
        if (targetId >= 0 && pendingSwingTick >= 0) {
            skill.noteOwnHit(targetId, tick);
            pendingSwingTick = -1;
        }
    }

    /** Drops the rank-dial request, if this layer holds one. */
    void clearSkillRequest() {
        if (skillRequested) {
            skillRequested = false;
            lastRequestTick = Long.MIN_VALUE / 2;
            Services.humanizer().clearSkillRequest(moduleId);
        }
    }

    /** True while the rank dial holds a skill request. */
    public boolean skillRequested() {
        return skillRequested;
    }

    public int rankRequests() {
        return rankRequests;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Solver / keeper / feint convention (+1 left key) to {@link StrafePlanner}'s. */
    static int sideOf(int solverStrafe) {
        return solverStrafe > 0 ? StrafePlanner.LEFT : (solverStrafe < 0 ? StrafePlanner.RIGHT : StrafePlanner.NONE);
    }

    /** {@link StrafePlanner} convention to the solver's (+1 left key). */
    static int solverStrafe(int side) {
        return side == StrafePlanner.LEFT ? 1 : (side == StrafePlanner.RIGHT ? -1 : 0);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    // =================================================================================================================
    // Production adapters (built once)
    // =================================================================================================================

    static Combo of(ComboKeeper k) {
        return new Combo() {
            @Override
            public boolean begin(TradeInput in, boolean sprintHit, boolean allowBackTap) {
                return k.begin(in, sprintHit, allowBackTap);
            }

            @Override
            public boolean step(TradeInput in) {
                return k.step(in);
            }

            @Override
            public boolean active() {
                return k.active();
            }

            @Override
            public void reset() {
                k.reset();
            }

            @Override
            public int resetKind() {
                return k.resetKind();
            }

            @Override
            public int resetTicks() {
                return k.resetTicks();
            }

            @Override
            public int forward() {
                return k.forward();
            }

            @Override
            public int strafe() {
                return k.strafe();
            }

            @Override
            public boolean sprint() {
                return k.sprint();
            }
        };
    }

    static Phase of(StrafePhaseModel m) {
        return new Phase() {
            @Override
            public void observe(int id, long tick, double ox, double oz, double ovx, double ovz, double px,
                                double pz) {
                m.observe(id, tick, ox, oz, ovx, ovz, px, pz);
            }

            @Override
            public double confidence(int id) {
                return m.confidence(id);
            }

            @Override
            public int lateralSignAt(int id, long now, int ticksAhead) {
                return m.lateralSignAt(id, now, ticksAhead);
            }

            @Override
            public double predictOffset(int id, long now, double leadTicks, double ovx, double ovz, double[] out) {
                return m.predictOffset(id, now, leadTicks, ovx, ovz, out);
            }

            @Override
            public void clear() {
                m.clear();
            }
        };
    }

    static Arena of(ArenaField f) {
        return new Arena() {
            @Override
            public void recenter(int cx, int cy, int cz) {
                f.recenter(cx, cy, cz);
            }

            @Override
            public int scan(CombatVoxels w, int budgetColumns) {
                return f.scan(w, budgetColumns);
            }

            @Override
            public void clear() {
                f.clear();
            }

            @Override
            public ArenaQuery query() {
                return f;
            }
        };
    }

    static Positional of(ArenaTactics t) {
        return new Positional() {
            @Override
            public void assess(CombatSnapshot s, ArenaQuery field) {
                t.assess(s, field);
            }

            @Override
            public double highGround() {
                return t.highGround();
            }

            @Override
            public double ceilingDeny() {
                return t.ceilingDeny();
            }

            @Override
            public boolean plan(CombatSnapshot s, ArenaQuery field, TacticUtility.Tactic tactic) {
                return t.plan(s, field, tactic);
            }

            @Override
            public int forward() {
                return t.forward();
            }

            @Override
            public int side() {
                return t.side();
            }

            @Override
            public boolean sprint() {
                return t.sprint();
            }

            @Override
            public boolean jump() {
                return t.jump();
            }

            @Override
            public void reset() {
                t.reset();
            }
        };
    }

    static Feints of(FeintPlanner p) {
        return new Feints() {
            @Override
            public void observe(int id, long tick, double ox, double oz, float oppYawDeg, double ovx, double ovz,
                                boolean oppSprinting, double px, double pz, double pvx, double pvz, int ourStrafe) {
                p.observe(id, tick, ox, oz, oppYawDeg, ovx, ovz, oppSprinting, px, pz, pvx, pvz, ourStrafe);
            }

            @Override
            public FeintPlanner.Move plan(int id, long tick, int ourStrafe, double distance, int oppReadyIn,
                                          int ownReadyIn, boolean canBackUp, double unit) {
                return p.plan(id, tick, ourStrafe, distance, oppReadyIn, ownReadyIn, canBackUp, unit);
            }

            @Override
            public int strafeOverride() {
                return p.strafeOverride();
            }

            @Override
            public int forwardOverride() {
                return p.forwardOverride();
            }

            @Override
            public boolean allowSprint() {
                return p.allowSprint();
            }

            @Override
            public void reset() {
                p.reset();
            }
        };
    }

    static Skill of(SkillEstimator e) {
        return new Skill() {
            @Override
            public void noteOpponentSwing(int id, long tick, boolean inReach) {
                e.noteOpponentSwing(id, tick, inReach);
            }

            @Override
            public void noteOpponentHit(int id, long tick, float damage) {
                e.noteOpponentHit(id, tick, damage);
            }

            @Override
            public void noteOwnHit(int id, long tick) {
                e.noteOwnHit(id, tick);
            }

            @Override
            public void noteOwnMiss(int id, long tick) {
                e.noteOwnMiss(id, tick);
            }

            @Override
            public void noteReaction(int id, int ticks) {
                e.noteReaction(id, ticks);
            }

            @Override
            public double skill(int id) {
                return e.skill(id);
            }

            @Override
            public int samples(int id) {
                return e.samples(id);
            }

            @Override
            public void clear() {
                e.clear();
            }
        };
    }
}
