package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared errands across queued jobs: two jobs that withdraw from the same container, or mine in the same Y-band, are
 * batched so the player travels once. Pure function over plans; the executor applies the batches.
 */
public final class ErrandBatcher {

    public static final int DEFAULT_BAND_HEIGHT = 16;

    public record JobPlan(long jobId, Plan plan) {
    }

    /**
     * @param key   "chest@x y z" or "yband@-64..-49"
     * @param steps the batched steps, in job order (parallel to {@code jobIds})
     */
    public record Errand(String key, List<Long> jobIds, List<PlanStep> steps) {
        public Errand {
            jobIds = List.copyOf(jobIds);
            steps = List.copyOf(steps);
        }
    }

    private static final Map<String, Integer> PREFERRED_Y = Map.ofEntries(
            Map.entry("minecraft:diamond", -58), Map.entry("minecraft:redstone", -58),
            Map.entry("minecraft:raw_gold", -16), Map.entry("minecraft:lapis_lazuli", 0),
            Map.entry("minecraft:raw_iron", 16), Map.entry("minecraft:raw_copper", 48),
            Map.entry("minecraft:coal", 96), Map.entry("minecraft:emerald", 232),
            Map.entry("minecraft:obsidian", -40), Map.entry("minecraft:ancient_debris", 15));

    private ErrandBatcher() {
    }

    /** Typical Y-level the miner works at for the item, or {@link Integer#MIN_VALUE} when unknown (surface work). */
    public static int preferredY(String itemId) {
        return PREFERRED_Y.getOrDefault(itemId, Integer.MIN_VALUE);
    }

    public static int band(int y, int bandHeight) {
        return Math.floorDiv(y, bandHeight);
    }

    /** True when both mining steps would happen in the same Y-band. */
    public static boolean sameBand(PlanStep a, PlanStep b, int bandHeight) {
        int ya = preferredY(a.itemId());
        int yb = preferredY(b.itemId());
        return ya != Integer.MIN_VALUE && yb != Integer.MIN_VALUE && band(ya, bandHeight) == band(yb, bandHeight);
    }

    /** Errands shared by at least two different jobs, containers first, then Y-bands. */
    public static List<Errand> batch(List<JobPlan> jobs, int bandHeight) {
        Map<String, List<Long>> ids = new LinkedHashMap<>();
        Map<String, List<PlanStep>> steps = new LinkedHashMap<>();
        Map<String, Set<Long>> distinct = new LinkedHashMap<>();
        for (JobPlan jp : jobs) {
            for (PlanStep s : jp.plan().steps()) {
                String key = null;
                if (s.kind() == StepKind.FETCH && s.storagePos() != PlanStep.NO_POS) {
                    key = "chest@" + s.dimension() + " " + PackedPos.format(s.storagePos());
                } else if (s.kind() == StepKind.MINE) {
                    int y = preferredY(s.itemId());
                    if (y != Integer.MIN_VALUE) {
                        int b = band(y, bandHeight);
                        key = "yband@" + (b * bandHeight) + ".." + (b * bandHeight + bandHeight - 1);
                    }
                }
                if (key == null) {
                    continue;
                }
                ids.computeIfAbsent(key, k -> new ArrayList<>()).add(jp.jobId());
                steps.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
                distinct.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(jp.jobId());
            }
        }
        List<Errand> out = new ArrayList<>();
        for (String key : ids.keySet()) {
            if (distinct.get(key).size() >= 2) {
                out.add(new Errand(key, ids.get(key), steps.get(key)));
            }
        }
        out.sort((a, b) -> Boolean.compare(b.key().startsWith("chest"), a.key().startsWith("chest")));
        return out;
    }
}
