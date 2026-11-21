package com.utilitysuite.tools.player;

import com.utilitysuite.tools.player.FishingCycle.Action;
import com.utilitysuite.tools.player.FishingCycle.Phase;
import com.utilitysuite.tools.player.FishingCycle.StopReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingCycleTest {

    private static final int REEL = 5;
    private static final int RECAST = 12;
    private static final int MAX = Integer.MAX_VALUE;

    private static FishingCycle cycle() {
        return new FishingCycle(new FishingCycle.Delays() {
            public int reelTicks() {
                return REEL;
            }

            public int recastTicks() {
                return RECAST;
            }
        });
    }

    @Test
    void biteReelRecastTiming() {
        FishingCycle f = cycle();
        assertEquals(Action.USE, f.tick(true, false, MAX, 3, false), "first tick casts");
        assertEquals(Phase.CASTING, f.phase());
        assertEquals(Action.NONE, f.tick(true, true, MAX, 3, false));
        assertEquals(Phase.WAITING, f.phase());
        for (int i = 0; i < 50; i++) {
            assertEquals(Action.NONE, f.tick(true, true, MAX, 3, false));
        }
        f.onBite();
        assertEquals(Action.NONE, f.tick(true, true, MAX, 3, false));
        assertEquals(Phase.REEL_DELAY, f.phase());
        int ticksToReel = 0;
        Action a;
        do {
            a = f.tick(true, true, MAX, 3, false);
            ticksToReel++;
        } while (a == Action.NONE && ticksToReel < 100);
        assertEquals(Action.USE, a);
        assertEquals(REEL, ticksToReel, "reel after the reaction delay");
        assertTrue(ticksToReel <= FishingCycle.MAX_REEL_TICKS);
        assertEquals(1, f.catches());
        int ticksToCast = 0;
        do {
            a = f.tick(true, false, MAX, 3, false);
            ticksToCast++;
        } while (a == Action.NONE && ticksToCast < 100);
        assertEquals(Action.USE, a);
        assertEquals(RECAST + 1, ticksToCast, "recast after the human delay");
        assertEquals(Phase.CASTING, f.phase());
    }

    @Test
    void biteIgnoredUnlessWaiting() {
        FishingCycle f = cycle();
        f.onBite();
        assertEquals(Action.USE, f.tick(true, false, MAX, 3, false));
        assertEquals(Phase.CASTING, f.phase());
    }

    @Test
    void stopsOnLowDurabilityBeforeCasting() {
        FishingCycle f = cycle();
        assertEquals(Action.STOP, f.tick(true, false, 3, 3, false));
        assertEquals(Phase.STOPPED, f.phase());
        assertEquals(StopReason.ROD_LOW, f.stopReason());
        assertEquals(Action.NONE, f.tick(true, false, 50, 3, false), "stays stopped");
    }

    @Test
    void stopsWhenFullOrRodGone() {
        FishingCycle f = cycle();
        assertEquals(Action.STOP, f.tick(true, false, MAX, 3, true));
        assertEquals(StopReason.INVENTORY_FULL, f.stopReason());
        f.start();
        f.tick(true, false, MAX, 3, false);
        assertEquals(Action.STOP, f.tick(false, false, MAX, 3, false));
        assertEquals(StopReason.NO_ROD, f.stopReason());
    }

    @Test
    void failedCastStops() {
        FishingCycle f = cycle();
        f.tick(true, false, MAX, 3, false);
        Action a = Action.NONE;
        for (int i = 0; i <= FishingCycle.CAST_TIMEOUT && a == Action.NONE; i++) {
            a = f.tick(true, false, MAX, 3, false);
        }
        assertEquals(Action.STOP, a);
        assertEquals(StopReason.CAST_FAILED, f.stopReason());
    }

    @Test
    void tickDoesNotAllocate() {
        if (!com.utilitysuite.core.AllocationProbe.supported()) {
            return;
        }
        FishingCycle f = cycle();
        long bytes = com.utilitysuite.core.AllocationProbe.measure(100_000, () -> {
            if (f.phase() == Phase.STOPPED) {
                f.start();
            }
            f.onBite();
            f.tick(true, true, MAX, 3, false);
        });
        assertEquals(0, bytes, "FishingCycle.tick allocated " + bytes + " bytes");
    }
}
