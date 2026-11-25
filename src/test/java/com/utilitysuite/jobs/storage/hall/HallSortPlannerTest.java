package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.jobs.storage.hall.HallSortPlanner.Chest;
import com.utilitysuite.jobs.storage.hall.HallSortPlanner.Move;
import com.utilitysuite.jobs.storage.hall.HallSortPlanner.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HallSortPlannerTest {

    @Test
    void categoriesFromIds() {
        assertEquals(StorageCategories.ORES, StorageCategories.of("minecraft:raw_iron"));
        assertEquals(StorageCategories.ORES, StorageCategories.of("minecraft:redstone_ore"));
        assertEquals(StorageCategories.REDSTONE, StorageCategories.of("minecraft:redstone"));
        assertEquals(StorageCategories.WOOD, StorageCategories.of("minecraft:oak_log"));
        assertEquals(StorageCategories.GEAR, StorageCategories.of("minecraft:diamond_pickaxe"));
        assertEquals(StorageCategories.STONE, StorageCategories.of("minecraft:cobbled_deepslate"));
        assertEquals(StorageCategories.BOOKS, StorageCategories.of("minecraft:enchanted_book"));
        assertEquals(StorageCategories.MISC, StorageCategories.of("minecraft:painting"));
    }

    @Test
    void inferenceNeedsAMajority() {
        assertEquals(StorageCategories.WOOD, StorageCategories.infer(
                new String[]{"minecraft:oak_log", "minecraft:cobblestone", null}, new int[]{64, 10, 0}));
        assertNull(StorageCategories.infer(
                new String[]{"minecraft:oak_log", "minecraft:cobblestone", "minecraft:bread"}, new int[]{10, 10, 10}));
        assertNull(StorageCategories.infer(new String[]{null}, new int[]{0}));
    }

    @Test
    void misplacedItemsGoToTheirCategoryChestMergingFirst() {
        Chest wood = new Chest(1L, null, 4).put(0, "minecraft:oak_log", "", 64, 64)
                .put(1, "minecraft:oak_log", "", 64, 64).put(2, "minecraft:cobblestone", "", 20, 64);
        Chest stone = new Chest(2L, StorageCategories.STONE, 4).put(0, "minecraft:cobblestone", "", 50, 64);
        Plan p = new HallSortPlanner().plan(List.of(wood, stone), 0.9);
        assertEquals(StorageCategories.WOOD, p.categories()[0]);
        assertEquals(2, p.moves().size());
        Move m0 = p.moves().get(0);
        assertEquals(1, m0.toChest());
        assertEquals(0, m0.toSlot());
        assertEquals(14, m0.count());
        Move m1 = p.moves().get(1);
        assertEquals(1, m1.toSlot());
        assertEquals(6, m1.count());
        // the input is not modified
        assertEquals(20, wood.count[2]);
    }

    @Test
    void enchantedBooksGroupByEnchantment() {
        Chest a = new Chest(1L, StorageCategories.BOOKS, 3).put(0, "minecraft:enchanted_book", "mending", 1, 1)
                .put(1, "minecraft:enchanted_book", "mending", 1, 1).put(2, "minecraft:enchanted_book", "sharpness_5", 1, 1);
        Chest b = new Chest(2L, StorageCategories.BOOKS, 3).put(0, "minecraft:enchanted_book", "mending", 1, 1)
                .put(1, "minecraft:enchanted_book", "sharpness_5", 1, 1)
                .put(2, "minecraft:enchanted_book", "sharpness_5", 1, 1);
        Plan p = new HallSortPlanner().plan(List.of(a, b, new Chest(3L, StorageCategories.BOOKS, 2)), 2.0);
        // mending -> chest 0 (has 2), but chest 0 is full; sharpness -> chest 1 (has 2), but chest 1 is full too
        assertTrue(p.moves().isEmpty());

        Chest a2 = new Chest(1L, StorageCategories.BOOKS, 4).put(0, "minecraft:enchanted_book", "mending", 1, 1)
                .put(1, "minecraft:enchanted_book", "mending", 1, 1);
        Chest b2 = new Chest(2L, StorageCategories.BOOKS, 4).put(0, "minecraft:enchanted_book", "mending", 1, 1)
                .put(1, "minecraft:enchanted_book", "unbreaking_3", 1, 1);
        Plan p2 = new HallSortPlanner().plan(List.of(a2, b2), 2.0);
        assertEquals(1, p2.moves().size());
        Move m = p2.moves().get(0);
        assertEquals(1, m.fromChest());
        assertEquals(0, m.fromSlot());
        assertEquals(0, m.toChest());
        assertEquals(2, m.toSlot());
    }

    @Test
    void partialStacksConsolidateAndFullChestsAreFlagged() {
        Chest c = new Chest(1L, StorageCategories.STONE, 3).put(0, "minecraft:cobblestone", "", 30, 64)
                .put(1, "minecraft:dirt", "", 64, 64).put(2, "minecraft:cobblestone", "", 40, 64);
        Plan p = new HallSortPlanner().plan(List.of(c), 0.9);
        assertEquals(1, p.moves().size());
        Move m = p.moves().get(0);
        assertEquals(2, m.fromSlot());
        assertEquals(0, m.toSlot());
        assertEquals(34, m.count());
        assertEquals(List.of(0), p.nearlyFull());
    }

    @Test
    void mixedChestsAreLeftAlone() {
        Chest mixed = new Chest(1L, null, 3).put(0, "minecraft:oak_log", "", 5, 64)
                .put(1, "minecraft:cobblestone", "", 5, 64).put(2, "minecraft:bread", "", 5, 64);
        Chest wood = new Chest(2L, StorageCategories.WOOD, 3);
        Plan p = new HallSortPlanner().plan(List.of(mixed, wood), 0.9);
        assertNull(p.categories()[0]);
        assertTrue(p.moves().isEmpty());
    }
}
