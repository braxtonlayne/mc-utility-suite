package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.jobs.planner.ToolTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
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
            ResourceLocation rl = ResourceLocation.tryParse(id);
            it = rl == null ? net.minecraft.world.item.Items.AIR : BuiltInRegistries.ITEM.get(rl);
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
        add(out, inv.items);
        add(out, inv.offhand);
        return out;
    }

    private void add(Map<String, Integer> out, List<ItemStack> stacks) {
        for (int i = 0, n = stacks.size(); i < n; i++) {
            ItemStack s = stacks.get(i);
            if (!s.isEmpty()) {
                out.merge(idOf(s.getItem()), s.getCount(), Integer::sum);
            }
        }
    }

    @Override
    public List<com.utilitysuite.core.api.NestedContainer> carriedContainers() {
        Inventory inv = inventory();
        if (inv == null) {
            return List.of();
        }
        List<com.utilitysuite.core.api.NestedContainer> out = new java.util.ArrayList<>();
        com.utilitysuite.jobs.storage.ShulkerItems.collect(inv.items, out);
        com.utilitysuite.jobs.storage.ShulkerItems.collect(inv.offhand, out);
        return out;
    }

    @Override
    public int count(String itemId) {
        Inventory inv = inventory();
        if (inv == null) {
            return 0;
        }
        Item item = itemOf(itemId);
        return count(inv.items, item) + count(inv.offhand, item);
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
        for (int i = 0, n = inv.items.size(); i < n; i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof PickaxeItem p) {
                best = Math.max(best, tierOf(p, s));
            }
        }
        return best;
    }

    /** Harvest tier of a pickaxe; modded tiers fall back to their vanilla equivalent by registry id, else 2. */
    public int tierOf(PickaxeItem p, ItemStack s) {
        Tier t = p.getTier();
        if (t instanceof Tiers v) {
            return switch (v) {
                case WOOD, GOLD -> 1;
                case STONE -> 2;
                case IRON -> 3;
                case DIAMOND -> 4;
                case NETHERITE -> 5;
            };
        }
        int byId = ToolTiers.tierOf(idOf(s.getItem()));
        return byId > 0 ? byId : 2;
    }

    /** Fills the guard snapshot from the 36 inventory slots and the selected slot. No allocation after warm-up. */
    public void fillToolGuard(ToolDurabilityGuard g) {
        Inventory inv = inventory();
        g.clear();
        if (inv == null) {
            g.selected = -1;
            return;
        }
        g.selected = inv.selected;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty()) {
                continue;
            }
            Item item = s.getItem();
            if (item instanceof PickaxeItem p) {
                g.kind[i] = ToolDurabilityGuard.PICKAXE;
                g.tier[i] = tierOf(p, s);
            } else if (item instanceof net.minecraft.world.item.ShovelItem) {
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
        var fortune = mc.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.FORTUNE);
        int bestTier = -1;
        int level = 0;
        for (ItemStack s : inv.items) {
            if (s.getItem() instanceof PickaxeItem p) {
                int t = tierOf(p, s);
                if (t > bestTier) {
                    bestTier = t;
                    level = EnchantmentHelper.getItemEnchantmentLevel(fortune, s);
                }
            }
        }
        return level;
    }
}
