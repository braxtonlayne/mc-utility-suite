package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table-driven harvest knowledge: which blocks drop an item, how many, whether Fortune applies, and the pickaxe tier a
 * block needs (diamond ore needs iron = 3, obsidian needs diamond = 4). Injectable so tests and modpacks can override
 * it; {@link #vanilla()} covers the vanilla blocks the planner mines.
 */
public final class HarvestTable {

    /** How Fortune changes the drop count. */
    public enum FortuneRule {
        /** No effect (logs, stone, obsidian). */
        NONE,
        /** Vanilla {@code ore_drops}: multiplier 1..L+1, mean (2 + L(L+3)/2) / (L+2). */
        ORE_DROPS,
        /** Vanilla {@code uniform_bonus_count}: adds 0..L, mean L/2 (redstone, glowstone). */
        UNIFORM_BONUS
    }

    /**
     * One way to obtain an item by breaking blocks.
     *
     * @param dropsPerBlock mean drop count without Fortune
     * @param byHand        gathered without a pickaxe (trees, dirt); the executor may still use any tool
     */
    public record Source(String blockId, String dropItem, double dropsPerBlock, FortuneRule fortune, int requiredTier,
                         boolean byHand) {
    }

    private final Map<String, List<Source>> byItem;
    private final Map<String, Integer> tierByBlock;

    private HarvestTable(Map<String, List<Source>> byItem, Map<String, Integer> tierByBlock) {
        this.byItem = byItem;
        this.tierByBlock = tierByBlock;
    }

    /** Sources dropping the item, preferred first. Empty when the item cannot be mined. */
    public List<Source> sourcesFor(String itemId) {
        return byItem.getOrDefault(itemId, List.of());
    }

    /** Required pickaxe tier to get drops from the block (0 = hand). */
    public int requiredTier(String blockId) {
        return tierByBlock.getOrDefault(blockId, 0);
    }

    /** Mean items per block broken with the given Fortune level (0 = none). Silk Touch is handled by the caller. */
    public static double expectedDrops(Source s, int fortuneLevel) {
        double base = s.dropsPerBlock();
        if (fortuneLevel <= 0) {
            return base;
        }
        int l = fortuneLevel;
        return switch (s.fortune()) {
            case NONE -> base;
            case ORE_DROPS -> base * (2.0 + l * (l + 3) / 2.0) / (l + 2.0);
            case UNIFORM_BONUS -> base + l / 2.0;
        };
    }

    /** Blocks that must still be broken to obtain {@code remainingItems} more, rounded up. */
    public static int blocksNeeded(Source s, int fortuneLevel, int remainingItems) {
        if (remainingItems <= 0) {
            return 0;
        }
        double per = expectedDrops(s, fortuneLevel);
        return (int) Math.ceil(remainingItems / per - 1e-9);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, List<Source>> byItem = new LinkedHashMap<>();
        private final Map<String, Integer> tiers = new HashMap<>();

        public Builder source(String block, String drop, double perBlock, FortuneRule fortune, int tier, boolean hand) {
            String b = Items.qualify(block);
            String d = Items.qualify(drop);
            byItem.computeIfAbsent(d, k -> new ArrayList<>()).add(new Source(b, d, perBlock, fortune, tier, hand));
            tiers.merge(b, tier, Math::max);
            return this;
        }

        /** A block that drops itself. */
        public Builder self(String block, int tier, boolean hand) {
            return source(block, block, 1, FortuneRule.NONE, tier, hand);
        }

        public Builder tier(String block, int tier) {
            tiers.put(Items.qualify(block), tier);
            return this;
        }

        public HarvestTable build() {
            Map<String, List<Source>> frozen = new LinkedHashMap<>();
            byItem.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
            return new HarvestTable(frozen, Map.copyOf(tiers));
        }
    }

    private static final HarvestTable VANILLA = createVanilla();

    public static HarvestTable vanilla() {
        return VANILLA;
    }

    private static HarvestTable createVanilla() {
        Builder b = builder();
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"}) {
            b.self(wood + "_log", 0, true);
        }
        b.self("crimson_stem", 0, true).self("warped_stem", 0, true);
        b.source("stone", "cobblestone", 1, FortuneRule.NONE, 1, false);
        b.self("cobblestone", 1, false);
        b.source("deepslate", "cobbled_deepslate", 1, FortuneRule.NONE, 1, false);
        b.self("blackstone", 1, false);
        b.self("dirt", 0, true).self("sand", 0, true).self("gravel", 0, true).self("clay", 0, true);
        b.self("netherrack", 1, false).self("andesite", 1, false).self("diorite", 1, false).self("granite", 1, false);
        b.source("coal_ore", "coal", 1, FortuneRule.ORE_DROPS, 1, false);
        b.source("deepslate_coal_ore", "coal", 1, FortuneRule.ORE_DROPS, 1, false);
        b.source("iron_ore", "raw_iron", 1, FortuneRule.ORE_DROPS, 2, false);
        b.source("deepslate_iron_ore", "raw_iron", 1, FortuneRule.ORE_DROPS, 2, false);
        b.source("copper_ore", "raw_copper", 3.5, FortuneRule.ORE_DROPS, 2, false);
        b.source("deepslate_copper_ore", "raw_copper", 3.5, FortuneRule.ORE_DROPS, 2, false);
        b.source("lapis_ore", "lapis_lazuli", 6.5, FortuneRule.ORE_DROPS, 2, false);
        b.source("deepslate_lapis_ore", "lapis_lazuli", 6.5, FortuneRule.ORE_DROPS, 2, false);
        b.source("gold_ore", "raw_gold", 1, FortuneRule.ORE_DROPS, 3, false);
        b.source("deepslate_gold_ore", "raw_gold", 1, FortuneRule.ORE_DROPS, 3, false);
        b.source("nether_gold_ore", "gold_nugget", 4, FortuneRule.ORE_DROPS, 1, false);
        b.source("redstone_ore", "redstone", 4.5, FortuneRule.UNIFORM_BONUS, 3, false);
        b.source("deepslate_redstone_ore", "redstone", 4.5, FortuneRule.UNIFORM_BONUS, 3, false);
        b.source("diamond_ore", "diamond", 1, FortuneRule.ORE_DROPS, 3, false);
        b.source("deepslate_diamond_ore", "diamond", 1, FortuneRule.ORE_DROPS, 3, false);
        b.source("emerald_ore", "emerald", 1, FortuneRule.ORE_DROPS, 3, false);
        b.source("deepslate_emerald_ore", "emerald", 1, FortuneRule.ORE_DROPS, 3, false);
        b.source("nether_quartz_ore", "quartz", 1, FortuneRule.ORE_DROPS, 1, false);
        b.self("obsidian", 4, false).self("crying_obsidian", 4, false);
        b.self("ancient_debris", 4, false);
        return b.build();
    }
}
