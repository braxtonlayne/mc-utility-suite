package com.utilitysuite.jobs.schematic.mc;

import com.utilitysuite.jobs.schematic.bom.ItemRequirement;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft adapter for the pure layer: resolves a volume's palette strings to {@link BlockState}s and item ids to
 * {@link Item}s once, so the per-tick code compares by identity. Unknown blocks (a mod that is not installed) resolve
 * to {@code null} and are treated as "anything but air matches nothing".
 */
public final class McStateResolver {

    private McStateResolver() {
    }

    /** Palette index to state ({@code null} for void and unknown entries). Client thread (registry access). */
    public static BlockState[] resolvePalette(SchematicVolume v) {
        BlockState[] out = new BlockState[v.paletteSize()];
        for (int i = 0; i < out.length; i++) {
            if (!SchematicVolume.isVoid(i)) {
                out[i] = resolve(v.paletteEntry(i));
            }
        }
        return out;
    }

    public static BlockState resolve(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (Exception e) {
            // Unknown property value or a newer property: fall back to the block's default state.
            ResourceLocation id = ResourceLocation.tryParse(BlockStateString.blockId(state));
            if (id == null) {
                return null;
            }
            return BuiltInRegistries.BLOCK.getOptional(id).map(Block::defaultBlockState).orElse(null);
        }
    }

    /** Item for an id, or {@code null} when unknown. */
    public static Item item(String itemId) {
        if (itemId == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == Items.AIR ? null : item;
    }

    /** Item placed for each palette entry (the first item of its requirement), {@code null} when none. */
    public static Item[] placementItems(SchematicVolume v, ItemRequirement[] reqs) {
        Item[] out = new Item[v.paletteSize()];
        for (int i = 0; i < out.length; i++) {
            ItemRequirement r = reqs[i];
            if (r != null && r.isItem() && r.item2() == null) {
                out[i] = item(r.item());
            }
        }
        return out;
    }

    /** Registry id of an item ({@code minecraft:stone}). */
    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
