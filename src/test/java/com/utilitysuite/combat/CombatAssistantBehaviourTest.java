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
}
