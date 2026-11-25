package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.jobs.planner.DisplayNames;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.Plan;
import com.utilitysuite.jobs.planner.PlanStep;
import com.utilitysuite.jobs.planner.StepKind;
import com.utilitysuite.jobs.planner.StepRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link HallSortPlanner.Plan} into goal-engine steps, engine free:
 * <ul>
 *   <li>per source chest, trips of at most {@link #MAX_STACKS_PER_TRIP} slots: one {@code HALL_TAKE} naming the exact
 *       slots (so one enchanted book can leave a chest of mixed books), then one {@code HALL_PUT} per destination;</li>
 *   <li>chests with partial stacks to merge get the merge flag ({@code crafts} = 1) on their last visit, or a
 *       {@code HALL_MERGE} step of their own when no trip visits them.</li>
 * </ul>
 * Step entries are {@code "slot|count|item|variant"} (take) and {@code "count|item|variant"} (put) strings in
 * {@code blockIds}; {@link #key} names an item+variant in transfer reports.
 */
public final class HallSortSteps {

    public static final int MAX_STACKS_PER_TRIP = 27;
    public static final String MILESTONE = "Sort hall";

    /** One slot to take: container slot position, items wanted, and what the slot must hold. */
    public record Take(int slot, int count, String item, String variant) {
        public String encode() {
            return slot + "|" + count + "|" + item + "|" + variant;
        }

        public static Take decode(String s) {
            String[] f = s.split("\\|", 4);
            return new Take(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f[2], f.length > 3 ? f[3] : "");
        }
    }

    /** Items to put: count of an item+variant. */
    public record Put(int count, String item, String variant) {
        public String encode() {
            return count + "|" + item + "|" + variant;
        }

        public static Put decode(String s) {
            String[] f = s.split("\\|", 3);
            return new Put(Integer.parseInt(f[0]), f[1], f.length > 2 ? f[2] : "");
        }
    }

    private HallSortSteps() {
    }

    /** Transfer-report key: the item id, plus {@code #variant} when it has one. */
    public static String key(String item, String variant) {
        return variant == null || variant.isEmpty() ? item : item + "#" + variant;
    }

    public static List<Take> takes(PlanStep step) {
        List<Take> out = new ArrayList<>(step.blockIds().size());
        for (String s : step.blockIds()) {
            out.add(Take.decode(s));
        }
        return out;
    }

    public static List<Put> puts(PlanStep step) {
        List<Put> out = new ArrayList<>(step.blockIds().size());
        for (String s : step.blockIds()) {
            out.add(Put.decode(s));
        }
        return out;
    }

    /** True when the step should merge the container's partial stacks before closing it. */
    public static boolean mergeAfter(PlanStep step) {
        return step.kind() == StepKind.HALL_MERGE || step.crafts() == 1;
    }

    public static Plan build(List<HallSortPlanner.Chest> chests, HallSortPlanner.Plan plan, String dim) {
        List<PlanStep> steps = new ArrayList<>();
        Map<Integer, List<HallSortPlanner.Move>> bySource = new LinkedHashMap<>();
        Set<Integer> mergeChests = new LinkedHashSet<>();
        for (HallSortPlanner.Move m : plan.moves()) {
            if (m.fromChest() == m.toChest()) {
                mergeChests.add(m.fromChest());
            } else {
                bySource.computeIfAbsent(m.fromChest(), k -> new ArrayList<>()).add(m);
            }
        }
        for (Map.Entry<Integer, List<HallSortPlanner.Move>> e : bySource.entrySet()) {
            long src = chests.get(e.getKey()).pos;
            // slot -> take entry (a slot split over two destinations is one take); destination -> put entries
            Map<Integer, Take> take = new LinkedHashMap<>();
            Map<Long, Map<String, Put>> dep = new LinkedHashMap<>();
            for (HallSortPlanner.Move m : e.getValue()) {
                if (!take.containsKey(m.fromSlot()) && take.size() >= MAX_STACKS_PER_TRIP) {
                    addTrip(steps, src, take, dep, dim);
                    take.clear();
                    dep.clear();
                }
                Take old = take.get(m.fromSlot());
                take.put(m.fromSlot(), new Take(m.fromSlot(), (old == null ? 0 : old.count()) + m.count(), m.itemId(),
                        m.variant()));
                Map<String, Put> d = dep.computeIfAbsent(chests.get(m.toChest()).pos, k -> new LinkedHashMap<>());
                Put p = d.get(key(m.itemId(), m.variant()));
                d.put(key(m.itemId(), m.variant()), new Put((p == null ? 0 : p.count()) + m.count(), m.itemId(),
                        m.variant()));
            }
            addTrip(steps, src, take, dep, dim);
        }
        for (int c : mergeChests) {
            long pos = chests.get(c).pos;
            int last = -1;
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (steps.get(i).storagePos() == pos) {
                    last = i;
                    break;
                }
            }
            if (last >= 0) {
                PlanStep s = steps.get(last);
                steps.set(last, new PlanStep(s.kind(), s.itemId(), s.count(), 1, null, s.blockIds(), 0, pos, dim,
                        s.role(), s.label() + ", merge partial stacks", MILESTONE));
            } else {
                steps.add(new PlanStep(StepKind.HALL_MERGE, "", 0, 1, null, List.of(), 0, pos, dim, StepRole.TARGET,
                        "Merge partial stacks at " + PackedPos.format(pos), MILESTONE));
            }
        }
        List<String> warnings = new ArrayList<>();
        if (!plan.nearlyFull().isEmpty()) {
            warnings.add(plan.nearlyFull().size() + " hall chest(s) nearly full");
        }
        return new Plan(steps, Map.of(), warnings, Map.of());
    }

    private static void addTrip(List<PlanStep> steps, long src, Map<Integer, Take> take,
                                Map<Long, Map<String, Put>> dep, String dim) {
        if (take.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<>(take.size());
        Map<String, Integer> summary = new LinkedHashMap<>();
        int total = 0;
        for (Take t : take.values()) {
            entries.add(t.encode());
            summary.merge(t.item(), t.count(), Integer::sum);
            total += t.count();
        }
        steps.add(new PlanStep(StepKind.HALL_TAKE, firstItem(summary), total, 0, null, entries, 0, src, dim,
                StepRole.COMPONENT, "Take " + describe(summary) + " from " + PackedPos.format(src), MILESTONE));
        for (Map.Entry<Long, Map<String, Put>> d : dep.entrySet()) {
            List<String> pe = new ArrayList<>(d.getValue().size());
            Map<String, Integer> sum = new LinkedHashMap<>();
            int n = 0;
            for (Put p : d.getValue().values()) {
                pe.add(p.encode());
                sum.merge(p.item(), p.count(), Integer::sum);
                n += p.count();
            }
            steps.add(new PlanStep(StepKind.HALL_PUT, firstItem(sum), n, 0, null, pe, 0, d.getKey(), dim,
                    StepRole.TARGET, "Put " + describe(sum) + " into " + PackedPos.format(d.getKey()), MILESTONE));
        }
    }

    private static String firstItem(Map<String, Integer> summary) {
        return summary.isEmpty() ? "" : summary.keySet().iterator().next();
    }

    private static String describe(Map<String, Integer> summary) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : summary.entrySet()) {
            if (shown == 3) {
                sb.append(", +").append(summary.size() - 3).append(" more");
                break;
            }
            if (shown++ > 0) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append(' ').append(DisplayNames.plural(e.getKey(), e.getValue()));
        }
        return sb.toString();
    }
}
