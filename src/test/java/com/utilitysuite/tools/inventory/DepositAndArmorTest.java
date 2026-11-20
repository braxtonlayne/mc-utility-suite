package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.IContainerClicker.Click;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deposit selection, pinned slots and auto-armor ranking. */
class DepositAndArmorTest {

    static final int COBBLE = 1;
    static final int IRON = 2;
    static final int SWORD = 3;
    static final int DIRT = 4;

    /** A single chest (27 slots): player inventory menu slots follow at 27.. (main 27-53, hotbar 54-62). */
    private static int[] chestMapping() {
        int[] map = new int[InventoryModel.SLOTS];
        for (int s = 0; s < InventoryModel.SLOTS; s++) {
            map[s] = s < InventoryModel.HOTBAR ? 54 + s : 27 + (s - InventoryModel.HOTBAR);
        }
        return map;
    }

    private static final class Chest {
        final int[] item = new int[27];
        final int[] key = new int[27];
        final int[] count = new int[27];
        final int[] max = new int[27];

        Chest() {
            Arrays.fill(max, 64);
        }

        Chest put(int slot, int id, int n) {
            item[slot] = id;
            key[slot] = id;
            count[slot] = n;
            return this;
        }
    }

    private static int plan(InventoryModel inv, Chest c, DepositPlanner.Mode mode, int pinned, boolean keepHotbar,
                            ClickPlan out) {
        return new DepositPlanner().plan(inv, chestMapping(), c.item, c.key, c.count, c.max, 27, mode, pinned,
                keepHotbar, out);
    }

    @Test
    void quickDepositMovesOnlyItemsTheChestAlreadyHolds() {
        InventoryModel inv = new InventoryModel();
        inv.clear();
        inv.set(10, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        inv.set(11, DIRT, DIRT, 64, 64, InventoryModel.FLAG_BLOCK);
        inv.set(3, COBBLE, COBBLE, 30, 64, InventoryModel.FLAG_BLOCK);
        Chest chest = new Chest().put(0, COBBLE, 10);
        ClickPlan out = new ClickPlan();
        assertEquals(2, plan(inv, chest, DepositPlanner.Mode.MATCHING, 0, false, out));
        assertEquals(28, out.slot(0), "main slot 10 first");
        assertEquals(57, out.slot(1), "then hotbar slot 3");
        assertEquals(Click.QUICK_MOVE, out.click(0));
    }

    @Test
    void dumpSkipsPinnedHotbarSlots() {
        InventoryModel inv = new InventoryModel();
        inv.clear();
        inv.set(0, SWORD, SWORD, 1, 1, 0);
        inv.set(1, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        inv.set(15, IRON, IRON, 12, 64, 0);
        ClickPlan out = new ClickPlan();
        int pinned = PinnedSlots.parse("1");
        assertEquals(2, plan(inv, new Chest(), DepositPlanner.Mode.ALL, pinned, false, out));
        for (int i = 0; i < out.size(); i++) {
            assertTrue(out.slot(i) != 54, "pinned hotbar slot 1 (menu 54) must never be dumped");
        }
    }

    @Test
    void keepHotbarLeavesTheWholeHotbar() {
        InventoryModel inv = new InventoryModel();
        inv.clear();
        inv.set(1, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        inv.set(15, IRON, IRON, 12, 64, 0);
        ClickPlan out = new ClickPlan();
        assertEquals(1, plan(inv, new Chest(), DepositPlanner.Mode.ALL, 0, true, out));
        assertEquals(33, out.slot(0));
    }

    @Test
    void fullChestProducesNoClicksAndPartialRoomIsUsedFirst() {
        InventoryModel inv = new InventoryModel();
        inv.clear();
        inv.set(9, COBBLE, COBBLE, 10, 64, InventoryModel.FLAG_BLOCK);
        inv.set(10, DIRT, DIRT, 10, 64, InventoryModel.FLAG_BLOCK);
        Chest chest = new Chest();
        for (int i = 0; i < 27; i++) {
            chest.put(i, IRON, 64);
        }
        ClickPlan out = new ClickPlan();
        assertEquals(0, plan(inv, chest, DepositPlanner.Mode.ALL, 0, false, out));

        chest.put(5, COBBLE, 60); // 4 room for cobble, still no empty slot
        out.clear();
        assertEquals(1, plan(inv, chest, DepositPlanner.Mode.ALL, 0, false, out), "cobble partly fits, dirt not");
        assertEquals(27, out.slot(0), "menu slot 27 = inventory slot 9");
    }

    @Test
    void capacityIsSimulatedAcrossStacks() {
        InventoryModel inv = new InventoryModel();
        inv.clear();
        inv.set(9, COBBLE, COBBLE, 64, 64, InventoryModel.FLAG_BLOCK);
        inv.set(10, DIRT, DIRT, 64, 64, InventoryModel.FLAG_BLOCK);
        inv.set(11, IRON, IRON, 64, 64, 0);
        Chest chest = new Chest();
        for (int i = 0; i < 25; i++) {
            chest.put(i, SWORD, 1);
            chest.max[i] = 1;
        }
        ClickPlan out = new ClickPlan();
        assertEquals(2, plan(inv, chest, DepositPlanner.Mode.ALL, 0, false, out), "only two empty slots");
    }

    // ---- auto-armor ---------------------------------------------------------------------------------------------

    @Test
    void scoreRanksNetheriteOverDiamondAndProtectionOverPlainNetherite() {
        int diamond = ArmorRanker.score(8, 2f, 0);
        int netherite = ArmorRanker.score(8, 3f, 0);
        int diamondProt4 = ArmorRanker.score(8, 2f, 4);
        int iron = ArmorRanker.score(6, 0f, 0);
        assertTrue(netherite > diamond);
        assertTrue(diamondProt4 > netherite);
        assertTrue(diamond > iron);
    }

    private static InventoryModel armorInv() {
        InventoryModel inv = new InventoryModel();
        inv.clear();
        return inv;
    }

    private static void armorPiece(InventoryModel inv, int slot, int type, int score) {
        inv.set(slot, 100 + slot, 100 + slot, 1, 1, 0);
        inv.armorType[slot] = type;
        inv.armorScore[slot] = score;
    }

    @Test
    void equipsTheBestUpgradeIntoAnEmptySlotWithOneShiftClick() {
        InventoryModel inv = armorInv();
        armorPiece(inv, 12, 2, ArmorRanker.score(6, 0, 0));
        armorPiece(inv, 13, 2, ArmorRanker.score(8, 2, 0));
        ClickPlan out = new ClickPlan();
        assertEquals(1, ArmorRanker.plan(inv, out));
        assertEquals(1, out.size());
        assertEquals(13, out.slot(0));
        assertEquals(Click.QUICK_MOVE, out.click(0));
    }

    @Test
    void swapsAWorsePieceWithThreeClicks() {
        InventoryModel inv = armorInv();
        inv.equippedCount[3] = 1;
        inv.equippedIsArmor[3] = true;
        inv.equippedScore[3] = ArmorRanker.score(2, 0, 0);
        armorPiece(inv, 4, 3, ArmorRanker.score(3, 2, 0));
        ClickPlan out = new ClickPlan();
        assertEquals(1, ArmorRanker.plan(inv, out));
        assertEquals(3, out.size());
        assertEquals(40, out.slot(0), "hotbar slot 4 = menu 40");
        assertEquals(InventoryModel.armorMenuSlot(3), out.slot(1));
        assertEquals(5, out.slot(1), "head is menu slot 5");
        assertEquals(40, out.slot(2));
    }

    @Test
    void neverReplacesElytraLockedOrBetterPiecesAndSkipsUnusableCandidates() {
        InventoryModel inv = armorInv();
        inv.equippedCount[2] = 1;
        inv.equippedIsArmor[2] = false; // elytra
        armorPiece(inv, 9, 2, ArmorRanker.score(8, 3, 4));
        inv.equippedCount[1] = 1;
        inv.equippedIsArmor[1] = true;
        inv.equippedLocked[1] = true; // binding curse
        armorPiece(inv, 10, 1, ArmorRanker.score(7, 3, 4));
        inv.equippedCount[0] = 1;
        inv.equippedIsArmor[0] = true;
        inv.equippedScore[0] = ArmorRanker.score(3, 2, 0);
        armorPiece(inv, 11, 0, ArmorRanker.score(1, 0, 0));
        armorPiece(inv, 14, 3, -1); // nearly broken helmet
        assertEquals(-1, ArmorRanker.bestFor(inv, 2));
        assertEquals(-1, ArmorRanker.bestFor(inv, 1));
        assertEquals(-1, ArmorRanker.bestFor(inv, 0));
        assertEquals(-1, ArmorRanker.bestFor(inv, 3));
        assertTrue(!ArmorRanker.hasUpgrade(inv));
    }

    @Test
    void pinnedSlotsParseAndFormat() {
        int mask = PinnedSlots.parse(" 1, 9 ,3, 12, x");
        assertTrue(PinnedSlots.isPinned(mask, 0));
        assertTrue(PinnedSlots.isPinned(mask, 2));
        assertTrue(PinnedSlots.isPinned(mask, 8));
        assertTrue(!PinnedSlots.isPinned(mask, 1));
        assertEquals("1,3,9", PinnedSlots.format(mask));
        assertEquals("1,9", PinnedSlots.format(PinnedSlots.toggle(mask, 2)));
        assertEquals(0, PinnedSlots.parse(null));
    }
}
