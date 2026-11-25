package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.PlanPreview;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.jobs.planner.HallJobSource;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.Plan;
import com.utilitysuite.jobs.planner.PositionPredicate;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.storage.StorageMemory;
import com.utilitysuite.jobs.storage.hall.HallSortPlanner;
import com.utilitysuite.jobs.storage.hall.HallSortSteps;
import com.utilitysuite.jobs.storage.hall.StorageCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "sort hall" as a goal-engine job: parser, queue/preview, step flow, retries, pause/resume and Stop-All. */
class HallSortJobTest {

    static final String DIM = "minecraft:overworld";
    static final long WOOD = PackedPos.pack(0, 64, 0);
    static final long STONE = PackedPos.pack(2, 64, 0);
    static final long ORES = PackedPos.pack(4, 64, 0);

    Fakes.Inventory inv;
    Fakes.Actions actions;
    ActionArbiter arbiter;
    Fakes.Bus bus;
    JobExecutor exec;
    final long[] ticks = {0};
    HallJobSource.Result next;
    int planned;

    @BeforeEach
    void setUp() {
        inv = new Fakes.Inventory();
        actions = new Fakes.Actions(inv);
        bus = new Fakes.Bus();
        StorageMemory storage = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        arbiter = new ActionArbiter();
        Fakes.Driver driver = new Fakes.Driver();
        JobExecutor.Deps deps = new JobExecutor.Deps(arbiter, () -> bus, () -> inv, () -> driver, actions,
                () -> null, () -> storage, storage::markStale, VanillaRecipes::planner, PositionPredicate.NONE,
                () -> ticks[0], () -> 0L, Runnable::run);
        exec = new JobExecutor(deps, new JobExecutor.Settings());
        next = new HallJobSource.Result(hallPlan(), null);
        exec.setHallSource((dim, from, forRun) -> {
            planned++;
            return next;
        });
    }

    /** Wood chest holding cobblestone and raw iron, plus two oak log partials to merge. */
    static Plan hallPlan() {
        HallSortPlanner.Chest wood = new HallSortPlanner.Chest(WOOD, StorageCategories.WOOD, 27)
                .put(0, "minecraft:oak_log", "", 30, 64)
                .put(1, "minecraft:cobblestone", "", 20, 64)
                .put(2, "minecraft:raw_iron", "", 10, 64)
                .put(3, "minecraft:oak_log", "", 10, 64);
        HallSortPlanner.Chest stone = new HallSortPlanner.Chest(STONE, StorageCategories.STONE, 27);
        HallSortPlanner.Chest ores = new HallSortPlanner.Chest(ORES, StorageCategories.ORES, 27);
        List<HallSortPlanner.Chest> chests = List.of(wood, stone, ores);
        return HallSortSteps.build(chests, new HallSortPlanner().plan(chests, 0.9), DIM);
    }

    static GoalRequest sort() {
        return new GoalRequest.SortHall(CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY);
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            ticks[0]++;
            arbiter.tick();
            exec.tick();
        }
    }

    JobSnapshot last(long id) {
        var ev = bus.of(SuiteEvent.JobChanged.class);
        for (int i = ev.size() - 1; i >= 0; i--) {
            if (ev.get(i).snapshot().jobId() == id) {
                return ev.get(i).snapshot();
            }
        }
        throw new AssertionError("no event");
    }

    List<String> hallLog() {
        return actions.log.stream().filter(s -> s.startsWith("hall")).toList();
    }

    @Test
    void parserAcceptsSortHallAndSortStorage() {
        GoalParser p = new GoalParser(() -> null, null, () -> DataSourceMode.OBSERVED_ONLY, () -> 0L, () -> DIM);
        for (String cmd : List.of("sort hall", "Sort Storage", "sort the hall", "tidy storage", "sort hall cost=safest")) {
            assertInstanceOf(GoalRequest.SortHall.class, p.parse(cmd).request(), cmd);
        }
        assertEquals(CostProfile.SAFEST, p.parse("sort hall cost=safest").request().cost());
        assertFalse(p.parse("sort diamonds").isOk());
        assertTrue(p.parse("sort").suggestions().contains("sort hall"));
        assertEquals("Sort storage hall", sort().label());
    }

    @Test
    void sortHallWalksTakeThenPutsAndMergesThenCompletesWithASummary() {
        long id = exec.submit(sort());
        assertFalse(exec.planSteps(id).isEmpty(), "Show Plan works while queued");
        tick(20);
        assertEquals(List.of(
                "hall HALL_TAKE 0 64 0 30 +merge",
                "hall HALL_PUT 2 64 0 20",
                "hall HALL_PUT 4 64 0 10"), hallLog());
        JobSnapshot s = last(id);
        assertEquals(JobStatus.COMPLETED, s.status());
        assertTrue(s.reason().startsWith("Hall sorted: 30 items put away"), s.reason());
        assertTrue(exec.activeJob().isEmpty());
    }

    @Test
    void anUnreachableChestIsRetriedThenPausesOnTheBudget() {
        actions.hallFails = "Could not reach container";
        actions.hallFailTimes = 1;
        long id = exec.submit(sort());
        tick(20);
        assertEquals(2, hallLog().stream().filter(l -> l.startsWith("hall HALL_TAKE")).count(), hallLog().toString());
        assertEquals(JobStatus.COMPLETED, last(id).status());

        actions.log.clear();
        actions.hallFailTimes = 100;
        long again = exec.submit(sort());
        tick(40);
        JobSnapshot s = last(again);
        assertEquals(JobStatus.PAUSED, s.status());
        assertTrue(s.reason().contains("replan budget"), s.reason());
        assertTrue(s.reason().contains("Could not reach container"), s.reason());
    }

    @Test
    void pauseResumeAndStopAllContinueFromTheCurrentStep() {
        long id = exec.submit(sort());
        tick(3);
        assertEquals(1, hallLog().size());
        actions.hallHold = true; // the next step stays in progress
        actions.pending = com.utilitysuite.jobs.goal.WorldActions.Result.DONE;
        tick(2);
        int idx = exec.stepIndex(id);
        exec.pause(id);
        assertEquals(JobStatus.PAUSED, last(id).status());
        tick(5);
        exec.resume(id);
        actions.hallHold = false;
        tick(20);
        assertEquals(JobStatus.COMPLETED, last(id).status(), last(id).reason());
        assertTrue(idx >= 1);
        // the interrupted put was started again, not skipped: every put ran to DONE once
        assertTrue(actions.hallCarried.values().stream().allMatch(v -> v == 0), actions.hallCarried.toString());

        actions.log.clear();
        actions.hallHold = true;
        long stop = exec.submit(sort());
        tick(5);
        exec.haltAll("Stopped by Stop-All; resume from the job card");
        assertEquals(JobStatus.PAUSED, last(stop).status());
        assertTrue(actions.log.contains("abort"));
        exec.resume(stop);
        actions.hallHold = false;
        tick(20);
        assertEquals(JobStatus.COMPLETED, last(stop).status());
    }

    @Test
    void anErrorFromTheSorterFailsTheJobAndAnEmptyPlanCompletesAsSorted() {
        next = HallJobSource.Result.error("Hall Sorter is off (Jobs tab)");
        long off = exec.submit(sort());
        tick(3);
        assertEquals(JobStatus.FAILED, last(off).status());
        assertEquals("Hall Sorter is off (Jobs tab)", last(off).reason());

        next = new HallJobSource.Result(Plan.EMPTY, null);
        long tidy = exec.submit(sort());
        tick(3);
        assertEquals(JobStatus.COMPLETED, last(tidy).status());
        assertEquals("Hall already sorted", last(tidy).reason());
        assertTrue(hallLog().isEmpty());
    }

    @Test
    void noSorterRegisteredFailsCleanly() {
        exec.setHallSource(null);
        long id = exec.submit(sort());
        tick(3);
        assertEquals(JobStatus.FAILED, last(id).status());
        assertTrue(last(id).reason().contains("not loaded"));
    }

    @Test
    void previewListsTheHallStepsOrWhyItCannotRun() {
        PlanPreview p = GoalEngine.hallPreview(sort(), next);
        assertEquals(3, p.steps().size());
        assertTrue(p.steps().get(0).contains("Take"), p.steps().get(0));
        assertTrue(p.estimatedSeconds() > 0);
        PlanPreview err = GoalEngine.hallPreview(sort(), HallJobSource.Result.error("Open each hall chest once"));
        assertTrue(err.steps().isEmpty());
        assertEquals(List.of("Open each hall chest once"), err.warnings());
        assertEquals(Map.of(), err.missingMaterials());
    }
}
