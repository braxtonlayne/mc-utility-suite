package com.utilitysuite.combat;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.DisengageReason;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-over key and the Combat Assistant: real {@link ActionArbiter}, {@link InputSmoother} and
 * {@link ModuleRegistry}, a fake world, and the core's key as a fake {@link IAutopilot}. The module itself is OFF
 * unless a test says otherwise: the key alone starts and ends the fight.
 */
class HandOverCombatTest {

    static final int ZOMBIE = 10;
    static final int COW = 11;
    static final int ALLY = 20;
    /** Rotation cap per tick, with a little float slack. */
    static final float MAX_STEP = (float) (IInputSmoother.MAX_TURN_DEG_PER_SEC / 20.0) + 0.01f;

    FakeCombatWorld world;
    AllyRoster roster;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    long[] now;
    boolean[] held;
    float maxStep;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.add(ZOMBIE, "Zombie", 3, 64, 0).hostile = true;
        world.add(COW, "Cow", 0, 64, 3);
        world.addPlayer(ALLY, "AllyPlayer", -3, 64, 0);
        roster = new AllyRoster();
        roster.add(world.uuidOf(ALLY), "AllyPlayer", false);
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(4));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        now = new long[]{100_000L};
        assistant = new CombatAssistant(world, roster, arbiter, smoother, smoother, () -> now[0]);
        registry.register(assistant); // registered, not enabled
        held = new boolean[1];
        Services.registerAutopilot(new IAutopilot() {
            @Override
            public boolean isOn() {
                return held[0];
            }

            @Override
            public boolean allows(Reflex reflex) {
                return held[0];
            }

            @Override
            public Reach reach() {
                return Reach.ASSIST;
            }

            @Override
            public boolean handOverHeld() {
                return held[0];
            }
        });
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    /** One client tick in the game's order: core (arbiter, modules), then the combat bridge's own tick listener. */
    void tick(int n) {
        for (int i = 0; i < n; i++) {
            float before = backend.yaw();
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            assistant.autopilotTick();
            assistant.handOverTick();
            smoother.postTick();
            now[0] += 50;
            maxStep = Math.max(maxStep, Math.abs(net.minecraft.util.Mth.wrapDegrees(backend.yaw() - before)));
        }
    }

    void assertNoLease() {
        for (ControlChannel c : ControlChannel.all()) {
            assertFalse(arbiter.isHeldBy(c, CombatAssistant.ID), "channel " + c + " still held");
        }
    }

    @Test
    void idleWithoutTheKeyNothingHappens() {
        world.crosshairId = ZOMBIE;
        tick(40);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(0, assistant.engagements());
        assertEquals(0, world.scans, "no scan while the key is up");
        assertNoLease();
    }

    @Test
    void heldEngagesTheThreatUnderTheCrosshairAndReleaseEndsItWithinATick() {
        world.crosshairId = ZOMBIE;
        held[0] = true;
        tick(10);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertEquals(ZOMBIE, assistant.targetId());
        assertTrue(assistant.handOverEngaged());
        assertTrue(arbiter.isHeldBy(ControlChannel.CAMERA, CombatAssistant.ID)
                || arbiter.isHeldBy(ControlChannel.MOVEMENT, CombatAssistant.ID), "the fight runs under a lease");

        held[0] = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state(), "one tick after release");
        assertEquals(DisengageReason.HAND_OVER_RELEASED, assistant.lastDisengageReason());
        assertNoLease();
        tick(40);
        assertEquals(1, assistant.engagements(), "nothing engages again with the key up");
        assertTrue(maxStep <= MAX_STEP, "a rotation step of " + maxStep + " deg is over the cap");
    }

    @Test
    void neverAnAllyNeverANonThreatAndNoCrosshairPickWhereExtrasAreNotAllowed() {
        held[0] = true;
        world.crosshairId = ALLY;
        tick(20);
        assertEquals(CombatState.DORMANT, assistant.state(), "a roster ally is never targeted");
        world.crosshairId = COW;
        tick(20);
        assertEquals(CombatState.DORMANT, assistant.state(), "the target filter decides what a threat is");
        world.crosshairId = ZOMBIE;
        world.allowed = false;
        tick(20);
        assertEquals(CombatState.DORMANT, assistant.state(), "a proactive pick: single-player / allow-listed only");
        assertEquals(0, assistant.engagements());
    }

    @Test
    void heldEngagesWhoeverAttacksYouEvenWhereExtrasAreNotAllowed() {
        world.allowed = false;
        held[0] = true;
        tick(2);
        assistant.onDamageEvent(world.localPlayerId(), "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(3);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertEquals(ZOMBIE, assistant.targetId());
        held[0] = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertNoLease();
    }

    @Test
    void stopAllEndsTheFight() {
        world.crosshairId = ZOMBIE;
        held[0] = true;
        tick(10);
        assertEquals(CombatState.ENGAGED, assistant.state());
        arbiter.stopAll("test");
        held[0] = false; // the core revokes the key on Stop-All (HandOverTest covers that side)
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertNoLease();
    }

    @Test
    void anEngagementTheModuleStartedItselfSurvivesTheKey() {
        assistant.setEnabled(true);
        assistant.onDamageEvent(world.localPlayerId(), "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(3);
        assertEquals(CombatState.ENGAGED, assistant.state());
        held[0] = true;
        tick(5);
        held[0] = false;
        tick(5);
        assertEquals(CombatState.ENGAGED, assistant.state(), "the module's own fight is not the key's to end");
        assertFalse(assistant.handOverEngaged());
    }
}
