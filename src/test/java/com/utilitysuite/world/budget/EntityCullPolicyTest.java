package com.utilitysuite.world.budget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.world.budget.EntityCullPolicy.ARMOR_STAND;
import static com.utilitysuite.world.budget.EntityCullPolicy.ITEM_DISPLAY;
import static com.utilitysuite.world.budget.EntityCullPolicy.ITEM_FRAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCullPolicyTest {

    private static final double D32 = 32.0 * 32.0;

    @AfterEach
    void clearHooks() {
        EntityCullHooks.clear();
    }

    @Test
    void cullsOnlyEnabledKindsPastTheDistanceInADegradedTier() {
        int mask = ITEM_FRAME | ITEM_DISPLAY;
        assertTrue(EntityCullPolicy.shouldCull(ITEM_FRAME, mask, 2, 2, 40 * 40, D32));
        assertTrue(EntityCullPolicy.shouldCull(ITEM_DISPLAY, mask, 3, 2, 40 * 40, D32));
        assertFalse(EntityCullPolicy.shouldCull(ITEM_FRAME, mask, 2, 2, 20 * 20, D32), "inside the distance");
        assertFalse(EntityCullPolicy.shouldCull(ITEM_FRAME, mask, 1, 2, 40 * 40, D32), "tier below the start");
        assertFalse(EntityCullPolicy.shouldCull(ITEM_FRAME, mask, 0, 1, 40 * 40, D32), "full detail never culls");
        assertFalse(EntityCullPolicy.shouldCull(ARMOR_STAND, mask, 3, 1, 40 * 40, D32), "kind not enabled");
        assertFalse(EntityCullPolicy.shouldCull(0, -1, 3, 1, 40 * 40, D32), "not a cullable kind");
        assertFalse(EntityCullPolicy.shouldCull(ITEM_FRAME, 0, 3, 1, 1e9, D32), "nothing enabled");
    }

    @Test
    void armorStandsKeepTheFixedTierThreeRule() {
        assertTrue(EntityCullPolicy.shouldCullArmorStand(0, 3, 2, 25 * 25, D32), "tier 3: past 24 blocks");
        assertFalse(EntityCullPolicy.shouldCullArmorStand(0, 3, 2, 20 * 20, D32));
        assertFalse(EntityCullPolicy.shouldCullArmorStand(0, 2, 2, 100 * 100, D32), "opt-in rule off");
        assertTrue(EntityCullPolicy.shouldCullArmorStand(ARMOR_STAND, 2, 2, 40 * 40, D32), "opt-in from tier 2");
        assertFalse(EntityCullPolicy.shouldCullArmorStand(ARMOR_STAND, 1, 2, 40 * 40, D32));
    }

    @Test
    void hooksTakeOnlyTheMixinKindsAndClear() {
        EntityCullHooks.configure(ITEM_FRAME | ITEM_DISPLAY | ARMOR_STAND, 2, 32);
        assertEquals(ITEM_FRAME | ITEM_DISPLAY, EntityCullHooks.mask(), "armor stands go through the event");
        EntityCullHooks.clear();
        assertEquals(0, EntityCullHooks.mask());
    }

    @Test
    void decisionDoesNotAllocate() {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean sun) || !sun.isThreadAllocatedMemorySupported()) {
            return;
        }
        long tid = Thread.currentThread().getId();
        boolean sink = false;
        for (int i = 0; i < 10_000; i++) { // warm up
            sink ^= EntityCullPolicy.shouldCull(ITEM_FRAME, 3, i & 3, 2, i, D32);
        }
        long before = sun.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) {
            sink ^= EntityCullPolicy.shouldCull(ITEM_FRAME, 3, i & 3, 2, i, D32);
            sink ^= EntityCullPolicy.shouldCullArmorStand(ARMOR_STAND, i & 3, 2, i, D32);
        }
        long used = sun.getThreadAllocatedBytes(tid) - before;
        assertTrue(used < 1024, "allocated " + used + " bytes (sink " + sink + ")");
    }
}
