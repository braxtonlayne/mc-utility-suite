package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.TradeDriver;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One owner of the shield while the assistant is engaged: the real {@link CombatAssistant} with the real
 * {@link ShieldDuelStage} tells the combat reflexes when a stage runs USE ({@link CombatAssistant#stageOwns}).
 */
class ShieldOwnerTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;

    FakeCombatWorld world;
    ActionArbiter arbiter;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    ShieldDuelStage stage;
    long[] now = {50_000L};

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        TradeDriver.shieldFeed().clear();
        world = new FakeCombatWorld();
        FakeCombatWorld.E t = world.add(TARGET, "Vindicator", 2.5, 64, 0);
        t.yaw = 90f;
        world.self.yaw = -90f;
        arbiter = new ActionArbiter();
        smoother = new InputSmoother(new FakeInputBackend(), new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        ShieldDuelStageTest.FakeFacts facts = new ShieldDuelStageTest.FakeFacts();
        facts.headYaw = 90f;
        stage = new ShieldDuelStage(facts);
        assistant.addStage(stage);
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
    void theStageOwnsUseOnlyWithBothSettingsOn() {
        assertTrue(stage.ownsKey(SimKey.USE));
        assertFalse(stage.ownsKey(SimKey.ATTACK));
        stage.configure(true, false);
        assertFalse(stage.ownsKey(SimKey.USE), "block setting off: the shield is not this stage's");
        stage.configure(false, true);
        assertFalse(stage.ownsKey(SimKey.USE), "duel off: the stage never raises");
    }

    @Test
    void anEngagedAssistantWithTheBlockStageOwnsUse() {
        assertNull(assistant.lease());
        assertFalse(assistant.stageOwns(SimKey.USE), "not engaged: the reflex keeps the shield");
        engage();
        assertTrue(assistant.stageOwns(SimKey.USE), "engaged with the block stage: the reflex stands down");
        assertFalse(assistant.stageOwns(SimKey.JUMP));
    }

    @Test
    void withTheBlockSettingOffTheReflexKeepsTheShieldInAFight() {
        stage.configure(true, false);
        engage();
        assertFalse(assistant.stageOwns(SimKey.USE));
    }

    @Test
    void ownershipEndsWithTheLease() {
        engage();
        assertTrue(assistant.stageOwns(SimKey.USE));
        arbiter.stopAll("test");
        assertFalse(assistant.stageOwns(SimKey.USE));
    }

    /**
     * The clash this rule removes: a second writer of USE on the fight lease lowers the stage's shield, and the
     * assistant's key cache never presses it again. Kept as a record of why the lease has one USE owner.
     */
    @Test
    void aSecondUseWriterOnTheFightLeaseWouldLeaveTheStagesShieldDown() {
        engage();
        ControlLease fight = assistant.lease();
        assistant.stageContext().hold(SimKey.USE, true);
        assertTrue(smoother.isKeyHeldByAutomation(SimKey.USE));
        smoother.setKeyHeld(fight, SimKey.USE, false); // what the shield reflex did on a borrowed lease
        assistant.stageContext().hold(SimKey.USE, true); // the stage's per-tick hold: cached, not sent again
        assertFalse(smoother.isKeyHeldByAutomation(SimKey.USE));
    }

    @Test
    void theOwnershipCheckAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        engage();
        boolean[] sink = {false};
        long bytes = AllocationProbe.measure(100_000, () -> sink[0] ^= assistant.stageOwns(SimKey.USE));
        assertTrue(bytes < 1_024, "stageOwns allocated " + bytes + " bytes");
    }
}
