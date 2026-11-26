package com.utilitysuite.world;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Registry id strings for blocks, items and dimensions, cached by identity so hot paths never build strings.
 * Client thread only.
 */
public final class WorldIds {

    private static final Reference2ObjectOpenHashMap<Block, String> BLOCKS = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<Item, String> ITEMS = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<ResourceKey<Level>, String> DIMENSIONS =
            new Reference2ObjectOpenHashMap<>();

    private WorldIds() {
    }

    public static String block(Block block) {
        String s = BLOCKS.get(block);
        if (s == null) {
            s = BuiltInRegistries.BLOCK.getKey(block).toString();
            BLOCKS.put(block, s);
        }
        return s;
    }

    public static String item(Item item) {
        String s = ITEMS.get(item);
        if (s == null) {
            s = BuiltInRegistries.ITEM.getKey(item).toString();
            ITEMS.put(item, s);
        }
        return s;
    }

    public static String dimension(Level level) {
        ResourceKey<Level> key = level.dimension();
        String s = DIMENSIONS.get(key);
        if (s == null) {
            s = key.identifier().toString();
            DIMENSIONS.put(key, s);
        }
        return s;
    }
}
