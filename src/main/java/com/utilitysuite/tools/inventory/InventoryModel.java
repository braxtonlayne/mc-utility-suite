package com.utilitysuite.tools.inventory;

import java.util.Arrays;

/**
 * Engine-agnostic, reusable snapshot of the player's inventory for the SmartInventory planners. Primitive arrays only,
 * refilled in place by the Minecraft adapter, so scanning allocates nothing.
 * <p>
 * Slot indices follow vanilla {@code Inventory}: 0-8 hotbar, 9-35 main inventory. Armor indices follow
 * {@code Inventory.getItem(36 + i)} / {@code EquipmentSlot.getIndex()}: 0 feet, 1 legs, 2 chest, 3 head.
 * <ul>
 *   <li>{@link #item}: raw item id (same item, any components)</li>
 *   <li>{@link #key}: item + components identity (stacks with equal keys merge)</li>
 *   <li>{@link #count} 0 means empty (the other fields are then ignored)</li>
 * </ul>
 */
public final class InventoryModel {

    public static final int SLOTS = 36;
    public static final int HOTBAR = 9;
    public static final int ARMOR_SLOTS = 4;

    /** Category flags used by hotbar refill. */
    public static final int FLAG_BLOCK = 1;
    public static final int FLAG_FOOD = 2;
    public static final int FLAG_PEARL = 4;
    public static final int FLAG_ROCKET = 8;

    /** {@link #armorType} value for items that are not armor. */
    public static final int NOT_ARMOR = -1;

    public final int[] item = new int[SLOTS];
    public final int[] key = new int[SLOTS];
    public final int[] count = new int[SLOTS];
    public final int[] maxStack = new int[SLOTS];
    public final int[] flags = new int[SLOTS];
    /** Armor slot (0-3) this item equips into, or {@link #NOT_ARMOR}. */
    public final int[] armorType = new int[SLOTS];
    /** Ranking score from {@link ArmorRanker#score}; negative = never equip (binding curse, nearly broken). */
    public final int[] armorScore = new int[SLOTS];

    /** What is equipped now. */
    public final int[] equippedCount = new int[ARMOR_SLOTS];
    /** True when the equipped item is real armor (an elytra or a carved pumpkin is not and is never replaced). */
    public final boolean[] equippedIsArmor = new boolean[ARMOR_SLOTS];
    /** True when the equipped piece cannot be taken off (curse of binding). */
    public final boolean[] equippedLocked = new boolean[ARMOR_SLOTS];
    public final int[] equippedScore = new int[ARMOR_SLOTS];

    public void clear() {
        Arrays.fill(item, 0);
        Arrays.fill(key, 0);
        Arrays.fill(count, 0);
        Arrays.fill(maxStack, 0);
        Arrays.fill(flags, 0);
        Arrays.fill(armorType, NOT_ARMOR);
        Arrays.fill(armorScore, 0);
        Arrays.fill(equippedCount, 0);
        Arrays.fill(equippedIsArmor, false);
        Arrays.fill(equippedLocked, false);
        Arrays.fill(equippedScore, 0);
    }

    /** Test/adapter helper: sets one inventory slot. */
    public InventoryModel set(int slot, int itemId, int key, int count, int maxStack, int flags) {
        this.item[slot] = itemId;
        this.key[slot] = key;
        this.count[slot] = count;
        this.maxStack[slot] = maxStack;
        this.flags[slot] = flags;
        this.armorType[slot] = NOT_ARMOR;
        this.armorScore[slot] = 0;
        return this;
    }

    public boolean isEmpty(int slot) {
        return count[slot] <= 0;
    }

    /** Menu slot index of an inventory slot in the player's own inventory menu (no container open). */
    public static int inventoryMenuSlot(int invSlot) {
        return invSlot < HOTBAR ? 36 + invSlot : invSlot;
    }

    /** Menu slot index of an armor slot (0 feet .. 3 head) in the player's inventory menu: head 5 .. feet 8. */
    public static int armorMenuSlot(int armorIndex) {
        return 8 - armorIndex;
    }
}
