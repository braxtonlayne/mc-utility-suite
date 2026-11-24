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
    void urgentPrefersTheMostHealingFoodOverTheLeastWasteful() {
        // hunger 19: the beef overfills by 7, so the calm choice is the carrot; in danger the beef heals more
        put(1, "minecraft:carrot", 3, 3.6f);
        put(2, "minecraft:cooked_beef", 8, 12.8f);
        assertEquals(1, FoodSelector.choose(ids, nut, sat, 19, false, false, false));
        assertEquals(2, FoodSelector.choose(ids, nut, sat, 19, false, true, false));
    }

    @Test
    void notUrgentMatchesTheOldChoice() {
        put(1, "minecraft:carrot", 3, 3.6f);
        put(2, "minecraft:cooked_beef", 8, 12.8f);
        put(3, "minecraft:golden_apple", 4, 9.6f);
        assertEquals(FoodSelector.choose(ids, nut, sat, 16, true),
                FoodSelector.choose(ids, nut, sat, 16, true, false, true));
    }

    @Test
    void goldenApplesOnlyWhenUrgentAndAllowed() {
        put(2, "minecraft:cooked_beef", 8, 12.8f);
        put(5, "minecraft:golden_apple", 4, 9.6f);
        assertEquals(2, FoodSelector.choose(ids, nut, sat, 10, false), "never eaten for hunger");
        assertEquals(2, FoodSelector.choose(ids, nut, sat, 10, false, false, true), "not urgent: still skipped");
        assertEquals(2, FoodSelector.choose(ids, nut, sat, 10, false, true, false), "setting off: still skipped");
        assertEquals(5, FoodSelector.choose(ids, nut, sat, 10, false, true, true), "danger + setting: golden wins");
    }

    @Test
    void plainGoldenAppleGoesBeforeTheEnchantedOne() {
        put(3, "minecraft:enchanted_golden_apple", 4, 9.6f);
        put(7, "minecraft:golden_apple", 4, 9.6f);
        put(20, "minecraft:cooked_beef", 8, 12.8f);
        assertEquals(7, FoodSelector.choose(ids, nut, sat, 10, false, true, true));
        ids[7] = null;
        nut[7] = 0;
        assertEquals(3, FoodSelector.choose(ids, nut, sat, 10, false, true, true));
    }

    @Test
    void atFullHungerOnlyGoldenApplesQualify() {
        put(2, "minecraft:cooked_beef", 8, 12.8f);
        assertEquals(-1, FoodSelector.choose(ids, nut, sat, 20, false, true, true));
        put(12, "minecraft:golden_apple", 4, 9.6f);
        assertEquals(12, FoodSelector.choose(ids, nut, sat, 20, false, true, true));
    }

    @Test
    void urgentStillSkipsOtherUnsafeFood() {
        put(0, "minecraft:rotten_flesh", 4, 0.8f);
        put(1, "minecraft:pufferfish", 1, 0.2f);
        assertEquals(-1, FoodSelector.choose(ids, nut, sat, 4, false, true, true));
        put(9, "minecraft:bread", 5, 6f);
        assertEquals(9, FoodSelector.choose(ids, nut, sat, 4, false, true, true));
    }

    @Test
    void urgentHotbarWinsAnExactTie() {
        put(4, "minecraft:bread", 5, 6f);
        put(15, "minecraft:bread", 5, 6f);
        assertEquals(4, FoodSelector.choose(ids, nut, sat, 10, false, true, false));
    }

    @Test
    void eatingBlockedInLavaBurningByLavaOrBeforeABlast() {
        assertFalse(FoodSelector.eatingBlocked(false, false, false, false));
        assertTrue(FoodSelector.eatingBlocked(true, false, false, false));
        assertTrue(FoodSelector.eatingBlocked(false, true, true, false));
        assertFalse(FoodSelector.eatingBlocked(false, true, false, false), "burning away from lava: eating is fine");
        assertFalse(FoodSelector.eatingBlocked(false, false, true, false), "lava nearby but not burning");
        assertTrue(FoodSelector.eatingBlocked(false, false, false, true));
    }

    @Test
    void swapBackOnlyWhenPulledAndEnabled() {
        assertTrue(FoodSelector.needsSwapBack(true, true));
        assertFalse(FoodSelector.needsSwapBack(true, false));
        assertFalse(FoodSelector.needsSwapBack(false, true));
    }
}
