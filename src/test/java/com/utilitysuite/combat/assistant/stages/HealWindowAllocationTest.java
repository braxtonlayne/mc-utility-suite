package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.tactics.HealWindow;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.SimKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HealWindowStage#step} and the {@link HealWindow} planner allocate nothing per tick. */
class HealWindowAllocationTest {

    private static final long BUDGET = 1_024;

    /** A context whose actions do nothing (the stage's own work is measured). */
    static final class StubContext implements FightContext {
        final CombatSettings settings = new CombatSettings();
        final FakeCombatWorld world = new FakeCombatWorld();
        final OpponentModel opponents = new OpponentModel();
        long clock;
        int eats;

        @Override public CombatSettings settings() { return settings; }
        @Override public CombatWorld world() { return world; }
        @Override public OpponentModel opponents() { return opponents; }
        @Override public int targetId() { return 10; }
        @Override public boolean targetIsPlayer() { return true; }
        @Override public long clock() { return clock; }
        @Override public boolean eating() { return false; }
        @Override public int weaponSlot() { return 0; }
        @Override public float lastHitDamage() { return 5f; }
        @Override public void aimAtTarget(CombatSnapshot s) { }
        @Override public void aimAt(float yaw, float pitch, float speed) { }
        @Override public void move(int forward, int side, boolean sprint) { }
        @Override public void retreatMove(CombatSnapshot s) { }
        @Override public boolean retreatRoute(CombatSnapshot s) { return false; }
        @Override public void tapAttack() { }
        @Override public void hold(SimKey key, boolean down) { }
        @Override public void tap(SimKey key, int holdTicks, int delayTicks) { }
        @Override public void selectHotbar(int slot) { }
        @Override public void selectWeapon() { }
        @Override public void startEating(CombatSnapshot s, int hotbarSlot) { eats++; }
        @Override public void vetoSwing() { }
        @Override public void vetoJump() { }
        @Override public void hitPolicy(HitPlanner.Policy policy) { }
        @Override public void forceSide(int side) { }
        @Override public void widenSpacing(double blocks) { }
        @Override public void pressAdvantage() { }
        @Override public void requestTarget(int entityId) { }
    }

    @Test
    void stepAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HealWindowStageTest.FakeHealFacts facts = new HealWindowStageTest.FakeHealFacts();
        facts.hunger = 12;
        facts.foodSlot = 5;
        facts.nutrition = 6;
        HealWindowStage stage = new HealWindowStage(facts);
        StubContext ctx = new StubContext();
        CombatSnapshot s = new CombatSnapshot();
        s.health = 20f;
        s.maxHealth = 20f;
        s.attackStrength = 1f;
        s.gappleSlot = 3;
        s.offhandTotem = true;
        s.tx = 3.0;
        s.minX = 2.7;
        s.maxX = 3.3;
        s.maxY = 1.8;
        s.eyeY = 1.62;
        s.minZ = -0.3;
        s.maxZ = 0.3;
        s.lineOfSight = true;
        stage.onEngage(ctx);
        int[] t = {0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            int i = t[0]++;
            ctx.clock = i;
            // cycle through hits, knockback windows, distance and lost sight
            s.health = 10f + (i % 11);
            s.targetHurtTime = i % 10;
            s.tvx = (i & 1) == 0 ? 0.9 : 0.0;
            s.targetOnGround = (i & 2) == 0;
            s.tx = 3.0 + (i % 13);
            s.lineOfSight = (i & 4) == 0;
            stage.step(s, ctx);
        });
        assertTrue(bytes < BUDGET, "step allocated " + bytes + " bytes");
        assertTrue(ctx.eats > 0, "the measured loop exercised the eat path");
    }

    @Test
    void plannerAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        long bytes = AllocationProbe.measure(100_000, () -> {
            int safe = HealWindow.safeTicks(3.5, 0.9, HealWindow.knockTicksLeft(9, false), 3.0,
                    HealWindow.PLAYER_APPROACH, 4, true);
            HealWindow.wantHeal(10f, 10f, 20f, 4.0, 2.5, 8f);
            HealWindow.plan(safe, HealWindow.EAT_TICKS, true, 1f);
            HealWindow.topUp(12, 6, safe, 32);
        });
        assertTrue(bytes < BUDGET, "planner allocated " + bytes + " bytes");
    }
}
