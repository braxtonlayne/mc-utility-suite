package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.NestedContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ender chest mirror planning (engine free). The ender chest is one portable inventory per player, readable from any
 * placed ender chest. Given the last recorded contents (top level plus nested shulker boxes), plans how to get an item
 * that is only there:
 * <ul>
 *   <li>Use a known placed ender chest within {@code maxDistance}; else place a carried one, but only with a Silk Touch
 *       pickaxe to pick it back up (mining it without Silk Touch destroys it and drops obsidian).</li>
 *   <li>Take top-level stacks first; for the rest take shulker boxes holding the item (most first), unpack each with the
 *       existing SHULKER_RESTOCK step and put the box back.</li>
 * </ul>
 */
public final class EnderChestPlanner {

    public static final String ENDER_CHEST = "minecraft:ender_chest";

    private EnderChestPlanner() {
    }

    public enum Access {
        /** Nothing to do: the item is not in the mirror, or no ender chest can be used. */
        NONE,
        /** Walk to a known placed ender chest. */
        USE_KNOWN,
        /** Place the carried ender chest, use it, mine it back with Silk Touch. */
        PLACE_CARRIED
    }

    /**
     * @param access      how to reach an ender chest
     * @param chestPos    known chest position for USE_KNOWN, else {@link PlanStep#NO_POS}
     * @param takeDirect  top-level count to take
     * @param boxes       shulker boxes to take out, unpack (SHULKER_RESTOCK) and return to the ender chest
     * @param pickUpAfter true when the placed chest must be mined back (Silk Touch)
     * @param shortfall   count the mirror cannot provide
     * @param reason      short explanation when access is NONE
     */
    public record Plan(Access access, long chestPos, int takeDirect, List<ShulkerContents.Take> boxes,
                       boolean pickUpAfter, int shortfall, String reason) {
        public int planned() {
            int s = takeDirect;
            for (ShulkerContents.Take t : boxes) {
                s += t.count();
            }
            return s;
        }
    }

    /**
     * @param contents      recorded top-level contents
     * @param nested        recorded shulker boxes inside the ender chest
     * @param knownChests   placed ender chests in the player's dimension (packed positions)
     * @param carriesChest  an ender chest item is in the inventory
     * @param hasSilkTouch  a Silk Touch pickaxe is carried
     */
    public static Plan plan(String itemId, int need, Map<String, Integer> contents, List<NestedContainer> nested,
                            List<Long> knownChests, long from, int maxDistance, boolean carriesChest,
                            boolean hasSilkTouch) {
        int direct = Math.min(need, contents.getOrDefault(itemId, 0));
        List<ShulkerContents.Take> boxes = ShulkerContents.plan(nested, itemId, need - direct, Map.of());
        int got = direct;
        for (ShulkerContents.Take t : boxes) {
            got += t.count();
        }
        if (got == 0) {
            return new Plan(Access.NONE, PlanStep.NO_POS, 0, List.of(), false, need, "not in the ender chest");
        }
        long best = PlanStep.NO_POS;
        long bestD = (long) maxDistance * maxDistance;
        for (long p : knownChests) {
            long d = PackedPos.distSq(p, from);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best != PlanStep.NO_POS) {
            return new Plan(Access.USE_KNOWN, best, direct, List.copyOf(boxes), false, need - got, null);
        }
        if (carriesChest && hasSilkTouch) {
            return new Plan(Access.PLACE_CARRIED, PlanStep.NO_POS, direct, List.copyOf(boxes), true, need - got, null);
        }
        return new Plan(Access.NONE, PlanStep.NO_POS, 0, List.of(), false, need,
                carriesChest ? "no Silk Touch pickaxe to pick a placed ender chest back up"
                        : "no ender chest nearby or carried");
    }

    /** Count of an item in the mirror, top level plus nested boxes. */
    public static int count(String itemId, Map<String, Integer> contents, List<NestedContainer> nested) {
        return contents.getOrDefault(itemId, 0) + ShulkerContents.count(nested, itemId);
    }

    /** Items taken out but not needed go back: what to deposit after a take (taken minus used, per item). */
    public static List<Map.Entry<String, Integer>> leftovers(Map<String, Integer> taken, Map<String, Integer> used) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : taken.entrySet()) {
            int rest = e.getValue() - used.getOrDefault(e.getKey(), 0);
            if (rest > 0) {
                out.add(Map.entry(e.getKey(), rest));
            }
        }
        return out;
    }
}
