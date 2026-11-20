package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.PositionPredicate;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed Goal Console commands follow the World tab's routing data source, and "travel to WAYPOINT" uses the same 8:1
 * overworld/nether conversion as the World tab "Go" (cross-dimension portal routing is not supported yet).
 */
class GoalRoutingTest {

    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    static final String END = "minecraft:the_end";

    static final ItemNameMatcher MATCHER = new ItemNameMatcher(List.of("minecraft:diamond", "minecraft:coal"));

    /** Waypoint service fake with the same conversion rule as the world module's WaypointStore. */
    static final class Waypoints implements IWaypointService {
        final List<Waypoint> list = new ArrayList<>();

        @Override
        public List<Waypoint> all() {
            return list;
        }

        @Override
        public Optional<Waypoint> byName(String name) {
            return list.stream().filter(w -> w.name().equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public Waypoint add(String name, String group, int x, int y, int z, String dimension) {
            Waypoint w = new Waypoint(name, name, group, x, y, z, dimension, 0);
            list.add(w);
            return w;
        }

        @Override
        public void remove(String id) {
        }

        @Override
        public long positionIn(Waypoint w, String dimension) {
            int x = w.x();
            int z = w.z();
            if (OVERWORLD.equals(w.dimension()) && NETHER.equals(dimension)) {
                x = Math.floorDiv(x, 8);
                z = Math.floorDiv(z, 8);
            } else if (NETHER.equals(w.dimension()) && OVERWORLD.equals(dimension)) {
                x *= 8;
                z *= 8;
            }
            return PackedPos.pack(x, w.y(), z);
        }

        @Override
        public long[] breadcrumbs() {
            return new long[0];
        }
    }

    final Waypoints waypoints = new Waypoints();
    String currentDim = OVERWORLD;

    GoalParser parser(Supplier<DataSourceMode> source) {
        return new GoalParser(() -> MATCHER, name -> GoalParser.resolveWaypoint(waypoints, name, currentDim), source,
                () -> PackedPos.pack(0, 64, 0), () -> currentDim);
    }

    // ---- routing data source ------------------------------------------------------------------------------------------

    @Test
    void typedCommandsFollowWorldTabRoutingMode() {
        Fakes.Knowledge k = new Fakes.Knowledge();
        GoalParser p = parser(GoalParser.routingDefault(() -> k));
        k.setRoutingMode(DataSourceMode.SIMULATED_ONLY);
        assertEquals(DataSourceMode.SIMULATED_ONLY, p.parse("mine 4 diamonds").request().dataSource());
        k.setRoutingMode(DataSourceMode.OBSERVED_ONLY);
        assertEquals(DataSourceMode.OBSERVED_ONLY, p.parse("mine 4 diamonds").request().dataSource());
        // explicit override wins
        assertEquals(DataSourceMode.HYBRID, p.parse("mine 4 diamonds source=hybrid").request().dataSource());
        k.setRoutingMode(DataSourceMode.HYBRID);
        assertEquals(DataSourceMode.SIMULATED_ONLY,
                p.parse("mine 4 diamonds source=simulated").request().dataSource());
    }

    @Test
    void noWorldKnowledgeMeansObservedOnly() {
        GoalParser p = parser(GoalParser.routingDefault(() -> (IWorldKnowledgeService) null));
        assertEquals(DataSourceMode.OBSERVED_ONLY, p.parse("gather 3 coal").request().dataSource());
    }

    @Test
    void goalEngineKeepsRoutingSourceWhenApplyingDefaultCost() {
        Fakes.Knowledge k = new Fakes.Knowledge();
        k.setRoutingMode(DataSourceMode.SIMULATED_ONLY);
        GoalEngine engine = new GoalEngine(parser(GoalParser.routingDefault(() -> k)), VanillaRecipes::planner,
                newExecutor(new Fakes.Actions(new Fakes.Inventory()), new Fakes.Bus()), Runnable::run);
        GoalRequest r = engine.parse("mine 4 diamonds").request();
        assertEquals(DataSourceMode.SIMULATED_ONLY, r.dataSource());
        assertEquals(CostProfile.FASTEST, r.cost());
        assertEquals(DataSourceMode.OBSERVED_ONLY,
                engine.parse("mine 4 diamonds source=observed cost=safest").request().dataSource());
    }

    // ---- waypoint travel ----------------------------------------------------------------------------------------------

    @Test
    void netherWaypointIsScaledUpInOverworld() {
        waypoints.add("Fortress", "Bases", 100, 70, -20, NETHER);
        currentDim = OVERWORLD;
        GoalRequest.Travel t = assertInstanceOf(GoalRequest.Travel.class,
                parser(() -> DataSourceMode.HYBRID).parse("travel to fortress").request());
        assertEquals(800, t.x());
        assertEquals(70, t.y());
        assertEquals(-160, t.z());
        assertEquals(OVERWORLD, t.dimension());
    }

    @Test
    void overworldWaypointIsScaledDownInNether() {
        waypoints.add("Base", "Bases", 100, 64, -20, OVERWORLD);
        currentDim = NETHER;
        GoalRequest.Travel t = assertInstanceOf(GoalRequest.Travel.class,
                parser(() -> DataSourceMode.HYBRID).parse("travel to Base").request());
        assertEquals(12, t.x());
        assertEquals(-3, t.z()); // floorDiv(-20, 8), same as the World tab "Go"
        assertEquals(NETHER, t.dimension());
    }

    @Test
    void sameDimensionWaypointIsUnchanged() {
        waypoints.add("Mine", "Mines", -33, 12, 47, OVERWORLD);
        GoalRequest.Travel t = (GoalRequest.Travel) parser(() -> DataSourceMode.HYBRID).parse("goto Mine").request();
        assertEquals(-33, t.x());
        assertEquals(12, t.y());
        assertEquals(47, t.z());
    }

    @Test
    void endWaypointFailsWithClearMessage() {
        waypoints.add("End Island", "Bases", 0, 60, 0, END);
        currentDim = OVERWORLD;
        ParseResult r = parser(() -> DataSourceMode.HYBRID).parse("travel to end island");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("the_end") && r.error().contains("not supported"), r.error());
        assertFalse(parser(() -> DataSourceMode.HYBRID).parse("travel to nowhere").isOk());
    }

    @Test
    void executorRefusesTravelGoalInAnotherDimension() {
        Fakes.Actions actions = new Fakes.Actions(new Fakes.Inventory());
        Fakes.Bus bus = new Fakes.Bus();
        JobExecutor exec = newExecutor(actions, bus);
        exec.submit(new GoalRequest.Travel(0, 60, 0, END, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        for (int i = 0; i < 5; i++) {
            exec.tick();
        }
        List<SuiteEvent.JobChanged> ev = bus.of(SuiteEvent.JobChanged.class);
        SuiteEvent.JobChanged last = ev.get(ev.size() - 1);
        assertEquals(JobStatus.FAILED, last.snapshot().status());
        assertTrue(last.snapshot().reason().contains("cross-dimension"), last.snapshot().reason());
    }

    static JobExecutor newExecutor(Fakes.Actions actions, Fakes.Bus bus) {
        StorageMemory storage = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        ActionArbiter arbiter = new ActionArbiter();
        long[] ticks = {0};
        Fakes.Driver driver = new Fakes.Driver();
        JobExecutor.Deps deps = new JobExecutor.Deps(arbiter, () -> bus, () -> actions.inv, () -> driver,
                actions, () -> null, () -> storage, (dim, pos) -> {
        }, VanillaRecipes::planner, PositionPredicate.NONE, () -> ticks[0]++, () -> 0L, Runnable::run);
        return new JobExecutor(deps, new JobExecutor.Settings());
    }
}
