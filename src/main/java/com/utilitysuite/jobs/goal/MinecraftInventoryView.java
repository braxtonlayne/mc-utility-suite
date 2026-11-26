package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.IInventoryView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IInventoryView} over the local player's main inventory, hotbar and offhand. {@link #count} and
 * {@link #bestPickaxeTier} allocate nothing after warm-up (item lookups are cached), so the executor can call them
 * every tick. Client thread only.
 */
public final class MinecraftInventoryView implements IInventoryView {

    private final Map<String, Item> itemById = new HashMap<>();
    private final IdentityHashMap<Item, String> idByItem = new IdentityHashMap<>();

    private static Inventory inventory() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getInventory();
    }

    public String idOf(Item item) {
        String id = idByItem.get(item);
        if (id == null) {
            id = BuiltInRegistries.ITEM.getKey(item).toString();
            idByItem.put(item, id);
        }
        return id;
    }

    public Item itemOf(String id) {
        Item it = itemById.get(id);
        if (it == null) {
            Identifier rl = Identifier.tryParse(id);
            it = rl == null ? net.minecraft.world.item.Items.AIR : BuiltInRegistries.ITEM.getValue(rl);
            itemById.put(id, it);
        }
        return it;
    }

    @Override
    public Map<String, Integer> counts() {
        Map<String, Integer> out = new HashMap<>();
        Inventory inv = inventory();
        if (inv == null) {
            return out;
        }
        add(out, inv.getNonEquipmentItems());
        add(out, offhand(inv));
        return out;
    }

    /** The offhand as a one-element list (26.3: {@code Inventory.offhand} is gone; slot {@link Inventory#SLOT_OFFHAND}). */
    private static List<ItemStack> offhand(Inventory inv) {
        return List.of(inv.getItem(Inventory.SLOT_OFFHAND));
    }

    private void add(Map<String, Integer> out, List<ItemStack> stacks) {
        for (int i = 0, n = stacks.size(); i < n; i++) {
            ItemStack s = stacks.get(i);
            if (!s.isEmpty()) {
                String id = idOf(s.getItem());
                out.merge(id, s.getCount(), Integer::sum);
                String pk = potionKey(s, id);
                if (pk != null) {
                    out.merge(pk, s.getCount(), Integer::sum); // brewing stages: "minecraft:potion#minecraft:awkward"
                }
            }
        }
    }

    /** {@code <item id>#<potion id>} for a potion-like stack (see BrewingPlanner keys), else null. */
    public static String potionKey(ItemStack s, String itemId) {
        net.minecraft.world.item.alchemy.PotionContents pc =
                s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (pc == null || pc.potion().isEmpty()) {
            return null;
        }
        return pc.potion().get().unwrapKey().map(k -> itemId + "#" + k.identifier()).orElse(null);
    }

    @Override
    public List<com.utilitysuite.core.api.NestedContainer> carriedContainers() {
        Inventory inv = inventory();
        if (inv == null) {
            return List.of();
        }
        List<com.utilitysuite.core.api.NestedContainer> out = new java.util.ArrayList<>();
        com.utilitysuite.jobs.storage.ShulkerItems.collect(inv.getNonEquipmentItems(), out);
        com.utilitysuite.jobs.storage.ShulkerItems.collect(offhand(inv), out);
        return out;
    }

    @Override
    public int count(String itemId) {
        Inventory inv = inventory();
        if (inv == null) {
            return 0;
        }
        Item item = itemOf(itemId);
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        return count(inv.getNonEquipmentItems(), item) + (off.getItem() == item ? off.getCount() : 0);
    }

    private static int count(List<ItemStack> stacks, Item item) {
        int c = 0;
        for (int i = 0, n = stacks.size(); i < n; i++) {
            ItemStack s = stacks.get(i);
            if (s.getItem() == item) {
                c += s.getCount();
            }
        }
        return c;
    }

    @Override
    public int bestPickaxeTier() {
        Inventory inv = inventory();
        if (inv == null) {
            return 0;
        }
        int best = 0;
        for (int i = 0, n = inv.getNonEquipmentItems().size(); i < n; i++) {
            ItemStack s = inv.getNonEquipmentItems().get(i);
            if (McTools.isPickaxe(s)) {
                best = Math.max(best, tierOf(s));
            }
        }
        return best;
    }

    /**
     * Harvest tier of a pickaxe: vanilla by registry id, modded by the tool component's "incorrect for" tag, else 2
     * (26.3: the {@code Tier} enum is gone).
     */
    public int tierOf(ItemStack s) {
        return McTools.pickaxeTier(s, idOf(s.getItem()));
    }

    /** Fills the guard snapshot from the 36 inventory slots and the selected slot. No allocation after warm-up. */
    public void fillToolGuard(ToolDurabilityGuard g) {
        Inventory inv = inventory();
        g.clear();
        if (inv == null) {
            g.selected = -1;
            return;
        }
        g.selected = inv.getSelectedSlot();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getNonEquipmentItems().get(i);
            if (s.isEmpty()) {
                continue;
            }
            if (McTools.isPickaxe(s)) {
                g.kind[i] = ToolDurabilityGuard.PICKAXE;
                g.tier[i] = tierOf(s);
            } else if (McTools.isShovel(s)) {
                g.kind[i] = ToolDurabilityGuard.SHOVEL;
            } else {
                continue;
            }
            g.remaining[i] = s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue()
                    : ToolDurabilityGuard.UNBREAKABLE;
            g.enchanted[i] = !s.getEnchantments().isEmpty();
        }
    }

    /** Fortune level on the best pickaxe carried. */
    public int fortuneOfBestPickaxe() {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = inventory();
        if (inv == null || mc.level == null) {
            return 0;
        }
        var fortune = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);
        int bestTier = -1;
        int level = 0;
        for (ItemStack s : inv.getNonEquipmentItems()) {
            if (McTools.isPickaxe(s)) {
                int t = tierOf(s);
                if (t > bestTier) {
                    bestTier = t;
                    level = EnchantmentHelper.getItemEnchantmentLevel(fortune, s);
                }
            }
        }
        return level;
    }

    /** True for a pickaxe with Silk Touch (mines an ender chest back as itself instead of obsidian). */
    public boolean isSilkTouchPickaxe(ItemStack s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || s.isEmpty() || !McTools.isPickaxe(s)) {
            return false;
        }
        var silk = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH);
        return EnchantmentHelper.getItemEnchantmentLevel(silk, s) > 0;
    }

    /** Inventory slot (0..35, hotbar first) of a Silk Touch pickaxe, or -1. Not per tick. */
    public int silkTouchPickaxeSlot() {
        Inventory inv = inventory();
        if (inv == null) {
            return -1;
        }
        for (int i = 0; i < 36; i++) {
            if (isSilkTouchPickaxe(inv.getNonEquipmentItems().get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** An ender chest item and a Silk Touch pickaxe are both carried (the carried chest can be placed and taken back). */
    public boolean canUseCarriedEnderChest() {
        return count("minecraft:ender_chest") > 0 && silkTouchPickaxeSlot() >= 0;
    }
}
