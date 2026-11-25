package com.utilitysuite.jobs.storage;

import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.jobs.planner.ShulkerContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads shulker box contents from item stacks ({@link DataComponents#CONTAINER}). Called when a container closes or a
 * plan context is captured, never per tick.
 */
public final class ShulkerItems {

    private ShulkerItems() {
    }

    /** The box and its contents, or null when the stack is not a shulker box. An empty box yields empty contents. */
    public static NestedContainer read(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!ShulkerContents.isShulkerBox(id)) {
            return null;
        }
        Map<String, Integer> contents = new LinkedHashMap<>();
        ItemContainerContents c = stack.get(DataComponents.CONTAINER);
        if (c != null) {
            for (ItemStack in : c.nonEmptyItems()) {
                contents.merge(BuiltInRegistries.ITEM.getKey(in.getItem()).toString(), in.getCount(), Integer::sum);
            }
        }
        return new NestedContainer(id, contents);
    }

    /** Appends every shulker box among the stacks (one entry per box; stacked empty boxes count once each). */
    public static void collect(List<ItemStack> stacks, List<NestedContainer> out) {
        for (int i = 0, n = stacks.size(); i < n; i++) {
            ItemStack s = stacks.get(i);
            NestedContainer nc = read(s);
            if (nc != null && !nc.contents().isEmpty()) {
                out.add(nc);
            }
        }
    }
}
