package com.utilitysuite.combat;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.coach.FightCoach;
import com.utilitysuite.combat.coach.ThreatSource;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import com.utilitysuite.combat.assistant.TradeDriver;
import com.utilitysuite.combat.assistant.TradeDriverTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Engaged behaviour driven end to end through the real arbiter and smoother; the coach; hot-path allocation. */
class CombatAssistantBehaviourTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;

    FakeCombatWorld world;
    AllyRoster roster;
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
        world.add(TARGET, "Vindicator", 3, 64, 0);
        roster = new AllyRoster();
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, roster, arbiter, smoother, smoother, () -> now[0]);
        assistant.setEnabled(true);
        registry.register(assistant);
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            now[0] += 50;
        }
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        assertNotNull(assistant.lease());
    }

    @Test
    void totemGoesToOffhandThroughTheClickerWhenLethal() {
        world.self.health = 3f;
        world.self.totemInvSlot = 12;
        engage();
        tick(20); // container clicks are spaced by the smoother's gaussian timing
        assertFalse(backend.containerClicks.isEmpty(), "a SWAP click was queued and performed");
        int[] click = backend.containerClicks.get(0);
        assertEquals(12, click[0], "menu slot of inventory index 12");
        assertEquals(40, click[1], "offhand swap button");
        assertEquals(IContainerClicker.Click.SWAP.ordinal(), click[2]);
        assertTrue(backend.isDown(SimKey.BACK), "and backs off at low health");
        assertFalse(backend.isDown(SimKey.FORWARD));
    }

    @Test
    void drawsTheBestHotbarWeaponWhenTheFightStarts() {
        world.self.selectedSlot = 0;
        world.bestMelee = 3;
        engage();
        tick(1);
        assertEquals(3, backend.selectedSlot, "switched to the strongest weapon once engaged");
    }

    @Test
    void keepsTheHeldSlotWhenNothingBeatsIt() {
        world.self.selectedSlot = 4;
        backend.selectedSlot = 4;
        engage();
        tick(1);
        assertEquals(4, backend.selectedSlot);
    }

    @Test
    void eatsGoldenAppleThenReturnsToWeapon() {
        world.self.health = 6f;
        world.self.gappleSlot = 5;
        world.self.selectedSlot = 0;
        engage();
        tick(1);
        assertEquals(5, backend.selectedSlot);
        assertTrue(backend.isDown(SimKey.USE));
        world.self.usingItem = true;
        tick(10);
        world.self.usingItem = false; // finished eating
        world.self.health = 14f;
        world.self.gappleSlot = -1;
        tick(2);
        assertFalse(backend.isDown(SimKey.USE));
        assertEquals(0, backend.selectedSlot, "weapon slot restored");
    }

    @Test
    void shieldBreakSwapsToAxeAndBackThroughTheLease() {
        world.entities.get(TARGET).blocking = true;
        world.self.axeSlot = 2;
        world.self.selectedSlot = 0;
        engage();
        assertEquals(2, backend.selectedSlot, "axe selected");
        world.self.selectedSlot = 2;
        int clicksBefore = backend.clicks[SimKey.ATTACK.ordinal()];
        tick(1);
        assertEquals(clicksBefore + 1, backend.clicks[SimKey.ATTACK.ordinal()], "one axe swing");
        tick(1);
        assertEquals(0, backend.selectedSlot, "main weapon back");
    }

    @Test
    void disengageRestoresWeaponSlot() {
        world.entities.get(TARGET).blocking = true;
        world.self.axeSlot = 2;
        world.self.selectedSlot = 0;
        engage();
        assertEquals(2, backend.selectedSlot);
        world.entities.get(TARGET).alive = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(0, backend.selectedSlot);
    }

    @Test
    void closesDistanceAndStrafesInBand() {
        world.entities.get(TARGET).x = 6;
        world.self.attackStrength = 0.2f;
        engage();
        assertTrue(backend.isDown(SimKey.FORWARD));
        assertTrue(backend.isDown(SimKey.SPRINT));
        world.entities.get(TARGET).x = 2.9;
        tick(1);
        assertFalse(backend.isDown(SimKey.FORWARD));
        assertTrue(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "strafing in the band");
        world.self.hazardLeft = true;
        world.self.hazardRight = true;
        tick(1);
        assertFalse(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "hazards on both sides");
    }

    @Test
    void engagedTickAllocatesNothingInSteadyState() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        world.self.attackStrength = 0.3f; // charging: aim, spacing and strafe run every tick
        engage();
        long bytes = AllocationProbe.measure(50_000, () -> {
            smoother.preTick();
            assistant.onTick();
            smoother.postTick();
        });
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertTrue(bytes < 4_096, "engaged onTick allocated " + bytes + " bytes over 50k ticks");
    }

    // ---- hit-trade solver wiring ------------------------------------------------------------------------------------

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    TradeDriverTest.ScriptedStep scripted() {
        TradeDriverTest.ScriptedStep step = new TradeDriverTest.ScriptedStep();
        assistant.useTradeDriver(new TradeDriver(step));
        return step;
    }

    @Test
    void anInvalidSolverAnswerKeepsTheBandBehaviour() {
        TradeDriverTest.ScriptedStep step = scripted();
        step.valid = false;
        closesDistanceAndStrafesInBand();
        assertEquals(0, assistant.tradeTicks(), "the band and the strafe planner drove");
    }

    @Test
    void theProductionSolverDrivesInsideTheBandAndHazardsStillVeto() {
        Services.registerBodySims(FakeCombatWorld.stillBodies()); // real HitTradeSolver on still bodies
        world.entities.get(TARGET).x = 6;
        world.self.attackStrength = 0.2f;
        engage();
        assertTrue(backend.isDown(SimKey.FORWARD), "outside the solver range the band closes the distance");
        assertTrue(backend.isDown(SimKey.SPRINT));
        assertEquals(0, assistant.tradeTicks(), "solver not consulted beyond spacingMax + 2.5");
        world.entities.get(TARGET).x = 2.9;
        tick(1);
        assertTrue(assistant.tradeTicks() > 0, "a valid production answer drives the keys");
        world.self.hazardLeft = true;
        world.self.hazardRight = true;
        tick(4); // longer than the answer dwell
        assertFalse(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "hazards on both sides");
    }

    @Test
    void aValidSolverAnswerDrivesTheKeysInsteadOfTheBand() {
        TradeDriverTest.ScriptedStep step = scripted();
        world.entities.get(TARGET).x = 2.9; // inside the band: the band alone would hold
        world.self.attackStrength = 0.2f;
        step.answer(-1, 1, 1.0, 0.0); // back off to the left
        engage();
        tick(1);
        assertTrue(assistant.tradeTicks() > 0);
        assertTrue(backend.isDown(SimKey.BACK));
        assertFalse(backend.isDown(SimKey.FORWARD));
        assertTrue(backend.isDown(SimKey.LEFT));
        assertFalse(backend.isDown(SimKey.RIGHT));
        assertFalse(backend.isDown(SimKey.SPRINT), "no sprint while backing off");
    }

    @Test
    void theSolverIsNotUsedFarOutsideTheBandOrWhenOff() {
        TradeDriverTest.ScriptedStep step = scripted();
        step.answer(-1, 0, 1.0, 0.0);
        world.entities.get(TARGET).x = 7; // beyond spacing max + 2.5
        world.self.attackStrength = 0.2f;
        engage();
        tick(1);
        assertEquals(0, assistant.tradeTicks());
        assertTrue(backend.isDown(SimKey.FORWARD), "the band closes in");
        world.entities.get(TARGET).x = 3;
        assistant.config().hitTrade = false;
        tick(2);
        assertEquals(0, assistant.tradeTicks(), "setting off");
    }

    @Test
    void hazardsVetoTheSolverAnswer() {
        TradeDriverTest.ScriptedStep step = scripted();
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 0.2f;
        world.self.hazardFront = true;
        world.self.hazardLeft = true;
        step.answer(1, 1, 1.0, 0.0); // forward-left, straight into both hazards
        step.sprint = true;
        engage();
        tick(2);
        assertTrue(assistant.tradeTicks() > 0);
        assertFalse(backend.isDown(SimKey.FORWARD), "never steps toward the front hazard");
        assertFalse(backend.isDown(SimKey.LEFT), "never strafes into the left hazard");
        assertFalse(backend.isDown(SimKey.SPRINT));
    }

    @Test
    void sprintResetTakesPriorityOverTheSolver() {
        TradeDriverTest.ScriptedStep step = scripted();
        assistant.config().critSync = false;
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 1f; // swings on the engage tick
        step.answer(1, 0, 1.0, 0.0);
        step.sprint = true;
        int clicks = backend.clicks[SimKey.ATTACK.ordinal()];
        engage();
        assertTrue(backend.clicks[SimKey.ATTACK.ordinal()] > clicks || assistant.swings() > 0, "swung");
        assertFalse(backend.isDown(SimKey.FORWARD), "W released for the sprint reset");
        assertFalse(backend.isDown(SimKey.SPRINT));
        world.self.attackStrength = 0.2f;
        tick(4);
        assertTrue(backend.isDown(SimKey.FORWARD), "the solver's keys come back after the reset");
        assertTrue(backend.isDown(SimKey.SPRINT));
    }

    @Test
    void solverJumpsOnlyWhenCritSyncIsNotManagingHops() {
        TradeDriverTest.ScriptedStep step = scripted();
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 0.2f; // charging: crit sync is not waiting yet
        step.answer(0, 1, 1.0, 0.0);
        step.jump = true;
        engage();
        boolean jumped = false;
        for (int i = 0; i < 4; i++) {
            tick(1);
            jumped |= backend.down[SimKey.JUMP.ordinal()];
        }
        assertTrue(jumped, "the solver's hop was tapped");
        assertTrue(assistant.tradeTicks() > 0);
    }

    @Test
    void opponentSwingsFeedTheCooldownReading() {
        world.entities.get(TARGET).swingStarted = true;
        world.entities.get(TARGET).attackSpeed = 1.6;
        world.self.attackStrength = 0.2f;
        engage();
        tick(2);
        assertTrue(assistant.opponents().attackReadyIn(TARGET, 3) >= 0);
        assertTrue(assistant.opponents().cooldownTicks(TARGET) > 0);
    }

    @Test
    void engagedTickWithTheSolverPathAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        TradeDriverTest.ScriptedStep step = scripted();
        step.answer(1, -1, 1.0, 0.0);
        step.sprint = true;
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 0.3f;
        engage();
        long bytes = AllocationProbe.measure(50_000, () -> {
            smoother.preTick();
            assistant.onTick();
            smoother.postTick();
        });
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertTrue(assistant.tradeTicks() > 0);
        assertTrue(bytes < 4_096, "engaged onTick (solver path) allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void engagedTickWithTheProductionSolverAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Services.registerBodySims(FakeCombatWorld.stillBodies());
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 0.3f;
        engage();
        tick(1); // the solver is built once here (its scratch bodies allocate once)
        Runnable body = () -> {
            smoother.preTick();
            assistant.onTick();
            smoother.postTick();
        };
        // a first window absorbs one-time JIT recompiles (the scripted pro-feel tests in this JVM make the seam call
        // sites polymorphic, and a deopt in the window rematerializes a few objects once); steady state is measured
        AllocationProbe.measure(50_000, body);
        long bytes = AllocationProbe.measure(50_000, body);
        assertTrue(bytes < 4_096, "engaged onTick (production solver) allocated " + bytes + " bytes");
    }

    @Test
    void dormantTickAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        long bytes = AllocationProbe.measure(100_000, assistant::onTick);
        assertTrue(bytes < 1_024, "dormant onTick allocated " + bytes + " bytes");
    }

    // ---- coach ------------------------------------------------------------------------------------------------------

    @Test
    void coachSharesDecisionsAndNeverLeases() {
        boolean[] clean = {false};
        FightCoach coach = new FightCoach(world, assistant, roster, ThreatSource.NONE_SOURCE, () -> clean[0]);
        coach.setEnabled(true);
        assistant.setEnabled(false);

        coach.onTick();
        assertFalse(coach.readout().visible, "no opponent nearby: nothing drawn");

        world.crosshairId = TARGET;
        world.entities.get(TARGET).x = 2.0;
        world.entities.get(TARGET).blocking = true;
        world.self.axeSlot = 3;
        coach.onTick();
        assertTrue(coach.readout().visible);
        assertEquals(-1, coach.readout().range, "too close");
        assertTrue(coach.readout().swingReady);
        assertTrue(coach.readout().shieldBreak);
        assertFalse(coach.readout().retreat);

        world.self.health = 5f;
        coach.onTick();
        assertTrue(coach.readout().retreat);

        clean[0] = true;
        coach.onTick();
        assertFalse(coach.readout().visible, "Clean-Screen hides everything");

        roster.add(world.uuidOf(TARGET), "Vindicator", false);
        clean[0] = false;
        coach.onTick();
        assertFalse(coach.readout().visible, "allies are not opponents");

        for (ControlChannel c : ControlChannel.all()) {
            assertTrue(arbiter.holder(c).isEmpty(), "coach requested no lease");
        }
    }

    @Test
    void coachTickAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        FightCoach coach = new FightCoach(world, assistant, roster, ThreatSource.NONE_SOURCE, () -> false);
        world.crosshairId = TARGET;
        long bytes = AllocationProbe.measure(100_000, coach::onTick);
        assertTrue(coach.readout().visible);
        assertTrue(bytes < 1_024, "coach onTick allocated " + bytes + " bytes");
    }

    // ---- tactics ----------------------------------------------------------------------------------------------------

    @Test
    void gapAmbushHitsTheChaserMidJumpAndNeverWalksIntoThePit() {
        world.self.gapNear = 1.0; // ravine between us: near edge 1 block ahead, far edge 2.5
        world.self.gapFar = 2.5;
        world.self.attackStrength = 0.3f; // still charging: nothing to swing yet
        engage();
        tick(3);
        assertEquals(com.utilitysuite.combat.assistant.TacticsPlanner.Tactic.HOLD_GAP, assistant.tactic());
        assertFalse(backend.isDown(SimKey.FORWARD), "holds at the edge");
        assertFalse(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "no strafing on the lip");
        // the chaser jumps: airborne over the pit, in reach
        world.entities.get(TARGET).onGround = false;
        world.self.targetDropBelow = 20;
        world.self.attackStrength = 1f;
        int before = assistant.tacticSwings();
        tick(2);
        assertTrue(assistant.tacticSwings() > before, "swung while the target was over the drop");
    }

    // ---- food objective ---------------------------------------------------------------------------------------------

    static final int COW = 40;

    @Test
    void huntsAFoodAnimalOnlyWhenThereIsNoFoodAnywhere() {
        proactive();
        world.add(COW, "Cow", 5, 64, 0).food = true;
        world.invFood = 3;
        tick(30);
        assertEquals(CombatState.DORMANT, assistant.state(), "has food: leaves the cow alone");
        world.invFood = 0;
        world.storedFood = 4;
        tick(FOOD_REFRESH + 10);
        assertEquals(CombatState.DORMANT, assistant.state(), "food in a nearby chest counts");
        world.storedFood = 0;
        tick(FOOD_REFRESH + 10);
        assertEquals(COW, assistant.targetId());
        assertTrue(assistant.onFoodObjective());
    }

    static final int FOOD_REFRESH = com.utilitysuite.combat.assistant.FoodObjective.REFRESH_TICKS;

    @Test
    void aHostileAlwaysComesBeforeFood() {
        proactive();
        world.invFood = 0;
        world.add(COW, "Cow", 3, 64, 0).food = true;
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        assertEquals(COW, assistant.targetId());
        world.add(ZOMBIE, "Zombie", 6, 64, 0).hostile = true;
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        assertEquals(ZOMBIE, assistant.targetId(), "switched to the hostile mid-hunt");
        assertFalse(assistant.onFoodObjective());
    }

    @Test
    void walksOverTheDropsAfterTheKillThenStops() {
        proactive();
        world.invFood = 0;
        world.add(COW, "Cow", 3, 64, 0).food = true;
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        world.entities.get(COW).alive = false;
        world.drop = new double[]{2.5, 0, 0};
        tick(3);
        assertEquals(CombatState.ENGAGED, assistant.state(), "keeps control to collect");
        assertTrue(backend.isDown(SimKey.FORWARD), "walks to the drop");
        world.drop = null; // picked up
        world.invFood = 3;
        tick(2);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(1, assistant.foodKills());
        assertEquals(1, assistant.foodPickups());
        world.add(COW + 1, "Cow2", 4, 64, 0).food = true;
        tick(FOOD_REFRESH);
        assertEquals(CombatState.DORMANT, assistant.state(), "has food again: the objective is off");
    }

    // ---- proactive engagement ---------------------------------------------------------------------------------------

    static final int ZOMBIE = 20;
    static final int SKELETON = 21;

    void proactive() {
        assistant.config().engageMode = com.utilitysuite.combat.assistant.TargetSelector.Mode.PROACTIVE;
    }

    @Test
    void reactiveModeLeavesAnIdleHostileAlone() {
        world.add(ZOMBIE, "Zombie", 4, 64, 0).hostile = true;
        tick(40);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(0, world.scans, "reactive mode never scans");
    }

    @Test
    void proactiveEngagesAVisibleHostileInRange() {
        proactive();
        world.add(ZOMBIE, "Zombie", 4, 64, 0).hostile = true;
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertEquals(ZOMBIE, assistant.targetId());
        assertEquals(1, assistant.proactiveEngagements());
    }

    @Test
    void proactiveSkipsHostilesBehindWallsAndOutOfRange() {
        proactive();
        FakeCombatWorld.E hidden = world.add(ZOMBIE, "Zombie", 4, 64, 0);
        hidden.hostile = true;
        hidden.visible = false;
        world.add(SKELETON, "Skeleton", 12, 64, 0).hostile = true; // beyond the default 8-block engage range
        tick(30);
        assertEquals(CombatState.DORMANT, assistant.state());
        assistant.config().engageRequiresLos = false; // the relaxed legit level
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        assertEquals(ZOMBIE, assistant.targetId());
    }

    @Test
    void proactiveIsReactiveOnlyOutsideAllowedServers() {
        proactive();
        world.allowed = false;
        world.add(ZOMBIE, "Zombie", 4, 64, 0).hostile = true;
        tick(30);
        assertEquals(CombatState.DORMANT, assistant.state());
    }

    @Test
    void proactiveMovesOnToTheNextTargetWhenOneDies() {
        proactive();
        world.add(ZOMBIE, "Zombie", 3, 64, 0).hostile = true;
        world.add(SKELETON, "Skeleton", 6, 64, 0).hostile = true;
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        assertEquals(ZOMBIE, assistant.targetId());
        world.entities.get(ZOMBIE).alive = false;
        tick(1);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertEquals(SKELETON, assistant.targetId());
        assertNotNull(assistant.lease(), "kept the lease across the retarget");
    }

    @Test
    void cancelKeyPausesProactiveEngagement() {
        proactive();
        world.add(ZOMBIE, "Zombie", 4, 64, 0).hostile = true;
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 1);
        assistant.onCancelKey();
        tick(CombatAssistant.PAUSE_AFTER_CANCEL_TICKS - 10);
        assertEquals(CombatState.DORMANT, assistant.state(), "stays down while paused");
        tick(CombatAssistant.SCAN_INTERVAL_TICKS + 20);
        assertEquals(CombatState.ENGAGED, assistant.state(), "proactive again after the pause");
    }

    @Test
    void neverProactivelyTargetsARosterAlly() {
        proactive();
        assistant.config().targets = com.utilitysuite.combat.assistant.TargetSelector.Targets.HOSTILE_AND_PLAYERS;
        FakeCombatWorld.E friend = world.addPlayer(30, "Friend", 3, 64, 0);
        roster.add(friend.uuid, "Friend", false); // neverTarget by default
        tick(30);
        assertEquals(CombatState.DORMANT, assistant.state());
    }

    // ---- A8: broad tactic by utility -------------------------------------------------------------------------------

    @Test
    void readTheFightBacksOffWhenLosingBadlyWithNothingToHeal() {
        assistant.config().sustain = false; // isolate the utility from the sustain retreat
        world.self.attackStrength = 0.2f;
        engage();
        assertEquals(com.utilitysuite.combat.tactics.TacticUtility.Tactic.FIGHT, assistant.broadTactic());
        world.self.health = 3f; // took 17 in one tick
        tick(2);
        assertEquals(com.utilitysuite.combat.tactics.TacticUtility.Tactic.FLEE, assistant.broadTactic());
        assertTrue(backend.isDown(SimKey.BACK), "backs off");
        assertFalse(backend.isDown(SimKey.FORWARD));
    }

    @Test
    void readTheFightOffKeepsTheOldBehaviour() {
        assistant.config().sustain = false;
        assistant.config().tacticUtility = false;
        world.self.attackStrength = 0.2f;
        engage();
        world.self.health = 3f;
        tick(2);
        assertEquals(com.utilitysuite.combat.tactics.TacticUtility.Tactic.FIGHT, assistant.broadTactic());
    }

    // ---- hand-off to the player package's offhand manager ----------------------------------------------------------

    @Test
    void totemKeeperStandsAsideWhileTheOffhandManagerIsOn() {
        boolean[] on = {true};
        registry.register(new com.utilitysuite.core.api.IModule() {
            @Override
            public String id() {
                return "offhand_manager";
            }

            @Override
            public com.utilitysuite.core.api.ModuleCategory category() {
                return com.utilitysuite.core.api.ModuleCategory.TOOLS;
            }

            @Override
            public boolean isEnabled() {
                return on[0];
            }

            @Override
            public void setEnabled(boolean enabled) {
                on[0] = enabled;
            }

            @Override
            public void onTick() {
            }
        });
        assistant.bindModuleRegistry(registry);
        world.self.totemInvSlot = 20;
        tick(60);
        assertTrue(backend.containerClicks.isEmpty(), "the offhand manager owns the offhand");
        on[0] = false;
        tick(60);
        assertFalse(backend.containerClicks.isEmpty(), "keeper resumes when it is off");
    }

    // ---- pro-feel wiring (scripted units through the ProFeelLayer seams) --------------------------------------------

    static final int DUELIST = 30;

    com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedCombo scriptedCombo() {
        var c = new com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedCombo();
        assistant.proFeel().useCombo(c);
        return c;
    }

    com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedPhase scriptedPhase() {
        var p = new com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedPhase();
        assistant.proFeel().usePhase(p);
        return p;
    }

    com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedFeints scriptedFeints() {
        var f = new com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedFeints();
        assistant.proFeel().useFeints(f);
        return f;
    }

    /** Engage a player opponent (feints are a player-only feature). */
    void engagePlayer(double x) {
        world.addPlayer(DUELIST, "Duelist", x, 64, 0);
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", DUELIST, DUELIST);
        tick(1);
        assertNotNull(assistant.lease());
        assertEquals(DUELIST, assistant.targetId());
    }

    void tickWithFrames(int n) {
        for (int i = 0; i < n; i++) {
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            smoother.onFrame(0.025);
            smoother.onFrame(0.025);
            now[0] += 50;
        }
    }

    @Test
    void comboKeeperDrivesAheadOfTheSolverAfterItsReset() {
        TradeDriverTest.ScriptedStep step = scripted();
        step.answer(-1, 0, 1.0, 0.0); // the solver would back off
        var combo = scriptedCombo();
        combo.forward = 1;
        combo.strafe = 1; // left key
        combo.sprint = true;
        combo.resetTicks = 2;
        assistant.config().critSync = false;
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 1f; // swings on the engage tick
        engage();
        assertEquals(1, combo.begins, "the hit in reach started a plan");
        assertFalse(backend.isDown(SimKey.FORWARD), "W released for the keeper's reset");
        assertFalse(backend.isDown(SimKey.BACK), "not the solver's back step");
        world.self.attackStrength = 0.2f;
        tick(2);
        assertTrue(backend.isDown(SimKey.FORWARD), "the keeper's keys after its reset");
        assertTrue(backend.isDown(SimKey.LEFT));
        assertTrue(backend.isDown(SimKey.SPRINT));
        assertTrue(combo.steps > 0, "stepped every tick");

        world.self.hazardLeft = true;
        world.self.hazardFront = true;
        tick(1);
        assertFalse(backend.isDown(SimKey.LEFT), "hazards veto the keeper");
        assertFalse(backend.isDown(SimKey.FORWARD));
        assertFalse(backend.isDown(SimKey.SPRINT));

        world.self.hazardLeft = false;
        world.self.hazardFront = false;
        combo.stepAnswer = false; // plan over: the solver drives again
        tick(5);
        assertTrue(backend.isDown(SimKey.BACK), "back to the solver's answer");
    }

    @Test
    void comboKeeperSTapReplacesTheFixedReset() {
        scripted().answer(1, 0, 1.0, 0.0);
        var combo = scriptedCombo();
        combo.resetKind = com.utilitysuite.combat.tactics.ComboKeeper.RESET_S_TAP;
        combo.resetTicks = 2;
        assistant.config().critSync = false;
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 1f;
        engage();
        assertTrue(backend.isDown(SimKey.BACK), "the keeper's S-tap although the setting says W release");
    }

    @Test
    void comboKeeperOffOrSprintResetOffKeepsTheOldKeys() {
        TradeDriverTest.ScriptedStep step = scripted();
        step.answer(-1, 0, 1.0, 0.0);
        var combo = scriptedCombo();
        assistant.config().comboKeeper = false;
        assistant.config().critSync = false;
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 1f;
        engage();
        world.self.attackStrength = 0.2f;
        tick(3);
        assertEquals(0, combo.begins, "never consulted");
        assertTrue(backend.isDown(SimKey.BACK), "the solver drives");

        assistant.config().comboKeeper = true;
        assistant.config().sprintReset = com.utilitysuite.combat.assistant.CombatSettings.SprintReset.OFF;
        world.self.attackStrength = 1f;
        tick(1);
        assertEquals(0, combo.begins, "sprint reset OFF disables the keeper");
    }

    @Test
    void anticipatoryAimLeadsByTheLearnedPhaseAndOffRestoresTheVelocityLead() {
        var phase = scriptedPhase();
        phase.useOffset = true;
        phase.offX = 0.0;
        phase.offZ = 2.0; // it is about to be two blocks to the side
        world.self.attackStrength = 0.2f;
        world.entities.get(TARGET).x = 3.0;
        engage();
        tickWithFrames(40);
        assertEquals(-57.4f, backend.yaw, 3f, "aims where the strafe phase puts the target");

        assistant.config().anticipatoryAim = false;
        tickWithFrames(40);
        assertEquals(-90f, backend.yaw, 3f, "setting off: the plain velocity lead (target standing still)");
    }

    @Test
    void strafeWithOnlyBreaksSolverTies() {
        TradeDriverTest.ScriptedStep step = scripted();
        var phase = scriptedPhase();
        phase.confidence = 0.8;
        phase.sign = 1; // moving to its own right: we strafe left with it
        step.answer(0, -1, 1.0, 1.0); // right key chosen, every combination ties
        world.self.attackStrength = 0.2f;
        world.entities.get(TARGET).x = 3.0;
        engage();
        tick(2);
        assertTrue(backend.isDown(SimKey.LEFT), "a tie goes to strafing with the opponent");
        assertFalse(backend.isDown(SimKey.RIGHT));

        step.answer(0, -1, 1.0, 0.0); // the right key now scores better
        tick(2);
        assertTrue(backend.isDown(SimKey.RIGHT), "never over a better answer");
        assertFalse(backend.isDown(SimKey.LEFT));

        step.answer(0, -1, 1.0, 1.0);
        assistant.config().strafeWith = false;
        tick(2);
        assertTrue(backend.isDown(SimKey.RIGHT), "setting off: the solver's own pick");
    }

    @Test
    void lowHeadroomSuppressesCritHopsAndSolverJumps() {
        var arena = new com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedArena();
        arena.headroom = 2;
        assistant.proFeel().useArena(arena);
        assistant.useArenaVoxels(com.utilitysuite.combat.assistant.ProFeelLayerTest.FLAT);
        TradeDriverTest.ScriptedStep step = scripted();
        step.answer(0, 1, 1.0, 0.0);
        step.jump = true;
        world.entities.get(TARGET).x = 3.0;
        world.self.attackStrength = 1f; // ready: crit sync would hop first
        engage();
        boolean jumped = backend.down[SimKey.JUMP.ordinal()];
        for (int i = 0; i < 4; i++) {
            tick(1);
            jumped |= backend.down[SimKey.JUMP.ordinal()];
        }
        assertFalse(jumped, "no hop under a two-block ceiling");
        assertTrue(assistant.swings() > 0, "swings straight away instead of waiting on a crit hop");

        arena.headroom = 4;
        world.self.attackStrength = 1f;
        jumped = false;
        for (int i = 0; i < 4; i++) {
            tick(1);
            jumped |= backend.down[SimKey.JUMP.ordinal()];
        }
        assertTrue(jumped, "room to jump: the hops come back");
    }

    @Test
    void arenaReadOffOrWithoutVoxelsNeverScans() {
        var arena = new com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedArena();
        assistant.proFeel().useArena(arena);
        world.self.attackStrength = 0.2f;
        engage();
        tick(5);
        assertEquals(0, arena.scans, "no voxels set: idle");
        assistant.useArenaVoxels(com.utilitysuite.combat.assistant.ProFeelLayerTest.FLAT);
        tick(2);
        assertEquals(2, arena.scans, "budgeted scan every engaged tick");
        assistant.config().arenaRead = false;
        tick(3);
        assertEquals(2, arena.scans, "setting off");
        assistant.config().arenaRead = true;
        world.entities.get(TARGET).alive = false;
        tick(1);
        assertTrue(arena.clears > 0, "cleared on disengage");
    }

    @Test
    void feintsOverrideTheBandAgainstAPlayerAndHazardsVeto() {
        var feints = scriptedFeints();
        feints.move = com.utilitysuite.combat.tactics.FeintPlanner.Move.BAIT_RETREAT;
        feints.forwardOverride = -1;
        feints.allowSprint = false;
        world.self.attackStrength = 0.2f;
        world.entities.remove(TARGET);
        engagePlayer(3.0);
        tick(1);
        assertTrue(feints.plans > 0);
        assertTrue(backend.isDown(SimKey.BACK), "baiting a sprint-in");
        assertFalse(backend.isDown(SimKey.FORWARD));
        assertTrue(assistant.lastNote().startsWith("Feint"), "noted on the transition");
        world.self.hazardBack = true;
        tick(1);
        assertFalse(backend.isDown(SimKey.BACK), "never back into a hazard");

        world.self.hazardBack = false;
        assistant.config().feints = false;
        tick(2);
        assertFalse(backend.isDown(SimKey.BACK), "setting off: the band holds in its range");
    }

    @Test
    void feintsNeverFireAgainstAMob() {
        var feints = scriptedFeints();
        feints.move = com.utilitysuite.combat.tactics.FeintPlanner.Move.BAIT_RETREAT;
        feints.forwardOverride = -1;
        world.self.attackStrength = 0.2f;
        world.entities.get(TARGET).x = 2.9;
        engage(); // the Vindicator
        tick(5);
        assertEquals(0, feints.plans);
        assertFalse(backend.isDown(SimKey.BACK));
    }

    @Test
    void rankDialRequestsAndClearsTheHumanizerSkill() {
        var human = new com.utilitysuite.combat.assistant.ProFeelLayerTest.RecordingHumanizer();
        Services.registerHumanizer(human);
        var skill = new com.utilitysuite.combat.assistant.ProFeelLayerTest.ScriptedSkill();
        skill.samples = 20;
        skill.skill = 0.6;
        assistant.proFeel().useSkill(skill);
        world.self.attackStrength = 0.2f;
        engage();
        tick(30);
        assertEquals(0, human.requests, "off by default");

        assistant.config().rankDial = true;
        tick(1);
        assertEquals(1, human.requests);
        assertEquals(0.7, human.lastSkill, 1e-9);
        assertEquals(CombatAssistant.ID, human.lastModule);
        world.entities.get(TARGET).alive = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(1, human.clears, "dropped on disengage");

        world.entities.get(TARGET).alive = true;
        engage();
        tick(1);
        assertTrue(human.holding);
        assistant.onStopAll();
        assertFalse(human.holding, "dropped on Stop-All");

        engage();
        tick(1);
        assertTrue(human.holding);
        assistant.setEnabled(false);
        assertFalse(human.holding, "dropped on disable");

        assistant.setEnabled(true);
        engage();
        tick(1);
        assertTrue(human.holding);
        assistant.onWorldUnload();
        assertFalse(human.holding, "dropped on world unload");
    }

    @Test
    void theReadIsPublishedWhileEngagedAndClearedAfter() {
        var read = new com.utilitysuite.combat.tactics.ReadState();
        assistant.useReadState(read);
        world.entities.get(TARGET).swingStarted = true;
        world.entities.get(TARGET).attackSpeed = 1.6;
        world.self.attackStrength = 0.2f;
        engage();
        tick(1);
        assertTrue(read.active);
        assertEquals(TARGET, read.targetId);
        assertTrue(read.charge < 1f);
        assertTrue(read.readyIn > 0);
        world.entities.get(TARGET).alive = false;
        tick(1);
        assertFalse(read.active);
    }

    @Test
    void theProductionStubsChangeNothing() {
        // every pro-feel setting on, production stubs, voxels and a read: the band test passes unchanged
        assistant.useArenaVoxels(com.utilitysuite.combat.assistant.ProFeelLayerTest.FLAT);
        assistant.useReadState(new com.utilitysuite.combat.tactics.ReadState());
        assistant.config().rankDial = true;
        closesDistanceAndStrafesInBand();
    }
}
