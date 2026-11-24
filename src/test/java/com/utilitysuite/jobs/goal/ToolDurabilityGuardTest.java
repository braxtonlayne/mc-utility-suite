package com.utilitysuite.jobs.goal;

import com.utilitysuite.jobs.goal.ToolDurabilityGuard.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pre-break tool swap decision: backups, enchanted protection, tier floor, no allocation. */
class ToolDurabilityGuardTest {

    private static ToolDurabilityGuard guard() {
        ToolDurabilityGuard g = new ToolDurabilityGuard();
        g.clear();
        g.selected = 0;
        return g;
    }

    private static void tool(ToolDurabilityGuard g, int slot, int kind, int left, int tier, boolean ench) {
        g.kind[slot] = kind;
        g.remaining[slot] = left;
        g.tier[slot] = tier;
        g.enchanted[slot] = ench;
    }

    @Test
    void healthyOrNonToolHeldIsOk() {
        ToolDurabilityGuard g = guard();
        assertEquals(Decision.OK, g.decide(5, true, 0)); // empty hand
        tool(g, 0, ToolDurabilityGuard.PICKAXE, 6, 3, false);
        assertEquals(Decision.OK, g.decide(5, true, 3));
    }

    @Test
    void swapsToTheBestPlainBackupOfTheSameKind() {
        ToolDurabilityGuard g = guard();
        tool(g, 0, ToolDurabilityGuard.PICKAXE, 5, 3, false);
        tool(g, 12, ToolDurabilityGuard.PICKAXE, 100, 3, true);
        tool(g, 20, ToolDurabilityGuard.PICKAXE, 40, 3, false);
        tool(g, 21, ToolDurabilityGuard.PICKAXE, 90, 3, false);
        tool(g, 22, ToolDurabilityGuard.SHOVEL, 200, 0, false);
        assertEquals(Decision.SWAP, g.decide(5, true, 3));
        assertEquals(21, g.swapSlot);
    }

    @Test
    void backupMustReachTheRequiredTier() {
        ToolDurabilityGuard g = guard();
        tool(g, 0, ToolDurabilityGuard.PICKAXE, 2, 4, false);
        tool(g, 3, ToolDurabilityGuard.PICKAXE, 50, 2, false);
        assertEquals(Decision.NO_BACKUP, g.decide(5, true, 3));
        assertEquals(Decision.SWAP, g.decide(5, true, 2));
        assertEquals(3, g.swapSlot);
    }

    @Test
    void enchantedToolIsNeverRunDownByDefault() {
        ToolDurabilityGuard g = guard();
        tool(g, 0, ToolDurabilityGuard.PICKAXE, 1, 4, true);
        assertEquals(Decision.NO_BACKUP_ENCHANTED, g.decide(5, true, 3));
        assertEquals(Decision.OK, g.decide(5, false, 3), "opt-out lets it run");
    }

    @Test
    void backupBelowTheThresholdDoesNotCount() {
        ToolDurabilityGuard g = guard();
        tool(g, 0, ToolDurabilityGuard.SHOVEL, 3, 0, false);
        tool(g, 5, ToolDurabilityGuard.SHOVEL, 5, 0, false);
        assertEquals(Decision.NO_BACKUP, g.decide(5, true, 0));
    }

    @Test
    void decideAllocatesNothing() {
        ToolDurabilityGuard g = guard();
        tool(g, 0, ToolDurabilityGuard.PICKAXE, 5, 3, false);
        tool(g, 20, ToolDurabilityGuard.PICKAXE, 90, 3, false);
        for (int i = 0; i < 20_000; i++) {
            g.decide(5, true, 3); // warm up
        }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long before = mx.getThreadAllocatedBytes(Thread.currentThread().getId());
            for (int i = 0; i < 10_000; i++) {
                g.decide(5, true, 3);
            }
            best = Math.min(best, mx.getThreadAllocatedBytes(Thread.currentThread().getId()) - before);
        }
        assertTrue(best < 1024, "allocated " + best);
    }
}
