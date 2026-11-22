package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotbarRefillPlannerAllocationTest {

    @Test
    void classRefillPlanAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HotbarRefillPlanner planner = new HotbarRefillPlanner();
        InventoryModel m = new InventoryModel();
        m.clear();
        HotbarRefillPlanner.ItemClasses c = new HotbarRefillPlanner.ItemClasses();
        c.clear();
        ClickPlan plan = new ClickPlan();
        int all = InventoryModel.FLAG_BLOCK | InventoryModel.FLAG_FOOD;
        long bytes = AllocationProbe.measure(100_000, () -> {
            m.set(2, 20, 0x80000001, 1, 1, 0); // pickaxe in the hotbar, remembered
            c.set(2, HotbarRefillPlanner.ItemClasses.PICKAXE, 500);
            plan.clear();
            planner.plan(m, c, all, 25, 0, true, false, plan);
            m.set(2, 0, 0, 0, 0, 0); // it breaks
            c.set(2, 0, 0);
            m.set(20, 20, 0x80000002, 1, 1, 0); // a spare in the pack
            c.set(20, HotbarRefillPlanner.ItemClasses.PICKAXE, 800);
            plan.clear();
            planner.plan(m, c, all, 25, 0, true, false, plan);
            m.set(2, 0, 0, 0, 0, 0);
            m.set(20, 0, 0, 0, 0, 0);
            c.set(20, 0, 0);
        });
        assertEquals(0, bytes, "the per-tick plan must not allocate");
    }
}
