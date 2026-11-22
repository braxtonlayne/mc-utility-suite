package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IContainerClicker.Click;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotbarRefillPlannerTest {

    static final int COBBLE = 1;
    static final int BREAD = 2;
    static final int PEARL = 3;
    static final int DIRT = 4;
    static final int ALL = InventoryModel.FLAG_BLOCK | InventoryModel.FLAG_FOOD | InventoryModel.FLAG_PEARL
            | InventoryModel.FLAG_ROCKET;

    private static InventoryModel inv() {
        InventoryModel m = new InventoryModel();
        m.clear();
        return m;
    }

    @Test
    void emptiedSlotIsRefilledWithOneSwapFromTheLargestMatchingStack() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(2, COBBLE, COBBLE, 5, 64, InventoryModel.FLAG_BLOCK);
        ClickPlan plan = new ClickPlan();
        assertEquals(0, planner.plan(m, ALL, 25, plan), "5/64 is below 25% but no source yet");

        // The stack runs out; the main inventory has two cobblestone stacks.
        m.set(2, 0, 0, 0, 0, 0);
        m.set(12, COBBLE, COBBLE, 20, 64, InventoryModel.FLAG_BLOCK);
        m.set(30, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        plan.clear();
        assertEquals(1, planner.plan(m, ALL, 25, plan));
        assertEquals(1, plan.size());
        assertEquals(30, plan.slot(0), "largest source stack, menu slot = inventory slot for main slots");
        assertEquals(2, plan.button(0), "SWAP button = hotbar index");
        assertEquals(Click.SWAP, plan.click(0));
        assertEquals(64, m.count[2]);
        assertEquals(0, m.count[30]);
    }

    @Test
    void partialStackBelowThresholdIsToppedUpAndRemainderPutBack() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(0, PEARL, PEARL, 2, 16, InventoryModel.FLAG_PEARL);
        m.set(9, PEARL, PEARL, 16, 16, InventoryModel.FLAG_PEARL);
        ClickPlan plan = new ClickPlan();
        assertEquals(1, planner.plan(m, ALL, 25, plan));
        assertEquals(3, plan.size(), "pick up, place (merges 14), put the remaining 2 back");
        assertEquals(9, plan.slot(0));
        assertEquals(36, plan.slot(1), "hotbar slot 0 is menu slot 36");
        assertEquals(9, plan.slot(2));
        assertEquals(Click.PICKUP, plan.click(0));
        assertEquals(16, m.count[0]);
        assertEquals(2, m.count[9]);
    }

    @Test
    void exactFitNeedsNoReturnClick() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(1, BREAD, BREAD, 4, 64, InventoryModel.FLAG_FOOD);
        m.set(20, BREAD, BREAD, 60, 64, InventoryModel.FLAG_FOOD);
        ClickPlan plan = new ClickPlan();
        planner.plan(m, ALL, 25, plan);
        assertEquals(2, plan.size());
        assertEquals(64, m.count[1]);
    }

    @Test
    void aboveThresholdOrDisabledCategoryIsLeftAlone() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(0, COBBLE, COBBLE, 32, 64, InventoryModel.FLAG_BLOCK);
        m.set(1, BREAD, BREAD, 1, 64, InventoryModel.FLAG_FOOD);
        m.set(9, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        m.set(10, BREAD, BREAD, 64, 64, InventoryModel.FLAG_FOOD);
        ClickPlan plan = new ClickPlan();
        assertEquals(0, planner.plan(m, InventoryModel.FLAG_BLOCK, 25, plan), "cobble at 50%, food not enabled");
        assertTrue(plan.isEmpty());
    }

    @Test
    void neverFillsASlotWithADifferentItemAndNeverTakesFromTheHotbar() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(4, COBBLE, COBBLE, 3, 64, InventoryModel.FLAG_BLOCK);
        m.set(5, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK); // hotbar: not a source
        m.set(9, DIRT, DIRT, 64, 64, InventoryModel.FLAG_BLOCK);     // different item
        ClickPlan plan = new ClickPlan();
        assertEquals(0, planner.plan(m, ALL, 25, plan));
        assertTrue(plan.isEmpty());
    }

    @Test
    void differentComponentsDoNotMerge() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(0, COBBLE, COBBLE, 1, 64, InventoryModel.FLAG_BLOCK);
        m.set(9, COBBLE, 0x80001234, 64, 64, InventoryModel.FLAG_BLOCK); // same item, renamed (other key)
        ClickPlan plan = new ClickPlan();
        assertEquals(0, planner.plan(m, ALL, 25, plan));
    }

    @Test
    void slotsEmptiedWhileAScreenIsOpenAreForgotten() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(3, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        m.set(9, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        ClickPlan plan = new ClickPlan();
        planner.plan(m, ALL, 25, plan);
        assertTrue(planner.remembers(3));
        // User drags the stack out of slot 3 in the inventory screen.
        m.set(3, 0, 0, 0, 0, 0);
        planner.observe(m);
        assertFalse(planner.remembers(3));
        plan.clear();
        assertEquals(0, planner.plan(m, ALL, 25, plan), "closing the inventory must not refill the cleared slot");
    }

    @Test
    void twoSlotsOfTheSameItemDoNotShareOneSource() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(0, COBBLE, COBBLE, 1, 64, InventoryModel.FLAG_BLOCK);
        m.set(1, COBBLE, COBBLE, 1, 64, InventoryModel.FLAG_BLOCK);
        m.set(9, COBBLE, COBBLE, 63, 64, InventoryModel.FLAG_BLOCK);
        ClickPlan plan = new ClickPlan();
        assertEquals(1, planner.plan(m, ALL, 25, plan), "the single source fills slot 0 completely");
        assertEquals(64, m.count[0]);
        assertEquals(1, m.count[1]);
        assertEquals(0, m.count[9]);
    }

    // ---- class refill -----------------------------------------------------------------------------------------------

    static final int PICK = 20;
    static final int TOTEM = 21;
    static final int STEAK = 22;
    static final int PICK_KEY = 0x80000001;

    private static HotbarRefillPlanner.ItemClasses classes() {
        HotbarRefillPlanner.ItemClasses c = new HotbarRefillPlanner.ItemClasses();
        c.clear();
        return c;
    }

    private static void tool(InventoryModel m, HotbarRefillPlanner.ItemClasses c, int slot, int itemId, int key,
                             int cls, int score) {
        m.set(slot, itemId, key, 1, 1, 0);
        c.set(slot, cls, score);
    }

    private static int planClasses(HotbarRefillPlanner p, InventoryModel m, HotbarRefillPlanner.ItemClasses c,
                                   int pinned, boolean unpinnedOk, boolean pinnedOk, ClickPlan plan) {
        plan.clear();
        return p.plan(m, c, ALL, 25, pinned, unpinnedOk, pinnedOk, plan);
    }

    @Test
    void brokenToolIsReplacedByTheBestRemainingToolOfItsClass() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        tool(m, c, 3, PICK, PICK_KEY, HotbarRefillPlanner.ItemClasses.PICKAXE, 500);
        assertEquals(0, planClasses(planner, m, c, 0, true, false, plan));

        m.set(3, 0, 0, 0, 0, 0); // the pickaxe broke
        c.set(3, 0, 0);
        tool(m, c, 12, PICK, PICK_KEY + 1, HotbarRefillPlanner.ItemClasses.PICKAXE, 200);
        tool(m, c, 20, PICK, PICK_KEY + 2, HotbarRefillPlanner.ItemClasses.PICKAXE, 800);
        tool(m, c, 25, PICK, PICK_KEY + 3, HotbarRefillPlanner.ItemClasses.PICKAXE, -1);
        tool(m, c, 26, 99, 99, HotbarRefillPlanner.ItemClasses.SWORD, 900);
        assertEquals(1, planClasses(planner, m, c, 0, true, false, plan));
        assertEquals(1, plan.size());
        assertEquals(20, plan.slot(0), "highest scoring pickaxe, not the nearly broken one or the sword");
        assertEquals(3, plan.button(0));
        assertEquals(Click.SWAP, plan.click(0));
        assertEquals(PICK_KEY + 2, m.key[3]);
        assertEquals(0, m.count[20]);
    }

    @Test
    void usedTotemIsReplacedByTheNextTotem() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        tool(m, c, 8, TOTEM, TOTEM, HotbarRefillPlanner.ItemClasses.TOTEM, 0);
        planClasses(planner, m, c, 0, true, false, plan);
        m.set(8, 0, 0, 0, 0, 0);
        c.set(8, 0, 0);
        tool(m, c, 14, TOTEM, TOTEM, HotbarRefillPlanner.ItemClasses.TOTEM, 0);
        assertEquals(1, planClasses(planner, m, c, 0, true, false, plan));
        assertEquals(14, plan.slot(0));
        assertEquals(8, plan.button(0));
    }

    @Test
    void pinnedSlotKeepsExactlyItsOwnItem() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        tool(m, c, 0, PICK, PICK_KEY, HotbarRefillPlanner.ItemClasses.PICKAXE, 500);
        planClasses(planner, m, c, 1, true, false, plan);
        m.set(0, 0, 0, 0, 0, 0);
        c.set(0, 0, 0);
        tool(m, c, 12, PICK, PICK_KEY + 1, HotbarRefillPlanner.ItemClasses.PICKAXE, 800);
        assertEquals(0, planClasses(planner, m, c, 1, true, false, plan), "pinned: no different item");
        assertEquals(1, planClasses(planner, m, c, 1, true, true, plan), "pinned with the option on");
    }

    @Test
    void identicalItemIsPreferredOverAClassMatch() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        tool(m, c, 1, PICK, PICK_KEY, HotbarRefillPlanner.ItemClasses.PICKAXE, 300);
        planClasses(planner, m, c, 0, true, false, plan);
        m.set(1, 0, 0, 0, 0, 0);
        c.set(1, 0, 0);
        tool(m, c, 15, PICK, PICK_KEY + 5, HotbarRefillPlanner.ItemClasses.PICKAXE, 900);
        tool(m, c, 16, PICK, PICK_KEY, HotbarRefillPlanner.ItemClasses.PICKAXE, 100); // identical key, lower score
        assertEquals(1, planClasses(planner, m, c, 0, true, false, plan));
        assertEquals(16, plan.slot(0));
    }

    @Test
    void slotTheUserClearedWithAScreenOpenIsNotRefilledByClass() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        tool(m, c, 4, PICK, PICK_KEY, HotbarRefillPlanner.ItemClasses.PICKAXE, 500);
        planClasses(planner, m, c, 0, true, false, plan);
        m.set(4, 0, 0, 0, 0, 0);
        c.set(4, 0, 0);
        tool(m, c, 13, PICK, PICK_KEY + 1, HotbarRefillPlanner.ItemClasses.PICKAXE, 800);
        planner.observe(m, c); // inventory screen open
        assertFalse(planner.remembers(4));
        assertEquals(0, planClasses(planner, m, c, 0, true, false, plan));
    }

    @Test
    void foodFallsBackToTheBestFoodWhenTheIdenticalStackIsGone() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        m.set(5, BREAD, BREAD, 3, 64, InventoryModel.FLAG_FOOD);
        c.set(5, HotbarRefillPlanner.ItemClasses.FOOD, 80);
        planClasses(planner, m, c, 0, true, false, plan);
        m.set(5, 0, 0, 0, 0, 0);
        c.set(5, 0, 0);
        m.set(18, STEAK, STEAK, 12, 64, InventoryModel.FLAG_FOOD);
        c.set(18, HotbarRefillPlanner.ItemClasses.FOOD, 160);
        assertEquals(1, planClasses(planner, m, c, 0, true, false, plan));
        assertEquals(18, plan.slot(0));
        assertEquals(12, m.count[5]);
    }

    @Test
    void classRefillOffMeansNothingChanges() {
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        HotbarRefillPlanner.ItemClasses c = classes();
        ClickPlan plan = new ClickPlan();
        tool(m, c, 0, PICK, PICK_KEY, HotbarRefillPlanner.ItemClasses.PICKAXE, 500);
        planClasses(planner, m, c, 0, false, false, plan);
        m.set(0, 0, 0, 0, 0, 0);
        c.set(0, 0, 0);
        tool(m, c, 12, PICK, PICK_KEY + 1, HotbarRefillPlanner.ItemClasses.PICKAXE, 800);
        assertEquals(0, planClasses(planner, m, c, 0, false, false, plan));
    }

    @Test
    void planningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = inv();
        ClickPlan plan = new ClickPlan();
        long bytes = AllocationProbe.measure(100_000, () -> {
            m.set(0, COBBLE, COBBLE, 2, 64, InventoryModel.FLAG_BLOCK);
            m.set(9, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
            plan.clear();
            planner.plan(m, ALL, 25, plan);
        });
        assertTrue(bytes < 16 * 1024, "allocated " + bytes + " bytes");
    }
}
