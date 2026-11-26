package com.utilitysuite.jobs.goal;

import com.utilitysuite.jobs.planner.ToolTiers;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Tool classification for 26.3, where {@code PickaxeItem}, {@code SwordItem}, {@code TieredItem} and {@code Tier} are
 * gone: tool kinds come from item tags ({@link ItemTags#PICKAXES} ...), the harvest tier from the vanilla id or the
 * {@link DataComponents#TOOL} "denies drops" tag, and the material from {@link DataComponents#REPAIRABLE}. No
 * allocation (tag and component reads only).
 */
public final class McTools {

    private McTools() {
    }

    public static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && s.is(ItemTags.PICKAXES);
    }

    public static boolean isShovel(ItemStack s) {
        return !s.isEmpty() && s.is(ItemTags.SHOVELS);
    }

    public static boolean isAxe(ItemStack s) {
        return !s.isEmpty() && s.is(ItemTags.AXES);
    }

    public static boolean isSword(ItemStack s) {
        return !s.isEmpty() && s.is(ItemTags.SWORDS);
    }

    /**
     * Harvest tier of a pickaxe on the {@link ToolTiers} scale (1 wood/gold, 2 stone/copper, 3 iron, 4 diamond,
     * 5 netherite): vanilla by id, modded by the tool component's "incorrect for" tag, else 2.
     */
    public static int pickaxeTier(ItemStack s) {
        return pickaxeTier(s, BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    }

    /** {@link #pickaxeTier(ItemStack)} with the item id already known (callers that cache ids: no allocation). */
    public static int pickaxeTier(ItemStack s, String itemId) {
        int byId = ToolTiers.tierOf(itemId);
        if (byId > 0) {
            return byId;
        }
        Tool tool = s.get(DataComponents.TOOL);
        if (tool != null) {
            List<Tool.Rule> rules = tool.rules();
            for (int i = 0, n = rules.size(); i < n; i++) {
                Tool.Rule r = rules.get(i);
                if (r.correctForDrops().isPresent() && !r.correctForDrops().get()) {
                    int t = tierOfIncorrectTag(r.blocks());
                    if (t > 0) {
                        return t;
                    }
                }
            }
        }
        return 2;
    }

    private static int tierOfIncorrectTag(HolderSet<Block> blocks) {
        TagKey<Block> tag = blocks.unwrapKey().orElse(null);
        if (tag == null) {
            return 0;
        }
        if (tag.equals(BlockTags.INCORRECT_FOR_WOODEN_TOOL) || tag.equals(BlockTags.INCORRECT_FOR_GOLD_TOOL)) {
            return 1;
        }
        if (tag.equals(BlockTags.INCORRECT_FOR_STONE_TOOL) || tag.equals(BlockTags.INCORRECT_FOR_COPPER_TOOL)) {
            return 2;
        }
        if (tag.equals(BlockTags.INCORRECT_FOR_IRON_TOOL)) {
            return 3;
        }
        if (tag.equals(BlockTags.INCORRECT_FOR_DIAMOND_TOOL)) {
            return 4;
        }
        if (tag.equals(BlockTags.INCORRECT_FOR_NETHERITE_TOOL)) {
            return 5;
        }
        return 0;
    }

    /**
     * The tool material's attack bonus (the removed {@code Tier.getAttackDamageBonus()}: wood/gold 0, stone/copper 1,
     * iron 2, diamond 3, netherite 4), read from the repair material tag; 0 when unknown.
     */
    public static float materialAttackBonus(ItemStack s) {
        Repairable rep = s.get(DataComponents.REPAIRABLE);
        if (rep == null) {
            return 0f;
        }
        TagKey<Item> tag = rep.items().unwrapKey().orElse(null);
        if (tag == null) {
            return 0f;
        }
        if (tag.equals(ItemTags.STONE_TOOL_MATERIALS) || tag.equals(ItemTags.COPPER_TOOL_MATERIALS)) {
            return 1f;
        }
        if (tag.equals(ItemTags.IRON_TOOL_MATERIALS)) {
            return 2f;
        }
        if (tag.equals(ItemTags.DIAMOND_TOOL_MATERIALS)) {
            return 3f;
        }
        if (tag.equals(ItemTags.NETHERITE_TOOL_MATERIALS)) {
            return 4f;
        }
        return 0f;
    }
}
