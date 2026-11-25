package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brewing sequencer planning (engine free). A recipe graph from water bottles to the requested potion
 * (water -> nether wart -> base ingredient -> glowstone/redstone/fermented eye -> gunpowder -> dragon's breath), a batch
 * split of three bottles per brew, blaze powder fuel (20 brews per powder), and sourcing of every ingredient from the
 * inventory first, then remembered storage. Potions are keyed as {@code <item id>#<potion id>}, e.g.
 * {@code minecraft:splash_potion#minecraft:strong_healing}; a water bottle is {@code minecraft:potion#minecraft:water}.
 * Partially brewed bottles already carried (e.g. awkward potions) skip the earlier stages.
 * <p>
 * Brewing stand menu slots: bottles {@link #BOTTLE_SLOTS}, {@link #INGREDIENT_SLOT}, {@link #FUEL_SLOT}.
 */
public final class BrewingPlanner {

    public static final int[] BOTTLE_SLOTS = {0, 1, 2};
    public static final int INGREDIENT_SLOT = 3;
    public static final int FUEL_SLOT = 4;
    public static final int BREW_TICKS = 400;
    public static final int BREWS_PER_POWDER = 20;
    public static final String BLAZE_POWDER = "minecraft:blaze_powder";
    public static final String GLASS_BOTTLE = "minecraft:glass_bottle";
    public static final String POTION = "minecraft:potion";
    public static final String SPLASH = "minecraft:splash_potion";
    public static final String LINGERING = "minecraft:lingering_potion";
    public static final String WATER = key(POTION, "water");

    public enum Form { DRINK, SPLASH, LINGERING }

    /** One edge of the graph: {@code from} + {@code ingredient} brews {@code to}. */
    public record Edge(String from, String ingredient, String to) {
    }

    public static String key(String item, String potion) {
        return item + "#" + (potion.contains(":") ? potion : "minecraft:" + potion);
    }

    private static final Map<String, String> BASES = new LinkedHashMap<>(); // potion -> base ingredient from awkward
    private static final Map<String, String[]> CORRUPT = new LinkedHashMap<>(); // fermented eye: from -> to
    private static final List<String> STRONG = List.of("healing", "harming", "swiftness", "strength", "regeneration",
            "leaping", "poison", "slowness", "turtle_master");
    private static final List<String> LONG = List.of("swiftness", "slowness", "strength", "regeneration",
            "fire_resistance", "night_vision", "invisibility", "water_breathing", "leaping", "poison", "slow_falling",
            "turtle_master", "weakness");

    static {
        BASES.put("healing", "minecraft:glistering_melon_slice");
        BASES.put("swiftness", "minecraft:sugar");
        BASES.put("strength", "minecraft:blaze_powder");
        BASES.put("regeneration", "minecraft:ghast_tear");
        BASES.put("fire_resistance", "minecraft:magma_cream");
        BASES.put("night_vision", "minecraft:golden_carrot");
        BASES.put("water_breathing", "minecraft:pufferfish");
        BASES.put("leaping", "minecraft:rabbit_foot");
        BASES.put("poison", "minecraft:spider_eye");
        BASES.put("slow_falling", "minecraft:phantom_membrane");
        BASES.put("turtle_master", "minecraft:turtle_helmet");
        BASES.put("wind_charged", "minecraft:breeze_rod");
        BASES.put("weaving", "minecraft:cobweb");
        BASES.put("oozing", "minecraft:slime_block");
        BASES.put("infested", "minecraft:stone");
        CORRUPT.put("harming", new String[]{"healing"});
        CORRUPT.put("slowness", new String[]{"swiftness"});
        CORRUPT.put("invisibility", new String[]{"night_vision"});
        CORRUPT.put("weakness", new String[]{"water"});
    }

    private BrewingPlanner() {
    }

    /**
     * The brew chain for a drink potion id ("healing", "strong_healing", "long_swiftness"), as edges from a water
     * bottle, followed by the form conversion. Null when the potion cannot be brewed.
     */
    public static List<Edge> chain(String potion, Form form) {
        String p = potion.startsWith("minecraft:") ? potion.substring(10) : potion;
        String mod = p.startsWith("strong_") ? "strong" : p.startsWith("long_") ? "long" : null;
        String base = mod == null ? p : p.substring(mod.length() + 1);
        if (mod != null && !(mod.equals("strong") ? STRONG : LONG).contains(base)) {
            return null;
        }
        List<Edge> out = new ArrayList<>();
        if (!baseChain(base, out)) {
            return null;
        }
        String cur = base;
        if (mod != null) {
            String to = mod + "_" + base;
            out.add(new Edge(key(POTION, cur), mod.equals("strong") ? "minecraft:glowstone_dust" : "minecraft:redstone",
                    key(POTION, to)));
            cur = to;
        }
        if (form != Form.DRINK) {
            out.add(new Edge(key(POTION, cur), "minecraft:gunpowder", key(SPLASH, cur)));
            if (form == Form.LINGERING) {
                out.add(new Edge(key(SPLASH, cur), "minecraft:dragon_breath", key(LINGERING, cur)));
            }
        }
        return out;
    }

    private static boolean baseChain(String base, List<Edge> out) {
        if (CORRUPT.containsKey(base)) {
            String from = CORRUPT.get(base)[0];
            if (!from.equals("water") && !baseChain(from, out)) {
                return false;
            }
            out.add(new Edge(key(POTION, from), "minecraft:fermented_spider_eye", key(POTION, base)));
            return true;
        }
        String ing = BASES.get(base);
        if (ing == null) {
            return false;
        }
        out.add(new Edge(WATER, "minecraft:nether_wart", key(POTION, "awkward")));
        out.add(new Edge(key(POTION, "awkward"), ing, key(POTION, base)));
        return true;
    }

    /** Where an item comes from. */
    public record Source(String itemId, int count, long storagePos) {
        public boolean inventory() {
            return storagePos == PlanStep.NO_POS;
        }
    }

    /**
     * One brew of up to three bottles: start from {@code startKey}, add {@code ingredients} in order, one
     * {@link #BREW_TICKS} wait each.
     */
    public record Batch(int bottles, String startKey, List<String> ingredients) {
        public int ticks() {
            return ingredients.size() * BREW_TICKS;
        }
    }

    /**
     * @param withdrawals items to fetch from storage (pos set) — inventory sources are listed with NO_POS
     * @param fillBottles glass bottles to fill at water (cauldron / water source) before brewing
     * @param powder      blaze powder to load as fuel
     * @param missing     item id -> count not found anywhere
     */
    public record BrewPlan(String target, List<Edge> chain, List<Batch> batches, List<Source> withdrawals,
                           int fillBottles, int powder, Map<String, Integer> missing) {
        public boolean feasible() {
            return chain != null && missing.isEmpty();
        }
    }

    /**
     * Plans {@code count} of a potion.
     *
     * @param inventory   item id (and potion keys) -> count carried
     * @param storage     remembered containers, may be null
     * @param standFuel   brews left in the stand's fuel bar (0..20)
     */
    public static BrewPlan plan(String potion, Form form, int count, Map<String, Integer> inventory,
                                IStorageIndex storage, String dimension, long from, int standFuel) {
        List<Edge> chain = chain(potion, form);
        String target = chain == null || chain.isEmpty() ? null : chain.get(chain.size() - 1).to();
        if (chain == null || count <= 0) {
            return new BrewPlan(target, chain, List.of(), List.of(), 0, 0,
                    chain == null ? Map.of(potion, count) : Map.of());
        }
        Map<String, Integer> inv = new HashMap<>(inventory);
        // Use carried bottles from the most advanced stage first.
        List<Batch> batches = new ArrayList<>();
        Map<String, Integer> needs = new LinkedHashMap<>();
        int left = count - Math.min(count, inv.getOrDefault(target, 0));
        int waterNeeded = 0;
        for (int stage = chain.size() - 1; stage >= -1 && left > 0; stage--) {
            String startKey = stage >= 0 ? chain.get(stage).from() : null;
            int have;
            if (stage == -1) {
                have = left; // fresh water bottles, sourced below
            } else {
                have = Math.min(left, inv.getOrDefault(startKey, 0));
                if (startKey.equals(WATER) && stage == 0) {
                    have = 0; // water bottles are handled by the stage -1 pass
                }
            }
            if (have <= 0) {
                continue;
            }
            int first = stage >= 0 ? stage : 0;
            String start = stage >= 0 ? startKey : chain.get(0).from();
            List<String> ings = new ArrayList<>();
            for (int i = first; i < chain.size(); i++) {
                ings.add(chain.get(i).ingredient());
            }
            if (stage >= 0) {
                inv.merge(startKey, -have, Integer::sum);
            } else {
                waterNeeded = have;
            }
            for (int b = 0; b < have; b += 3) {
                int n = Math.min(3, have - b);
                batches.add(new Batch(n, start, List.copyOf(ings)));
                for (String ing : ings) {
                    needs.merge(ing, 1, Integer::sum);
                }
            }
            left -= have;
        }
        int brews = 0;
        for (Batch b : batches) {
            brews += b.ingredients().size();
        }
        int powder = Math.max(0, (int) Math.ceil((brews - standFuel) / (double) BREWS_PER_POWDER));
        needs.merge(BLAZE_POWDER, powder, Integer::sum);
        needs.values().removeIf(v -> v <= 0);

        List<Source> sources = new ArrayList<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        // water bottles: carried ones, then glass bottles to fill, then storage of either
        int fill = 0;
        if (waterNeeded > 0) {
            int w = Math.min(waterNeeded, inv.getOrDefault(WATER, 0));
            int rest = waterNeeded - w;
            int glass = Math.min(rest, inv.getOrDefault(GLASS_BOTTLE, 0));
            inv.merge(GLASS_BOTTLE, -glass, Integer::sum);
            rest -= glass;
            fill = glass;
            if (rest > 0) {
                int got = source(GLASS_BOTTLE, rest, storage, dimension, from, sources);
                fill += got;
                rest -= got;
            }
            if (rest > 0) {
                missing.put(GLASS_BOTTLE, rest);
            }
        }
        for (Map.Entry<String, Integer> e : needs.entrySet()) {
            String item = e.getKey();
            int want = e.getValue();
            int carried = Math.min(want, Math.max(0, inv.getOrDefault(item, 0)));
            if (carried > 0) {
                sources.add(new Source(item, carried, PlanStep.NO_POS));
                inv.merge(item, -carried, Integer::sum);
            }
            int rest = want - carried;
            if (rest > 0) {
                rest -= source(item, rest, storage, dimension, from, sources);
            }
            if (rest > 0) {
                missing.put(item, rest);
            }
        }
        return new BrewPlan(target, chain, List.copyOf(batches), List.copyOf(sources), fill, powder, missing);
    }

    private static int source(String item, int want, IStorageIndex storage, String dim, long from, List<Source> out) {
        if (storage == null) {
            return 0;
        }
        int got = 0;
        for (StorageEntry e : storage.containersWith(item, dim, from)) {
            int c = Math.min(want - got, e.contents().getOrDefault(item, 0));
            if (c > 0) {
                out.add(new Source(item, c, e.packedPos()));
                got += c;
            }
            if (got >= want) {
                break;
            }
        }
        return got;
    }
}
