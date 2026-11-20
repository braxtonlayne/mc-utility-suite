package com.utilitysuite.jobs.schematic.plan;

import com.utilitysuite.jobs.schematic.bom.ItemEquivalents;
import com.utilitysuite.jobs.schematic.bom.ItemRequirement;
import com.utilitysuite.jobs.schematic.model.BlockCategory;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Material staging: plans which items go into which designated project chest, grouped by build phase so the chest
 * nearest the work holds what the current layers need. Phases are bands of {@code layersPerPhase} layers in plan
 * order; attachment blocks form a final phase. Items are packed as full stacks; slots fill chest by chest.
 * Delivery itself (walking, opening chests, clicking) is done by the goal engine / storage modules: this is the plan.
 */
public final class StagingPlanner {

    /**
     * @param chest  index into the project chest list, or -1 when the chests are full (overflow)
     * @param slots  stacks this assignment occupies
     */
    public record Assignment(int chest, int phase, String item, int count, int slots) {
    }

    /**
     * @param chestsNeeded chests the whole plan would need
     */
    public record StagingPlan(List<Assignment> assignments, int phases, int chestsNeeded, int overflowItems) {
        public StagingPlan {
            assignments = List.copyOf(assignments);
        }
    }

    private StagingPlanner() {
    }

    /**
     * @param chests        number of designated project chests
     * @param slotsPerChest 27 for a single chest, 54 for a double
     */
    public static StagingPlan plan(BuildPlan plan, int layersPerPhase, int chests, int slotsPerChest) {
        SchematicVolume v = plan.volume();
        int layers = Math.max(1, layersPerPhase);
        int attachmentPhase = (v.sizeY() + layers - 1) / layers;
        ItemRequirement[] reqs = ItemEquivalents.forPalette(v);
        List<Map<String, Integer>> perPhase = new ArrayList<>();
        for (int i = 0; i <= attachmentPhase; i++) {
            perPhase.add(new LinkedHashMap<>());
        }
        for (int i = 0; i < plan.size(); i++) {
            int c = plan.cell(i);
            byte kind = plan.kind(i);
            if (kind == BuildPlan.REMOVE_SCAFFOLD) {
                continue;
            }
            int phase = v.yOf(c) / layers;
            if (kind == BuildPlan.SCAFFOLD) {
                perPhase.get(phase).merge(plan.scaffoldItem(), 1, Integer::sum);
                continue;
            }
            int p = v.paletteIndex(c);
            if (BlockCategory.of(v.paletteEntry(p)) == BlockCategory.ATTACHMENT) {
                phase = attachmentPhase;
            }
            ItemRequirement r = reqs[p];
            if (r.isItem()) {
                perPhase.get(phase).merge(r.item(), r.count(), Integer::sum);
                if (r.item2() != null) {
                    perPhase.get(phase).merge(r.item2(), r.count2(), Integer::sum);
                }
            }
        }
        List<Assignment> out = new ArrayList<>();
        int slot = 0;
        int capacity = chests * slotsPerChest;
        int overflow = 0;
        int phases = 0;
        for (int phase = 0; phase < perPhase.size(); phase++) {
            if (perPhase.get(phase).isEmpty()) {
                continue;
            }
            phases++;
            for (Map.Entry<String, Integer> e : perPhase.get(phase).entrySet()) {
                int stack = stackSize(e.getKey());
                int remaining = e.getValue();
                while (remaining > 0) {
                    int chest = slot < capacity ? slot / slotsPerChest : -1;
                    int freeInChest = chest < 0 ? Integer.MAX_VALUE : slotsPerChest - slot % slotsPerChest;
                    int stacks = (remaining + stack - 1) / stack;
                    int use = Math.min(stacks, freeInChest);
                    int count = Math.min(remaining, use * stack);
                    out.add(new Assignment(chest, phase, e.getKey(), count, use));
                    if (chest < 0) {
                        overflow += count;
                    }
                    slot += use;
                    remaining -= count;
                }
            }
        }
        int chestsNeeded = slotsPerChest <= 0 ? 0 : (slot + slotsPerChest - 1) / slotsPerChest;
        return new StagingPlan(out, phases, chestsNeeded, overflow);
    }

    /** Vanilla max stack size by id pattern (no registry in the pure layer). */
    public static int stackSize(String itemId) {
        String path = BlockStateString.path(itemId);
        if (path.endsWith("_bed") || path.endsWith("_shulker_box") || path.equals("shulker_box")
                || path.equals("cake") || (path.endsWith("_bucket")) || path.endsWith("_boat")
                || path.endsWith("minecart") || path.equals("saddle")) {
            return 1;
        }
        if (path.endsWith("_sign") || path.endsWith("_banner") || path.equals("snowball") || path.equals("egg")
                || path.equals("ender_pearl") || path.equals("honey_bottle") || path.equals("armor_stand")
                || path.equals("bucket")) {
            return 16;
        }
        return 64;
    }
}
