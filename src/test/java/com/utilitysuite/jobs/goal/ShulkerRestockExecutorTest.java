package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.jobs.planner.PositionPredicate;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SHULKER_RESTOCK execution against fakes: unpack, several boxes, and a safe abort when no spot exists. */
class ShulkerRestockExecutorTest {

    static final String BOX = "minecraft:shulker_box";
    static final String LOG = "minecraft:oak_log";

    Fakes.Inventory inv;
    Fakes.Driver driver;
    Fakes.Actions actions;
    ActionArbiter arbiter;
    Fakes.Bus bus;
    JobExecutor exec;
    final long[] ticks = {0};

    @BeforeEach
    void setUp() {
        inv = new Fakes.Inventory();
        driver = new Fakes.Driver();
        actions = new Fakes.Actions(inv);
        bus = new Fakes.Bus();
        StorageMemory storage = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        arbiter = new ActionArbiter();
        JobExecutor.Deps deps = new JobExecutor.Deps(arbiter, () -> bus, () -> inv, () -> driver, actions,
                () -> null, () -> storage, storage::markStale, VanillaRecipes::planner, PositionPredicate.NONE,
                () -> ticks[0], () -> 0L, Runnable::run);
        exec = new JobExecutor(deps, new JobExecutor.Settings());
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            ticks[0]++;
            arbiter.tick();
            exec.tick();
        }
    }

    com.utilitysuite.core.api.JobSnapshot last(long id) {
        java.util.List<com.utilitysuite.core.api.SuiteEvent.JobChanged> ev =
                bus.of(com.utilitysuite.core.api.SuiteEvent.JobChanged.class);
        for (int i = ev.size() - 1; i >= 0; i--) {
            if (ev.get(i).snapshot().jobId() == id) {
                return ev.get(i).snapshot();
            }
        }
        throw new AssertionError("no JobChanged for job " + id);
    }

    long gatherLogs(int n) {
        return exec.submit(new GoalRequest.Gather(LOG, n, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
    }

    @Test
    void unpacksTheCarriedBoxAndCompletes() {
        inv.addBox(BOX, Map.of(LOG, 10));
        long id = gatherLogs(6);
        tick(20);
        assertTrue(actions.log.contains("unpack " + BOX + " 6 " + LOG), actions.log.toString());
        assertEquals(6, inv.count(LOG));
        assertEquals(4, inv.boxes.get(0).getValue().get(LOG));
        assertEquals(JobStatus.COMPLETED, last(id).status());
        assertTrue(driver.log.isEmpty() || driver.log.stream().noneMatch(s -> s.startsWith("mine")),
                "no gathering when the box had enough: " + driver.log);
    }

    @Test
    void unpacksSeveralBoxesInOneStep() {
        inv.addBox(BOX, Map.of(LOG, 3));
        inv.addBox(BOX, Map.of(LOG, 4));
        long id = gatherLogs(7);
        tick(20);
        assertEquals(7, inv.count(LOG), actions.log.toString());
        assertEquals(JobStatus.COMPLETED, last(id).status());
    }

    @Test
    void noPlacementSpotReplansFromOtherSources() {
        inv.addBox(BOX, Map.of(LOG, 10));
        actions.restockFails = "No safe spot next to the player to place the shulker box";
        long id = gatherLogs(4);
        tick(20);
        assertTrue(actions.log.stream().anyMatch(s -> s.startsWith("unpack")), actions.log.toString());
        assertEquals(10, inv.boxes.get(0).getValue().get(LOG), "nothing taken from the box");
        String reason = last(id).reason();
        assertTrue(reason.contains("No safe spot") || !driver.log.isEmpty(),
                "the failure is reported and the job looks for logs elsewhere: " + reason + " " + driver.log);
    }
}
