package com.utilitysuite.jobs.fieldwork;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PenAndGrinderRulesTest {

    /** Client-side adults read age 1, never 0: readiness comes from the sweep's own feeding memory (playtest find). */
    @Test
    void adultsAreBreedReadyUnlessFedWithinTheCooldown() {
        assertTrue(PenRules.breedReady(true, Long.MIN_VALUE, 10));
        assertFalse(PenRules.breedReady(false, Long.MIN_VALUE, 10));
        assertFalse(PenRules.breedReady(true, 100, 100 + PenRules.BREED_COOLDOWN_TICKS - 1));
        assertTrue(PenRules.breedReady(true, 100, 100 + PenRules.BREED_COOLDOWN_TICKS));
    }

    /** After the first cow of a pair is fed it is no longer ready, but it is in love: the second is still fed. */
    @Test
    void aFedPartnerInLovePairsWithTheNextOne() {
        String[] types = {"minecraft:cow", "minecraft:cow"};
        boolean[] adult = {true, true};
        boolean[] ready = {false, true};
        boolean[] love = {PenRules.inLove(100, 150), false};
        boolean[] sheared = {true, true};
        PenRules.Means m = new PenRules.Means();
        m.food = t -> true;
        assertEquals(PenRules.Action.NONE, PenRules.actionFor(1, types, adult, ready, sheared, 2, m));
        assertEquals(PenRules.Action.BREED, PenRules.actionFor(1, types, adult, ready, love, sheared, 2, m));
        assertEquals(PenRules.Action.NONE, PenRules.actionFor(0, types, adult, ready, love, sheared, 2, m),
                "the fed one is not fed again");
        assertFalse(PenRules.inLove(100, 100 + PenRules.LOVE_TICKS));
        assertFalse(PenRules.inLove(Long.MIN_VALUE, 5));
    }

    @Test
    void breedingFoodTable() {
        assertTrue(PenRules.breeds("minecraft:cow", "minecraft:wheat"));
        assertTrue(PenRules.breeds("minecraft:sheep", "minecraft:wheat"));
        assertTrue(PenRules.breeds("minecraft:pig", "minecraft:beetroot"));
        assertTrue(PenRules.breeds("minecraft:chicken", "minecraft:wheat_seeds"));
        assertFalse(PenRules.breeds("minecraft:chicken", "minecraft:wheat"));
        assertFalse(PenRules.breeds("minecraft:pig", "minecraft:wheat"));
        assertEquals(0, PenRules.breedingFoods("minecraft:zombie").length);
    }

    @Test
    void shearsBeforeMilkBeforeBreedAndNeedsAPartner() {
        String[] types = {"minecraft:sheep", "minecraft:cow", "minecraft:cow", "minecraft:pig"};
        boolean[] adult = {true, true, true, true};
        boolean[] ready = {true, true, false, true};
        boolean[] sheared = {false, false, false, false};
        boolean[] inPen = {true, true, true, true};
        double[] dist = {9, 4, 16, 1};
        PenRules.Means m = new PenRules.Means();
        m.haveShears = true;
        m.food = t -> true;
        // Pig nearest but alone: no partner. Cow 1 has no ready partner either. Sheep: shear.
        assertEquals(0, PenRules.pick(types, adult, ready, sheared, inPen, dist, 4, m));
        sheared[0] = true;
        assertEquals(-1, PenRules.pick(types, adult, ready, sheared, inPen, dist, 4, m));
        m.haveEmptyBucket = true;
        assertEquals(1, PenRules.pick(types, adult, ready, sheared, inPen, dist, 4, m));
        assertEquals(PenRules.Action.MILK, PenRules.actionFor(1, types, adult, ready, sheared, 4, m));
        m.haveEmptyBucket = false;
        ready[2] = true;
        assertEquals(PenRules.Action.BREED, PenRules.actionFor(1, types, adult, ready, sheared, 4, m));
        m.populationCap = 2;
        assertEquals(PenRules.Action.NONE, PenRules.actionFor(1, types, adult, ready, sheared, 4, m));
        inPen[1] = false;
        inPen[2] = false;
        assertEquals(-1, PenRules.pick(types, adult, ready, sheared, inPen, dist, 4, m));
    }

    @Test
    void babiesAreLeftAlone() {
        String[] types = {"minecraft:sheep"};
        PenRules.Means m = new PenRules.Means();
        m.haveShears = true;
        assertEquals(PenRules.Action.NONE, PenRules.actionFor(0, types, new boolean[]{false},
                new boolean[]{true}, new boolean[]{false}, 1, m));
        assertTrue(PenRules.inPen(3.5, 64, 3.5, 0, 60, 0, 3, 70, 3));
        assertFalse(PenRules.inPen(4.01, 64, 3.5, 0, 60, 0, 3, 70, 3));
    }

    @Test
    void swingsOnlyAtFullChargeAndNotWhenStalled() {
        assertTrue(AfkGrinderRules.shouldSwing(1f, true, false, false));
        assertFalse(AfkGrinderRules.shouldSwing(0.9f, true, false, false));
        assertFalse(AfkGrinderRules.shouldSwing(1f, false, false, false));
        assertFalse(AfkGrinderRules.shouldSwing(1f, true, true, false));
        assertFalse(AfkGrinderRules.shouldSwing(1f, true, false, true));
        assertTrue(AfkGrinderRules.serverStalled(900, 5, AfkGrinderRules.STALL_MS));
        assertTrue(AfkGrinderRules.serverStalled(50, 300, AfkGrinderRules.STALL_MS));
        assertFalse(AfkGrinderRules.serverStalled(50, 5, AfkGrinderRules.STALL_MS));
    }

    @Test
    void targetsNearestHostileInReachThenLowestHealth() {
        boolean[] hostile = {false, true, true, true};
        double[] dist = {1, 4, 4, 25};
        float[] health = {20, 20, 6, 1};
        assertEquals(2, AfkGrinderRules.pickTarget(hostile, dist, health, 4, 9));
        assertEquals(-1, AfkGrinderRules.pickTarget(new boolean[]{true}, new double[]{16}, new float[]{5}, 1, 9));
    }

    @Test
    void swapsToMendingBackupBeforeTheWeaponBreaks() {
        boolean[] weapon = {true, true, true, false};
        int[] damage = {1550, 100, 0, 0};
        int[] max = {1561, 1561, 250, 0};
        boolean[] mending = {true, true, false, false};
        float[] attack = {8, 7, 9, 1};
        assertEquals(1, AfkGrinderRules.weaponSlot(weapon, damage, max, mending, attack, 4, 0,
                AfkGrinderRules.LOW_DURABILITY));
        damage[0] = 100;
        assertEquals(0, AfkGrinderRules.weaponSlot(weapon, damage, max, mending, attack, 4, 0,
                AfkGrinderRules.LOW_DURABILITY));
        damage[0] = 1560;
        damage[1] = 1560;
        damage[2] = 249;
        assertEquals(-1, AfkGrinderRules.weaponSlot(weapon, damage, max, mending, attack, 4, 0,
                AfkGrinderRules.LOW_DURABILITY));
        assertTrue(AfkGrinderRules.shouldEat(10, 20f, 20f, 14, 0.5f));
    }
}
