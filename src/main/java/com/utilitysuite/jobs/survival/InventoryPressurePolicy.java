package com.utilitysuite.jobs.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory pressure rules: when free slots run low, drop junk-list stacks (DROP_JUNK_LIST) and suggest compacting
 * 9-to-1 items into blocks (COMPACT_BLOCKS). KEEP_VALUABLES: valuables, tools, armor and pinned items are never dropped.
 */
public final class InventoryPressurePolicy {

    public enum Rule { KEEP_VALUABLES, COMPACT_BLOCKS, DROP_JUNK_LIST }

    /** One inventory stack. {@code slot} is the inventory-menu slot index. */
    public record Stack(int slot, String itemId, int count, boolean toolOrArmor, boolean enchanted) {
    }

    public sealed interface Action {
        record Drop(int slot, String itemId, int count) implements Action {
        }

        record Compact(String itemId, String blockId, int blocks) implements Action {
        }
    }

    public static final Set<String> DEFAULT_JUNK = Set.of("minecraft:rotten_flesh", "minecraft:poisonous_potato",
            "minecraft:dirt", "minecraft:gravel", "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
            "minecraft:tuff", "minecraft:netherrack", "minecraft:spider_eye");

    /** Never dropped regardless of the junk list. */
    public static final Set<String> VALUABLES = Set.of("minecraft:diamond", "minecraft:emerald",
            "minecraft:netherite_ingot", "minecraft:netherite_scrap", "minecraft:ancient_debris", "minecraft:raw_iron",
            "minecraft:raw_gold", "minecraft:raw_copper", "minecraft:iron_ingot", "minecraft:gold_ingot",
            "minecraft:copper_ingot", "minecraft:lapis_lazuli", "minecraft:redstone", "minecraft:totem_of_undying",
            "minecraft:elytra", "minecraft:enchanted_golden_apple", "minecraft:golden_apple", "minecraft:ender_pearl");

    /** 9 items -> 1 block. */
    public static final Map<String, String> COMPACTABLE = Map.of("minecraft:iron_ingot", "minecraft:iron_block",
            "minecraft:gold_ingot", "minecraft:gold_block", "minecraft:diamond", "minecraft:diamond_block",
            "minecraft:emerald", "minecraft:emerald_block", "minecraft:redstone", "minecraft:redstone_block",
            "minecraft:lapis_lazuli", "minecraft:lapis_block", "minecraft:coal", "minecraft:coal_block",
            "minecraft:raw_iron", "minecraft:raw_iron_block", "minecraft:raw_gold", "minecraft:raw_gold_block",
            "minecraft:raw_copper", "minecraft:raw_copper_block");

    private InventoryPressurePolicy() {
    }

    public static boolean isProtected(Stack s, Set<String> pinned) {
        return s.toolOrArmor() || s.enchanted() || pinned.contains(s.itemId()) || VALUABLES.contains(s.itemId())
                || s.itemId().endsWith("shulker_box");
    }

    /**
     * Actions to relieve pressure, or none when at least {@code minFreeSlots} are free.
     *
     * @param rules enabled rules
     */
    public static List<Action> decide(List<Stack> stacks, int freeSlots, int minFreeSlots, Set<Rule> rules,
                                      Set<String> junk, Set<String> pinned) {
        List<Action> out = new ArrayList<>();
        if (freeSlots >= minFreeSlots) {
            return out;
        }
        int needed = minFreeSlots - freeSlots;
        boolean keepValuables = rules.contains(Rule.KEEP_VALUABLES);
        if (rules.contains(Rule.DROP_JUNK_LIST)) {
            for (Stack s : stacks) {
                if (needed <= 0) {
                    break;
                }
                if (!junk.contains(s.itemId()) || pinned.contains(s.itemId()) || s.toolOrArmor() || s.enchanted()) {
                    continue;
                }
                if (keepValuables && VALUABLES.contains(s.itemId())) {
                    continue;
                }
                out.add(new Action.Drop(s.slot(), s.itemId(), s.count()));
                needed--;
            }
        }
        if (rules.contains(Rule.COMPACT_BLOCKS)) {
            java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
            for (Stack s : stacks) {
                if (COMPACTABLE.containsKey(s.itemId()) && !pinned.contains(s.itemId())) {
                    totals.merge(s.itemId(), s.count(), Integer::sum);
                }
            }
            totals.forEach((item, n) -> {
                if (n >= 9) {
                    out.add(new Action.Compact(item, COMPACTABLE.get(item), n / 9));
                }
            });
        }
        return out;
    }
}
