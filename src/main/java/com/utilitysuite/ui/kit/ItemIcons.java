package com.utilitysuite.ui.kit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/** Item id → icon stack, cached (a stack is created once per id); unknown ids fall back to a neutral icon. */
public final class ItemIcons {

    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private ItemIcons() {
    }

    /** The icon for a registry id ("minecraft:diamond"), or {@code fallback} when unknown/absent. */
    public static ItemStack forId(String id, Item fallback) {
        if (id == null || id.isEmpty()) {
            return stack(fallback);
        }
        ItemStack s = CACHE.get(id);
        if (s == null) {
            Identifier rl = Identifier.tryParse(id);
            Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
            s = item == null || item == Items.AIR ? stack(fallback) : new ItemStack(item);
            if (CACHE.size() > 256) {
                CACHE.clear();
            }
            CACHE.put(id, s);
        }
        return s;
    }

    public static ItemStack stack(Item item) {
        String key = "#" + BuiltInRegistries.ITEM.getKey(item);
        return CACHE.computeIfAbsent(key, k -> new ItemStack(item));
    }
}
