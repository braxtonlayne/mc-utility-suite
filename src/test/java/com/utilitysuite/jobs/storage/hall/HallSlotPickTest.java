package com.utilitysuite.jobs.storage.hall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slot-precise take: the right enchanted book leaves a chest of mixed books, even after the chest changed. */
class HallSlotPickTest {

    private static final String BOOK = "minecraft:enchanted_book";
    private static final String MENDING = "minecraft:mending=1";
    private static final String SHARP = "minecraft:sharpness=5";
    private static final String UNBREAKING = "minecraft:unbreaking=3";

    @Test
    void takesThePlannedSlotWhenItStillHoldsThatBook() {
        String[] item = {BOOK, BOOK, BOOK};
        String[] variant = {SHARP, MENDING, UNBREAKING};
        int[] count = {1, 1, 1};
        List<HallSlotPick.Pick> p = HallSlotPick.pick(List.of(new HallSortSteps.Take(1, 1, BOOK, MENDING)), item,
                variant, count);
        assertEquals(List.of(new HallSlotPick.Pick(1, 1)), p);
    }

    @Test
    void aMovedBookIsFoundByItsEnchantmentsNeverByItemIdAlone() {
        // remembered: mending in slot 0; now slot 0 holds sharpness and the mending book sits in slot 2
        String[] item = {BOOK, null, BOOK};
        String[] variant = {SHARP, "", MENDING};
        int[] count = {1, 0, 1};
        List<HallSlotPick.Pick> p = HallSlotPick.pick(List.of(new HallSortSteps.Take(0, 1, BOOK, MENDING)), item,
                variant, count);
        assertEquals(List.of(new HallSlotPick.Pick(2, 1)), p);
    }

    @Test
    void nothingIsTakenWhenNoSlotHoldsTheRightBook() {
        String[] item = {BOOK, BOOK};
        String[] variant = {SHARP, UNBREAKING};
        int[] count = {1, 1};
        assertTrue(HallSlotPick.pick(List.of(new HallSortSteps.Take(0, 1, BOOK, MENDING)), item, variant, count)
                .isEmpty());
    }

    @Test
    void plannedSlotsAreClaimedBeforeFallbacksAndEachSlotOnce() {
        String[] item = {BOOK, BOOK, "minecraft:cobblestone", "minecraft:cobblestone"};
        String[] variant = {MENDING, MENDING, "", ""};
        int[] count = {1, 1, 20, 64};
        List<HallSlotPick.Pick> p = HallSlotPick.pick(List.of(
                new HallSortSteps.Take(5, 1, BOOK, MENDING), // slot gone: falls back
                new HallSortSteps.Take(0, 1, BOOK, MENDING), // planned slot, claimed first
                new HallSortSteps.Take(2, 30, "minecraft:cobblestone", "")), item, variant, count);
        assertEquals(List.of(new HallSlotPick.Pick(0, 1), new HallSlotPick.Pick(2, 20), new HallSlotPick.Pick(1, 1),
                new HallSlotPick.Pick(3, 10)), p);
    }
}
