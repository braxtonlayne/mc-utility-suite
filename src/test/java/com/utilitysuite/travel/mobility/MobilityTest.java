package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.LegitPresets;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.travel.movement.ClutchLadder;
import com.utilitysuite.travel.movement.FallRiskEstimator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Elytra swap click plan and state machine, toggle latches, mount jump release, the Relaxed gate, allocation. */
class MobilityTest {

    @Test
    void hotbarElytraSwapsInOneClick() {
        ElytraSwapPlanner p = new ElytraSwapPlanner();
        assertEquals(1, p.planSwap(4));
        assertEquals(ElytraSwapPlanner.CHEST_MENU_SLOT, p.slot(0));
        assertEquals(4, p.button(0));
        assertEquals(IContainerClicker.Click.SWAP, p.click(0));
    }

    @Test
    void mainInventoryElytraTakesThreePickups() {
        ElytraSwapPlanner p = new ElytraSwapPlanner();
        assertEquals(3, p.planSwap(20));
        assertEquals(20, p.slot(0));
        assertEquals(ElytraSwapPlanner.CHEST_MENU_SLOT, p.slot(1));
        assertEquals(20, p.slot(2));
        for (int i = 0; i < 3; i++) {
            assertEquals(IContainerClicker.Click.PICKUP, p.click(i));
        }
        assertEquals(0, p.planSwap(40));
        assertEquals(36, ElytraSwapPlanner.menuSlot(0));
        assertEquals(9, ElytraSwapPlanner.menuSlot(9));
    }

    @Test
    void swapStateMachineRunsAFullFall() {
        ElytraSwapPlanner p = new ElytraSwapPlanner();
        assertEquals(ElytraSwapPlanner.Action.NONE, p.step(5, 20, 3, false, false, false, false), "short fall");
        assertEquals(ElytraSwapPlanner.Action.CLICK_SWAP_ON, p.step(40, 20, 3, false, false, false, false));
        assertEquals(3, p.swappedFrom());
        assertEquals(ElytraSwapPlanner.Action.NONE, p.step(39, 20, 3, false, false, false, true));
        assertEquals(ElytraSwapPlanner.Action.TAP_JUMP, p.step(38, 20, -1, true, false, false, false));
        assertEquals(ElytraSwapPlanner.Action.NONE, p.step(37, 20, -1, true, true, false, false));
        assertEquals(ElytraSwapPlanner.Phase.GLIDING, p.phase());
        assertEquals(ElytraSwapPlanner.Action.CLICK_SWAP_BACK, p.step(0, 20, -1, true, false, true, false));
        assertEquals(ElytraSwapPlanner.Action.NONE, p.step(0, 20, -1, false, false, true, false));
        assertEquals(ElytraSwapPlanner.Action.DONE, p.step(0, 20, -1, false, false, true, false));
        assertEquals(ElytraSwapPlanner.Phase.IDLE, p.phase());
    }

    @Test
    void noSwapWhenAlreadyWearingOne() {
        ElytraSwapPlanner p = new ElytraSwapPlanner();
        assertEquals(ElytraSwapPlanner.Action.NONE, p.step(40, 20, 3, true, false, false, false));
    }

    @Test
    void toggleLatchReleasesOnRealKey() {
        ToggleLatch l = new ToggleLatch();
        assertFalse(l.tick(false, false));
        assertTrue(l.pressToggle());
        assertTrue(l.tick(false, false));
        assertFalse(l.tick(true, false), "a fresh real press releases");
        assertFalse(l.tick(true, false));
        l.pressToggle();
        assertTrue(l.tick(true, false), "the real key still held from before does not count as fresh");
    }

    @Test
    void toggleLatchHeldRealKeyThenToggleKeepsLatch() {
        ToggleLatch l = new ToggleLatch();
        l.tick(true, false);
        l.pressToggle();
        assertTrue(l.tick(true, false));
        assertFalse(l.pressToggle(), "pressing the toggle again releases");
        l.pressToggle();
        assertFalse(l.tick(false, true), "a screen drops it");
        l.pressToggle();
        l.release();
        assertFalse(l.tick(false, false), "Stop-All releases");
    }

    @Test
    void mountJumpReleasesAtFullBar() {
        MountJumpRelease m = new MountJumpRelease();
        assertEquals(MountJumpRelease.Action.NONE, m.tick(true, true, 0f, 0.95f));
        assertEquals(MountJumpRelease.Action.HOLD, m.tick(true, false, 0.1f, 0.95f), "tap released: hold");
        assertEquals(MountJumpRelease.Action.NONE, m.tick(true, false, 0.5f, 0.95f));
        assertEquals(MountJumpRelease.Action.RELEASE, m.tick(true, false, 1.0f, 0.95f));
        assertFalse(m.holding());
    }

    @Test
    void mountJumpUserPressWins() {
        MountJumpRelease m = new MountJumpRelease();
        m.tick(true, true, 0f, 0.95f);
        m.tick(true, false, 0f, 0.95f);
        assertEquals(MountJumpRelease.Action.RELEASE, m.tick(true, true, 0.4f, 0.95f));
    }

    @Test
    void mountJumpLeavesManualChargeAlone() {
        MountJumpRelease m = new MountJumpRelease();
        for (int i = 0; i < 8; i++) {
            m.tick(true, true, 0.1f * i, 0.95f);
        }
        assertEquals(MountJumpRelease.Action.NONE, m.tick(true, false, 0.9f, 0.95f));
        assertFalse(m.holding());
    }

    @Test
    void gateRefusesUnderLegitOrOnForeignServers() {
        assertEquals(RelaxedGate.NEED_RELAXED, RelaxedGate.flyRefusal(true, true, true, LegitPresets.Level.LEGIT));
        assertEquals(RelaxedGate.NEED_RELAXED, RelaxedGate.flyRefusal(true, true, true, null));
        assertEquals(RelaxedGate.NEED_FLIGHT, RelaxedGate.flyRefusal(false, false, false,
                LegitPresets.Level.RELAXED));
        assertNull(RelaxedGate.flyRefusal(false, false, true, LegitPresets.Level.RELAXED));
        assertNull(RelaxedGate.flyRefusal(true, false, false, LegitPresets.Level.RELAXED));
        assertNotNull(RelaxedGate.velocityRefusal(false, false, LegitPresets.Level.RELAXED));
        assertNotNull(RelaxedGate.velocityRefusal(true, true, LegitPresets.Level.BALANCED));
        assertNull(RelaxedGate.velocityRefusal(false, true, LegitPresets.Level.RELAXED));
        assertEquals(0.25, RelaxedGate.scale(0.5, 0.5));
        assertEquals(0.5, RelaxedGate.scale(0.5, 3.0), "never amplifies");
    }

    @Test
    void perTickPathsAreAllocationFree() {
        Assumptions.assumeTrue(AllocationProbe.supported(), "thread allocation counters unavailable");
        FallRiskEstimator risk = new FallRiskEstimator();
        ClutchLadder ladder = new ClutchLadder();
        ElytraSwapPlanner swap = new ElytraSwapPlanner();
        ToggleLatch latch = new ToggleLatch();
        MountJumpRelease mount = new MountJumpRelease();
        long bytes = AllocationProbe.measure(100_000, () -> {
            risk.update(1.2, 20.0, 0.2, 5, 1);
            ladder.begin(30, 18f);
            ladder.offer(ClutchLadder.Option.BLOCK, 10, 2f);
            ladder.offerElytra(true);
            ladder.offerWaterSteer(1.5, true);
            ladder.offerPearl(true, 90, 450, 8, 5f);
            ladder.best();
            swap.planSwap(20);
            swap.step(40, 20, 3, false, false, false, false);
            latch.tick(false, false);
            mount.tick(true, false, 0.5f, 0.95f);
        });
        assertTrue(bytes < 16 * 1024, "allocated " + bytes);
    }
}
