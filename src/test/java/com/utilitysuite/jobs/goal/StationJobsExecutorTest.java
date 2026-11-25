package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.jobs.planner.BrewingPlanner;
import com.utilitysuite.jobs.planner.FurnaceBatchPlanner;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.PlanContext;
import com.utilitysuite.jobs.planner.PositionPredicate;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 3 wiring: station goals through the executor, station/ender memory, parser verbs. */
class StationJobsExecutorTest {

    static final String DIM = "minecraft:overworld";
    static final String IRON = "minecraft:raw_iron";

    Fakes.Inventory inv;
    Fakes.Actions actions;
    ActionArbiter arbiter;
    Fakes.Bus bus;
    JobExecutor exec;
    StorageMemory storage;
    final long[] ticks = {0};

    @BeforeEach
    void setUp() {
        inv = new Fakes.Inventory();
        actions = new Fakes.Actions(inv);
        bus = new Fakes.Bus();
        storage = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        arbiter = new ActionArbiter();
        Fakes.Driver driver = new Fakes.Driver();
        JobExecutor.Deps deps = new JobExecutor.Deps(arbiter, () -> bus, () -> inv, () -> driver, actions,
                () -> null, () -> storage, storage::markStale, VanillaRecipes::planner, PositionPredicate.NONE,
                () -> ticks[0], () -> 0L, Runnable::run);
        exec = new JobExecutor(deps, new JobExecutor.Settings());
        exec.setStationSource(storage);
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            ticks[0]++;
            arbiter.tick();
            exec.tick();
        }
    }

    JobStatus status(long id) {
        var ev = bus.of(com.utilitysuite.core.api.SuiteEvent.JobChanged.class);
        for (int i = ev.size() - 1; i >= 0; i--) {
            if (ev.get(i).snapshot().jobId() == id) {
                return ev.get(i).snapshot().status();
            }
        }
        throw new AssertionError("no event");
    }

    @Test
    void stationGoalsAreOffByDefault() {
        long id = exec.submit(new GoalRequest.SmeltArray(IRON, 64, null, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(5);
        assertEquals(JobStatus.FAILED, status(id));
        assertTrue(actions.log.isEmpty());
    }

    @Test
    void furnaceArrayLoadsEachRememberedFurnaceThenCollects() {
        exec.settings().stationJobs = true;
        for (int x = 1; x <= 3; x++) {
            storage.rememberStation(DIM, PackedPos.pack(x, 64, 0), FurnaceBatchPlanner.FURNACE);
        }
        storage.rememberStation(DIM, PackedPos.pack(500, 64, 0), FurnaceBatchPlanner.FURNACE); // out of range
        inv.add(IRON, 96);
        inv.add("minecraft:coal", 20);
        long id = exec.submit(new GoalRequest.SmeltArray(IRON, 96, null, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(40);
        assertEquals(3, actions.log.stream().filter(s -> s.startsWith("station FURNACE_LOAD 32 " + IRON)).count(),
                actions.log.toString());
        assertEquals(3, actions.log.stream().filter(s -> s.startsWith("station FURNACE_COLLECT 32")).count());
        assertEquals(JobStatus.COMPLETED, status(id));
    }

    @Test
    void brewGoalFetchesFromStorageAndBrews() {
        exec.settings().stationJobs = true;
        storage.rememberStation(DIM, PackedPos.pack(2, 64, 2), "minecraft:brewing_stand");
        long chest = PackedPos.pack(4, 64, 4);
        storage.record(DIM, chest, "minecraft:chest", Map.of("minecraft:gunpowder", 8));
        actions.realChests.put(chest, new java.util.HashMap<>(Map.of("minecraft:gunpowder", 8)));
        inv.add(BrewingPlanner.WATER, 3);
        inv.add("minecraft:nether_wart", 1);
        inv.add("minecraft:glistering_melon_slice", 1);
        inv.add("minecraft:glowstone_dust", 1);
        inv.add("minecraft:blaze_powder", 1);
        long id = exec.submit(new GoalRequest.Brew("strong_healing", "SPLASH", 3, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(40);
        assertTrue(actions.log.stream().anyMatch(s -> s.startsWith("fetch") && s.contains("gunpowder")),
                actions.log.toString());
        assertTrue(actions.log.contains("station BREW 3 minecraft:splash_potion#minecraft:strong_healing"),
                actions.log.toString());
        assertFalse(actions.log.stream().anyMatch(s -> s.contains("FILL_BOTTLES")));
        assertEquals(JobStatus.COMPLETED, status(id));
    }

    @Test
    void enderChestMirrorIsOneSharedSourceWhenEnabled() {
        long a = PackedPos.pack(5, 64, 0);
        long b = PackedPos.pack(9, 64, 0);
        storage.record(DIM, a, StorageMemory.ENDER_CHEST, Map.of("minecraft:ender_pearl", 4),
                List.of(new NestedContainer("minecraft:shulker_box", Map.of("minecraft:ender_pearl", 16))));
        storage.record(DIM, b, StorageMemory.ENDER_CHEST, Map.of("minecraft:ender_pearl", 4),
                List.of(new NestedContainer("minecraft:shulker_box", Map.of("minecraft:ender_pearl", 16))));
        assertEquals(0, storage.count("minecraft:ender_pearl", false)); // off by default
        storage.setEnderAsSource(true);
        assertEquals(20, storage.count("minecraft:ender_pearl", false)); // counted once, not per chest
        assertEquals(20, storage.enderMirror().snapshot().count("minecraft:ender_pearl"));
        PlanContext ctx = PlanContext.capture(inv, storage, DIM, PackedPos.pack(0, 64, 0),
                CostProfile.FASTEST, Map.of(), Set.of(), Set.of(), false);
        assertEquals(1, ctx.storage().size());
        assertEquals(a, ctx.storage().get(0).packedPos()); // nearest ender chest only
        storage.markStale(DIM, a); // a short fetch: the mirror is wrong at every chest
        assertTrue(storage.entries().stream().allMatch(e -> e.stale()));

        StorageMemory copy = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        storage.rememberStation(DIM, PackedPos.pack(1, 64, 1), "minecraft:brewing_stand");
        copy.loadJson(storage.toJson());
        assertEquals(20, copy.enderMirror().snapshot().count("minecraft:ender_pearl"));
        assertEquals(1, copy.stations(DIM, PackedPos.pack(0, 64, 0), 16, Set.of("minecraft:brewing_stand")).size());
    }

    @Test
    void parserBuildsStationGoals() {
        ItemNameMatcher m = new ItemNameMatcher(List.of(IRON, "minecraft:iron_ingot"));
        GoalParser p = new GoalParser(() -> m, n -> Optional.empty(), () -> DataSourceMode.OBSERVED_ONLY,
                () -> 0L, () -> DIM);
        ParseResult r = p.parse("furnaces 192 raw iron");
        var s = assertInstanceOf(GoalRequest.SmeltArray.class, r.request(), r.error());
        assertEquals(192, s.count());
        assertEquals(IRON, s.itemId());
        var blast = assertInstanceOf(GoalRequest.SmeltArray.class, p.parse("blast 64 raw iron").request());
        assertEquals("minecraft:blast_furnace", blast.station());
        var b = assertInstanceOf(GoalRequest.Brew.class, p.parse("brew 3 splash strong_healing").request());
        assertEquals("SPLASH", b.form());
        assertEquals(3, b.count());
        var b2 = assertInstanceOf(GoalRequest.Brew.class, p.parse("brew 2 healing ii").request());
        assertEquals("strong_healing", b2.potion());
        assertFalse(p.parse("brew 1 strong fire").isOk());
    }
}
