package com.utilitysuite.tools.enchant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Engine-free anvil combine-order planner (roadmap W12). Finds the merge tree that applies a set of enchanted books
 * (and same-type enchanted items) to a target for the fewest total levels, following vanilla 1.21.1
 * {@code AnvilMenu.createResult}:
 * <ul>
 *   <li>cost = left prior-work penalty + right prior-work penalty + enchantment cost;</li>
 *   <li>for every enchantment on the right: new level = equal ? level + 1 : max(left, right), capped at the max level;
 *       it adds {@code anvilCost * newLevel} (a book on the right halves anvilCost, min 1); every enchantment already on
 *       the left that is incompatible with it adds 1 and the enchantment is not applied; if nothing on the right can
 *       apply the merge fails;</li>
 *   <li>the result's penalty is {@code 2 * max(leftPenalty, rightPenalty) + 1} (so 2^n - 1 after n works);</li>
 *   <li>a step costing 40 or more is "Too Expensive!" outside creative.</li>
 * </ul>
 * The search is an exact DP over (subset, penalty, enchantments) states; anvil cost only depends on those, so keeping
 * the cheapest tree per state is optimal. Runs once per anvil-screen open on a background thread or the client thread
 * (8 books: a few thousand merges); never in a tick loop.
 */
public final class AnvilPlanner {

    /** Vanilla refuses a step at this cost or above in survival. */
    public static final int TOO_EXPENSIVE = 40;
    /** Inputs beyond this are ignored (the DP is exponential). */
    public static final int MAX_SACRIFICES = 8;

    private AnvilPlanner() {
    }

    /** Anvil cost multiplier (item on the right; books halve it) and max level, from the 1.21.1 data pack. */
    public record Info(int anvilCost, int maxLevel) {
    }

    public static final Map<String, Info> INFO = Map.ofEntries(
            e("protection", 1, 4), e("fire_protection", 2, 4), e("feather_falling", 2, 4),
            e("blast_protection", 4, 4), e("projectile_protection", 2, 4), e("respiration", 4, 3),
            e("aqua_affinity", 4, 1), e("thorns", 8, 3), e("depth_strider", 4, 3), e("frost_walker", 4, 2),
            e("binding_curse", 8, 1), e("soul_speed", 8, 3), e("swift_sneak", 8, 3), e("sharpness", 1, 5),
            e("smite", 2, 5), e("bane_of_arthropods", 2, 5), e("knockback", 2, 2), e("fire_aspect", 4, 2),
            e("looting", 4, 3), e("sweeping_edge", 4, 3), e("efficiency", 1, 5), e("silk_touch", 8, 1),
            e("unbreaking", 2, 3), e("fortune", 4, 3), e("power", 1, 5), e("punch", 4, 2), e("flame", 4, 1),
            e("infinity", 8, 1), e("luck_of_the_sea", 4, 3), e("lure", 4, 3), e("loyalty", 1, 3),
            e("impaling", 4, 5), e("riptide", 4, 3), e("channeling", 8, 1), e("multishot", 4, 1),
            e("quick_charge", 2, 3), e("piercing", 1, 4), e("mending", 4, 1), e("vanishing_curse", 8, 1),
            e("density", 2, 5), e("breach", 4, 4), e("wind_burst", 4, 3));

    /** Vanilla exclusive sets ({@code #minecraft:exclusive_set/*}) plus the pairwise exclusions. */
    private static final List<Set<String>> EXCLUSIVE = List.of(
            Set.of("minecraft:protection", "minecraft:fire_protection", "minecraft:blast_protection",
                    "minecraft:projectile_protection"),
            Set.of("minecraft:sharpness", "minecraft:smite", "minecraft:bane_of_arthropods", "minecraft:impaling",
                    "minecraft:density", "minecraft:breach"),
            Set.of("minecraft:silk_touch", "minecraft:fortune"),
            Set.of("minecraft:infinity", "minecraft:mending"),
            Set.of("minecraft:multishot", "minecraft:piercing"),
            Set.of("minecraft:depth_strider", "minecraft:frost_walker"),
            Set.of("minecraft:riptide", "minecraft:loyalty"),
            Set.of("minecraft:riptide", "minecraft:channeling"));

    private static Map.Entry<String, Info> e(String path, int cost, int max) {
        return Map.entry("minecraft:" + path, new Info(cost, max));
    }

    public static boolean compatible(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        for (Set<String> s : EXCLUSIVE) {
            if (s.contains(a) && s.contains(b)) {
                return false;
            }
        }
        return true;
    }

    static Info info(String id) {
        Info i = INFO.get(id);
        return i != null ? i : new Info(1, 5); // unknown (modded): cheapest guess
    }

    /**
     * One anvil input. {@code book} = enchanted book; {@code repairCost} = the stack's prior-work penalty
     * ({@code minecraft:repair_cost}); {@code enchants} id -> level.
     */
    public record Piece(String label, boolean book, int repairCost, Map<String, Integer> enchants) {
        public Piece {
            enchants = Collections.unmodifiableMap(new TreeMap<>(enchants));
        }
    }

    /** One anvil operation; {@code left}/{@code right} are node ids (0..n-1 inputs, n.. earlier step results). */
    public record Step(int left, int right, int result, int cost, Piece output) {
    }

    /**
     * The plan. {@code used} = indices of sacrifices it applies (all of them when {@code complete}); {@code skipped} =
     * sacrifices with nothing applicable to the target, or left out because no order fits under 40 levels.
     */
    public record Plan(List<Step> steps, int totalLevels, int maxStep, boolean complete, List<Integer> used,
                       List<Integer> skipped, Piece result) {
        public boolean empty() {
            return steps.isEmpty();
        }
    }

    /** Result of one vanilla merge, or null when the anvil shows nothing. */
    public record Merge(int cost, Piece result) {
    }

    /**
     * One vanilla merge of {@code right} onto {@code left}.
     *
     * @param applicable enchantments the target item accepts, or null for a book target (accepts all)
     */
    public static Merge merge(Piece left, Piece right, Set<String> applicable) {
        if (left.book() && !right.book()) {
            return null; // an item cannot go onto a book
        }
        int cost = 0;
        boolean anyApplied = false, anyRejected = false;
        Map<String, Integer> out = new TreeMap<>(left.enchants());
        for (Map.Entry<String, Integer> en : right.enchants().entrySet()) {
            String id = en.getKey();
            int rl = en.getValue();
            int ll = left.enchants().getOrDefault(id, 0);
            int nl = ll == rl ? rl + 1 : Math.max(rl, ll);
            boolean ok = left.book() || applicable == null || applicable.contains(id);
            for (String other : left.enchants().keySet()) {
                if (!other.equals(id) && !compatible(id, other)) {
                    ok = false;
                    cost++;
                }
            }
            if (!ok) {
                anyRejected = true;
                continue;
            }
            anyApplied = true;
            Info inf = info(id);
            nl = Math.min(nl, inf.maxLevel());
            out.put(id, nl);
            int mult = right.book() ? Math.max(1, inf.anvilCost() / 2) : inf.anvilCost();
            cost += mult * nl;
        }
        if (anyRejected && !anyApplied) {
            return null;
        }
        if (!anyApplied) {
            return null; // right had no enchantments: nothing to do
        }
        cost += left.repairCost() + right.repairCost();
        int rc = Math.max(left.repairCost(), right.repairCost()) * 2 + 1;
        return new Merge(cost, new Piece(left.book() && right.book() ? "book" : left.label(), left.book(), rc, out));
    }

    private static final class Node {
        final int mask;
        final Piece piece;
        final int total;
        final int maxStep;
        final Node l, r;
        final int leaf;
        final int stepCost;

        Node(int mask, Piece piece, int total, int maxStep, Node l, Node r, int leaf, int stepCost) {
            this.mask = mask;
            this.piece = piece;
            this.total = total;
            this.maxStep = maxStep;
            this.l = l;
            this.r = r;
            this.leaf = leaf;
            this.stepCost = stepCost;
        }

        boolean better(Node o) {
            return o == null || total < o.total || (total == o.total && maxStep < o.maxStep);
        }
    }

    /**
     * Plans applying {@code sacrifices} to {@code target}.
     *
     * @param applicable enchantments the target accepts (null when the target is a book)
     * @param creative   true lifts the 40-level cap
     */
    public static Plan plan(Piece target, List<Piece> sacrifices, Set<String> applicable, boolean creative) {
        int cap = creative ? Integer.MAX_VALUE : TOO_EXPENSIVE;
        List<Integer> skipped = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < sacrifices.size(); i++) {
            Piece p = sacrifices.get(i);
            boolean useful = false;
            for (String id : p.enchants().keySet()) {
                if (applicable == null || applicable.contains(id)) {
                    boolean clash = false;
                    for (String t : target.enchants().keySet()) {
                        if (!compatible(id, t)) {
                            clash = true;
                            break;
                        }
                    }
                    if (!clash) {
                        useful = true;
                        break;
                    }
                }
            }
            if (useful && idx.size() < MAX_SACRIFICES) {
                idx.add(i);
            } else {
                skipped.add(i);
            }
        }
        int n = idx.size();
        int tBit = 1 << n;
        int full = (1 << (n + 1)) - 1;
        // states per mask: key = penalty + enchant signature
        List<Map<String, Node>> table = new ArrayList<>(full + 1);
        for (int m = 0; m <= full; m++) {
            table.add(new HashMap<>());
        }
        for (int i = 0; i < n; i++) {
            Piece p = sacrifices.get(idx.get(i));
            put(table.get(1 << i), new Node(1 << i, p, 0, 0, null, null, i, 0));
        }
        put(table.get(tBit), new Node(tBit, target, 0, 0, null, null, n, 0));
        for (int m = 1; m <= full; m++) {
            if (Integer.bitCount(m) < 2) {
                continue;
            }
            boolean hasT = (m & tBit) != 0;
            Map<String, Node> dst = table.get(m);
            for (int a = (m - 1) & m; a > 0; a = (a - 1) & m) {
                int b = m ^ a;
                // a = left, b = right; the target (if present) must stay leftmost
                if ((b & tBit) != 0) {
                    continue;
                }
                for (Node ln : table.get(a).values()) {
                    for (Node rn : table.get(b).values()) {
                        Merge mg = merge(ln.piece, rn.piece, hasT ? applicable : null);
                        if (mg == null || mg.cost() >= cap) {
                            continue;
                        }
                        put(dst, new Node(m, mg.result(), ln.total + rn.total + mg.cost(),
                                Math.max(mg.cost(), Math.max(ln.maxStep, rn.maxStep)), ln, rn, -1, mg.cost()));
                    }
                }
            }
        }
        // best over masks containing the target: most sacrifices, then cheapest
        Node best = null;
        int bestCount = -1;
        for (int m = tBit; m <= full; m++) {
            if ((m & tBit) == 0) {
                continue;
            }
            int c = Integer.bitCount(m) - 1;
            for (Node nd : table.get(m).values()) {
                if (c > bestCount || (c == bestCount && nd.better(best))) {
                    best = nd;
                    bestCount = c;
                }
            }
        }
        List<Step> steps = new ArrayList<>();
        int[] next = {sacrifices.size() + 1};
        emit(best, idx, sacrifices.size(), steps, next);
        List<Integer> used = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if ((best.mask & (1 << i)) != 0) {
                used.add(idx.get(i));
            } else {
                skipped.add(idx.get(i));
            }
        }
        Collections.sort(skipped);
        return new Plan(List.copyOf(steps), best.total, best.maxStep, skipped.isEmpty(), List.copyOf(used),
                List.copyOf(skipped), best.piece);
    }

    private static void put(Map<String, Node> dst, Node nd) {
        String key = nd.piece.repairCost() + "|" + nd.piece.book() + "|" + nd.piece.enchants();
        Node cur = dst.get(key);
        if (nd.better(cur)) {
            dst.put(key, nd);
        }
    }

    /**
     * Post-order: node ids are sacrifice indices (original list), {@code targetId} for the target, and fresh ids from
     * {@code next} for step results.
     */
    private static int emit(Node nd, List<Integer> idx, int targetId, List<Step> out, int[] next) {
        if (nd.l == null) {
            return nd.leaf == idx.size() ? targetId : idx.get(nd.leaf);
        }
        int l = emit(nd.l, idx, targetId, out, next);
        int r = emit(nd.r, idx, targetId, out, next);
        int id = next[0]++;
        out.add(new Step(l, r, id, nd.stepCost, nd.piece));
        return id;
    }

    /** "Sharpness V, Unbreaking III". */
    public static String describe(Piece p) {
        List<EnchantPlanner.Ench> list = new ArrayList<>();
        for (Map.Entry<String, Integer> en : p.enchants().entrySet()) {
            list.add(new EnchantPlanner.Ench(en.getKey(), en.getValue()));
        }
        return EnchantPlanner.describe(list);
    }

    /** Human lines for the card: "1. Target + Mending book (5 levels)". */
    public static List<String> lines(Plan plan, Piece target, List<Piece> sacrifices) {
        List<String> out = new ArrayList<>();
        int targetId = sacrifices.size();
        Map<Integer, String> names = new HashMap<>();
        for (int i = 0; i < sacrifices.size(); i++) {
            names.put(i, sacrifices.get(i).label());
        }
        names.put(targetId, target.label());
        int k = 1;
        for (Step s : plan.steps()) {
            out.add(k + ". " + names.get(s.left()) + " + " + names.get(s.right()) + " (" + s.cost() + " levels)");
            names.put(s.result(), s.result() == plan.steps().get(plan.steps().size() - 1).result()
                    ? "result" : "step " + k);
            k++;
        }
        out.add("Total " + plan.totalLevels() + " levels" + (plan.complete() ? "" : ", "
                + plan.skipped().size() + " input(s) left out"));
        return out;
    }
}
