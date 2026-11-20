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
}
