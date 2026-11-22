package com.utilitysuite.jobs.survival;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.jobs.survival.FoodSelector.SwapBack;
import com.utilitysuite.jobs.survival.FoodSelector.SwapBack.Step;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.jobs.survival.FoodSelector.SwapBack.EMPTY;
import static org.junit.jupiter.api.Assertions.*;

/** Auto-Eat's pull / eat / swap-back cycle survives a preempted lease (run note item 11). */
class EatSwapBackTest {

    static final int BREAD = 101;
    static final int PICKAXE = 202;
    static final int COBBLE = 303;
    static final int SOURCE = 20;

    @Test
    void normalCycle() {
        SwapBack sb = new SwapBack();
        assertEquals(Step.NONE, sb.decide(false, false, EMPTY, BREAD), "nothing owed before a pull");
        sb.pulled(SOURCE, 4, BREAD, EMPTY); // into the empty hotbar slot 4
        assertTrue(sb.owed());
        assertFalse(sb.deferred(), "the lease is still held: the normal cycle swaps back itself");
        assertEquals(Step.RESTORE, sb.decide(false, false, BREAD, EMPTY));
        assertEquals(SOURCE, sb.source());
        assertEquals(4, sb.hotbar());
        sb.forget(); // the swap-back went out
        assertFalse(sb.owed());
        assertEquals(Step.NONE, sb.decide(false, false, EMPTY, BREAD));
    }

    @Test
    void preemptedAfterThePullRestoresLater() {
        SwapBack sb = new SwapBack();
        sb.pulled(SOURCE, 4, BREAD, EMPTY);
        sb.leaseEnded(); // a fight took the hotbar mid-eat
        assertTrue(sb.deferred());
        assertEquals(Step.WAIT, sb.decide(false, true, BREAD, EMPTY), "the combat lease holds the container channel");
        assertEquals(Step.RESTORE, sb.decide(false, false, BREAD, EMPTY), "free again: the food goes back");
    }

    @Test
    void userMovedTheFoodNoRestore() {
        SwapBack sb = new SwapBack();
        sb.pulled(SOURCE, 4, BREAD, EMPTY);
        sb.leaseEnded();
        assertEquals(Step.DROP, sb.decide(false, false, COBBLE, EMPTY), "the user put cobble in that hotbar slot");

        SwapBack moved = new SwapBack();
        moved.pulled(SOURCE, 4, BREAD, EMPTY);
        moved.leaseEnded();
        assertEquals(Step.DROP, moved.decide(false, false, BREAD, COBBLE), "the pack slot was filled by the user");

        SwapBack gone = new SwapBack();
        gone.pulled(SOURCE, 4, BREAD, EMPTY);
        gone.leaseEnded();
        assertEquals(Step.DROP, gone.decide(false, false, EMPTY, EMPTY), "the food left the hotbar: nothing to do");
    }

    @Test
    void screenOpenWaits() {
        SwapBack sb = new SwapBack();
        sb.pulled(SOURCE, 4, BREAD, EMPTY);
        sb.leaseEnded();
        assertEquals(Step.WAIT, sb.decide(true, false, COBBLE, EMPTY),
                "a screen is open: wait, do not even judge the slots (the user may be mid-move)");
        assertTrue(sb.owed());
    }

    @Test
    void worldUnloadForgets() {
        SwapBack sb = new SwapBack();
        sb.pulled(SOURCE, 4, BREAD, EMPTY);
        sb.leaseEnded();
        sb.forget();
        assertFalse(sb.owed());
        assertFalse(sb.deferred());
        assertEquals(Step.NONE, sb.decide(false, false, BREAD, EMPTY));
    }

    @Test
    void fullHotbarSwapIntoTheSelectedSlot() {
        // full hotbar: the food went into the selected slot 2, the pickaxe there went to the pack slot
        SwapBack sb = new SwapBack();
        sb.pulled(SOURCE, 2, BREAD, PICKAXE);
        sb.leaseEnded();
        assertEquals(Step.RESTORE, sb.decide(false, false, BREAD, PICKAXE), "the pickaxe comes home");
        assertEquals(2, sb.hotbar());
        assertEquals(Step.RESTORE, sb.decide(false, false, EMPTY, PICKAXE),
                "the last bread was eaten: the pickaxe still comes home");
        assertEquals(Step.DROP, sb.decide(false, false, BREAD, BREAD), "the user swapped the pickaxe out themselves");
    }

    @Test
    void swapBackAlreadyLandedIsNotRepeated() {
        // preempted right after the swap-back click went out: the slots are already restored
        SwapBack sb = new SwapBack();
        sb.pulled(SOURCE, 2, BREAD, PICKAXE);
        sb.leaseEnded();
        assertEquals(Step.DROP, sb.decide(false, false, PICKAXE, BREAD));
    }

    @Test
    void leaseEndWithNothingOwedIsIgnored() {
        SwapBack sb = new SwapBack();
        sb.leaseEnded();
        assertFalse(sb.deferred());
        assertFalse(sb.owed());
    }

    @Test
    void decideAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        SwapBack sb = new SwapBack();
        long bytes = AllocationProbe.measure(100_000, () -> {
            sb.pulled(SOURCE, 2, BREAD, PICKAXE);
            sb.leaseEnded();
            sb.decide(false, false, BREAD, PICKAXE);
            sb.decide(true, false, BREAD, PICKAXE);
            sb.forget();
        });
        assertTrue(bytes < 1_024, "swap-back decision allocated " + bytes + " bytes");
    }
}
