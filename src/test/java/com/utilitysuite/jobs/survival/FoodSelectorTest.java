package com.utilitysuite.jobs.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodSelectorTest {

    private final String[] ids = new String[36];
    private final int[] nut = new int[36];
    private final float[] sat = new float[36];

    private void put(int slot, String id, int nutrition, float saturation) {
        ids[slot] = id;
        nut[slot] = nutrition;
        sat[slot] = saturation;
    }

    @Test
    void inventoryFoodBeatsWorseHotbarFood() {
        put(2, "minecraft:apple", 4, 2.4f);
        put(20, "minecraft:cooked_beef", 8, 12.8f);
        assertEquals(20, FoodSelector.choose(ids, nut, sat, 8));
    }

    @Test
    void emptyHotbarStillFindsFoodInThePack() {
        put(30, "minecraft:bread", 5, 6f);
        assertEquals(30, FoodSelector.choose(ids, nut, sat, 10));
    }

    @Test
    void hotbarWinsAnExactTie() {
        put(4, "minecraft:bread", 5, 6f);
        put(15, "minecraft:bread", 5, 6f);
        assertEquals(4, FoodSelector.choose(ids, nut, sat, 10));
    }

    @Test
    void unsafeFoodsAreSkippedEverywhere() {
        put(1, "minecraft:rotten_flesh", 4, 0.8f);
        put(12, "minecraft:poisonous_potato", 2, 1.2f);
        put(13, "minecraft:golden_apple", 4, 9.6f);
        assertEquals(-1, FoodSelector.choose(ids, nut, sat, 5));
        put(33, "minecraft:carrot", 3, 3.6f);
        assertEquals(33, FoodSelector.choose(ids, nut, sat, 5));
    }

    @Test
    void longJobsPreferSaturation() {
        put(0, "minecraft:cooked_chicken", 6, 7.2f);
        put(1, "minecraft:cooked_porkchop", 8, 12.8f);
        put(2, "minecraft:bread", 5, 6f);
        assertEquals(1, FoodSelector.choose(ids, nut, sat, 10, true));
    }

    @Test
    void eatsBeforeALongSprintLegBelow18() {
        assertTrue(FoodSelector.shouldEat(17, 20, 20, 14, 0.5f, true));
        assertFalse(FoodSelector.shouldEat(17, 20, 20, 14, 0.5f, false));
        assertFalse(FoodSelector.shouldEat(18, 20, 20, 14, 0.5f, true));
        assertFalse(FoodSelector.shouldEat(20, 20, 20, 14, 0.5f, true));
    }

    @Test
    void noPullWhileAScreenIsOpenOrABlockIsBeingBroken() {
        assertTrue(FoodSelector.canPull(false, false));
        assertFalse(FoodSelector.canPull(true, false));
        assertFalse(FoodSelector.canPull(false, true));
    }

    @Test
    void swapTargetPrefersAnEmptyHotbarSlotElseTheSelectedOne() {
        boolean[] occupied = new boolean[9];
        java.util.Arrays.fill(occupied, true);
        occupied[6] = false;
        assertEquals(6, FoodSelector.chooseSwapTarget(occupied, 2));
        occupied[6] = true;
        assertEquals(2, FoodSelector.chooseSwapTarget(occupied, 2));
        assertEquals(0, FoodSelector.chooseSwapTarget(occupied, -1));
    }

    @Test
    void swapBackOnlyWhenPulledAndEnabled() {
        assertTrue(FoodSelector.needsSwapBack(true, true));
        assertFalse(FoodSelector.needsSwapBack(true, false));
        assertFalse(FoodSelector.needsSwapBack(false, true));
    }
}
