package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.SimKey;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ShieldDuelStage#step} runs every engaged tick: zero bytes per step after warm-up. */
class ShieldDuelAllocationTest {

    /** Allocation-free context: counts calls only. */
    static final class CountingContext implements FightContext {
        final OpponentModel om = new OpponentModel();
        long clock;
        long calls;

        @Override
        public CombatSettings settings() {
            return null;
        }

        @Override
        public CombatWorld world() {
            return null;
        }

        @Override
        public OpponentModel opponents() {
            return om;
        }

        @Override
        public int targetId() {
            return 10;
        }

        @Override
        public boolean targetIsPlayer() {
            return true;
        }

        @Override
        public long clock() {
            return clock;
        }

        @Override
        public boolean eating() {
            return (clock & 255) > 240;
        }

        @Override
        public int weaponSlot() {
            return 0;
        }

        @Override
        public float lastHitDamage() {
            return 0f;
        }

        @Override
        public void aimAtTarget(CombatSnapshot s) {
            calls++;
        }

        @Override
        public void aimAt(float yaw, float pitch, float speed) {
            calls++;
        }

        @Override
        public void move(int forward, int side, boolean sprint) {
            calls++;
        }

        @Override
        public void retreatMove(CombatSnapshot s) {
            calls++;
        }

        @Override
        public boolean retreatRoute(CombatSnapshot s) {
            return false;
        }

        @Override
        public void tapAttack() {
            calls++;
        }

        @Override
        public void hold(SimKey key, boolean down) {
            calls++;
        }

        @Override
        public void tap(SimKey key, int holdTicks, int delayTicks) {
            calls++;
        }

        @Override
        public void selectHotbar(int slot) {
            calls++;
        }

        @Override
        public void selectWeapon() {
            calls++;
        }

        @Override
        public void startEating(CombatSnapshot s, int hotbarSlot) {
            calls++;
        }

        @Override
        public void vetoSwing() {
            calls++;
        }

        @Override
        public void vetoJump() {
            calls++;
        }

        @Override
        public void hitPolicy(HitPlanner.Policy policy) {
            calls++;
        }

        @Override
        public void forceSide(int side) {
            calls += side;
        }

        @Override
        public void widenSpacing(double blocks) {
            calls++;
        }

        @Override
        public void pressAdvantage() {
            calls++;
        }

        @Override
        public void requestTarget(int entityId) {
            calls++;
        }
    }

    @Test
    void stepAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ShieldDuelStageTest.FakeFacts facts = new ShieldDuelStageTest.FakeFacts();
        ShieldDuelStage stage = new ShieldDuelStage(facts);
        CountingContext ctx = new CountingContext();
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.pz = 0;
        s.eyeY = 65.62;
        s.yaw = -90f;
        s.attackStrength = 1f;
        s.crosshairOnTarget = true;
        int[] k = {0};
        Runnable body = () -> {
            int i = k[0]++;
            ctx.clock = i;
            double x = 2.0 + (i & 7) * 0.2;
            s.tx = x;
            s.ty = 64;
            s.tz = (i & 8) != 0 ? 0.5 : -0.5;
            s.minX = x - 0.3;
            s.maxX = x + 0.3;
            s.minY = 64;
            s.maxY = 65.8;
            s.minZ = s.tz - 0.3;
            s.maxZ = s.tz + 0.3;
            s.targetBlocking = (i & 16) != 0;
            s.hazardLeft = (i & 32) != 0;
            facts.headYaw = (i & 64) != 0 ? 90f : -90f;
            facts.targetUse = s.targetBlocking ? 8 : (i & 3);
            facts.ownReady = (i & 128) == 0;
            facts.axe = (i & 512) != 0;
            facts.disabled = (i % 997) == 0 ? 100 : -1;
            if ((i & 15) == 0) {
                ctx.om.noteSwing(10, i, 1.6);
            }
            stage.step(s, ctx);
            if ((i & 4095) == 0) {
                stage.configure((i & 8192) == 0, true);
            }
        };
        for (int w = 0; w < 20_000; w++) {
            body.run(); // warm-up: JIT and every branch
        }
        long bytes = AllocationProbe.measure(100_000, body);
        assertTrue(bytes < 1_024, "step allocated " + bytes + " bytes over 100k ticks");
        assertTrue(ctx.calls != 0);
    }
}
