package com.utilitysuite.tools.inventory;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

import java.util.Arrays;

/**
 * Minecraft adapter: fills the engine-agnostic {@link InventoryModel} and container arrays from the live inventory.
 * Client thread only. Unpatched stacks use the raw item id as their key; stacks with components (enchanted, named,
 * damaged) get a negative item+components hash, so they only merge with identical stacks.
 */
final class McInventory {

    /** Reusable container scratch (a double chest has 54 slots). */
    final int[] cItem = new int[DepositPlanner.MAX_CONTAINER];
    final int[] cKey = new int[DepositPlanner.MAX_CONTAINER];
    final int[] cCount = new int[DepositPlanner.MAX_CONTAINER];
    final int[] cMax = new int[DepositPlanner.MAX_CONTAINER];
    final int[] menuSlotOfInv = new int[InventoryModel.SLOTS];
    int containerSlots;

    private Level protectionLevelOwner;
    private Holder<Enchantment> protection;

    static int itemId(Item item) {
        return BuiltInRegistries.ITEM.getId(item);
    }

    static int keyOf(ItemStack stack) {
        if (stack.isComponentsPatchEmpty()) {
            return itemId(stack.getItem());
        }
        return ItemStack.hashItemAndComponents(stack) | 0x80000000;
    }

    static int flagsOf(ItemStack stack) {
        Item item = stack.getItem();
        int f = 0;
        if (item instanceof BlockItem) {
            f |= InventoryModel.FLAG_BLOCK;
        }
        if (stack.has(DataComponents.FOOD)) {
            f |= InventoryModel.FLAG_FOOD;
        }
        if (item == Items.ENDER_PEARL) {
            f |= InventoryModel.FLAG_PEARL;
        }
        if (item == Items.FIREWORK_ROCKET) {
            f |= InventoryModel.FLAG_ROCKET;
        }
        return f;
    }

    /** Fills the 36 inventory slots (and armor scoring when {@code withArmor}). */
    void read(Player player, InventoryModel model, boolean withArmor) {
        Inventory inv = player.getInventory();
        for (int s = 0; s < InventoryModel.SLOTS; s++) {
            ItemStack st = inv.items.get(s);
            if (st.isEmpty()) {
                model.set(s, 0, 0, 0, 0, 0);
                continue;
            }
            model.set(s, itemId(st.getItem()), keyOf(st), st.getCount(), st.getMaxStackSize(), flagsOf(st));
            if (withArmor && st.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot().isArmor()) {
                model.armorType[s] = armor.getEquipmentSlot().getIndex();
                model.armorScore[s] = candidateScore(player.level(), st, armor);
            }
        }
        if (withArmor) {
            for (int a = 0; a < InventoryModel.ARMOR_SLOTS; a++) {
                ItemStack st = inv.armor.get(a);
                model.equippedCount[a] = st.getCount();
                boolean isArmor = st.getItem() instanceof ArmorItem;
                model.equippedIsArmor[a] = isArmor;
                model.equippedLocked[a] = !st.isEmpty()
                        && EnchantmentHelper.has(st, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
                model.equippedScore[a] = isArmor ? rawScore(player.level(), st, (ArmorItem) st.getItem()) : 0;
            }
        }
    }

    private int candidateScore(Level level, ItemStack st, ArmorItem armor) {
        if (EnchantmentHelper.has(st, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return -1;
        }
        if (st.isDamageableItem() && st.getMaxDamage() > 0
                && (st.getMaxDamage() - st.getDamageValue()) * 10 < st.getMaxDamage()) {
            return -1; // under 10% durability left
        }
        return rawScore(level, st, armor);
    }

    private int rawScore(Level level, ItemStack st, ArmorItem armor) {
        int prot = 0;
        Holder<Enchantment> p = protection(level);
        if (p != null) {
            prot = st.getEnchantmentLevel(p);
        }
        return ArmorRanker.score(armor.getDefense(), armor.getToughness(), prot);
    }

    private Holder<Enchantment> protection(Level level) {
        if (level != protectionLevelOwner) {
            protectionLevelOwner = level;
            protection = level == null ? null : level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolder(Enchantments.PROTECTION).orElse(null);
        }
        return protection;
    }

    /**
     * Fills the container arrays and {@link #menuSlotOfInv} from an open menu: every slot backed by another container
     * is a container slot; slots backed by the player's inventory map back to their inventory index.
     */
    void readMenu(Player player, AbstractContainerMenu menu) {
        Arrays.fill(menuSlotOfInv, -1);
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inv) {
                int idx = slot.getContainerSlot();
                if (idx >= 0 && idx < InventoryModel.SLOTS) {
                    menuSlotOfInv[idx] = i;
                }
                continue;
            }
            if (n >= DepositPlanner.MAX_CONTAINER) {
                continue;
            }
            ItemStack st = slot.getItem();
            if (st.isEmpty()) {
                cItem[n] = 0;
                cKey[n] = 0;
                cCount[n] = 0;
                cMax[n] = 64;
            } else {
                cItem[n] = itemId(st.getItem());
                cKey[n] = keyOf(st);
                cCount[n] = st.getCount();
                cMax[n] = Math.min(st.getMaxStackSize(), slot.getMaxStackSize(st));
            }
            n++;
        }
        containerSlots = n;
    }
}
