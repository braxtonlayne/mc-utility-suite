package com.utilitysuite.jobs.schematic.mc;

import com.utilitysuite.core.api.IInventoryView;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable snapshot of the player's inventory counts, taken on the client thread when an analysis starts (not per
 * tick) and handed to the background planner.
 */
public final class McInventoryView implements IInventoryView {

    private final Map<String, Integer> counts;

    private McInventoryView(Map<String, Integer> counts) {
        this.counts = Map.copyOf(counts);
    }

    public static McInventoryView snapshot(Player player) {
        Map<String, Integer> m = new TreeMap<>();
        if (player != null) {
            Inventory inv = player.getInventory();
            int n = inv.getContainerSize();
            for (int i = 0; i < n; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) {
                    m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
                }
            }
        }
        return new McInventoryView(m);
    }

    @Override
    public Map<String, Integer> counts() {
        return counts;
    }

    @Override
    public int count(String itemId) {
        return counts.getOrDefault(itemId, 0);
    }

    @Override
    public int bestPickaxeTier() {
        return 0; // not needed by the schematic toolkit
    }
}
