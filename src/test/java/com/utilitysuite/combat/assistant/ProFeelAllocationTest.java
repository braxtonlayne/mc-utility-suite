package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.tactics.FeintPlanner;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A full engaged tick of the Combat Assistant with every pro-feel feature on allocates nothing in steady state: the
 * production stubs, scripted units that keep every feature busy (combo plan, strafe phase, arena read, feints, rank
 * dial, the read), and a pearl intercept run. {@code aimAt} only allocates a rotation target when the aim moves by
 * more than its retarget threshold; the targets here stand still so the aim settles.
 */
class ProFeelAllocationTest {

    static final int LOCAL = 1;
    static final int DUELIST = 30;
    static final long LIMIT = 1_024;

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    long[] now = {50_000L};

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.addPlayer(DUELIST, "Duelist", 3, 64, 0);
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        assistant.setEnabled(true);
        registry.register(assistant);
        CombatSettings c = assistant.config();
        c.comboKeeper = true;
        c.anticipatoryAim = true;
        c.strafeWith = true;
        c.arenaRead = true;
        c.feints = true;
        c.rankDial = true;
        c.pearlIntercept = true;
        assistant.useArenaVoxels(ProFeelLayerTest.FLAT);
        assistant.useReadState(new ReadState());
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", DUELIST, DUELIST);
        for (int i = 0; i < 3; i++) {
            tick();
        }
        assertNotNull(assistant.lease());
        assertEquals(DUELIST, assistant.targetId());
    }

    void tick() {
        smoother.preTick();
        arbiter.tick();
        registry.tick();
        smoother.postTick();
        now[0] += 50;
    }

    long measure() {
        Runnable body = () -> {
            smoother.preTick();
            assistant.onTick();
            smoother.postTick();
        };
        AllocationProbe.measure(50_000, body); // absorbs one-time JIT recompiles
        return AllocationProbe.measure(50_000, body);
    }

    @Test
    void productionStubsWithEveryFeatureOnAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Services.registerBodySims(FakeCombatWorld.stillBodies()); // real solver; the combo keeper is built once
        Services.registerHumanizer(new ProFeelLayerTest.RecordingHumanizer());
        assistant.usePearlSource(new PearlInterceptTest.ScriptedPearls()); // no pearl in flight
        assistant.config().critSync = false;
        world.self.attackStrength = 1f; // swings every tick: the combo keeper is asked every tick
        engage();
        long bytes = measure();
        assertEquals(com.utilitysuite.combat.assistant.CombatState.ENGAGED, assistant.state());
        assertTrue(assistant.swings() > 1000);
        assertTrue(bytes < LIMIT, "engaged tick (production stubs) allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void theKnockVectorDriveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ProFeelLayer feel = assistant.proFeel();
        ProFeelLayerTest.ScriptedArena arena = new ProFeelLayerTest.ScriptedArena();
        arena.knock = true; // value 0.8 toward lava: the planner circles for the knock instead of the solver
        feel.useArena(arena);
        ProFeelLayerTest.ScriptedFeints feints = new ProFeelLayerTest.ScriptedFeints();
        feints.move = FeintPlanner.Move.BREAK_PATTERN;
        feel.useFeints(feints);
        Services.registerHumanizer(new ProFeelLayerTest.RecordingHumanizer());
        world.self.attackStrength = 0.3f;
        engage();
        long bytes = measure();
        assertTrue(arena.scans > 1000, "arena scanned every tick");
        assertEquals(0, feints.plans, "no feints while the knock vector drives the movement");
        assertTrue(bytes < LIMIT, "engaged tick (knock vector) allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void thePositionalDriveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ProFeelLayer feel = assistant.proFeel();
        ProFeelLayerTest.ScriptedPositional positional = new ProFeelLayerTest.ScriptedPositional();
        world.addPlayer(DUELIST, "Duelist", 3.6, 64, 0); // past 3 blocks: inside it FIGHT keeps a winning duel
        positional.highGround = 1.0; // the utility rates the spot: HIGH_GROUND takes the movement over
        positional.planAnswer = true;
        feel.usePositional(positional);
        ProFeelLayerTest.ScriptedFeints feints = new ProFeelLayerTest.ScriptedFeints();
        feints.move = FeintPlanner.Move.BREAK_PATTERN;
        feel.useFeints(feints);
        Services.registerHumanizer(new ProFeelLayerTest.RecordingHumanizer());
        world.self.attackStrength = 0.3f;
        engage();
        long bytes = measure();
        assertTrue(positional.plans > 1000, "the positional tactic drove the movement");
        assertTrue(feints.plans < 100, "feints only before the tactic took over (the utility dwell), not while it drives");
        assertTrue(bytes < LIMIT, "engaged tick (positional drive) allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void busyScriptedUnitsOnTheSolverPathAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ProFeelLayer feel = assistant.proFeel();
        TradeDriverTest.ScriptedStep step = new TradeDriverTest.ScriptedStep();
        step.answer(0, -1, 1.0, 1.0); // ties: strafe-with breaks them
        assistant.useTradeDriver(new TradeDriver(step));
        ProFeelLayerTest.ScriptedPhase phase = new ProFeelLayerTest.ScriptedPhase();
        phase.confidence = 0.8;
        phase.sign = 1;
        phase.useOffset = true;
        phase.offZ = 0.5;
        feel.usePhase(phase);
        ProFeelLayerTest.ScriptedArena arena = new ProFeelLayerTest.ScriptedArena();
        arena.knock = true;
        arena.knockValue = 0.3; // read every refresh but below KNOCK_VECTOR_MIN_VALUE: this test stays on the solver
        feel.useArena(arena);
        ProFeelLayerTest.ScriptedPositional positional = new ProFeelLayerTest.ScriptedPositional();
        positional.highGround = 0.3;
        feel.usePositional(positional);
        ProFeelLayerTest.ScriptedFeints feints = new ProFeelLayerTest.ScriptedFeints();
        feints.move = FeintPlanner.Move.BREAK_PATTERN;
        feints.strafeOverride = 1;
        feel.useFeints(feints);
        ProFeelLayerTest.ScriptedSkill skill = new ProFeelLayerTest.ScriptedSkill();
        skill.samples = 50;
        feel.useSkill(skill);
        ProFeelLayerTest.RecordingHumanizer human = new ProFeelLayerTest.RecordingHumanizer();
        Services.registerHumanizer(human);
        world.self.attackStrength = 0.3f;
        engage();
        long bytes = measure();
        assertTrue(feints.plans > 1000, "feints planned every tick");
        assertTrue(arena.scans > 1000, "arena scanned every tick");
        assertTrue(human.requests > 100, "rank dial requested once a second");
        assertTrue(bytes < LIMIT, "engaged tick (busy units) allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void anActiveComboPlanAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ProFeelLayerTest.ScriptedCombo combo = new ProFeelLayerTest.ScriptedCombo();
        combo.strafe = -1;
        assistant.proFeel().useCombo(combo);
        assistant.config().critSync = false;
        world.self.attackStrength = 1f;
        engage();
        long bytes = measure();
        assertTrue(combo.begins > 1000);
        assertTrue(combo.steps > 1000);
        assertTrue(bytes < LIMIT, "engaged tick (combo keeper) allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void aPearlInterceptRunAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        CombatRouteHandoverTest.FakeRunner runner = new CombatRouteHandoverTest.FakeRunner(smoother);
        Services.registerRouteRunner(runner);
        PearlInterceptTest.ScriptedPearls pearls = new PearlInterceptTest.ScriptedPearls();
        pearls.thrower = DUELIST;
        pearls.flying = true;
        assistant.usePearlSource(pearls);
        assistant.proFeel().usePearlLanding(new PearlInterceptTest.ScriptedLanding());
        world.self.attackStrength = 0.3f;
        engage();
        assertTrue(assistant.routeActive());
        long bytes = measure();
        assertTrue(assistant.routeActive());
        assertTrue(runner.pursues > 1000);
        assertTrue(bytes < LIMIT, "engaged tick (pearl intercept) allocated " + bytes + " bytes over 50k ticks");
    }
}
