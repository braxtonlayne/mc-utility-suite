package com.utilitysuite.tools.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DepletedPinnedTest {

    private static final int COBBLE = 1;
    private static final int ALL = InventoryModel.FLAG_BLOCK | InventoryModel.FLAG_FOOD | InventoryModel.FLAG_PEARL
            | InventoryModel.FLAG_ROCKET;

    private static InventoryModel inv() {
        InventoryModel m = new InventoryModel();
        m.clear();
        return m;
    }

    @Test
    void pinnedSlotThatRanOutWithNoReserveIsReported() {
        HotbarRefillPlanner p = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(2, COBBLE, COBBLE, 5, 64, InventoryModel.FLAG_BLOCK);
        p.plan(m, ALL, 25, new ClickPlan());
        m.set(2, 0, 0, 0, 0, 0);
        assertEquals(2, p.depletedPinned(m, ALL, 25, 1 << 2));
        assertEquals(COBBLE, p.itemAt(m, 2));
    }

    @Test
    void unpinnedSlotReserveInInventoryOrHealthyStackIsNotReported() {
        HotbarRefillPlanner p = new HotbarRefillPlanner();
        InventoryModel m = inv();
        m.set(2, COBBLE, COBBLE, 5, 64, InventoryModel.FLAG_BLOCK);
        p.plan(m, ALL, 25, new ClickPlan());
        assertEquals(-1, p.depletedPinned(m, ALL, 25, 0), "not pinned");
        m.set(20, COBBLE, COBBLE, 30, 64, InventoryModel.FLAG_BLOCK);
        assertEquals(-1, p.depletedPinned(m, ALL, 25, 1 << 2), "the main inventory still has some");
        m.set(20, 0, 0, 0, 0, 0);
        m.set(2, COBBLE, COBBLE, 60, 64, InventoryModel.FLAG_BLOCK);
        assertEquals(-1, p.depletedPinned(m, ALL, 25, 1 << 2), "stack is full enough");
    }
}
