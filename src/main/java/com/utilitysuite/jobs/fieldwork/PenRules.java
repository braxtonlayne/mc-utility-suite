package com.utilitysuite.jobs.fieldwork;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine-free rules for the animal-pen sweep: which food breeds which animal (vanilla 1.21.1 table, untamed farm
 * animals only), and which animal in the pen to walk to next (shear an unsheared sheep, milk an adult cow/mooshroom/goat
 * with an empty bucket, feed a breed-ready adult when a second breed-ready adult of the same kind is in the pen and the
 * kind is under its population cap). Callers pass parallel arrays they reuse, so a pick allocates nothing.
 */
public final class PenRules {

    public enum Action { NONE, BREED, SHEAR, MILK }

    private static final String[] NONE_FOOD = new String[0];
    private static final Map<String, String[]> FOODS = new HashMap<>();

    static {
        String[] wheat = {"minecraft:wheat"};
        FOODS.put("minecraft:cow", wheat);
        FOODS.put("minecraft:mooshroom", wheat);
        FOODS.put("minecraft:sheep", wheat);
        FOODS.put("minecraft:goat", wheat);
        FOODS.put("minecraft:pig", new String[]{"minecraft:carrot", "minecraft:potato", "minecraft:beetroot"});
        FOODS.put("minecraft:chicken", new String[]{"minecraft:wheat_seeds", "minecraft:melon_seeds",
                "minecraft:pumpkin_seeds", "minecraft:beetroot_seeds", "minecraft:torchflower_seeds",
                "minecraft:pitcher_pod"});
        FOODS.put("minecraft:rabbit", new String[]{"minecraft:dandelion", "minecraft:carrot",
                "minecraft:golden_carrot"});
        FOODS.put("minecraft:llama", new String[]{"minecraft:hay_block"});
        FOODS.put("minecraft:turtle", new String[]{"minecraft:seagrass"});
        FOODS.put("minecraft:fox", new String[]{"minecraft:sweet_berries", "minecraft:glow_berries"});
        FOODS.put("minecraft:hoglin", new String[]{"minecraft:crimson_fungus"});
        FOODS.put("minecraft:strider", new String[]{"minecraft:warped_fungus"});
        FOODS.put("minecraft:frog", new String[]{"minecraft:slime_ball"});
        FOODS.put("minecraft:panda", new String[]{"minecraft:bamboo"});
        FOODS.put("minecraft:camel", new String[]{"minecraft:cactus"});
        FOODS.put("minecraft:sniffer", new String[]{"minecraft:torchflower_seeds"});
        FOODS.put("minecraft:armadillo", new String[]{"minecraft:spider_eye"});
        FOODS.put("minecraft:axolotl", new String[]{"minecraft:tropical_fish_bucket"});
    }

    private PenRules() {
    }

    /** Breeding foods for an entity type id, in preference order (cheapest first); empty when unknown. */
    public static String[] breedingFoods(String entityType) {
        return FOODS.getOrDefault(entityType, NONE_FOOD);
    }

    public static boolean breeds(String entityType, String itemId) {
        for (String f : breedingFoods(entityType)) {
            if (f.equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean milkable(String entityType) {
        return entityType.equals("minecraft:cow") || entityType.equals("minecraft:mooshroom")
                || entityType.equals("minecraft:goat");
    }

    /** Settings and the player's means, for one pick. */
    public static final class Means {
        public boolean breed = true;
        public boolean shear = true;
        public boolean milk = true;
        /** Stop breeding a kind once the pen holds this many of it (pro default: keep entity counts sane). */
        public int populationCap = 24;
        public boolean haveShears;
        public boolean haveEmptyBucket;
        /** Returns true when the player carries a breeding food for this type. */
        public FoodCheck food = t -> false;
    }

    @FunctionalInterface
    public interface FoodCheck {
        boolean carries(String entityType);
    }

    /** What to do with animal {@code i}. */
    public static Action actionFor(int i, String[] types, boolean[] adult, boolean[] breedReady, boolean[] sheared,
                                   int n, Means m) {
        String type = types[i];
        if (!adult[i]) {
            return Action.NONE;
        }
        if (m.shear && m.haveShears && type.equals("minecraft:sheep") && !sheared[i]) {
            return Action.SHEAR;
        }
        if (m.milk && m.haveEmptyBucket && milkable(type)) {
            return Action.MILK;
        }
        if (m.breed && breedReady[i] && m.food.carries(type)) {
            int count = 0;
            int partners = 0;
            for (int j = 0; j < n; j++) {
                if (types[j].equals(type)) {
                    count++;
                    if (j != i && adult[j] && breedReady[j]) {
                        partners++;
                    }
                }
            }
            if (partners > 0 && count < m.populationCap) {
                return Action.BREED;
            }
        }
        return Action.NONE;
    }

    /** Nearest actionable animal inside the pen, or -1. */
    public static int pick(String[] types, boolean[] adult, boolean[] breedReady, boolean[] sheared,
                           boolean[] inPen, double[] distSq, int n, Means m) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (!inPen[i] || distSq[i] >= bestD) {
                continue;
            }
            if (actionFor(i, types, adult, breedReady, sheared, n, m) != Action.NONE) {
                best = i;
                bestD = distSq[i];
            }
        }
        return best;
    }

    /** Pen bounds test (inclusive box in block coordinates, any Y within the box). */
    public static boolean inPen(double x, double y, double z, int minX, int minY, int minZ, int maxX, int maxY,
                                int maxZ) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }
}
