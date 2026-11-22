package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.ArenaField;
import com.utilitysuite.combat.tactics.ArenaQuery;
import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.ComboKeeper;
import com.utilitysuite.combat.tactics.FeintPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.combat.tactics.TacticUtility;
import com.utilitysuite.combat.tactics.TradeInput;
import com.utilitysuite.core.api.IHumanizer;
import com.utilitysuite.core.api.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pro-feel layer against scripted units: each feature changes what it should, each setting off leaves the old
 * values, mobs never get feints, the rank dial asks for and drops the humanizer skill. The scripted units are public
 * so the assistant-level tests reuse them.
 */
public class ProFeelLayerTest {

    // =================================================================================================================
    // Scripted units (allocation free)
    // =================================================================================================================

    public static final class ScriptedCombo implements ProFeelLayer.Combo {
        public boolean beginAnswer = true;
        public boolean stepAnswer = true;
        public boolean active;
        public int resetKind = ComboKeeper.RESET_W_RELEASE;
        public int resetTicks = 2;
        public int forward = 1;
        public int strafe;
        public boolean sprint = true;
        public int begins, steps, resets;
        public boolean lastSprintHit, lastAllowBackTap;

        @Override
        public boolean begin(TradeInput in, boolean sprintHit, boolean allowBackTap) {
            begins++;
            lastSprintHit = sprintHit;
            lastAllowBackTap = allowBackTap;
            active = beginAnswer;
            return beginAnswer;
        }

        @Override
        public boolean step(TradeInput in) {
            steps++;
            active = stepAnswer;
            return stepAnswer;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void reset() {
            resets++;
            active = false;
        }

        @Override
        public int resetKind() {
            return resetKind;
        }

        @Override
        public int resetTicks() {
            return resetTicks;
        }

        @Override
        public int forward() {
            return forward;
        }

        @Override
        public int strafe() {
            return strafe;
        }

        @Override
        public boolean sprint() {
            return sprint;
        }
    }

    public static final class ScriptedPhase implements ProFeelLayer.Phase {
        public double confidence;
        public int sign;
        /** When set, predictOffset answers this offset instead of the velocity lead. */
        public boolean useOffset;
        public double offX, offZ;
        public int observes;

        @Override
        public void observe(int id, long tick, double ox, double oz, double ovx, double ovz, double px, double pz) {
            observes++;
        }

        @Override
        public double confidence(int id) {
            return confidence;
        }

        @Override
        public int lateralSignAt(int id, long now, int ticksAhead) {
            return sign;
        }

        @Override
        public double predictOffset(int id, long now, double leadTicks, double ovx, double ovz, double[] out) {
            if (useOffset) {
                out[0] = offX;
                out[1] = offZ;
                return confidence;
            }
            out[0] = ovx * leadTicks;
            out[1] = ovz * leadTicks;
            return 0.0;
        }

        @Override
        public void clear() {
        }
    }

    public static final class ScriptedArena implements ProFeelLayer.Arena, ArenaQuery {
        public int headroom = ArenaField.MAX_HEADROOM;
        public boolean knock;
        public double knockX = 1.0, knockZ, knockValue = 0.8;
        public int knockKind = ArenaField.KNOCK_LAVA;
        public int scans, recenters, clears, evals, lastBudget;
        public int cx, cy, cz;

        @Override
        public void recenter(int cx, int cy, int cz) {
            recenters++;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
        }

        @Override
        public int scan(CombatVoxels w, int budgetColumns) {
            scans++;
            lastBudget = budgetColumns;
            return budgetColumns;
        }

        @Override
        public void clear() {
            clears++;
        }

        @Override
        public ArenaQuery query() {
            return this;
        }

        @Override
        public double coverage() {
            return 1.0;
        }

        @Override
        public int floorY(int x, int z) {
            return 63;
        }

        @Override
        public int headroom(int x, int z) {
            return headroom;
        }

        @Override
        public int floorKind(int x, int z) {
            return CombatVoxels.SOLID;
        }

        @Override
        public boolean evalKnock(double tx, double ty, double tz) {
            evals++;
            return knock;
        }

        @Override
        public double knockX() {
            return knockX;
        }

        @Override
        public double knockZ() {
            return knockZ;
        }

        @Override
        public double knockValue() {
            return knockValue;
        }

        @Override
        public int knockKind() {
            return knockKind;
        }

        @Override
        public boolean findHighGround(double px, double py, double pz, double tx, double ty, double tz,
                                     double maxDist) {
            return false;
        }

        @Override
        public boolean findLowCeiling(double px, double py, double pz, double tx, double ty, double tz,
                                      double maxDist) {
            return false;
        }

        @Override
        public double spotX() {
            return 0;
        }

        @Override
        public double spotY() {
            return 0;
        }

        @Override
        public double spotZ() {
            return 0;
        }

        @Override
        public double spotGain() {
            return 0;
        }
    }

    public static final class ScriptedPositional implements ProFeelLayer.Positional {
        public double highGround, ceilingDeny;
        public boolean planAnswer;
        public int forward = SpacingController.FORWARD;
        public int side = StrafePlanner.NONE;
        public boolean sprint, jump;
        public int assesses, plans;

        @Override
        public void assess(CombatSnapshot s, ArenaQuery field) {
            assesses++;
        }

        @Override
        public double highGround() {
            return highGround;
        }

        @Override
        public double ceilingDeny() {
            return ceilingDeny;
        }

        @Override
        public boolean plan(CombatSnapshot s, ArenaQuery field, TacticUtility.Tactic tactic) {
            plans++;
            return planAnswer;
        }

        @Override
        public int forward() {
            return forward;
        }

        @Override
        public int side() {
            return side;
        }

        @Override
        public boolean sprint() {
            return sprint;
        }

        @Override
        public boolean jump() {
            return jump;
        }

        @Override
        public void reset() {
        }
    }

    public static final class ScriptedFeints implements ProFeelLayer.Feints {
        public FeintPlanner.Move move = FeintPlanner.Move.NONE;
        public int strafeOverride = FeintPlanner.KEEP;
        public int forwardOverride = FeintPlanner.KEEP;
        public boolean allowSprint = true;
        public int observes, plans, resets;
        public boolean lastCanBackUp;

        @Override
        public void observe(int id, long tick, double ox, double oz, float oppYawDeg, double ovx, double ovz,
                            boolean oppSprinting, double px, double pz, double pvx, double pvz, int ourStrafe) {
            observes++;
        }

        @Override
        public FeintPlanner.Move plan(int id, long tick, int ourStrafe, double distance, int oppReadyIn,
                                      int ownReadyIn, boolean canBackUp, double unit) {
            plans++;
            lastCanBackUp = canBackUp;
            return move;
        }

        @Override
        public int strafeOverride() {
            return move == FeintPlanner.Move.NONE ? FeintPlanner.KEEP : strafeOverride;
        }

        @Override
        public int forwardOverride() {
            return move == FeintPlanner.Move.NONE ? FeintPlanner.KEEP : forwardOverride;
        }

        @Override
        public boolean allowSprint() {
            return move == FeintPlanner.Move.NONE || allowSprint;
        }

        @Override
        public void reset() {
            resets++;
        }
    }

    public static final class ScriptedSkill implements ProFeelLayer.Skill {
        public int samples;
        public double skill = 0.5;
        public int swings, swingsInReach, hitsTaken, ownHits, ownMisses, reactions, lastReaction;

        @Override
        public void noteOpponentSwing(int id, long tick, boolean inReach) {
            swings++;
            if (inReach) {
                swingsInReach++;
            }
        }

        @Override
        public void noteOpponentHit(int id, long tick, float damage) {
            hitsTaken++;
        }

        @Override
        public void noteOwnHit(int id, long tick) {
            ownHits++;
        }

        @Override
        public void noteOwnMiss(int id, long tick) {
            ownMisses++;
        }

        @Override
        public void noteReaction(int id, int ticks) {
            reactions++;
            lastReaction = ticks;
        }

        @Override
        public double skill(int id) {
            return skill;
        }

        @Override
        public int samples(int id) {
            return samples;
        }

        @Override
        public void clear() {
        }
    }

    /** Records skill requests (no allocation per call). */
    public static final class RecordingHumanizer implements IHumanizer {
        public int requests, clears;
        public double lastSkill = -1;
        public String lastModule;
        public boolean holding;
        public int units;

        @Override
        public double skill() {
            return 0.75;
        }

        @Override
        public int reactionTicks(int medianTicks, int minTicks, int maxTicks) {
            return medianTicks;
        }

        @Override
        public double turnSeconds(double angleDeg, double targetWidthDeg) {
            return Math.abs(angleDeg) / 450.0;
        }

        @Override
        public void aimOffset(double halfWidth, double halfHeight, double[] out) {
            out[0] = 0;
            out[1] = 0;
        }

        @Override
        public double nextUnit() {
            units++;
            return 0.5;
        }

        @Override
        public void requestSkill(String moduleId, double skill) {
            requests++;
            lastSkill = skill;
            lastModule = moduleId;
            holding = true;
        }

        @Override
        public void clearSkillRequest(String moduleId) {
            clears++;
            holding = false;
        }
    }

    /** Solid ground everywhere at y < 64 (enough for the "voxels present" gate). */
    public static final CombatVoxels FLAT = (x, y, z) -> y < 64 ? CombatVoxels.SOLID : CombatVoxels.AIR;

    // =================================================================================================================

    static final int TARGET = 10;

    ProFeelLayer layer;
    CombatSettings st;
    OpponentModel opp;
    CombatSnapshot s;
    ScriptedCombo combo;
    ScriptedPhase phase;
    ScriptedArena arena;
    ScriptedPositional positional;
    ScriptedFeints feints;
    ScriptedSkill skill;
    RecordingHumanizer human;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        human = new RecordingHumanizer();
        Services.registerHumanizer(human);
        layer = new ProFeelLayer(CombatAssistant.ID);
        combo = new ScriptedCombo();
        phase = new ScriptedPhase();
        arena = new ScriptedArena();
        positional = new ScriptedPositional();
        feints = new ScriptedFeints();
        skill = new ScriptedSkill();
        layer.useCombo(combo);
        layer.usePhase(phase);
        layer.useArena(arena);
        layer.usePositional(positional);
        layer.useFeints(feints);
        layer.useSkill(skill);
        st = new CombatSettings();
        opp = new OpponentModel();
        s = new CombatSnapshot();
        s.px = 0.5;
        s.py = 64;
        s.pz = 0.5;
        s.eyeY = 65.62;
        s.tx = 3.5;
        s.ty = 64;
        s.tz = 0.5;
        s.minX = 3.2;
        s.maxX = 3.8;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = 0.2;
        s.maxZ = 0.8;
        s.onGround = true;
        s.attackStrength = 1f;
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    void observe(long tick) {
        layer.observe(s, st, opp, tick, 0, true);
    }

    // ---- combo keeper ----------------------------------------------------------------------------------------------

    @Test
    void comboKeeperBeginsOnAHitInReachAndDrivesWhileActive() {
        layer.engage(TARGET, true);
        observe(1);
        s.sprinting = true;
        assertTrue(layer.onSwingSent(s, st, 1));
        assertEquals(1, combo.begins);
        assertTrue(combo.lastSprintHit);
        assertTrue(combo.lastAllowBackTap);
        assertTrue(layer.comboDrives(st));
        assertEquals(ComboKeeper.RESET_W_RELEASE, layer.comboResetKind());
        observe(2);
        assertEquals(1, combo.steps, "stepped every tick while active");
        combo.stepAnswer = false; // the plan ends
        observe(3);
        assertFalse(layer.comboDrives(st));
    }

    @Test
    void comboKeeperHonoursHazardBehindReachAndTheSettings() {
        layer.engage(TARGET, true);
        observe(1);
        s.hazardBack = true;
        layer.onSwingSent(s, st, 1);
        assertFalse(combo.lastAllowBackTap, "no S-tap with a hazard behind");

        st.comboKeeper = false;
        combo.begins = 0;
        assertFalse(layer.onSwingSent(s, st, 2));
        assertEquals(0, combo.begins, "setting off: never consulted");
        assertFalse(layer.comboDrives(st), "setting off: the keys are not the keeper's");

        st.comboKeeper = true;
        st.sprintReset = CombatSettings.SprintReset.OFF;
        assertFalse(layer.onSwingSent(s, st, 3));
        assertEquals(0, combo.begins, "sprint reset off disables the keeper");

        st.sprintReset = CombatSettings.SprintReset.W_RELEASE;
        s.tx = 8; // out of reach
        s.minX = 7.7;
        s.maxX = 8.3;
        assertFalse(layer.onSwingSent(s, st, 4));
        assertEquals(0, combo.begins, "a swing out of reach plans nothing");
    }

    @Test
    void comboPlanEndsOnTargetChangeAndDisengage() {
        layer.engage(TARGET, true);
        observe(1);
        layer.onSwingSent(s, st, 1);
        assertTrue(combo.active);
        layer.engage(TARGET + 1, true);
        assertFalse(combo.active, "target switch");
        layer.onSwingSent(s, st, 2);
        layer.disengage(s);
        assertFalse(combo.active, "disengage");
    }

    // ---- strafe phase ------------------------------------------------------------------------------------------------

    @Test
    void strafeWithMirrorsThePredictedLateralSign() {
        layer.engage(TARGET, true);
        phase.confidence = 0.7;
        phase.sign = 1; // to its own right = our left
        observe(1);
        assertEquals(StrafePlanner.LEFT, layer.withDir());
        assertEquals(0.7, layer.withStrength(), 1e-9);
        phase.sign = -1;
        observe(2);
        assertEquals(StrafePlanner.RIGHT, layer.withDir());

        phase.confidence = 0.0; // no pattern: no preference
        observe(3);
        assertEquals(StrafePlanner.NONE, layer.withDir());
        assertEquals(0.0, layer.withStrength());

        phase.confidence = 0.9;
        st.strafeWith = false;
        observe(4);
        assertEquals(StrafePlanner.NONE, layer.withDir(), "setting off");
    }

    @Test
    void theProductionStrafePhaseStubIsThePlainVelocityLead() {
        ProFeelLayer plain = new ProFeelLayer(CombatAssistant.ID);
        plain.engage(TARGET, true);
        s.tvx = 0.1;
        s.tvz = -0.2;
        double[] out = new double[2];
        plain.aimOffset(s, 5, 2.0, out);
        assertEquals(0.2, out[0], 1e-12);
        assertEquals(-0.4, out[1], 1e-12);
    }

    // ---- arena read --------------------------------------------------------------------------------------------------

    @Test
    void arenaReadIsIdleWithoutVoxelsAndBudgetedWithThem() {
        layer.engage(TARGET, true);
        observe(1);
        assertEquals(0, arena.scans, "no voxels: idle");
        assertEquals(4, s.headroom);

        layer.useVoxels(FLAT);
        arena.knock = true;
        arena.headroom = 2;
        observe(2);
        assertEquals(1, arena.scans);
        assertEquals(ProFeelLayer.ARENA_COLUMNS_PER_TICK, arena.lastBudget);
        assertEquals(2, arena.cx, "recentred on the midpoint of player and target");
        assertEquals(64, arena.cy);
        assertEquals(0, arena.cz);
        assertEquals(1, arena.evals, "evaluated on the first engaged tick");
        assertEquals(0.8, s.knockValue, 1e-9);
        assertEquals(1.0, s.knockFieldX, 1e-9);
        assertEquals(ArenaField.KNOCK_LAVA, s.knockKind);
        assertEquals(2, s.headroom);
        for (int t = 3; t < 3 + ProFeelLayer.ARENA_EVAL_TICKS; t++) {
            observe(t);
        }
        assertEquals(2, arena.evals, "knock and ratings every " + ProFeelLayer.ARENA_EVAL_TICKS + " ticks");
        assertEquals(2, positional.assesses);
    }

    @Test
    void arenaRatingsFeedTheUtilityInputsAndClearWhenOff() {
        layer.useVoxels(FLAT);
        layer.engage(TARGET, true);
        positional.highGround = 0.6;
        positional.ceilingDeny = 0.4;
        arena.knock = true;
        observe(1);
        assertEquals(0.6, layer.highGround(), 1e-9);
        assertEquals(0.4, layer.ceilingDeny(), 1e-9);

        st.arenaRead = false;
        observe(2);
        assertEquals(0.0, layer.highGround());
        assertEquals(0.0, layer.ceilingDeny());
        assertEquals(0.0, s.knockValue);
        assertEquals(4, s.headroom);
        assertEquals(1, arena.clears, "the field is dropped once when the setting goes off");
        observe(3);
        assertEquals(1, arena.clears);
    }

    @Test
    void disengageClearsTheArenaAndTheSnapshotFields() {
        layer.useVoxels(FLAT);
        layer.engage(TARGET, true);
        arena.knock = true;
        arena.headroom = 2;
        observe(1);
        layer.disengage(s);
        assertEquals(1, arena.clears);
        assertEquals(0.0, s.knockValue);
        assertEquals(0, s.knockKind);
        assertEquals(4, s.headroom);
    }

    @Test
    void positionalPlanNeedsVoxels() {
        layer.engage(TARGET, true);
        positional.planAnswer = true;
        assertFalse(layer.planPositional(s, TacticUtility.Tactic.HIGH_GROUND));
        layer.useVoxels(FLAT);
        assertTrue(layer.planPositional(s, TacticUtility.Tactic.HIGH_GROUND));
        assertEquals(SpacingController.FORWARD, layer.positionalForward());
    }

    // ---- feints ------------------------------------------------------------------------------------------------------

    @Test
    void feintsNeverFireAgainstAMob() {
        layer.engage(TARGET, false);
        feints.move = FeintPlanner.Move.BAIT_RETREAT;
        observe(1);
        assertEquals(FeintPlanner.Move.NONE, layer.planFeint(s, st, opp, 1, StrafePlanner.NONE, 3.0));
        assertEquals(0, feints.plans, "never asked");
        assertEquals(0, feints.observes, "a mob's habits are not even recorded");
        assertEquals(0, human.units, "no random drawn");
    }

    @Test
    void feintsOverrideTheKeysAgainstAPlayerAndDrawFromTheHumanizer() {
        layer.engage(TARGET, true);
        observe(1);
        assertEquals(1, feints.observes);
        feints.move = FeintPlanner.Move.BAIT_RETREAT;
        feints.forwardOverride = -1;
        feints.allowSprint = false;
        assertEquals(FeintPlanner.Move.BAIT_RETREAT, layer.planFeint(s, st, opp, 1, StrafePlanner.LEFT, 3.0));
        assertEquals(1, human.units);
        assertEquals(SpacingController.BACK, layer.feintForward(SpacingController.FORWARD));
        assertEquals(StrafePlanner.LEFT, layer.feintSide(StrafePlanner.LEFT), "strafe kept");
        assertFalse(layer.feintSprint(true));
        assertTrue(feints.lastCanBackUp);
        assertEquals(1, layer.feintsStarted());
        feints.move = FeintPlanner.Move.BREAK_PATTERN;
        feints.strafeOverride = -1; // right key
        layer.planFeint(s, st, opp, 2, StrafePlanner.LEFT, 3.0);
        assertEquals(StrafePlanner.RIGHT, layer.feintSide(StrafePlanner.LEFT));

        st.feints = false;
        feints.plans = 0;
        assertEquals(FeintPlanner.Move.NONE, layer.planFeint(s, st, opp, 3, StrafePlanner.LEFT, 3.0));
        assertEquals(0, feints.plans, "setting off");
    }

    // ---- rank dial ---------------------------------------------------------------------------------------------------

    @Test
    void rankDialRequestsOneNotchAboveOncePerSecondAndClears() {
        layer.engage(TARGET, true);
        skill.samples = ProFeelLayer.RANK_MIN_SAMPLES;
        skill.skill = 0.62;
        observe(1);
        assertEquals(0, human.requests, "off by default");

        st.rankDial = true;
        skill.samples = ProFeelLayer.RANK_MIN_SAMPLES - 1;
        observe(2);
        assertEquals(0, human.requests, "too few samples");
        skill.samples = ProFeelLayer.RANK_MIN_SAMPLES;
        observe(3);
        assertEquals(1, human.requests);
        assertEquals(CombatAssistant.ID, human.lastModule);
        assertEquals(0.72, human.lastSkill, 1e-9);
        for (long t = 4; t < 3 + ProFeelLayer.RANK_REQUEST_TICKS; t++) {
            observe(t);
        }
        assertEquals(1, human.requests, "at most once a second");
        observe(3 + ProFeelLayer.RANK_REQUEST_TICKS);
        assertEquals(2, human.requests);

        skill.skill = 0.95;
        observe(3 + 2L * ProFeelLayer.RANK_REQUEST_TICKS);
        assertEquals(ProFeelLayer.RANK_HI, human.lastSkill, 1e-9, "clamped");

        layer.disengage(s);
        assertEquals(1, human.clears);
        assertFalse(human.holding);
        layer.disengage(s);
        assertEquals(1, human.clears, "nothing to drop twice");
    }

    @Test
    void rankDialSettingOffDropsTheRequest() {
        layer.engage(TARGET, true);
        st.rankDial = true;
        skill.samples = 20;
        observe(1);
        assertTrue(layer.skillRequested());
        st.rankDial = false;
        observe(2);
        assertFalse(layer.skillRequested());
        assertEquals(1, human.clears);
    }

    @Test
    void skillEstimatorIsFedFromSwingsHitsMissesAndReactions() {
        layer.engage(TARGET, true);
        observe(1);
        // our step-in at tick 2, its swing 4 ticks later (we are in its reach)
        layer.afterMovement(1, true, 0, 2);
        s.targetSwingStarted = true;
        observe(6);
        s.targetSwingStarted = false;
        assertEquals(1, skill.swings);
        assertEquals(1, skill.swingsInReach);
        assertEquals(1, skill.reactions);
        assertEquals(4, skill.lastReaction);

        layer.onSwingSent(s, st, 7);
        layer.noteOwnHit(8);
        assertEquals(1, skill.ownHits);
        layer.noteOwnHit(9);
        assertEquals(1, skill.ownHits, "one outcome per swing");

        layer.onSwingSent(s, st, 10);
        observe(10 + ProFeelLayer.MISS_WINDOW + 1);
        assertEquals(1, skill.ownMisses, "no hit within the window");

        layer.noteOpponentHit(30, 3f);
        assertEquals(1, skill.hitsTaken);
    }

    // ---- the read ----------------------------------------------------------------------------------------------------

    @Test
    void readStateIsPublishedAndCleared() {
        ReadState read = new ReadState();
        layer.useReadState(read);
        layer.engage(TARGET, true);
        opp.noteSwing(TARGET, 1, 1.6);
        phase.confidence = 0.5;
        phase.sign = 1; // to its own right = our left; we face +x, so our left is -z... (fz, -fx) = (0, -1)
        observe(2);
        assertTrue(read.active);
        assertEquals(TARGET, read.targetId);
        assertTrue(read.charge < 1f, "just swung: charging");
        assertTrue(read.readyIn > 0);
        assertEquals(0f, read.tiltX, 1e-6);
        assertEquals(-0.5f, read.tiltZ, 1e-6);
        layer.afterMovement(1, true, read.readyIn, 2);
        assertTrue(read.steppingIn, "stepping in on its cooldown");
        layer.disengage(s);
        assertFalse(read.active);
    }

    @Test
    void cooldownReadOffShowsAFullCharge() {
        ReadState read = new ReadState();
        layer.useReadState(read);
        layer.engage(TARGET, true);
        opp.noteSwing(TARGET, 1, 1.6);
        st.cooldownRead = false;
        observe(2);
        assertEquals(1f, read.charge);
        assertEquals(0, read.readyIn);
    }
}
