package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ISharedControl;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SharedGrant;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.utilitysuite.core.api.SharedGrant.KEEP;
import static com.utilitysuite.core.api.SharedGrant.OFF;
import static com.utilitysuite.core.api.SharedGrant.ON;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ISharedControl#beginAssist}: a grant that rides along with a job's MOVEMENT lease. It leaves the lease in
 * place, writes axes, jump and sneak only through the overlay's one apply pass, and ends with the lease.
 */
class SharedAssistGrantTest {

    private static final String JOB = "jobs.goal";
    private static final String ASSIST = "travel.movement";

    private ActionArbiter arbiter;
    private ManualFactsTracker facts;
    private YawCoSteer steer;
    private SharedInputOverlay overlay;
    private final boolean[] screen = {false};
    private SharedInputOverlay.MoveFields f;

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, null);
        facts = new ManualFactsTracker();
        steer = new YawCoSteer();
        overlay = new SharedInputOverlay(arbiter, facts, steer, () -> screen[0], () -> false);
        f = new SharedInputOverlay.MoveFields();
        facts.beginTick();
    }

    private ControlLease jobLease(ControlPriority priority) {
        return arbiter.request(LeaseRequest.of(JOB, priority, null, ControlChannel.MOVEMENT, ControlChannel.CAMERA))
                .orElseThrow();
    }

    /** The job walks forward: W held by automation, nothing else. */
    private void jobInput() {
        f.forwardImpulse = 1f;
        f.leftImpulse = 0f;
        f.up = true;
        f.down = false;
        f.left = false;
        f.right = false;
        f.jumping = false;
        f.shiftKeyDown = false;
    }

    private void nextTick() {
        facts.beginTick();
        overlay.beginTick(screen[0]);
    }

    @Test
    void ridesAlongWithTheJobsLeaseWithoutTakingMovement() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        assertNotNull(a);
        assertTrue(a.isActive());
        assertTrue(job.isActive(), "the job keeps its lease");
        assertSame(job, arbiter.holderOrNull(ControlChannel.MOVEMENT));
        assertTrue(arbiter.isHeldBy(ControlChannel.MOVEMENT, JOB));
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, ASSIST));
        assertEquals(ASSIST, arbiter.assistModuleId());
        assertNull(arbiter.sharedModuleId(), "not the shared grant: MOVEMENT is not counted as the module's");
        // a shared grant at the job's own priority is still refused, as before
        assertNull(overlay.begin("other", ControlPriority.GOAL, false));
        assertTrue(a.isActive());
    }

    @Test
    void writesGoThroughTheOverlaysApply() {
        jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        jobInput();
        a.jump(ON);
        a.axes(KEEP, -1);
        a.sneak(true);
        assertFalse(overlay.apply(f, 0.3f));
        assertTrue(f.jumping);
        assertEquals(1f, f.forwardImpulse, "KEEP leaves the job's forward key");
        assertTrue(f.up);
        assertEquals(-0.3f, f.leftImpulse, 1e-6f, "the sneak multiplier applies to a written axis");
        assertTrue(f.right);
        assertFalse(f.left);
        assertTrue(f.shiftKeyDown);

        jobInput();
        f.jumping = true;
        a.jump(OFF);
        a.axes(0, KEEP);
        overlay.apply(f, 1f);
        assertFalse(f.jumping, "OFF withholds the job's jump for the tick");
        assertEquals(0f, f.forwardImpulse);
        assertFalse(f.up);
    }

    @Test
    void writesLapseAfterOneTick() {
        jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        a.jump(ON);
        jobInput();
        overlay.apply(f, 1f);
        assertTrue(f.jumping);
        jobInput();
        overlay.apply(f, 1f); // applied once, then cleared
        assertFalse(f.jumping);

        a.jump(ON);
        nextTick();
        nextTick(); // two ticks old: stale
        jobInput();
        overlay.apply(f, 1f);
        assertFalse(f.jumping);
    }

    @Test
    void neverSprintNeverYaw() {
        jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        assertFalse(a.canWithholdSprint());
        a.startSprint();
        jobInput();
        assertFalse(overlay.apply(f, 1f), "no sprint start from the assist grant");
        a.withholdSprint();
        jobInput();
        overlay.apply(f, 1f);
        assertFalse(overlay.sprintStartBlocked(), "no sprint withhold from the assist grant");
        a.coSteer(90f, 100.0, 30.0, 1.0);
        assertFalse(steer.isLive(), "no co-steer from the assist grant");
        assertEquals(0.0, a.coSteerAddedDeg());
        assertEquals(0.0, a.opposingYawDeg());
    }

    @Test
    void refusedWithoutAForeignMovementLeaseBelowEmergency() {
        assertNull(overlay.beginAssist(ASSIST), "nobody holds MOVEMENT");
        assertNull(overlay.beginAssist(null));

        arbiter.request(LeaseRequest.of(JOB, ControlPriority.GOAL, null, ControlChannel.CAMERA)).orElseThrow();
        assertNull(overlay.beginAssist(ASSIST), "a lease without MOVEMENT");

        jobLease(ControlPriority.GOAL);
        assertNull(overlay.beginAssist(JOB), "the module's own lease");

        jobLease(ControlPriority.EMERGENCY);
        assertNull(overlay.beginAssist(ASSIST), "an EMERGENCY lease is never assisted");

        jobLease(ControlPriority.GOAL);
        screen[0] = true;
        assertNull(overlay.beginAssist(ASSIST), "a screen is open");
        screen[0] = false;
        assertNotNull(overlay.beginAssist(ASSIST));
        assertNull(overlay.beginAssist("other"), "one assist grant at a time");
    }

    @Test
    void stopAllEndsItAndRefusesANewOne() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        a.jump(ON);
        arbiter.stopAll("test");
        assertFalse(job.isActive());
        assertFalse(a.isActive());
        assertEquals(LeaseEndReason.STOP_ALL, overlay.lastAssistEndReason());
        assertNull(arbiter.assistModuleId());
        jobInput();
        overlay.apply(f, 1f);
        assertFalse(f.jumping, "the pending write is dropped");
        assertNull(overlay.beginAssist(ASSIST), "automation is stopped");
    }

    @Test
    void theUsersMovementKeysEndItWithTheJobsLease() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        a.jump(ON);
        a.axes(1, 1);
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY); // manual input wins: the job's lease ends
        assertFalse(job.isActive());
        assertFalse(a.isActive());
        assertEquals(LeaseEndReason.MANUAL_OVERRIDE, overlay.lastAssistEndReason());
        f.forwardImpulse = 0f;
        f.leftImpulse = -1f;
        f.right = true;
        f.jumping = false;
        overlay.apply(f, 1f);
        assertFalse(f.jumping);
        assertEquals(-1f, f.leftImpulse, "the user's own keys stand");
        assertNull(overlay.beginAssist(ASSIST), "no lease left to ride along with");
    }

    @Test
    void itEndsWhenTheLeaseEndsForAnyReason() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        job.close();
        assertFalse(a.isActive());
        assertEquals(LeaseEndReason.RELEASED, overlay.lastAssistEndReason());

        jobLease(ControlPriority.GOAL);
        a = overlay.beginAssist(ASSIST);
        arbiter.request(LeaseRequest.of("combat", ControlPriority.COMBAT, null, ControlChannel.MOVEMENT))
                .orElseThrow();
        assertFalse(a.isActive(), "bound to the job's lease, not to whoever holds MOVEMENT next");
        assertEquals(LeaseEndReason.PREEMPTED, overlay.lastAssistEndReason());
        arbiter.stopAll("clear");
        arbiter.resumeAutomation();

        jobLease(ControlPriority.GOAL);
        a = overlay.beginAssist(ASSIST);
        jobLease(ControlPriority.GOAL); // the job replaces its own lease: a new lease, a new grant
        assertFalse(a.isActive());
        a = overlay.beginAssist(ASSIST);
        assertNotNull(a);
        assertTrue(a.isActive());

        arbiter.request(LeaseRequest.of(JOB, ControlPriority.GOAL, null, ControlChannel.MOVEMENT).withTimeout(2))
                .orElseThrow();
        a = overlay.beginAssist(ASSIST);
        arbiter.tick();
        assertTrue(a.isActive());
        arbiter.tick();
        assertFalse(a.isActive());
        assertEquals(LeaseEndReason.TIMED_OUT, overlay.lastAssistEndReason());

        jobLease(ControlPriority.GOAL);
        a = overlay.beginAssist(ASSIST);
        arbiter.resetForWorldUnload();
        assertFalse(a.isActive());
        assertNull(arbiter.assistModuleId());
    }

    @Test
    void aSharedGrantThatOutranksTheJobEndsIt() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        SharedGrant g = overlay.begin(ASSIST, ControlPriority.TRAVEL, false);
        assertNotNull(g);
        assertFalse(job.isActive());
        assertFalse(a.isActive(), "the two grants never write in the same tick");
        assertTrue(g.isActive());
    }

    @Test
    void aScreenEndsIt() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        a.jump(ON);
        overlay.onScreenOpening();
        assertFalse(a.isActive());
        assertTrue(job.isActive(), "the job's lease is not the overlay's to end");
        assertNull(arbiter.assistModuleId());
        jobInput();
        overlay.apply(f, 1f);
        assertFalse(f.jumping);

        a = overlay.beginAssist(ASSIST);
        assertNotNull(a);
        screen[0] = true;
        nextTick(); // the per-tick check
        assertFalse(a.isActive());
    }

    @Test
    void endIsIdempotentAndLeavesTheLease() {
        ControlLease job = jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        a.end();
        a.end();
        assertFalse(a.isActive());
        assertEquals(LeaseEndReason.RELEASED, overlay.lastAssistEndReason());
        assertTrue(job.isActive());
        assertNull(arbiter.assistModuleId());
        a.jump(ON); // ignored on an ended grant
        jobInput();
        overlay.apply(f, 1f);
        assertFalse(f.jumping);
    }

    @Test
    void beginningAgainWhileLiveKeepsThePendingWrites() {
        jobLease(ControlPriority.GOAL);
        SharedGrant a = overlay.beginAssist(ASSIST);
        a.jump(ON);
        assertSame(a, overlay.beginAssist(ASSIST));
        jobInput();
        overlay.apply(f, 1f);
        assertTrue(f.jumping);
    }

    @Test
    void theDefaultContractRefuses() {
        assertNull(ISharedControl.NONE.beginAssist(ASSIST));
    }

    @Test
    void beginWritesAndApplyAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        jobLease(ControlPriority.GOAL);
        int[] i = {0};
        long[] sink = {0};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int k = i[0]++;
            SharedGrant a = overlay.beginAssist(ASSIST);
            if (a == null) {
                return;
            }
            a.axes((k & 1) == 0 ? KEEP : 1, (k % 3) - 1);
            a.jump((k & 3) == 0 ? ON : KEEP);
            a.sneak((k & 7) == 0);
            jobInput();
            overlay.apply(f, 1f);
            sink[0] += f.jumping ? 1 : 0;
            if ((k & 63) == 0) {
                a.end(); // the module's own end and a fresh begin are on the per-tick path too
            }
            facts.beginTick();
            overlay.beginTick(false);
        });
        assertTrue(sink[0] > 0);
        assertEquals(0L, bytes, "per-tick assist grant path must not allocate");
    }
}
