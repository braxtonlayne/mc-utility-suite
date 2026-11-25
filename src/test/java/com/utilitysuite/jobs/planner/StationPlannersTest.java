package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.IContainerClicker.Click;
import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 3 station planners: furnace arrays (J5), crafter templates (J5), brewing (J6), ender chest (I2). */
class StationPlannersTest {

    static final String IRON = "minecraft:raw_iron";
    static final String COAL = "minecraft:coal";
    final FurnaceBatchPlanner furnaces = new FurnaceBatchPlanner(FuelTable.vanilla());

    private static FurnaceBatchPlanner.Station f(int x) {
        return FurnaceBatchPlanner.Station.empty(PackedPos.pack(x, 64, 0), FurnaceBatchPlanner.FURNACE);
    }

    // ---- J5 furnace array ------------------------------------------------------------------------------------------

    @Test
    void splits192EvenlyAcrossFourFurnacesWithZeroFuelWaste() {
        var b = furnaces.plan(IRON, 192, List.of(f(6), f(0), f(2), f(4)), Map.of(COAL, 64), PackedPos.pack(0, 64, 0));
        assertEquals(4, b.loads().size());
        for (var l : b.loads()) {
            assertEquals(48, l.input());
            assertEquals(COAL, l.fuelItem());
            assertEquals(6, l.fuelCount());
            assertEquals(0.0, l.wastedSmelts(), 1e-9);
            assertEquals(48 * 200, l.readyTicks());
        }
        assertEquals(0, b.unassigned());
        assertEquals(24, b.fuelUsed().get(COAL));
        // nearest-neighbour route from x=0
        assertEquals(List.of(0, 2, 4, 6), b.loads().stream().map(l -> PackedPos.x(l.pos())).toList());
    }

    @Test
    void unevenCountSpreadsRemainderAndOverflowWaitsForNextRound() {
        var b = furnaces.plan(IRON, 10, List.of(f(0), f(1), f(2)), Map.of(COAL, 64), 0L);
        assertEquals(10, b.totalInput());
        assertEquals(List.of(4, 3, 3), b.loads().stream().map(FurnaceBatchPlanner.Load::input).sorted(
                (a, c) -> c - a).toList());
        var over = furnaces.plan(IRON, 200, List.of(f(0), f(1)), Map.of(COAL, 64), 0L);
        assertEquals(128, over.totalInput());
        assertEquals(72, over.unassigned());
    }

    @Test
    void blastFurnaceUsesTwiceTheFuelAndSkipsBlockedStations() {
        var blast = FurnaceBatchPlanner.Station.empty(PackedPos.pack(0, 64, 0), FurnaceBatchPlanner.BLAST_FURNACE);
        var busy = new FurnaceBatchPlanner.Station(PackedPos.pack(1, 64, 0), FurnaceBatchPlanner.FURNACE,
                "minecraft:raw_gold", 5, null, 0, null, 0);
        var b = furnaces.plan(IRON, 16, List.of(blast, busy), Map.of(COAL, 10), 0L);
        assertEquals(1, b.loads().size());
        assertEquals(4, b.loads().get(0).fuelCount()); // 4 items per coal in a blast furnace
        assertEquals(100, b.loads().get(0).readyTicks() / 16);
    }

    @Test
    void picksLeastWasteFuelAndShrinksLoadsWhenFuelRunsOut() {
        var b = furnaces.plan(IRON, 80, List.of(f(0)), Map.of(COAL, 64, "minecraft:coal_block", 1), 0L);
        assertEquals(64, b.loads().get(0).input()); // stack cap
        assertEquals(COAL, b.loads().get(0).fuelItem());
        var short1 = furnaces.plan(IRON, 32, List.of(f(0), f(1)), Map.of(COAL, 3), 0L);
        assertTrue(short1.fuelShort());
        assertEquals(24, short1.totalInput());
        assertEquals(8, short1.unassigned());
    }

    @Test
    void collectRouteTakesFinishedOrFullOutputs() {
        var done = new FurnaceBatchPlanner.Station(PackedPos.pack(5, 64, 0), "minecraft:furnace", null, 0, COAL, 1,
                "minecraft:iron_ingot", 12);
        var busy = new FurnaceBatchPlanner.Station(PackedPos.pack(1, 64, 0), "minecraft:furnace", IRON, 30, COAL, 3,
                "minecraft:iron_ingot", 2);
        var out = FurnaceBatchPlanner.collectRoute(List.of(done, busy), "minecraft:iron_ingot", 16, 0L);
        assertEquals(1, out.size());
        assertEquals(done, out.get(0));
    }

    @Test
    void slotTransferUsesFewestClicks() {
        assertEquals(2, SlotTransfer.exact(30, 64, 0, 64).size());
        var few = SlotTransfer.exact(30, 64, 0, 3);
        assertEquals(5, few.size());
        assertEquals(new SlotTransfer.Step(0, 1, Click.PICKUP), few.get(1));
        var most = SlotTransfer.exact(30, 64, 0, 60);
        assertEquals(6, most.size()); // pick up, 4 back, place 60
        assertEquals(new SlotTransfer.Step(0, 0, Click.PICKUP), most.get(5));
        assertTrue(SlotTransfer.exact(30, 0, 0, 5).isEmpty());
    }

    // ---- J5 crafter template ---------------------------------------------------------------------------------------

    @Test
    void crafterTemplateDiffClearsTogglesThenSeedsAndRoundTrips() {
        String[] items = {"minecraft:iron_ingot", null, "minecraft:iron_ingot", null, null, null, null, null, null};
        CrafterTemplate t = CrafterTemplate.capture("buckets", "minecraft:bucket", 0b111_101_000, items);
        // slots 3,5,6,7,8 disabled per mask 0b111101000
        assertTrue(t.disabled(3) && t.disabled(5) && !t.disabled(4));
        assertTrue(t.matches(t.disabledMask(), items));
        String[] wrong = {"minecraft:gold_ingot", null, null, "minecraft:stick", null, null, null, null, null};
        var fixes = t.diff(0, wrong);
        assertEquals(CrafterTemplate.FixKind.CLEAR, fixes.get(0).kind());
        assertEquals(0, fixes.get(0).slot());
        assertEquals(CrafterTemplate.FixKind.CLEAR, fixes.get(1).kind());
        assertEquals(3, fixes.get(1).slot()); // must be empty before it can be disabled
        long toggles = fixes.stream().filter(x -> x.kind() == CrafterTemplate.FixKind.TOGGLE).count();
        assertEquals(5, toggles);
        var seeds = fixes.stream().filter(x -> x.kind() == CrafterTemplate.FixKind.SEED).map(x -> x.slot()).toList();
        assertEquals(List.of(0, 2), seeds);
        assertEquals(t, CrafterTemplate.decode(t.encode()));
    }

    // ---- J6 brewing ------------------------------------------------------------------------------------------------

    @Test
    void splashHealingTwoChainAndBatch() {
        var chain = BrewingPlanner.chain("strong_healing", BrewingPlanner.Form.SPLASH);
        assertEquals(List.of("minecraft:nether_wart", "minecraft:glistering_melon_slice", "minecraft:glowstone_dust",
                "minecraft:gunpowder"), chain.stream().map(BrewingPlanner.Edge::ingredient).toList());
        StorageMemory mem = new StorageMemory(() -> 1000L, 1_000_000L);
        long chest = PackedPos.pack(3, 64, 3);
        mem.record("overworld", chest, "minecraft:chest", Map.of("minecraft:gunpowder", 5, "minecraft:glass_bottle", 2));
        var plan = BrewingPlanner.plan("strong_healing", BrewingPlanner.Form.SPLASH, 3,
                Map.of("minecraft:nether_wart", 4, "minecraft:glistering_melon_slice", 1, "minecraft:glowstone_dust", 9,
                        "minecraft:blaze_powder", 2, "minecraft:glass_bottle", 1),
                mem, "overworld", 0L, 0);
        assertTrue(plan.feasible(), () -> "missing " + plan.missing());
        assertEquals(1, plan.batches().size());
        assertEquals(3, plan.batches().get(0).bottles());
        assertEquals(4 * BrewingPlanner.BREW_TICKS, plan.batches().get(0).ticks());
        assertEquals(1, plan.powder());
        assertEquals(3, plan.fillBottles());
        assertTrue(plan.withdrawals().contains(new BrewingPlanner.Source("minecraft:gunpowder", 1, chest)));
        assertTrue(plan.withdrawals().contains(new BrewingPlanner.Source("minecraft:glass_bottle", 2, chest)));
        assertEquals("minecraft:splash_potion#minecraft:strong_healing", plan.target());
    }

    @Test
    void brewingReportsMissingSkipsCarriedStagesAndRejectsBadPotions() {
        String awkward = BrewingPlanner.key(BrewingPlanner.POTION, "awkward");
        var plan = BrewingPlanner.plan("long_swiftness", BrewingPlanner.Form.DRINK, 5,
                Map.of(awkward, 3, "minecraft:sugar", 1, "minecraft:redstone", 10, "minecraft:nether_wart", 2),
                null, "overworld", 0L, 20);
        // 3 carried awkward bottles need sugar+redstone; 2 fresh bottles need wart+sugar+redstone
        assertEquals(2, plan.batches().size());
        assertEquals(awkward, plan.batches().get(0).startKey());
        assertEquals(1, plan.missing().get("minecraft:sugar"));
        assertEquals(2, plan.missing().get(BrewingPlanner.GLASS_BOTTLE));
        assertEquals(0, plan.powder()); // stand fuel covers 6 brews
        assertFalse(plan.feasible());
        assertNull(BrewingPlanner.chain("strong_fire_resistance", BrewingPlanner.Form.DRINK));
        assertEquals(List.of("minecraft:fermented_spider_eye", "minecraft:gunpowder", "minecraft:dragon_breath"),
                BrewingPlanner.chain("weakness", BrewingPlanner.Form.LINGERING).stream()
                        .map(BrewingPlanner.Edge::ingredient).toList());
        assertEquals(4, BrewingPlanner.chain("harming", BrewingPlanner.Form.DRINK).size() + 1);
    }

    // ---- I2 ender chest --------------------------------------------------------------------------------------------

    @Test
    void enderChestUsesKnownChestThenCarriedOnlyWithSilkTouch() {
        String pearl = "minecraft:ender_pearl";
        var boxes = List.of(new NestedContainer("minecraft:red_shulker_box", Map.of(pearl, 16)),
                new NestedContainer("minecraft:shulker_box", Map.of(pearl, 32)));
        Map<String, Integer> top = Map.of(pearl, 4);
        long known = PackedPos.pack(10, 64, 0);
        long home = PackedPos.pack(0, 64, 0);
        var p = EnderChestPlanner.plan(pearl, 40, top, boxes, List.of(known), home, 32, false, false);
        assertEquals(EnderChestPlanner.Access.USE_KNOWN, p.access());
        assertEquals(known, p.chestPos());
        assertEquals(4, p.takeDirect());
        assertEquals(2, p.boxes().size());
        assertEquals(4, p.boxes().get(1).count());
        assertEquals("minecraft:shulker_box", p.boxes().get(0).containerItemId()); // largest holder first
        assertEquals(40, p.planned());

        var far = EnderChestPlanner.plan(pearl, 60, top, boxes, List.of(known), home, 5, true, true);
        assertEquals(EnderChestPlanner.Access.PLACE_CARRIED, far.access());
        assertTrue(far.pickUpAfter());
        assertEquals(8, far.shortfall());

        var noSilk = EnderChestPlanner.plan(pearl, 10, top, boxes, List.of(), home, 32, true, false);
        assertEquals(EnderChestPlanner.Access.NONE, noSilk.access());
        assertTrue(noSilk.reason().contains("Silk Touch"));

        assertEquals(EnderChestPlanner.Access.NONE,
                EnderChestPlanner.plan("minecraft:dirt", 1, top, boxes, List.of(known), home, 32, true, true).access());
        assertEquals(52, EnderChestPlanner.count(pearl, top, boxes));
        assertEquals(List.of(Map.entry(pearl, 6)),
                EnderChestPlanner.leftovers(Map.of(pearl, 16), Map.of(pearl, 10)));
    }

    @Test
    void enderChestMirrorSnapshot() {
        var m = new com.utilitysuite.jobs.storage.EnderChestMirror();
        assertFalse(m.snapshot().known());
        m.record(Map.of("minecraft:diamond", 3),
                List.of(new NestedContainer("minecraft:shulker_box", Map.of("minecraft:diamond", 9))), 5L);
        assertTrue(m.snapshot().known());
        assertEquals(12, m.snapshot().count("minecraft:diamond"));
    }
}
