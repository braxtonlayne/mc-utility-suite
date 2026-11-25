package com.utilitysuite.jobs.planner;

import com.utilitysuite.core.api.IStorageIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the station planners into executor {@link Plan}s (engine free): FETCH steps for storage withdrawals, then
 * FURNACE_LOAD / FURNACE_COLLECT per furnace, or FILL_BOTTLES and one BREW per three bottles at a brewing stand.
 */
public final class StationJobPlanner {

    private StationJobPlanner() {
    }

    public static final String BREWING_STAND = "minecraft:brewing_stand";
    public static final String CRAFTER = "minecraft:crafter";

    /** A remembered station: position and block id. */
    public record KnownStation(long pos, String type) {
    }

    /** Remembered stations (StorageMemory), nearest first. */
    public interface StationSource {
        List<KnownStation> stations(String dimension, long from, int range, java.util.Set<String> types);
    }

    /**
     * Smelt {@code count} of {@code item} across the remembered furnaces (input and fuel taken from the inventory,
     * then FETCHed from storage for what is missing).
     *
     * @param stations remembered stations in the dimension of a type that accepts the item, within range
     */
    public static Plan smeltArray(String item, String outputItem, int count, List<KnownStation> stations,
                                  Map<String, Integer> inventory, IStorageIndex storage, String dim, long from,
                                  FuelTable fuels) {
        if (stations.isEmpty()) {
            return new Plan(List.of(), Map.of(), List.of("No remembered furnaces: open each furnace once first"),
                    Map.of());
        }
        List<PlanStep> steps = new ArrayList<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int carriedInput = inventory.getOrDefault(item, 0);
        int fetched = carriedInput >= count ? 0 : fetch(item, count - carriedInput, storage, dim, from, steps);
        int input = Math.min(count, carriedInput + fetched);
        if (input < count) {
            missing.put(item, count - input);
        }
        Map<String, Integer> fuelStock = new LinkedHashMap<>();
        for (String f : fuels.consumableFuels()) {
            int have = inventory.getOrDefault(f, 0);
            if (have > 0) {
                fuelStock.put(f, have);
            }
        }
        if (fuelStock.isEmpty()) {
            // nothing carried: fetch the best stored fuel for the whole request (one item per smelt worst case)
            for (String f : fuels.consumableFuels()) {
                int want = (int) Math.ceil(input / Math.max(0.5, fuels.smeltsPer(f) / 2.0));
                int got = fetch(f, want, storage, dim, from, steps);
                if (got > 0) {
                    fuelStock.put(f, got);
                    break;
                }
            }
        }
        List<FurnaceBatchPlanner.Station> st = new ArrayList<>();
        for (KnownStation k : stations) {
            st.add(FurnaceBatchPlanner.Station.empty(k.pos(), k.type()));
        }
        FurnaceBatchPlanner.Batch b = new FurnaceBatchPlanner(fuels).plan(item, input, st, fuelStock, from);
        if (b.fuelShort()) {
            warnings.add("Not enough fuel for every furnace; loads were shrunk");
        }
        if (b.unassigned() > 0) {
            warnings.add(b.unassigned() + " " + item + " did not fit this round");
        }
        String milestone = "Smelt " + input + " " + item + " in " + b.loads().size() + " furnaces";
        for (FurnaceBatchPlanner.Load l : b.loads()) {
            steps.add(new PlanStep(StepKind.FURNACE_LOAD, item, l.input(), l.fuelCount(), null,
                    l.fuelItem() == null ? List.of() : List.of(l.fuelItem()), 0, l.pos(), dim, StepRole.COMPONENT,
                    "Load furnace at " + PackedPos.format(l.pos()), milestone));
        }
        for (FurnaceBatchPlanner.Load l : b.loads()) {
            steps.add(new PlanStep(StepKind.FURNACE_COLLECT, outputItem, l.input(), l.readyTicks(), null, List.of(), 0,
                    l.pos(), dim, StepRole.TARGET, "Collect " + outputItem + " at " + PackedPos.format(l.pos()),
                    milestone));
        }
        return new Plan(steps, missing, warnings, Map.of());
    }

    /** Brew {@code count} of a potion at the remembered brewing stand {@code standPos}. */
    public static Plan brew(String potion, BrewingPlanner.Form form, int count, long standPos,
                            Map<String, Integer> inventory, IStorageIndex storage, String dim, long from,
                            int standFuel) {
        if (standPos == PlanStep.NO_POS) {
            return new Plan(List.of(), Map.of(), List.of("No remembered brewing stand: open one first"), Map.of());
        }
        BrewingPlanner.BrewPlan bp = BrewingPlanner.plan(potion, form, count, inventory, storage, dim, from, standFuel);
        if (bp.chain() == null) {
            return new Plan(List.of(), Map.of(potion, count), List.of("Unknown or unbrewable potion " + potion),
                    Map.of());
        }
        List<PlanStep> steps = new ArrayList<>();
        String milestone = "Brew " + count + " " + bp.target();
        for (BrewingPlanner.Source s : bp.withdrawals()) {
            if (!s.inventory()) {
                steps.add(new PlanStep(StepKind.FETCH, s.itemId(), s.count(), 0, null, List.of(), 0, s.storagePos(),
                        dim, StepRole.COMPONENT, "Fetch " + s.count() + " " + s.itemId(), milestone));
            }
        }
        if (bp.fillBottles() > 0) {
            steps.add(new PlanStep(StepKind.FILL_BOTTLES, BrewingPlanner.WATER, bp.fillBottles(), 0, null, List.of(), 0,
                    PlanStep.NO_POS, dim, StepRole.COMPONENT, "Fill " + bp.fillBottles() + " water bottles",
                    milestone));
        }
        for (BrewingPlanner.Batch batch : bp.batches()) {
            List<String> ids = new ArrayList<>();
            ids.add(batch.startKey());
            ids.addAll(batch.ingredients());
            steps.add(new PlanStep(StepKind.BREW, bp.target(), batch.bottles(), batch.ingredients().size(), null, ids,
                    0, standPos, dim, StepRole.TARGET, "Brew " + batch.bottles() + " " + bp.target(), milestone));
        }
        List<String> warnings = bp.missing().isEmpty() ? List.of() : List.of("Missing " + bp.missing());
        return new Plan(steps, bp.missing(), warnings, Map.of());
    }

    private static int fetch(String item, int want, IStorageIndex storage, String dim, long from,
                             List<PlanStep> out) {
        if (storage == null || want <= 0) {
            return 0;
        }
        int got = 0;
        for (var e : storage.containersWith(item, dim, from)) {
            int c = Math.min(want - got, e.contents().getOrDefault(item, 0));
            if (c > 0) {
                out.add(new PlanStep(StepKind.FETCH, item, c, 0, null, List.of(), 0, e.packedPos(), dim,
                        StepRole.COMPONENT, "Fetch " + c + " " + item, null));
                got += c;
            }
            if (got >= want) {
                break;
            }
        }
        return got;
    }
}
