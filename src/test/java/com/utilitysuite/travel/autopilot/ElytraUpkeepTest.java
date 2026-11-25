package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.BlockQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hands-free takeoff, backup elytra swap and land-to-mend decision logic. */
class ElytraUpkeepTest {

    // ---- takeoff ----------------------------------------------------------------------------------------------------

    @Test
    void clearanceColumnCountsAirAboveTheHead() {
        BlockQuery ceilingAt70 = (x, y, z) -> y == 70;
        // feet at 64: head 65, checked 66..71 -> blocked at 70 after 4 clear blocks
        assertEquals(4, TakeoffSequence.clearAbove(ceilingAt70, 0, 64, 0, 6));
        assertFalse(TakeoffSequence.clearForTakeoff(ceilingAt70, 0, 64, 0));
        // feet at 62: checked 64..69, all clear
        assertTrue(TakeoffSequence.clearForTakeoff(ceilingAt70, 0, 62, 0));
        assertTrue(TakeoffSequence.clearForTakeoff((x, y, z) -> false, 5, 100, 5));
    }

    @Test
    void blockedColumnAbortsBeforeAnyJump() {
        TakeoffSequence t = new TakeoffSequence();
        assertEquals(TakeoffSequence.Action.ABORT_BLOCKED,
                t.step(false, true, false, 0, 0, false, 25, 10, false));
        assertEquals(TakeoffSequence.Phase.ABORTED, t.phase());
        assertEquals(TakeoffSequence.Action.NONE, t.step(true, true, false, 0, 0, false, 25, 10, false));
    }

    @Test
    void noRocketsOnTheGroundAborts() {
        TakeoffSequence t = new TakeoffSequence();
        assertEquals(TakeoffSequence.Action.ABORT_NO_ROCKETS, t.step(true, true, false, 0, 0, false, 25, 0, false));
    }

    @Test
    void groundTakeoffJumpsDeploysPitchesUpAndFires() {
        TakeoffSequence t = new TakeoffSequence();
        assertEquals(TakeoffSequence.Action.NONE, t.step(true, true, false, 0, 0, false, 25, 10, false));
        assertEquals(TakeoffSequence.Action.JUMP, t.step(true, true, false, 0, 0, false, 25, 10, false));
        // rising: no second press yet
        assertEquals(TakeoffSequence.Action.NONE, t.step(true, false, false, 1, 0.4, false, 20, 10, false));
        assertEquals(TakeoffSequence.Action.NONE, t.step(true, false, false, 3, 0.2, false, 18, 10, false));
        // apex: second press opens the elytra
        assertEquals(TakeoffSequence.Action.DEPLOY_JUMP, t.step(true, false, false, 6, 0.0, false, 15, 10, false));
        assertEquals(TakeoffSequence.Phase.DEPLOY, t.phase());
        assertEquals(TakeoffSequence.Action.NONE, t.step(true, false, false, 7, -0.1, true, 12, 10, false));
        assertEquals(TakeoffSequence.Phase.PITCH_UP, t.phase());
        // camera still far from the climb pitch: wait
        assertEquals(TakeoffSequence.Action.NONE, t.step(true, false, false, 8, -0.1, true, 12, 10, false));
        assertEquals(TakeoffSequence.Action.FIRE, t.step(true, false, false, 9, -0.1, true, 3, 10, false));
        TakeoffSequence.Action last = TakeoffSequence.Action.NONE;
        for (int i = 0; i < TakeoffSequence.BOOST_TICKS && last != TakeoffSequence.Action.CRUISE; i++) {
            last = t.step(true, false, false, 10 + i, 0.5, true, 2, 9, true);
        }
        assertEquals(TakeoffSequence.Action.CRUISE, last);
    }

    @Test
    void pitchTimeoutFiresAnywaySoTheGlideDoesNotStall() {
        TakeoffSequence t = new TakeoffSequence();
        t.step(true, false, false, 20, -0.5, true, 40, 10, false); // already gliding at start
        TakeoffSequence.Action a = TakeoffSequence.Action.NONE;
        for (int i = 0; i <= TakeoffSequence.PITCH_TIMEOUT && a != TakeoffSequence.Action.FIRE; i++) {
            a = t.step(true, false, false, 21 + i, -0.5, true, 40, 10, false);
        }
        assertEquals(TakeoffSequence.Action.FIRE, a);
    }

    @Test
    void waterTakeoffHoldsJumpUntilOutOfTheWater() {
        TakeoffSequence t = new TakeoffSequence();
        t.step(true, false, true, 5, 0, false, 25, 10, false);
        assertEquals(TakeoffSequence.Action.HOLD_JUMP, t.step(true, false, true, 6, 0.1, false, 25, 10, false));
        assertEquals(TakeoffSequence.Action.HOLD_JUMP, t.step(true, false, true, 7, 0.1, false, 25, 10, false));
        assertEquals(TakeoffSequence.Action.DEPLOY_JUMP, t.step(true, false, false, 8, 0.0, false, 25, 10, false));
    }

    @Test
    void elytraThatNeverOpensGivesUp() {
        TakeoffSequence t = new TakeoffSequence();
        t.step(true, true, false, 0, 0, false, 25, 10, false);
        TakeoffSequence.Action a = TakeoffSequence.Action.NONE;
        int airborne = 0;
        for (int i = 0; i < 200 && a != TakeoffSequence.Action.ABORT_NO_DEPLOY; i++) {
            boolean ground = i % 10 == 0; // hops: ground every 10 ticks, falling in between
            airborne = ground ? 0 : airborne + 1;
            a = t.step(true, ground, false, airborne, ground ? 0 : -0.2, false, 25, 10, false);
        }
        assertEquals(TakeoffSequence.Action.ABORT_NO_DEPLOY, a);
    }

    // ---- backup swap ------------------------------------------------------------------------------------------------

    @Test
    void spareChoicePrefersMendingThenMostUses() {
        int[] left = {-1, 300, 200, 50, -1};
        int[] max = {0, 432, 432, 432, 0};
        boolean[] mending = {false, false, true, true, false};
        // slot 3 has Mending but only 50/432 (< 25 %): not a spare; slot 2 Mending wins over slot 1
        assertEquals(2, BackupElytraSwap.pickSpare(left, max, mending, 30));
        mending[2] = false;
        assertEquals(1, BackupElytraSwap.pickSpare(left, max, mending, 30));
    }

    @Test
    void noSpareAboveAQuarterOrAboveTheWornOne() {
        int[] left = {100, 108, -1};
        int[] max = {432, 432, 0};
        boolean[] mending = {true, false, false};
        assertEquals(-1, BackupElytraSwap.pickSpare(left, max, mending, 20)); // 100 and 108 are <= 25 % of 432
        int[] left2 = {200};
        assertEquals(-1, BackupElytraSwap.pickSpare(left2, new int[]{432}, new boolean[]{false}, 250));
    }

    @Test
    void swapHappensBeforeTheReserveAndSettles() {
        BackupElytraSwap s = new BackupElytraSwap();
        int reserve = 20;
        assertFalse(BackupElytraSwap.due(reserve + BackupElytraSwap.SWAP_MARGIN + 1, reserve));
        assertEquals(BackupElytraSwap.Action.NONE, s.step(100, reserve, 5, true, false));
        assertEquals(BackupElytraSwap.Action.SWAP, s.step(reserve + 10, reserve, 5, true, false));
        assertTrue(s.swapping());
        for (int i = 0; i < BackupElytraSwap.SETTLE_TICKS; i++) {
            assertEquals(BackupElytraSwap.Action.WAIT, s.step(reserve + 10, reserve, -1, true, false));
        }
        // the new elytra is on: far from the reserve, nothing more to do
        assertEquals(BackupElytraSwap.Action.NONE, s.step(400, reserve, -1, true, false));
        assertFalse(s.swapping());
        assertFalse(s.gaveUp());
    }

    @Test
    void swapWaitsForSafeHeightAndIdleClicker() {
        BackupElytraSwap s = new BackupElytraSwap();
        assertEquals(BackupElytraSwap.Action.WAIT, s.step(25, 20, 5, false, false));
        assertEquals(BackupElytraSwap.Action.WAIT, s.step(25, 20, 5, true, true));
        assertEquals(BackupElytraSwap.Action.NONE, s.step(25, 20, -1, true, false)); // no spare: reserve as before
    }

    @Test
    void swapsThatChangeNothingGiveUp() {
        BackupElytraSwap s = new BackupElytraSwap();
        for (int attempt = 0; attempt < BackupElytraSwap.MAX_FAILURES; attempt++) {
            assertEquals(BackupElytraSwap.Action.SWAP, s.step(30, 20, 5, true, false));
            for (int i = 0; i < BackupElytraSwap.SETTLE_TICKS; i++) {
                s.step(30, 20, -1, true, false);
            }
        }
        assertEquals(BackupElytraSwap.Action.NONE, s.step(30, 20, 5, true, false));
        assertTrue(s.gaveUp());
    }

    @Test
    void rejectedClicksCountAsFailures() {
        BackupElytraSwap s = new BackupElytraSwap();
        assertEquals(BackupElytraSwap.Action.SWAP, s.step(30, 20, 5, true, false));
        s.swapRejected();
        assertFalse(s.swapping());
        assertEquals(BackupElytraSwap.Action.SWAP, s.step(30, 20, 5, true, false));
        s.swapRejected();
        assertTrue(s.gaveUp());
    }

    @Test
    void perTickDecisionsAllocateNothing() {
        TakeoffSequence t = new TakeoffSequence();
        BackupElytraSwap s = new BackupElytraSwap();
        MendLoop m = new MendLoop();
        int[] left = new int[36];
        int[] max = new int[36];
        boolean[] mending = new boolean[36];
        java.util.Arrays.fill(left, -1);
        left[12] = 300;
        max[12] = 432;
        BlockQuery air = (x, y, z) -> false;
        Runnable loop = () -> {
            for (int i = 0; i < 10_000; i++) {
                t.reset();
                t.step(TakeoffSequence.clearForTakeoff(air, 0, 64, 0), true, false, 0, 0, false, 25, 10, false);
                s.step(30, 20, BackupElytraSwap.pickSpare(left, max, mending, 30), true, false);
                m.begin();
                m.step(true, 88, 30, 432, 20, 10, true);
            }
        };
        loop.run(); // warm up
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long before = mx.getThreadAllocatedBytes(Thread.currentThread().getId());
            loop.run();
            best = Math.min(best, mx.getThreadAllocatedBytes(Thread.currentThread().getId()) - before);
        }
        assertTrue(best < 1024, "allocated " + best);
    }

    // ---- land to mend -----------------------------------------------------------------------------------------------

    @Test
    void landToMendNeedsSettingMendingAndBottles() {
        assertTrue(MendLoop.shouldLandToMend(true, true, 5));
        assertFalse(MendLoop.shouldLandToMend(false, true, 5));
        assertFalse(MendLoop.shouldLandToMend(true, false, 5));
        assertFalse(MendLoop.shouldLandToMend(true, true, 0));
    }

    @Test
    void mendLoopLandsAimsDownThrowsUntilRepairedThenTakesOff() {
        MendLoop m = new MendLoop();
        m.begin();
        assertTrue(m.active());
        assertEquals(MendLoop.Action.NONE, m.step(false, 0, 20, 432, 20, 64, false));
        assertEquals(MendLoop.Action.AIM_DOWN, m.step(true, 0, 20, 432, 20, 64, false));
        assertEquals(MendLoop.Action.AIM_DOWN, m.step(true, 40, 20, 432, 20, 64, false));
        assertEquals(MendLoop.Action.AIM_DOWN, m.step(true, 85, 20, 432, 20, 64, false));
        assertEquals(MendLoop.Phase.THROW, m.phase());
        assertEquals(MendLoop.Action.SELECT_BOTTLE, m.step(true, 88, 20, 432, 20, 64, false));
        assertEquals(MendLoop.Action.THROW, m.step(true, 88, 20, 432, 20, 64, true));
        assertEquals(MendLoop.Action.AIM_DOWN, m.step(true, 88, 30, 432, 20, 63, true)); // throw interval
        int uses = 30;
        MendLoop.Action a = MendLoop.Action.NONE;
        int throwsSeen = 0;
        for (int i = 0; i < 2000 && a != MendLoop.Action.TAKE_OFF; i++) {
            a = m.step(true, 88, uses, 432, 20, 60, true);
            if (a == MendLoop.Action.THROW) {
                throwsSeen++;
                uses = Math.min(431, uses + 14); // one bottle: about 7 xp, 2 durability per point
            }
        }
        assertEquals(MendLoop.Action.TAKE_OFF, a);
        assertTrue(throwsSeen > 0);
        assertFalse(m.active());
    }

    @Test
    void mendLoopLooksDownAgainWhenTheCameraMoved() {
        MendLoop m = new MendLoop();
        m.begin();
        m.step(true, 0, 20, 432, 20, 64, true);
        m.step(true, 85, 20, 432, 20, 64, true);
        assertEquals(MendLoop.Action.AIM_DOWN, m.step(true, 30, 20, 432, 20, 64, true));
        assertEquals(MendLoop.Phase.AIM_DOWN, m.phase());
    }

    @Test
    void outOfBottlesTakesOffWhenAboveTheReserveElseGivesUp() {
        MendLoop m = new MendLoop();
        m.begin();
        m.step(true, 0, 200, 432, 20, 0, false);
        m.step(true, 88, 200, 432, 20, 0, false);
        assertEquals(MendLoop.Action.AIM_DOWN, m.step(true, 88, 200, 432, 20, 0, false)); // to ABSORB
        MendLoop.Action a = MendLoop.Action.AIM_DOWN;
        for (int i = 0; i <= MendLoop.ABSORB_TICKS && a == MendLoop.Action.AIM_DOWN; i++) {
            a = m.step(true, 88, 200, 432, 20, 0, false);
        }
        assertEquals(MendLoop.Action.TAKE_OFF, a);

        MendLoop g = new MendLoop();
        g.begin();
        g.step(true, 0, 22, 432, 20, 0, false);
        g.step(true, 88, 22, 432, 20, 0, false);
        g.step(true, 88, 22, 432, 20, 0, false);
        a = MendLoop.Action.AIM_DOWN;
        for (int i = 0; i <= MendLoop.ABSORB_TICKS && a == MendLoop.Action.AIM_DOWN; i++) {
            a = g.step(true, 88, 22, 432, 20, 0, false);
        }
        assertEquals(MendLoop.Action.GIVE_UP, a);
    }
}
