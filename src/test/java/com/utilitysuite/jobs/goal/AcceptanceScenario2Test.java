package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.PlanStep;
import com.utilitysuite.jobs.planner.PositionPredicate;
import com.utilitysuite.jobs.planner.StepKind;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance Scenario 2 (live replanning): a pickaxe breaks mid-job, a supply chest turns out empty, and a seed
 * prediction is a verified miss. Only the broken branch is repaired, the reason is logged on the job card, the job
 * resumes, and the retry budget is never exceeded (the job pauses with a diagnostic instead of looping).
 */
class AcceptanceScenario2Test {

    static final String DIAMOND = "minecraft:diamond";

    Fakes.Inventory inv;
    Fakes.Driver driver;
    Fakes.Actions actions;
    Fakes.Knowledge knowledge;
    Fakes.Bus bus;
    StorageMemory storage;
    ActionArbiter arbiter;
    JobExecutor exec;
    final long[] ticks = {0};
    final List<String> staleMarks = new ArrayList<>();
    boolean useKnowledge;

    @BeforeEach
    void setUp() {
        inv = new Fakes.Inventory();
        driver = new Fakes.Driver();
        actions = new Fakes.Actions(inv);
        knowledge = new Fakes.Knowledge();
        bus = new Fakes.Bus();
        storage = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        arbiter = new ActionArbiter();
        JobExecutor.Deps deps = new JobExecutor.Deps(arbiter, () -> bus, () -> inv, () -> driver, actions,
                () -> useKnowledge ? knowledge : null, () -> storage, (dim, pos) -> {
            staleMarks.add(PackedPos.format(pos));
            storage.markStale(dim, pos);
        }, VanillaRecipes::planner, PositionPredicate.NONE, () -> ticks[0], () -> 0L, Runnable::run);
        exec = new JobExecutor(deps, new JobExecutor.Settings());
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            ticks[0]++;
            arbiter.tick();
            exec.tick();
        }
    }

    JobSnapshot snap(long id) {
        return exec.snapshot(id).orElseThrow();
    }

    JobSnapshot lastPosted(long id) {
        List<SuiteEvent.JobChanged> ev = bus.of(SuiteEvent.JobChanged.class);
        for (int i = ev.size() - 1; i >= 0; i--) {
            if (ev.get(i).snapshot().jobId() == id) {
                return ev.get(i).snapshot();
            }
        }
        throw new AssertionError("no JobChanged for job " + id);
    }



    /** Gather-only builds also go through the toolkit, which knows the missing materials only after its diff. */
    @Test
    void gatherOnlyBuildIsHandedToTheToolkit() {
        List<GoalRequest.Build> started = new ArrayList<>();
        exec.setBuildHandler(new BuildHandler() {
            @Override
            public Map<String, Integer> missingMaterials(GoalRequest.Build request) {
                return Map.of();
            }

            @Override
            public void startBuild(long jobId, GoalRequest.Build request) {
                started.add(request);
            }

            @Override
            public JobStatus buildStatus(long jobId) {
                return JobStatus.COMPLETED;
            }

            @Override
            public void cancelBuild(long jobId) {
            }
        });
        long id = exec.submit(new GoalRequest.Build("bricks.nbt", 0L, true, CostProfile.CHEAPEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(10);
        assertEquals(1, started.size(), "startBuild called for the gather-only request");
        assertTrue(started.get(0).gatherOnly());
        assertEquals(JobStatus.COMPLETED, lastPosted(id).status());
    }
    /** The schematic toolkit's Build Assist leases its own channels; the goal job must not hold them meanwhile. */
    @Test
    void buildStepLeavesTheChannelsToTheToolkit() {
        JobStatus[] status = {JobStatus.RUNNING};
        exec.setBuildHandler(new BuildHandler() {
            @Override
            public Map<String, Integer> missingMaterials(GoalRequest.Build request) {
                return Map.of();
            }

            @Override
            public void startBuild(long jobId, GoalRequest.Build request) {
            }

            @Override
            public JobStatus buildStatus(long jobId) {
                return status[0];
            }

            @Override
            public void cancelBuild(long jobId) {
            }
        });
        long id = exec.submit(new GoalRequest.Build("hut.nbt", 0L, false, CostProfile.CHEAPEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(10);
        assertEquals(JobStatus.RUNNING, snap(id).status());
        for (ControlChannel c : ControlChannel.all()) {
            assertFalse(arbiter.isHeldBy(c, JobExecutor.MODULE_ID), "goal job holds " + c + " during the build");
        }
        LeaseListener none = (lease, reason, by) -> {
        };
        assertTrue(arbiter.request(new LeaseRequest("schematic_toolkit", ControlPriority.GOAL, java.util.EnumSet.of(
                ControlChannel.WORLD_INTERACT, ControlChannel.CAMERA, ControlChannel.HOTBAR), 0, none)).isPresent(),
                "Build Assist gets its lease");
        status[0] = JobStatus.COMPLETED;
        tick(5);
        assertEquals(JobStatus.COMPLETED, lastPosted(id).status());
    }
    @Test
    void brokenPickaxeRepairsOnlyTheToolBranchAndResumes() {
        inv.add("minecraft:iron_pickaxe", 1);
        inv.add("minecraft:iron_ingot", 3);
        inv.add("minecraft:stick", 2);
        inv.add("minecraft:crafting_table", 1);
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 10, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(3);
        List<PlanStep> before = exec.steps(id);
        assertEquals(List.of(StepKind.MINE, StepKind.RETURN), before.stream().map(PlanStep::kind).toList());
        assertEquals("mine minecraft:diamond x10", driver.log.get(driver.log.size() - 1));
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID));

        inv.add(DIAMOND, 4);
        tick(1);
        inv.removeAll("minecraft:iron_pickaxe"); // broke
        tick(1);

        List<PlanStep> after = exec.steps(id);
        assertEquals(3, after.size());
        assertEquals(StepKind.CRAFT, after.get(0).kind());
        assertEquals("minecraft:iron_pickaxe", after.get(0).itemId());
        assertSame(before.get(0), after.get(1), "the mining step itself is kept, not re-planned");
        assertSame(before.get(1), after.get(2), "later steps are untouched");
        JobSnapshot s = snap(id);
        assertTrue(s.reason().contains("Pickaxe broke"), s.reason());
        assertEquals(1, s.replanCount());

        tick(4); // craft the replacement, restart mining from the checkpoint
        assertTrue(actions.log.contains("craft minecraft:iron_pickaxe"));
        assertEquals(6, driver.lastDesired, "yield checkpoint kept: 4 of 10 already mined");
        assertEquals(JobStatus.RUNNING, snap(id).status());

        inv.add(DIAMOND, 6);
        tick(2);
        assertEquals(StepKind.RETURN, exec.steps(id).get(exec.stepIndex(id)).kind());
        driver.status = MiningDriver.Status.ARRIVED;
        tick(2);
        assertEquals(JobStatus.COMPLETED, lastPosted(id).status());
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID), "lease released when done");
    }

    @Test
    void emptySupplyChestIsMarkedStaleAndOnlyThatBranchIsReplaced() {
        long chestA = PackedPos.pack(5, 64, 0);
        long chestB = PackedPos.pack(40, 64, 0);
        storage.record("minecraft:overworld", chestA, "minecraft:chest", Map.of("minecraft:iron_ingot", 3));
        storage.record("minecraft:overworld", chestB, "minecraft:chest", Map.of("minecraft:iron_ingot", 3));
        actions.realChests.put(chestA, new java.util.HashMap<>()); // emptied by someone else
        actions.realChests.put(chestB, new java.util.HashMap<>(Map.of("minecraft:iron_ingot", 3)));
        inv.add("minecraft:stick", 2);
        inv.add("minecraft:crafting_table", 1);

        long id = exec.submit(new GoalRequest.Craft("minecraft:iron_pickaxe", 1, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(2);
        List<PlanStep> before = exec.steps(id);
        assertEquals(List.of(StepKind.FETCH, StepKind.CRAFT), before.stream().map(PlanStep::kind).toList());
        assertEquals(chestA, before.get(0).storagePos(), "nearest chest first");

        tick(2); // fetch from A comes back empty
        List<PlanStep> after = exec.steps(id);
        assertEquals(StepKind.FETCH, after.get(0).kind());
        assertEquals(chestB, after.get(0).storagePos(), "replacement branch uses the other chest");
        assertSame(before.get(1), after.get(1), "the craft step is untouched");
        assertEquals(List.of(PackedPos.format(chestA)), staleMarks);
        assertTrue(storage.entries().stream().anyMatch(e -> e.packedPos() == chestA && e.stale()));
        JobSnapshot s = snap(id);
        assertTrue(s.reason().contains("Supply container at 5 64 0 had 0/3"), s.reason());
        assertEquals(1, s.replanCount());

        tick(6);
        assertEquals(JobStatus.COMPLETED, lastPosted(id).status());
        assertEquals(1, inv.count("minecraft:iron_pickaxe"));
    }

    @Test
    void likeAPlayerIgnoresPredictedOreAndMinesWhatItSees() {
        useKnowledge = true;
        long hidden = PackedPos.pack(10, -58, 10);
        knowledge.predict(hidden, "minecraft:deepslate_diamond_ore", 0.9);
        actions.blocks.put(hidden, "minecraft:deepslate_diamond_ore");
        inv.add("minecraft:iron_pickaxe", 1);
        assertEquals(MiningStyle.LIKE_A_PLAYER, exec.settings().miningStyle, "the default");

        exec.submit(new GoalRequest.Gather(DIAMOND, 2, CostProfile.FASTEST, DataSourceMode.HYBRID));
        tick(3);
        assertTrue(driver.log.stream().noneMatch(l -> l.startsWith("goto")), "never walks to hidden ore: " + driver.log);
        assertTrue(driver.log.stream().anyMatch(l -> l.startsWith("mine ")), driver.log.toString());
    }

    @Test
    void likeAPlayerStripMinesAlongTheOreAndMinesWhatTheTunnelUncovers() {
        useKnowledge = true;
        // a vein beside the +x line under the player, inside diamond's band
        long a = PackedPos.pack(4, -58, 1);
        long b = PackedPos.pack(5, -58, 1);
        knowledge.predict(a, "minecraft:deepslate_diamond_ore", 0.9);
        knowledge.predict(b, "minecraft:deepslate_diamond_ore", 0.9);
        actions.blocks.put(a, "minecraft:deepslate_diamond_ore");
        actions.blocks.put(b, "minecraft:deepslate_diamond_ore");
        inv.add("minecraft:iron_pickaxe", 1);

        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 2, CostProfile.FASTEST, DataSourceMode.HYBRID));
        tick(3);
        assertEquals("goto 0 -58 0", driver.log.get(driver.log.size() - 1), "digs down under its feet first");
        assertTrue(snap(id).reason().contains("heading east"), snap(id).reason());
        driver.status = MiningDriver.Status.ARRIVED;
        tick(1);
        assertEquals("clear 1 -58 0 to 4 -57 0", driver.log.get(driver.log.size() - 1), "first tunnel segment");
        driver.status = MiningDriver.Status.ARRIVED;
        tick(1);
        assertEquals("mine minecraft:diamond x2", driver.log.get(driver.log.size() - 1), "mines the ore it uncovered");
        // the wall ore is mined, which uncovers the rest of the vein behind it
        actions.blocks.put(a, "minecraft:air");
        tick(1);
        assertEquals("mine minecraft:diamond x2", driver.log.get(driver.log.size() - 1), "still on the vein");
        actions.blocks.put(b, "minecraft:air");
        tick(1);
        assertEquals("clear 5 -58 0 to 8 -57 0", driver.log.get(driver.log.size() - 1), driver.log.toString());
    }

    @Test
    void verifiedMissFallsThroughToTheNextCandidate() {
        useKnowledge = true;
        exec.settings().miningStyle = MiningStyle.KNOWN_ORES; // predicted targets are a KNOWN_ORES feature
        long miss = PackedPos.pack(10, -58, 10);
        long hit = PackedPos.pack(20, -58, 20);
        knowledge.predict(miss, "minecraft:deepslate_diamond_ore", 0.9);
        knowledge.predict(hit, "minecraft:deepslate_diamond_ore", 0.8);
        actions.blocks.put(miss, "minecraft:deepslate");
        actions.blocks.put(hit, "minecraft:deepslate_diamond_ore");
        inv.add("minecraft:iron_pickaxe", 1);

        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 2, CostProfile.FASTEST, DataSourceMode.HYBRID));
        tick(3);
        assertEquals("goto 10 -58 10", driver.log.get(driver.log.size() - 1));
        tick(1);
        assertEquals(List.of(miss), knowledge.misses);
        // The knowledge service owns the VerifiedMiss event (posted once per new miss); the executor only posts it
        // itself when no knowledge service is registered, so with the fake service it posts nothing.
        assertEquals(0, bus.of(SuiteEvent.VerifiedMiss.class).size());
        assertTrue(driver.log.contains("goto 20 -58 20"));
        JobSnapshot s = snap(id);
        assertTrue(s.reason().contains("was minecraft:deepslate") || s.reason().contains("Heading"), s.reason());
        assertEquals(1, s.replanCount());
        tick(1);
        assertEquals(List.of(hit), knowledge.observed);
        assertEquals("mine minecraft:diamond x2", driver.log.get(driver.log.size() - 1));
        inv.add(DIAMOND, 2);
        tick(1);
        assertEquals(StepKind.RETURN, exec.steps(id).get(exec.stepIndex(id)).kind());
    }

    @Test
    void retryBudgetPausesWithADiagnosticInsteadOfLooping() {
        inv.add("minecraft:iron_pickaxe", 1);
        driver.alwaysFail = "lava on route";
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 5, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(200);
        JobSnapshot s = snap(id);
        assertEquals(JobStatus.PAUSED, s.status());
        assertEquals(3, s.replanCount(), "max 3 replans per step");
        assertTrue(s.reason().contains("replan budget (3) exhausted"), s.reason());
        assertTrue(s.reason().contains("lava on route"), s.reason());
        assertEquals(4, driver.mineCalls, "initial attempt + 3 replans, then no more");
        assertTrue(exec.activeJob().isEmpty(), "paused job steps aside");
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID));

        // user resumes after fixing the route: budget for the step is reset, job continues from its checkpoint
        driver.alwaysFail = null;
        exec.resume(id);
        tick(3);
        assertEquals(JobStatus.RUNNING, snap(id).status());
        assertEquals(5, driver.mineCalls);
    }

    /** Playtest 2026-11-20: a craft that kept failing replanned 35 times because each repair made new step objects. */
    @Test
    void repeatedCraftFailureStillPausesAfterTheReplanBudget() {
        inv.add("minecraft:oak_log", 4);
        actions.craftFails = "Recipe minecraft:oak_planks is not unlocked in your recipe book";
        long id = exec.submit(new GoalRequest.Craft("minecraft:oak_planks", 4, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(300);
        JobSnapshot s = snap(id);
        assertEquals(JobStatus.PAUSED, s.status(), s.reason());
        assertTrue(s.replanCount() <= 3, "replans " + s.replanCount());
        assertTrue(s.reason().contains("replan budget (3) exhausted"), s.reason());
        assertTrue(s.reason().contains("not unlocked"), s.reason());
        assertTrue(actions.craftCalls <= 4, "craft attempts " + actions.craftCalls);
    }

    @Test
    void stuckWithoutProgressTriggersAReplan() {
        inv.add("minecraft:iron_pickaxe", 1);
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 5, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(3);
        tick(300);
        assertEquals(0, snap(id).replanCount());
        tick(2);
        assertEquals(1, snap(id).replanCount());
        assertTrue(snap(id).reason().contains("No progress for 15 s"), snap(id).reason());
    }

    @Test
    void preemptionPausesAndResumesFromTheCheckpoint() {
        inv.add("minecraft:iron_pickaxe", 1);
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 5, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(3);
        inv.add(DIAMOND, 2);
        tick(1);
        int postedBefore = bus.of(SuiteEvent.JobChanged.class).size();
        tick(50);
        assertEquals(postedBefore, bus.of(SuiteEvent.JobChanged.class).size(), "no JobChanged spam while running");

        ControlLease eat = arbiter.request(LeaseRequest.of("survival_autopilot", ControlPriority.SURVIVAL,
                LeaseListener.NONE, ControlChannel.HOTBAR, ControlChannel.WORLD_INTERACT)).orElseThrow();
        assertEquals(JobStatus.PAUSED, snap(id).status());
        assertTrue(snap(id).reason().contains("preempted by survival_autopilot"), snap(id).reason());
        assertTrue(driver.paused);
        tick(5);
        assertEquals(JobStatus.PAUSED, snap(id).status());

        eat.close();
        tick(2);
        assertEquals(JobStatus.RUNNING, snap(id).status());
        assertTrue(driver.log.contains("resume"));
        assertEquals(2, snap(id).progressCurrent(), "progress kept across the preemption");
    }

    @Test
    void stopAllHoldsTheJobUntilTheUserResumes() {
        inv.add("minecraft:iron_pickaxe", 1);
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 5, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(3);
        arbiter.stopAll("test");
        assertEquals(JobStatus.PAUSED, snap(id).status());
        assertTrue(snap(id).reason().contains("Stop-All"));
        arbiter.resumeAutomation();
        tick(20);
        assertEquals(JobStatus.PAUSED, snap(id).status(), "no automatic resume after Stop-All");
        exec.resume(id);
        tick(2);
        assertEquals(JobStatus.RUNNING, snap(id).status());
    }

    @Test
    void buildJobsWaitForTheSchematicToolkit() {
        long id = exec.submit(new GoalRequest.Build("house.litematic", Long.MIN_VALUE, false, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(5);
        JobSnapshot s = snap(id);
        assertEquals(JobStatus.QUEUED, s.status());
        assertEquals("Waiting for schematic toolkit", s.currentStep());
        assertTrue(exec.activeJob().isEmpty());
    }

    /** Mines 1 diamond starting at {@code start}, then sits on the Return/Deposit step at {@code end}. */
    long gatherAndReachReturn(long start, long end) {
        inv.add("minecraft:iron_pickaxe", 1);
        actions.playerPos = start;
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 1, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(3);
        inv.add(DIAMOND, 1);
        actions.playerPos = end;
        tick(2);
        assertEquals(StepKind.RETURN, exec.steps(id).get(exec.stepIndex(id)).kind());
        return id;
    }

    @Test
    void anUnreachableStartNearbyStillFinishesTheJob() {
        // started on a tree top: the way back fails, but the player is right below it
        long id = gatherAndReachReturn(PackedPos.pack(0, 68, 0), PackedPos.pack(3, 64, 1));
        driver.status = MiningDriver.Status.FAILED;
        tick(2);
        JobSnapshot s = lastPosted(id);
        assertEquals(JobStatus.COMPLETED, s.status(), s.reason());
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID));
    }

    @Test
    void aReturnThatNeverGetsThereFinishesInsteadOfPausing() {
        long id = gatherAndReachReturn(PackedPos.pack(0, 64, 0), PackedPos.pack(40, 64, 0));
        JobExecutor.Settings defaults = new JobExecutor.Settings();
        // stuck far away: retries first, then gives up on the courtesy trip, never pauses a met goal
        tick((defaults.stuckTicks + 2) * (defaults.maxReplans + 1) + 10);
        JobSnapshot s = lastPosted(id);
        assertEquals(JobStatus.COMPLETED, s.status(), s.reason());
        assertEquals(defaults.maxReplans, s.replanCount(), "the normal retries happened first");
    }
}
