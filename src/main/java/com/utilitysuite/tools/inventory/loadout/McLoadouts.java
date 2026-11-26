package com.utilitysuite.tools.inventory.loadout;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;
import java.util.TreeMap;

/**
 * Reads ItemStacks into {@link GearItem}s for the loadout planner. Client thread; runs only when the user saves or
 * equips a loadout (allocation is fine here).
 */
public final class McLoadouts {

    private McLoadouts() {
    }

    public static GearItem gear(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return null;
        }
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        Map<String, Integer> ench = new TreeMap<>();
        ItemEnchantments e = st.getEnchantments();
        for (var entry : e.entrySet()) {
            Holder<Enchantment> h = entry.getKey();
            h.unwrapKey().ifPresent(k -> ench.put(k.identifier().toString(), entry.getIntValue()));
        }
        Component name = st.get(DataComponents.CUSTOM_NAME);
        return new GearItem(id, ench, name == null ? "" : name.getString());
    }

    /** The player's current armour, offhand and hotbar as a loadout. */
    public static Loadout capture(String name, Inventory inv) {
        GearItem[] s = new GearItem[Loadout.TARGETS];
        for (int a = 0; a < 4; a++) {
            s[a] = gear(inv.getItem(36 + a));
        }
        s[Loadout.OFFHAND_TARGET] = gear(inv.getItem(Inventory.SLOT_OFFHAND));
        for (int h = 0; h < 9; h++) {
            s[Loadout.HOTBAR_TARGET + h] = gear(inv.getNonEquipmentItems().get(h));
        }
        return new Loadout(name, s);
    }

    /**
     * Planner positions for the player's inventory plus the open container's slots ({@code containerSlots} = 0 for
     * the player's own inventory menu).
     */
    public static GearItem[] positions(Inventory inv, AbstractContainerMenu menu, int containerSlots) {
        GearItem[] pos = new GearItem[LoadoutPlanner.CONTAINER + containerSlots];
        for (int i = 0; i < 36; i++) {
            pos[i] = gear(inv.getNonEquipmentItems().get(i));
        }
        for (int a = 0; a < 4; a++) {
            pos[LoadoutPlanner.ARMOR + a] = gear(inv.getItem(36 + a));
        }
        pos[LoadoutPlanner.OFFHAND] = gear(inv.getItem(Inventory.SLOT_OFFHAND));
        for (int c = 0; c < containerSlots; c++) {
            pos[LoadoutPlanner.CONTAINER + c] = gear(menu.slots.get(c).getItem());
        }
        return pos;
    }

    /** Bit i set = armour piece i carries curse of binding. */
    public static int lockedArmor(Inventory inv) {
        int mask = 0;
        for (int a = 0; a < 4; a++) {
            ItemStack st = inv.getItem(36 + a);
            if (!st.isEmpty() && EnchantmentHelper.has(st, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                mask |= 1 << a;
            }
        }
        return mask;
    }
}
