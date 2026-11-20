package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.StorageEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive recipe and bootstrapping planner: decides <em>what</em> to do next, never <em>how</em> (that is the
 * executor's and the Baritone adapter's job). Pure Java, thread-safe (each call uses its own session), so it runs on a
 * worker thread and in unit tests.
 *
 * <h2>Algorithm</h2>
 * Depth-first resolution over a virtual inventory (carried items + queued-job surplus):
 * <ol>
 *   <li>Use carried items, then known storage (FETCH), then produce.</li>
 *   <li>Produce = mine/gather the item, or run its cheapest recipe. Recipes fully covered by inventory/storage beat
 *       mining raw (multi-recipe optimizer); recipe cycles are cut off.</li>
 *   <li>Before a recipe: its station; before mining: a pickaxe of the block's tier (fresh-spawn bootstrapper, which
 *       recursively reaches logs, planks, crafting table, wooden pick, cobblestone, stone pick, furnace, iron, ...).
 *       Tools and stations are kept, not consumed.</li>
 *   <li>Before smelting: fuel (carried, stored, else mined coal / planks under PRESERVE_TERRAIN).</li>
 *   <li>Recipe output beyond the demand is credited back to the virtual inventory (surplus accounting).</li>
 * </ol>
 * A step is emitted when its item first completes (post-order); later demands for the same item only raise its count,
 * which keeps one step per item (all cobblestone in one trip) with every prerequisite earlier in the list.
 */
public final class DependencyPlanner {

    public enum TargetMode {
        /** Obtain items by any means (storage, mining, recipes). */
        GATHER,
        /** Produce the items by their recipe. */
        CRAFT
    }

    public record Target(String itemId, int count, TargetMode mode) {
    }

    private static final int MAX_DEPTH = 24;
    private static final int COST_DEPTH = 6;
    private static final double INF = Double.POSITIVE_INFINITY;

    private final RecipeSource recipes;
    private final HarvestTable harvest;
    private final FuelTable fuels;

    public DependencyPlanner(RecipeSource recipes, HarvestTable harvest, FuelTable fuels) {
        this.recipes = recipes;
        this.harvest = harvest;
        this.fuels = fuels;
    }

    public RecipeSource recipes() {
        return recipes;
    }

    public HarvestTable harvest() {
        return harvest;
    }

    /** Full plan for the targets, in order, sharing one virtual inventory. */
    public Plan plan(List<Target> targets, PlanContext ctx) {
        Session s = new Session(ctx);
        for (Target t : targets) {
            s.acquireTarget(t);
        }
        if (ctx.cost() == CostProfile.SAFEST && s.digs()) {
            // Safety prep (food, a weapon) goes before the first mining trip: replan with the prep resolved first so
            // its steps come early and later demands share its nodes (one cobblestone trip, one crafting table).
            s = new Session(ctx);
            s.safetyPrep();
            for (Target t : targets) {
                s.acquireTarget(t);
            }
        }
        if (ctx.includeReturn() && s.harvests()) {
            s.addReturn();
        }
        return s.finish();
    }

    // ---- cost profile policy ----------------------------------------------------------------------------------------

    /** Food the SAFEST profile counts and fetches (safe, no side effects). */
    public static final List<String> SAFE_FOODS = List.of("minecraft:cooked_beef", "minecraft:cooked_porkchop",
            "minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:cooked_salmon", "minecraft:cooked_cod",
            "minecraft:baked_potato", "minecraft:bread", "minecraft:golden_carrot", "minecraft:apple",
            "minecraft:carrot", "minecraft:cooked_rabbit", "minecraft:pumpkin_pie");
    /** SAFEST brings at least this much food before mining. */
    public static final int SAFE_FOOD_COUNT = 8;
    /**
     * Lowest Y the SAFEST profile mines predicted ore at. In 1.21.1 cave aquifers below Y=-54 are filled with lava
     * (lava lakes at Y=-55), so candidates under -50 are skipped when others exist.
     */
    public static final int SAFE_MIN_Y = -50;

    /**
     * One-line description of how the profile shapes the plan, for the plan preview.
     */
    public static String profileNote(CostProfile cost) {
        return switch (cost == null ? CostProfile.FASTEST : cost) {
            case FASTEST -> "Fastest: no optional prep, best stored pickaxe, ore candidates at any depth";
            case SAFEST -> "Safest: food and a sword before mining, best stored pickaxe, skips predicted ore below Y="
                    + SAFE_MIN_Y + " (lava level)";
            case CHEAPEST -> "Cheapest: lowest sufficient stored pickaxe (saves better tools), no extra tools, "
                    + "storage before mining";
            case PRESERVE_TERRAIN -> "Preserve terrain: storage and recipes before digging, planks instead of coal "
                    + "for fuel, lowest sufficient stored pickaxe";
        };
    }

    /** Whether a profile fetches the best stored pickaxe (speed) instead of the lowest sufficient one (thrift). */
    static boolean prefersBestTool(CostProfile cost) {
        return cost == CostProfile.FASTEST || cost == CostProfile.SAFEST;
    }

    public Plan plan(Target target, PlanContext ctx) {
        return plan(List.of(target), ctx);
    }

    /** Branch repair: only what is needed to hold a pickaxe of the tier again. */
    public Plan planTool(int tier, PlanContext ctx) {
        Session s = new Session(ctx);
        s.ensureTool(tier, 0);
        return s.finish();
    }

    /** Branch repair: only what is needed to have {@code count} more of the item (no return step). */
    public Plan planAcquire(String itemId, int count, PlanContext ctx) {
        Session s = new Session(ctx);
        s.acquire(itemId, count, StepRole.PRIMARY, 0);
        return s.finish();
    }

    // ---- session ----------------------------------------------------------------------------------------------------

    private static final class Node {
        final StepKind kind;
        final String item;
        final PlannerRecipe recipe;
        final List<String> blocks;
        final int tier;
        final long storagePos;
        StepRole role;
        int count;
        int crafts;

        Node(StepKind kind, String item, PlannerRecipe recipe, List<String> blocks, int tier, long storagePos,
             StepRole role) {
            this.kind = kind;
            this.item = item;
            this.recipe = recipe;
            this.blocks = blocks;
            this.tier = tier;
            this.storagePos = storagePos;
            this.role = role;
        }
    }

    /** Production method chosen for an item, fixed for the session so repeated demands stay consistent. */
    private record Choice(PlannerRecipe recipe, List<HarvestTable.Source> sources) {
    }

    private final class Session {
        final PlanContext ctx;
        final Map<String, Integer> vinv = new HashMap<>();
        final Map<String, Integer> start = new HashMap<>();
        /** Withdrawn amounts keyed "pos|item". */
        final Map<String, Integer> taken = new HashMap<>();
        final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, Choice> choices = new HashMap<>();
        final Set<String> inProgress = new HashSet<>();
        final Set<String> stations = new HashSet<>();
        final Map<String, Integer> missing = new LinkedHashMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final Map<String, Double> costMemo = new HashMap<>();
        int toolTier;
        int unresolvedCount;
        double fuelCredit;

        Session(PlanContext ctx) {
            this.ctx = ctx;
            ctx.inventory().forEach((k, v) -> vinv.merge(k, v, Integer::sum));
            ctx.credit().forEach((k, v) -> vinv.merge(k, v, Integer::sum));
            start.putAll(vinv);
            toolTier = ctx.pickaxeTier();
            for (Map.Entry<String, Integer> e : vinv.entrySet()) {
                if (e.getValue() > 0) {
                    toolTier = Math.max(toolTier, ToolTiers.tierOf(e.getKey()));
                    if (Items.isStation(e.getKey())) {
                        stations.add(e.getKey());
                    }
                }
            }
            stations.addAll(ctx.nearbyStations());
        }

        // ---- demand resolution -------------------------------------------------------------------------------------

        void acquireTarget(Target t) {
            String item = Items.qualify(t.itemId());
            int n = t.count();
            if (n <= 0) {
                return;
            }
            if (t.mode() == TargetMode.GATHER) {
                n -= fetch(item, n, StepRole.TARGET);
                if (n > 0) {
                    produce(item, n, StepRole.TARGET, 0, false);
                }
            } else {
                produce(item, n, StepRole.TARGET, 0, true);
            }
        }

        void acquire(String item, int n, StepRole role, int depth) {
            if (n <= 0) {
                return;
            }
            int have = vinv.getOrDefault(item, 0);
            int take = Math.min(have, n);
            if (take > 0) {
                vinv.put(item, have - take);
                n -= take;
            }
            if (n == 0) {
                return;
            }
            n -= fetch(item, n, role);
            if (n > 0) {
                produce(item, n, role, depth, false);
            }
        }

        /** @return true when fully resolved */
        boolean produce(String item, int n, StepRole role, int depth, boolean forceRecipe) {
            if (depth > MAX_DEPTH || !inProgress.add(item)) {
                unresolved(item, n, "Recipe cycle or depth limit while resolving " + item);
                return false;
            }
            try {
                Choice c = choose(item, forceRecipe);
                if (c == null) {
                    unresolved(item, n, "No known source for " + item);
                    return false;
                }
                if (c.recipe() != null) {
                    return craft(c.recipe(), item, n, role, depth);
                }
                return mine(c.sources(), item, n, role, depth);
            } finally {
                inProgress.remove(item);
            }
        }

        boolean mine(List<HarvestTable.Source> sources, String item, int n, StepRole role, int depth) {
            int tier = Integer.MAX_VALUE;
            boolean hand = true;
            for (HarvestTable.Source s : sources) {
                tier = Math.min(tier, s.requiredTier());
            }
            List<String> blocks = new ArrayList<>();
            for (HarvestTable.Source s : sources) {
                if (s.requiredTier() == tier) {
                    blocks.add(s.blockId());
                    hand &= s.byHand();
                }
            }
            boolean ok = ensureTool(tier, depth + 1);
            Node node = touch(hand ? StepKind.GATHER : StepKind.MINE, item, null, blocks, tier, PlanStep.NO_POS, role);
            node.count += n;
            return ok;
        }

        boolean craft(PlannerRecipe r, String item, int n, StepRole role, int depth) {
            int crafts = (n + r.outputCount() - 1) / r.outputCount();
            boolean ok = true;
            String station = r.station();
            if (station != null) {
                ok &= ensureStation(station, depth + 1);
            }
            for (RecipeIngredient ing : r.ingredients()) {
                int need = ing.count() * crafts;
                String opt = chooseOption(ing, need);
                int unresolvedBefore = unresolvedCount;
                acquire(opt, need, Items.STICK.equals(opt) ? StepRole.COMPONENT : StepRole.PRIMARY, depth + 1);
                ok &= unresolvedCount == unresolvedBefore;
            }
            if (r.kind().isCooking()) {
                ok &= ensureFuel(crafts, depth + 1);
            }
            Node node = touch(r.kind().isCooking() ? StepKind.SMELT : StepKind.CRAFT, item, r, List.of(), 0,
                    PlanStep.NO_POS, role);
            node.count += n;
            node.crafts += crafts;
            int extra = crafts * r.outputCount() - n;
            if (extra > 0) {
                vinv.merge(item, extra, Integer::sum);
            }
            return ok;
        }

        /** Makes sure a pickaxe of at least {@code tier} is held; tools are kept, not consumed. */
        boolean ensureTool(int tier, int depth) {
            if (tier <= 0 || toolTier >= tier) {
                return true;
            }
            // FASTEST/SAFEST take the best stored pickaxe (mines faster); CHEAPEST/PRESERVE_TERRAIN the lowest one
            // that is enough, keeping better tools' durability.
            boolean best = prefersBestTool(ctx.cost());
            for (int i = 0; i <= 5 - tier; i++) {
                int t = best ? 5 - i : tier + i;
                String p = ToolTiers.pickaxeForTier(t);
                if (storageAvailable(p) > 0) {
                    fetch(p, 1, StepRole.TOOL);
                    toolTier = Math.max(toolTier, t);
                    return true;
                }
            }
            String pick = ToolTiers.pickaxeForTier(tier);
            boolean ok = produce(pick, 1, StepRole.TOOL, depth + 1, false);
            if (ok) {
                toolTier = Math.max(toolTier, tier);
            }
            return ok;
        }

        boolean ensureStation(String station, int depth) {
            if (stations.contains(station)) {
                return true;
            }
            if (vinv.getOrDefault(station, 0) > 0) {
                stations.add(station);
                return true;
            }
            if (fetch(station, 1, StepRole.PRIMARY) > 0) {
                stations.add(station);
                return true;
            }
            StepRole role = Items.CRAFTING_TABLE.equals(station) ? StepRole.PRIMARY : StepRole.STATION;
            boolean ok = produce(station, 1, role, depth + 1, false);
            stations.add(station);
            return ok;
        }

        /** Fuel for {@code smelts} operations: leftover credit, carried, stored, then produced. */
        boolean ensureFuel(int smelts, int depth) {
            double need = smelts - fuelCredit;
            if (need <= 1e-9) {
                fuelCredit -= smelts;
                return true;
            }
            fuelCredit = 0;
            for (String f : fuels.consumableFuels()) {
                double per = fuels.smeltsPer(f);
                int avail = vinv.getOrDefault(f, 0);
                if (avail <= 0 || per <= 0) {
                    continue;
                }
                int use = (int) Math.min(avail, Math.ceil(need / per - 1e-9));
                vinv.put(f, avail - use);
                need -= use * per;
                if (need <= 1e-9) {
                    break;
                }
            }
            if (need > 1e-9) {
                for (String f : fuels.consumableFuels()) {
                    double per = fuels.smeltsPer(f);
                    int avail = storageAvailable(f);
                    if (avail <= 0 || per <= 0) {
                        continue;
                    }
                    int got = fetch(f, (int) Math.min(avail, Math.ceil(need / per - 1e-9)), StepRole.FUEL);
                    need -= got * per;
                    if (need <= 1e-9) {
                        break;
                    }
                }
            }
            boolean ok = true;
            if (need > 1e-9) {
                String fuel = ctx.cost() == CostProfile.PRESERVE_TERRAIN ? planksForFuel() : Items.COAL;
                double per = fuels.smeltsPer(fuel);
                int count = (int) Math.ceil(need / per - 1e-9);
                // produce() consumes exactly `count` (burned) and credits any recipe overshoot back to vinv
                ok = produce(fuel, count, StepRole.FUEL, depth + 1, false);
                need -= count * per;
                if (!ok) {
                    warnings.add("No fuel source for smelting " + smelts + " items");
                }
            }
            fuelCredit = Math.max(0, -need);
            return ok;
        }

        String planksForFuel() {
            for (Map.Entry<String, Integer> e : vinv.entrySet()) {
                if (e.getValue() > 0 && e.getKey().endsWith("_log")) {
                    String planks = e.getKey().substring(0, e.getKey().length() - 4) + "_planks";
                    if (!recipes.recipesFor(planks).isEmpty()) {
                        return planks;
                    }
                }
            }
            return "minecraft:oak_planks";
        }

        // ---- storage -----------------------------------------------------------------------------------------------

        int storageAvailable(String item) {
            int sum = 0;
            for (StorageEntry e : ctx.storage()) {
                Integer c = e.contents().get(item);
                if (c != null) {
                    sum += c - taken.getOrDefault(e.packedPos() + "|" + item, 0);
                }
            }
            return sum;
        }

        /** Withdraws up to {@code n} from the nearest containers; one FETCH step per container. */
        int fetch(String item, int n, StepRole role) {
            int got = 0;
            for (StorageEntry e : ctx.storage()) {
                if (got >= n) {
                    break;
                }
                Integer c = e.contents().get(item);
                if (c == null) {
                    continue;
                }
                String key = e.packedPos() + "|" + item;
                int avail = c - taken.getOrDefault(key, 0);
                if (avail <= 0) {
                    continue;
                }
                int take = Math.min(avail, n - got);
                taken.merge(key, take, Integer::sum);
                Node node = touch(StepKind.FETCH, item, null, List.of(), 0, e.packedPos(), role);
                node.count += take;
                got += take;
            }
            return got;
        }

        // ---- choices -----------------------------------------------------------------------------------------------

        Choice choose(String item, boolean forceRecipe) {
            String key = forceRecipe ? "!" + item : item;
            Choice cached = choices.get(key);
            if (cached != null) {
                return cached;
            }
            List<HarvestTable.Source> sources = forceRecipe ? List.of() : harvest.sourcesFor(item);
            PlannerRecipe best = null;
            double bestCost = INF;
            Set<String> visiting = new HashSet<>();
            visiting.add(item);
            for (PlannerRecipe r : recipes.recipesFor(item)) {
                double c = recipeCost(r, 1, visiting);
                if (c < bestCost) {
                    bestCost = c;
                    best = r;
                }
            }
            if (best == null && forceRecipe) {
                List<PlannerRecipe> all = recipes.recipesFor(item);
                best = all.isEmpty() ? null : all.get(0);
            }
            Choice choice;
            if (best != null && (sources.isEmpty() || bestCost < mineCost(sources))) {
                choice = new Choice(best, List.of());
            } else if (!sources.isEmpty()) {
                choice = new Choice(null, sources);
            } else {
                choice = null;
            }
            if (choice != null) {
                choices.put(key, choice);
            }
            return choice;
        }

        String chooseOption(RecipeIngredient ing, int need) {
            List<String> opts = ing.options();
            if (opts.size() == 1) {
                return opts.get(0);
            }
            String best = opts.get(0);
            int bestAvail = -1;
            double bestCost = INF;
            for (String o : opts) {
                int avail = vinv.getOrDefault(o, 0) + storageAvailable(o);
                int availScore = Math.min(avail, need);
                double cost = availScore >= need ? 0 : unitCost(o, 1, new HashSet<>());
                if (availScore > bestAvail || (availScore == bestAvail && cost < bestCost)) {
                    best = o;
                    bestAvail = availScore;
                    bestCost = cost;
                }
            }
            return best;
        }

        double mineCost(List<HarvestTable.Source> sources) {
            double best = INF;
            for (HarvestTable.Source s : sources) {
                double c = (s.byHand() ? 1.0 : 2.0) + Math.max(0, s.requiredTier() - toolTier) * 3.0;
                if (!s.byHand() && ctx.cost() == CostProfile.PRESERVE_TERRAIN) {
                    c *= 3.0; // digging scars terrain: recipes from carried/stored materials win more often
                }
                best = Math.min(best, c / Math.max(0.1, s.dropsPerBlock()));
            }
            return best;
        }

        /** Rough cost per unit; 0 when available. Memoized for root queries only (cycle-safe). */
        double unitCost(String item, int depth, Set<String> visiting) {
            if (vinv.getOrDefault(item, 0) > 0 || storageAvailable(item) > 0) {
                return 0;
            }
            if (depth > COST_DEPTH || visiting.contains(item)) {
                return INF;
            }
            boolean root = visiting.isEmpty();
            if (root) {
                Double m = costMemo.get(item);
                if (m != null) {
                    return m;
                }
            }
            visiting.add(item);
            double best = mineCost(harvest.sourcesFor(item));
            for (PlannerRecipe r : recipes.recipesFor(item)) {
                best = Math.min(best, recipeCost(r, depth + 1, visiting));
            }
            visiting.remove(item);
            if (root) {
                costMemo.put(item, best);
            }
            return best;
        }

        double recipeCost(PlannerRecipe r, int depth, Set<String> visiting) {
            double sum = 0.2 + (r.kind().isCooking() ? 0.15 : 0);
            for (RecipeIngredient ing : r.ingredients()) {
                double best = INF;
                for (String o : ing.options()) {
                    best = Math.min(best, unitCost(o, depth, visiting));
                    if (best == 0) {
                        break;
                    }
                }
                if (best == INF) {
                    return INF;
                }
                sum += best * ing.count() / r.outputCount();
            }
            String station = r.station();
            if (station != null && !stations.contains(station) && vinv.getOrDefault(station, 0) <= 0) {
                double sc = unitCost(station, depth, visiting);
                if (sc == INF) {
                    return INF;
                }
                sum += sc / 8.0;
            }
            return sum;
        }

        // ---- bookkeeping -------------------------------------------------------------------------------------------

        Node touch(StepKind kind, String item, PlannerRecipe recipe, List<String> blocks, int tier, long pos,
                   StepRole role) {
            String key = kind + "|" + item + "|" + pos;
            Node n = nodes.get(key);
            if (n == null) {
                n = new Node(kind, item, recipe, blocks, tier, pos, role);
                nodes.put(key, n);
            } else if (role == StepRole.TARGET) {
                n.role = StepRole.TARGET;
            }
            return n;
        }

        void unresolved(String item, int n, String why) {
            unresolvedCount++;
            missing.merge(item, n, Integer::sum);
            warnings.add(why);
        }

        /** True when the plan mines with a pickaxe (not just hand-gathering logs or dirt). */
        boolean digs() {
            for (Node n : nodes.values()) {
                if (n.kind == StepKind.MINE) {
                    return true;
                }
            }
            return false;
        }

        /** SAFEST prep: food from storage when low, and a sword (stored, else a crafted stone/wooden sword). */
        void safetyPrep() {
            int food = 0;
            for (String f : SAFE_FOODS) {
                food += vinv.getOrDefault(f, 0);
            }
            if (food < SAFE_FOOD_COUNT) {
                int need = SAFE_FOOD_COUNT - food;
                for (String f : SAFE_FOODS) {
                    if (need <= 0) {
                        break;
                    }
                    need -= fetch(f, need, StepRole.PRIMARY);
                }
                if (need == SAFE_FOOD_COUNT - food && food == 0) {
                    warnings.add("Safest: no food carried or in known storage; bring food before mining");
                }
            }
            for (Map.Entry<String, Integer> e : vinv.entrySet()) {
                if (e.getValue() > 0 && e.getKey().endsWith("_sword")) {
                    return;
                }
            }
            for (StorageEntry e : ctx.storage()) {
                for (String item : e.contents().keySet()) {
                    if (item.endsWith("_sword") && storageAvailable(item) > 0) {
                        fetch(item, 1, StepRole.PRIMARY);
                        return;
                    }
                }
            }
            for (String sword : new String[]{"minecraft:stone_sword", "minecraft:wooden_sword"}) {
                if (!recipes.recipesFor(sword).isEmpty()) {
                    produce(sword, 1, StepRole.PRIMARY, 0, true);
                    return;
                }
            }
            warnings.add("Safest: no sword recipe known; mining without a weapon");
        }

        boolean harvests() {
            for (Node n : nodes.values()) {
                if (n.kind == StepKind.MINE || n.kind == StepKind.GATHER) {
                    return true;
                }
            }
            return false;
        }

        void addReturn() {
            nodes.put("RETURN", new Node(StepKind.RETURN, "", null, List.of(), 0, PlanStep.NO_POS, StepRole.RETURN));
        }

        Plan finish() {
            List<PlanStep> steps = new ArrayList<>(nodes.size());
            Map<String, Integer> raw = new LinkedHashMap<>();
            for (Node n : nodes.values()) {
                steps.add(new PlanStep(n.kind, n.item, n.count, n.crafts, n.recipe == null ? null : n.recipe.id(),
                        n.blocks, n.tier, n.storagePos, ctx.dimension(), n.role, label(n), null));
                if (n.kind == StepKind.MINE || n.kind == StepKind.GATHER) {
                    raw.merge(n.item, n.count, Integer::sum);
                }
            }
            missing.forEach((k, v) -> raw.merge(k, v, Integer::sum));
            Map<String, Integer> surplus = new LinkedHashMap<>();
            vinv.forEach((k, v) -> {
                int d = v - start.getOrDefault(k, 0);
                if (d > 0) {
                    surplus.put(k, d);
                }
            });
            return new Plan(groupMilestones(steps), raw, new ArrayList<>(warnings), surplus);
        }
    }

    // ---- labels -----------------------------------------------------------------------------------------------------

    private static String label(Node n) {
        if (n.kind == StepKind.RETURN) {
            return "Return/Deposit";
        }
        if (n.role == StepRole.TARGET) {
            String what = n.count + " " + DisplayNames.plural(n.item, n.count);
            return switch (n.kind) {
                case MINE -> "Mine " + what;
                case GATHER -> "Gather " + what;
                case SMELT -> "Smelt " + what;
                case FETCH -> "Fetch " + what;
                default -> "Craft " + what;
            };
        }
        if (n.kind == StepKind.FETCH) {
            return "Fetch " + DisplayNames.of(n.item);
        }
        return DisplayNames.of(n.item);
    }

    /**
     * Milestone grouping: a secondary station joins the following craft ("Stone Pick + Furnace"), fuel joins the
     * preceding mining step ("Iron Ore + Fuel"), components (sticks) silently join the next milestone.
     */
    static List<PlanStep> groupMilestones(List<PlanStep> in) {
        int n = in.size();
        String[] m = new String[n];
        for (int i = 0; i < n; i++) {
            m[i] = in.get(i).label();
        }
        for (int i = 0; i < n; i++) {
            PlanStep s = in.get(i);
            if (s.role() == StepRole.STATION && s.kind() == StepKind.CRAFT && i + 1 < n
                    && in.get(i + 1).kind() == StepKind.CRAFT && in.get(i + 1).role() != StepRole.COMPONENT) {
                String joined = m[i + 1] + " + " + s.label();
                m[i + 1] = joined;
                m[i] = joined;
            } else if (s.role() == StepRole.FUEL && i > 0
                    && (in.get(i - 1).kind() == StepKind.MINE || in.get(i - 1).kind() == StepKind.GATHER)) {
                String prev = m[i - 1];
                String joined = prev + " + Fuel";
                for (int j = i - 1; j >= 0 && m[j].equals(prev); j--) {
                    m[j] = joined;
                }
                m[i] = joined;
            }
        }
        for (int i = n - 2; i >= 0; i--) {
            if (in.get(i).role() == StepRole.COMPONENT) {
                m[i] = m[i + 1];
            }
        }
        List<PlanStep> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(in.get(i).withMilestone(m[i]));
        }
        return out;
    }
}
