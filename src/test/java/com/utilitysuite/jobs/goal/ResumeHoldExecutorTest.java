package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IDangerModel;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.PositionPredicate;
import com.utilitysuite.jobs.planner.StepKind;
import com.utilitysuite.jobs.planner.VanillaRecipes;
import com.utilitysuite.jobs.storage.StorageMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roadmap 4.1: after an EMERGENCY preemption the executor resumes only once explosion and lava hazards near the
 * player or the next step have settled, pauses with a notice when one outlasts the wait, and never holds the user's
 * own resume or Stop-All.
 */
class ResumeHoldExecutorTest {

    static final String DIAMOND = "minecraft:diamond";
    static final int SETTLE = ResumeHoldGate.DEFAULT_SETTLE_TICKS;
    static final int MAX = ResumeHoldGate.DEFAULT_MAX_HOLD_TICKS;

    /** Danger model fake: the test sets which kinds are live. */
    static final class Danger implements IDangerModel {
        final EnumSet<HazardKind> live = EnumSet.noneOf(HazardKind.class);

        @Override
        public void report(HazardKind kind, int ticksToImpact, float expectedDamage, float bearingYawDeg) {
            live.add(kind);
        }

        @Override
        public void clear(HazardKind kind) {
            live.remove(kind);
        }

        @Override
        public boolean any() {
            return !live.isEmpty();
        }

        @Override
        public boolean has(HazardKind kind) {
            return live.contains(kind);
        }

        @Override
        public int ticksToImpact(HazardKind kind) {
            return has(kind) ? 10 : Integer.MAX_VALUE;
        }

        @Override
        public float expectedDamage(HazardKind kind) {
            return has(kind) ? 8f : 0f;
        }

        @Override
        public float bearingYawDeg(HazardKind kind) {
            return NO_BEARING;
        }

        @Override
        public HazardKind worst() {
            return live.isEmpty() ? null : live.iterator().next();
        }
    }

    Fakes.Inventory inv;
    Fakes.Driver driver;
    Fakes.Actions actions;
    Fakes.Bus bus;
    StorageMemory storage;
    ActionArbiter arbiter;
    JobExecutor exec;
    Danger danger;
    final long[] ticks = {0};

    @BeforeEach
    void setUp() {
        inv = new Fakes.Inventory();
        driver = new Fakes.Driver();
        actions = new Fakes.Actions(inv);
        bus = new Fakes.Bus();
        storage = new StorageMemory(() -> 1000L, Long.MAX_VALUE / 4);
        arbiter = new ActionArbiter();
        danger = new Danger();
        Services.registerAutopilot(new IAutopilot() {
            @Override
            public boolean isOn() {
                return true;
            }

            @Override
            public boolean allows(Reflex reflex) {
                return true;
            }

            @Override
            public Reach reach() {
                return Reach.DANGER_ONLY;
            }

            @Override
            public IDangerModel danger() {
                return danger;
            }
        });
        JobExecutor.Deps deps = new JobExecutor.Deps(arbiter, () -> bus, () -> inv, () -> driver, actions,
                () -> null, () -> storage, storage::markStale, VanillaRecipes::planner, PositionPredicate.NONE,
                () -> ticks[0], () -> 0L, Runnable::run);
        exec = new JobExecutor(deps, new JobExecutor.Settings());
    }

    @AfterEach
    void tearDown() {
        Services.registerAutopilot(null);
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

    /** A running mine job (no fixed next-step position: only hazards near the player matter). */
    long startMining() {
        inv.add("minecraft:iron_pickaxe", 1);
        long id = exec.submit(new GoalRequest.Gather(DIAMOND, 10, CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        tick(3);
        assertEquals(JobStatus.RUNNING, snap(id).status());
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID));
        return id;
    }

    /** A reflex takes MOVEMENT at EMERGENCY priority (the job is preempted). */
    ControlLease escape() {
        return arbiter.request(new LeaseRequest("reflex", ControlPriority.EMERGENCY,
                Set.of(ControlChannel.MOVEMENT, ControlChannel.CAMERA), 0, null)).orElseThrow();
    }

    @Test
    void resumeWaitsForTheExplosionToClearAndSettle() {
        long id = startMining();
        danger.live.add(HazardKind.EXPLOSION);
        ControlLease reflex = escape();
        assertEquals(JobStatus.PAUSED, snap(id).status());

        tick(15); // the reflex is still backing off
        arbiter.release(reflex);
        tick(25);
        assertEquals(JobStatus.PAUSED, snap(id).status());
        assertEquals("Waiting for hazard to clear", snap(id).reason());
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID), "no lease while it waits");

        danger.live.clear();
        tick(SETTLE - 1);
        assertEquals(JobStatus.PAUSED, snap(id).status(), "still settling");
        tick(3);
        assertEquals(JobStatus.RUNNING, snap(id).status());
        assertTrue(driver.log.contains("resume"));
        assertEquals(0, actions.hazardQueries, "a mine step without a fixed target needs no world search");
    }

    @Test
    void lavaHoldsTheResumeToo() {
        long id = startMining();
        danger.live.add(HazardKind.LAVA);
        ControlLease reflex = escape();
        arbiter.release(reflex);
        tick(SETTLE + 20);
        assertEquals(JobStatus.PAUSED, snap(id).status());
        assertEquals("Waiting for hazard to clear", snap(id).reason());
        danger.live.clear();
        tick(SETTLE + 3);
        assertEquals(JobStatus.RUNNING, snap(id).status());
    }

    @Test
    void escapeWithoutAHazardResumesAfterTheSettleWindow() {
        long id = startMining();
        ControlLease reflex = escape(); // fall clutch: nothing explosive, no lava
        arbiter.release(reflex);
        tick(SETTLE - 2);
        assertEquals(JobStatus.PAUSED, snap(id).status());
        tick(6);
        assertEquals(JobStatus.RUNNING, snap(id).status());
    }

    @Test
    void hazardOutlastingTheMaxWaitPausesWithANoticeAndStaysPaused() {
        long id = startMining();
        danger.live.add(HazardKind.EXPLOSION);
        ControlLease reflex = escape();
        arbiter.release(reflex);
        tick(MAX - 5);
        assertEquals("Waiting for hazard to clear", snap(id).reason());
        tick(10);
        JobSnapshot s = snap(id);
        assertEquals(JobStatus.PAUSED, s.status());
        assertTrue(s.reason().contains("explosion hazard is still close"), s.reason());
        assertTrue(s.reason().contains("resume from the job card"), s.reason());
        assertEquals(1, bus.of(SuiteEvent.Diagnostic.class).size(), "one notice, not one per tick");
        assertTrue(bus.of(SuiteEvent.Diagnostic.class).get(0).message().contains("still close"));

        danger.live.clear();
        int resumesBefore = count(driver.log, "mine ");
        tick(SETTLE * 4);
        assertEquals(JobStatus.PAUSED, snap(id).status(), "does not walk back in on its own, even once clear");
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID));
        assertEquals(resumesBefore, count(driver.log, "mine "));

        exec.resume(id);
        tick(3);
        assertEquals(JobStatus.RUNNING, snap(id).status(), "the user's resume restarts it");
    }

    @Test
    void usersOwnResumeIsNotHeldByALiveHazard() {
        long id = startMining();
        danger.live.add(HazardKind.EXPLOSION);
        ControlLease reflex = escape();
        arbiter.release(reflex);
        tick(10);
        assertEquals("Waiting for hazard to clear", snap(id).reason());
        exec.resume(id);
        tick(2);
        assertEquals(JobStatus.RUNNING, snap(id).status());
    }

    @Test
    void stopAllDuringTheHoldPausesAndTheUserResumeIsImmediate() {
        long id = startMining();
        danger.live.add(HazardKind.EXPLOSION);
        ControlLease reflex = escape();
        arbiter.release(reflex);
        tick(10);
        exec.haltAll("Stopped by Stop-All; resume from the job card");
        tick(MAX + SETTLE);
        JobSnapshot s = snap(id);
        assertEquals(JobStatus.PAUSED, s.status());
        assertTrue(s.reason().startsWith("Stopped by Stop-All"), s.reason());
        assertTrue(bus.of(SuiteEvent.Diagnostic.class).isEmpty(), "Stop-All ends the hold: no later hazard notice");
        exec.resume(id);
        tick(3);
        assertEquals(JobStatus.RUNNING, snap(id).status(), "hazard still live, the user's resume wins");
    }

    /** A craft job whose first step fetches iron from a chest five blocks away. */
    long startFetch(long chest) {
        storage.record("minecraft:overworld", chest, "minecraft:chest", Map.of("minecraft:iron_ingot", 3));
        actions.realChests.put(chest, new java.util.HashMap<>(Map.of("minecraft:iron_ingot", 3)));
        inv.add("minecraft:stick", 2);
        inv.add("minecraft:crafting_table", 1);
        long id = exec.submit(new GoalRequest.Craft("minecraft:iron_pickaxe", 1, CostProfile.FASTEST,
                DataSourceMode.OBSERVED_ONLY));
        tick(3);
        assertEquals(StepKind.FETCH, exec.steps(id).get(exec.stepIndex(id)).kind());
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, JobExecutor.MODULE_ID), "the fetch is under way");
        actions.log.clear();
        return id;
    }

    @Test
    void fuseNearTheNextStepHoldsAfterThePlayerBackedOutOfRange() {
        long chest = PackedPos.pack(5, 64, 0);
        long id = startFetch(chest);
        danger.live.add(HazardKind.EXPLOSION);
        ControlLease reflex = escape();
        danger.live.clear(); // backed off: nothing threatens the player any more
        actions.hazards = ResumeHoldGate.EXPLOSION | ResumeHoldGate.LAVA; // but the fuse burns by the chest
        arbiter.release(reflex);
        tick(SETTLE + 20);
        assertEquals(JobStatus.PAUSED, snap(id).status());
        assertEquals("Waiting for hazard to clear", snap(id).reason());
        assertEquals(chest, actions.lastHazardPos);
        assertTrue(actions.hazardQueries <= (SETTLE + 20) / JobExecutor.STEP_PROBE_INTERVAL + 1,
                "searched every few ticks, not every tick: " + actions.hazardQueries);

        actions.hazards = ResumeHoldGate.LAVA; // the fuse is gone; lava by the chest was never this escape's danger
        tick(SETTLE + JobExecutor.STEP_PROBE_INTERVAL + 3);
        assertTrue(resumed(id), "still held");
        assertTrue(bus.of(SuiteEvent.Diagnostic.class).isEmpty());
    }

    @Test
    void unrelatedOrDistantStepHazardDoesNotHold() {
        // unrelated: a fall-clutch escape, lava and a fuse by the chest
        long id = startFetch(PackedPos.pack(5, 64, 0));
        actions.hazards = ResumeHoldGate.EXPLOSION | ResumeHoldGate.LAVA;
        ControlLease reflex = escape();
        arbiter.release(reflex);
        tick(SETTLE + 5);
        assertTrue(resumed(id), "still held");
        assertEquals(0, actions.hazardQueries, "nothing to look for when the escape saw no explosion or lava");
    }

    @Test
    void distantNextStepIsNotSearched() {
        long id = startFetch(PackedPos.pack(JobExecutor.STEP_WATCH_RANGE + 20, 64, 0));
        danger.live.add(HazardKind.EXPLOSION);
        ControlLease reflex = escape();
        danger.live.clear();
        actions.hazards = ResumeHoldGate.EXPLOSION;
        arbiter.release(reflex);
        tick(SETTLE + 5);
        assertTrue(resumed(id), "still held");
        assertEquals(0, actions.hazardQueries);
    }

    /** The job went back to work: it fetched from the chest (it may already have finished and left the queue). */
    private boolean resumed(long id) {
        boolean fetched = count(actions.log, "fetch ") > 0;
        return fetched && exec.snapshot(id).map(s -> s.status() != JobStatus.PAUSED).orElse(true);
    }

    private static int count(java.util.List<String> log, String prefix) {
        int n = 0;
        for (String s : log) {
            if (s.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }
}
