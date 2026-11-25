package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.core.api.StorageEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shulker-aware planning: items inside carried or stored shulker boxes are used before mining. */
class ShulkerPlanTest {

    static final String DIA = "minecraft:diamond";
    static final String BOX = "minecraft:shulker_box";
    static final String RED = "minecraft:red_shulker_box";

    final DependencyPlanner planner = VanillaRecipes.planner();
    final long chest = PackedPos.pack(4, 64, 4);

    private Plan gather(String item, int n, PlanContext ctx) {
        return planner.plan(new DependencyPlanner.Target(item, n, DependencyPlanner.TargetMode.GATHER),
                ctx.withReturn(false));
    }

    private static List<PlanStep> of(Plan p, StepKind kind) {
        return p.steps().stream().filter(s -> s.kind() == kind).toList();
    }

    @Test
    void takesFullestCarriedBoxesFirst() {
        List<NestedContainer> boxes = List.of(new NestedContainer(BOX, Map.of(DIA, 4)),
                new NestedContainer(RED, Map.of(DIA, 9, "minecraft:stick", 3)),
                new NestedContainer(BOX, Map.of("minecraft:stick", 64)));
        List<ShulkerContents.Take> t = ShulkerContents.plan(boxes, DIA, 11, Map.of());
        assertEquals(2, t.size());
        assertEquals(new ShulkerContents.Take(1, RED, 9), t.get(0));
        assertEquals(new ShulkerContents.Take(0, BOX, 2), t.get(1));

        List<ShulkerContents.Take> rest = ShulkerContents.plan(boxes, DIA, 11, Map.of(1, 9));
        assertEquals(List.of(new ShulkerContents.Take(0, BOX, 4)), rest, "already promised items are not reused");
        assertTrue(ShulkerContents.plan(boxes, DIA, 0, Map.of()).isEmpty());
        assertEquals(13, ShulkerContents.count(boxes, DIA));
        assertTrue(ShulkerContents.isShulkerBox(RED));
        assertFalse(ShulkerContents.isShulkerBox("minecraft:chest"));
    }

    @Test
    void carriedShulkerBecomesRestockStepInsteadOfMining() {
        PlanContext ctx = PlanContext.ofInventory(Map.of(BOX, 1), 0)
                .withCarriedShulkers(List.of(new NestedContainer(BOX, Map.of(DIA, 12))));
        Plan p = gather(DIA, 5, ctx);
        List<PlanStep> restock = of(p, StepKind.SHULKER_RESTOCK);
        assertEquals(1, restock.size(), p.steps().toString());
        assertEquals(DIA, restock.get(0).itemId());
        assertEquals(5, restock.get(0).count());
        assertEquals(List.of(BOX), restock.get(0).blockIds());
        assertTrue(of(p, StepKind.MINE).isEmpty(), "no digging when a carried box has enough");
    }

    @Test
    void carriedBoxShortfallIsProducedNormally() {
        PlanContext ctx = PlanContext.ofInventory(Map.of(BOX, 1), 0)
                .withCarriedShulkers(List.of(new NestedContainer(BOX, Map.of("minecraft:oak_log", 2))));
        Plan p = gather("minecraft:oak_log", 6, ctx);
        assertEquals(2, of(p, StepKind.SHULKER_RESTOCK).get(0).count());
        assertEquals(4, of(p, StepKind.GATHER).get(0).count(), p.steps().toString());
    }

    @Test
    void looseStacksBeforeStoredBoxThenFetchBoxBeforeUnpacking() {
        StorageEntry e = new StorageEntry(chest, "minecraft:overworld", "minecraft:chest",
                Map.of(DIA, 3, RED, 1), 0, false, List.of(new NestedContainer(RED, Map.of(DIA, 10))));
        Plan p = gather(DIA, 5, PlanContext.ofInventory(Map.of(), 0).withStorage(List.of(e)));
        List<PlanStep> steps = p.steps();
        int looseIdx = -1;
        int boxIdx = -1;
        int unpackIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            PlanStep s = steps.get(i);
            if (s.kind() == StepKind.FETCH && s.itemId().equals(DIA)) {
                looseIdx = i;
                assertEquals(3, s.count());
            } else if (s.kind() == StepKind.FETCH && s.itemId().equals(RED)) {
                boxIdx = i;
                assertEquals(1, s.count());
                assertEquals(chest, s.storagePos());
            } else if (s.kind() == StepKind.SHULKER_RESTOCK) {
                unpackIdx = i;
                assertEquals(2, s.count());
                assertEquals(List.of(RED), s.blockIds());
            }
        }
        assertTrue(looseIdx >= 0 && boxIdx >= 0 && unpackIdx > boxIdx, steps.toString());
        assertTrue(of(p, StepKind.MINE).isEmpty());
    }

    @Test
    void storedBoxIsFetchedOnceForSeveralItems() {
        StorageEntry e = new StorageEntry(chest, "minecraft:overworld", "minecraft:chest", Map.of(BOX, 1), 0, false,
                List.of(new NestedContainer(BOX, Map.of(DIA, 4, "minecraft:emerald", 4))));
        Plan p = planner.plan(List.of(new DependencyPlanner.Target(DIA, 2, DependencyPlanner.TargetMode.GATHER),
                        new DependencyPlanner.Target("minecraft:emerald", 2, DependencyPlanner.TargetMode.GATHER)),
                PlanContext.ofInventory(Map.of(), 0).withStorage(List.of(e)).withReturn(false));
        List<PlanStep> boxFetches = p.steps().stream()
                .filter(s -> s.kind() == StepKind.FETCH && s.itemId().equals(BOX)).toList();
        assertEquals(1, boxFetches.size(), p.steps().toString());
        assertEquals(1, boxFetches.get(0).count());
        assertEquals(2, of(p, StepKind.SHULKER_RESTOCK).size());
    }

    @Test
    void restockStepHasEstimateAndDescription() {
        PlanStep s = new PlanStep(StepKind.SHULKER_RESTOCK, DIA, 3, 0, null, List.of(BOX), 0, PlanStep.NO_POS,
                "minecraft:overworld", StepRole.TARGET, "Unpack 3 Diamonds from Shulker Box", null);
        assertTrue(s.describe().contains(BOX));
        assertEquals(PlanEstimate.SHULKER_RESTOCK, PlanEstimate.stepSeconds(s));
    }
}
