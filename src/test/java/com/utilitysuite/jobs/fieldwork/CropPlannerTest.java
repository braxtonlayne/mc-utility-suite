package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropPlannerTest {

    static IInventoryView inv(Map<String, Integer> items) {
        return new IInventoryView() {
            @Override
            public Map<String, Integer> counts() {
                return items;
            }

            @Override
            public int count(String itemId) {
                return items.getOrDefault(itemId, 0);
            }

            @Override
            public int bestPickaxeTier() {
                return 0;
            }
        };
    }

    private static final int WHEAT = CropPlanner.kindOf("minecraft:wheat");
    private static final int BEET = CropPlanner.kindOf("minecraft:beetroots");

    @Test
    void onlyMatureCropsAreKept() {
        CropPlanner p = new CropPlanner();
        p.offer(PackedPos.pack(1, 64, 0), WHEAT, 6);
        p.offer(PackedPos.pack(2, 64, 0), WHEAT, 7);
        p.offer(PackedPos.pack(3, 64, 0), BEET, 3);
        p.offer(PackedPos.pack(4, 64, 0), -1, 7);
        p.offer(PackedPos.pack(5, 64, 0), CropPlanner.COCOA, 2); // cocoa off by default
        assertEquals(2, p.size());
        boolean[] seeds = {true, true, true, true, true, true};
        int i = p.next(0.5, 65.6, 0.5, 5, true, seeds);
        assertEquals(PackedPos.pack(2, 64, 0), p.cell(i), "the nearest mature one, not the young wheat");
        p.setIncludeCocoa(true);
        p.offer(PackedPos.pack(5, 64, 0), CropPlanner.COCOA, 2);
        assertEquals(3, p.size());
    }

    @Test
    void replantNeedsSeeds() {
        assertTrue(CropPlanner.canReplant(WHEAT, inv(Map.of("minecraft:wheat_seeds", 3))));
        assertFalse(CropPlanner.canReplant(WHEAT, inv(Map.of("minecraft:wheat", 3))));
        assertTrue(CropPlanner.canReplant(CropPlanner.kindOf("minecraft:potatoes"),
                inv(Map.of("minecraft:potato", 1))));

        CropPlanner p = new CropPlanner();
        p.offer(PackedPos.pack(1, 64, 0), WHEAT, 7);
        p.offer(PackedPos.pack(3, 64, 0), BEET, 3);
        boolean[] seeds = new boolean[CropPlanner.CROP_BLOCKS.length];
        seeds[BEET] = true;
        int i = p.next(0.5, 65.6, 0.5, 5, true, seeds);
        assertEquals(PackedPos.pack(3, 64, 0), p.cell(i), "skips wheat: no wheat seeds carried");
        assertEquals(-1, p.next(0.5, 65.6, 0.5, 5, true, new boolean[6]));
        assertTrue(p.next(0.5, 65.6, 0.5, 5, false, new boolean[6]) >= 0, "harvest-only mode ignores seeds");
        p.remove(PackedPos.pack(3, 64, 0));
        assertEquals(1, p.size());
    }

    @Test
    void outOfReachIsIgnored() {
        CropPlanner p = new CropPlanner();
        p.offer(PackedPos.pack(9, 64, 0), WHEAT, 7);
        assertEquals(-1, p.next(0.5, 65.6, 0.5, 4.5, false, new boolean[6]));
    }

    @Test
    void torchDecision() {
        assertTrue(FieldRules.shouldPlaceTorch(3, 8, 10, 99, 5));
        assertFalse(FieldRules.shouldPlaceTorch(8, 8, 10, 99, 5), "bright enough");
        assertFalse(FieldRules.shouldPlaceTorch(0, 8, 0, 99, 5), "no torch carried");
        assertFalse(FieldRules.shouldPlaceTorch(0, 8, 10, 2, 5), "too close to the last torch");
        assertEquals("minecraft:torch", FieldRules.torchItem(inv(Map.of("minecraft:torch", 4))));
        assertEquals("minecraft:soul_torch", FieldRules.torchItem(inv(Map.of("minecraft:soul_torch", 4))));
        assertNull(FieldRules.torchItem(inv(Map.of("minecraft:stick", 4))));
    }

    @Test
    void lavaPlugNeverSpendsValuables() {
        for (String v : new String[]{"minecraft:diamond_block", "minecraft:diamond_ore", "minecraft:iron_ore",
                "minecraft:gold_block", "minecraft:obsidian", "minecraft:oak_planks", "minecraft:ancient_debris",
                "minecraft:emerald_block", "minecraft:shulker_box"}) {
            assertFalse(FieldRules.isPlugItem(v), v);
        }
        assertNull(FieldRules.plugItem(inv(Map.of("minecraft:diamond_block", 64, "minecraft:iron_ore", 5))));
        assertEquals("minecraft:cobblestone", FieldRules.plugItem(inv(Map.of("minecraft:cobblestone", 1,
                "minecraft:dirt", 64, "minecraft:diamond_block", 64))));
        assertEquals("minecraft:netherrack", FieldRules.plugItem(inv(Map.of("minecraft:netherrack", 3))));
    }

    @Test
    void pickingATargetDoesNotAllocate() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        CropPlanner p = new CropPlanner();
        boolean[] seeds = {true, true, true, true, true, true};
        long bytes = AllocationProbe.measure(5_000, () -> {
            p.clear();
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    p.offer(PackedPos.pack(x, 64, z), WHEAT, (x + z) & 7);
                }
            }
            p.next(0.5, 65.6, 0.5, 4.5, true, seeds);
        });
        assertTrue(bytes < 64 * 1024, "allocated " + bytes + " bytes over 5000 runs");
    }
}
