package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.SharedGrant;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.utilitysuite.core.api.SharedGrant.KEEP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SharedGrant.withholdSprint}: one tick of "no sprint" (a running sprint ends in the event, the mixin keeps
 * {@code aiStep} from starting one), winning over {@code startSprint}, lapsing by itself, never while the grant is
 * inactive, cleared by end, Stop-All and a screen. The mixin's condition is {@link SharedInputOverlay#isSprintStartBlocked}.
 */
class SharedSprintWithholdTest {

    private ActionArbiter arbiter;
    private ManualFactsTracker facts;
    private YawCoSteer steer;
    private SharedInputOverlay overlay;
    private SharedInputOverlay.MoveFields f;

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, null);
        facts = new ManualFactsTracker();
        steer = new YawCoSteer();
        overlay = new SharedInputOverlay(arbiter, facts, steer, () -> false, () -> false);
        f = new SharedInputOverlay.MoveFields();
        facts.beginTick();
    }

    /** ClientTickEvent.Pre of the next tick. */
    private void nextTick() {
        facts.beginTick();
        overlay.beginTick(false);
    }

    /** The user runs: W held (sprint key handling is vanilla's, after the event). */
    private void userRuns() {
        f.forwardImpulse = 1f;
        f.up = true;
        f.leftImpulse = 0f;
        f.jumping = false;
        f.shiftKeyDown = false;
    }

    @Test
    void theOverlayCanWithhold() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        assertTrue(g.canWithholdSprint());
    }

    @Test
    void withholdForSomeTicksThenItLapses() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        for (int t = 0; t < 4; t++) {
            nextTick();
            assertFalse(SharedInputOverlay.isSprintStartBlocked(), "a new tick starts unblocked");
            g.withholdSprint();
            userRuns();
            assertFalse(overlay.apply(f, 1f), "no sprint start asked for");
            assertTrue(overlay.sprintStartBlocked());
            assertTrue(SharedInputOverlay.isSprintStartBlocked(), "the mixin sees the block in this tick");
            assertEquals(1f, f.forwardImpulse, "withholding sprint leaves the keys alone");
            assertTrue(f.up);
        }
        // the module stops asking: the very next tick is the user's again
        nextTick();
        assertFalse(SharedInputOverlay.isSprintStartBlocked());
        userRuns();
        overlay.apply(f, 1f);
        assertFalse(overlay.sprintStartBlocked());
        assertFalse(SharedInputOverlay.isSprintStartBlocked());
    }

    @Test
    void theBlockEndsWithTheTickEvenWithoutAnApply() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        overlay.apply(f, 1f);
        assertTrue(SharedInputOverlay.isSprintStartBlocked());
        nextTick();
        assertFalse(SharedInputOverlay.isSprintStartBlocked());
    }

    @Test
    void withholdBeatsStartSprintInTheSameTick() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.startSprint();
        g.withholdSprint();
        userRuns();
        assertFalse(overlay.apply(f, 1f));
        assertTrue(overlay.sprintStartBlocked());

        nextTick();
        g.withholdSprint();
        g.startSprint(); // order does not matter
        assertFalse(overlay.apply(f, 1f));
        assertTrue(overlay.sprintStartBlocked());

        nextTick();
        g.startSprint(); // the withhold lapsed: a fill works again
        assertTrue(overlay.apply(f, 1f));
        assertFalse(overlay.sprintStartBlocked());
    }

    @Test
    void aStaleWithholdIsDropped() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        nextTick();
        nextTick(); // written two ticks ago: too old
        overlay.apply(f, 1f);
        assertFalse(overlay.sprintStartBlocked());
    }

    @Test
    void nothingAfterEnd() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        overlay.apply(f, 1f);
        assertTrue(SharedInputOverlay.isSprintStartBlocked());
        g.end();
        assertFalse(SharedInputOverlay.isSprintStartBlocked(), "end() clears the block at once");
        g.withholdSprint(); // ignored: the grant is inactive
        nextTick();
        overlay.apply(f, 1f);
        assertFalse(overlay.sprintStartBlocked());
        assertEquals(LeaseEndReason.RELEASED, overlay.lastEndReason());
    }

    @Test
    void withholdBeforeEndInTheSameTickWritesNothing() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        g.end();
        overlay.apply(f, 1f);
        assertFalse(overlay.sprintStartBlocked());
    }

    @Test
    void stopAllClearsIt() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        overlay.apply(f, 1f);
        assertTrue(SharedInputOverlay.isSprintStartBlocked());
        arbiter.stopAll("test");
        assertFalse(g.isActive());
        assertFalse(SharedInputOverlay.isSprintStartBlocked());
        g.withholdSprint();
        overlay.apply(f, 1f);
        assertFalse(overlay.sprintStartBlocked());
    }

    @Test
    void aScreenOpeningClearsIt() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        overlay.apply(f, 1f);
        assertTrue(SharedInputOverlay.isSprintStartBlocked());
        overlay.onScreenOpening();
        assertFalse(g.isActive());
        assertFalse(SharedInputOverlay.isSprintStartBlocked());
        assertEquals(LeaseEndReason.PREEMPTED, overlay.lastEndReason());
    }

    @Test
    void aScreenAtTickStartClearsIt() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.withholdSprint();
        overlay.apply(f, 1f);
        facts.beginTick();
        overlay.beginTick(true);
        assertFalse(g.isActive());
        assertFalse(SharedInputOverlay.isSprintStartBlocked());
    }

    @Test
    void withholdCountsAsAWriteWithOtherWritesKept() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.axes(KEEP, 1);
        g.withholdSprint();
        userRuns();
        overlay.apply(f, 1f);
        assertEquals(1f, f.leftImpulse);
        assertTrue(f.left);
        assertTrue(overlay.sprintStartBlocked());
    }

    @Test
    void withholdPathAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        int[] i = {0};
        long[] sink = {0};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int k = i[0]++;
            SharedGrant g = overlay.begin("travel.movement", ControlPriority.TRAVEL, true);
            if (g == null) {
                return;
            }
            if ((k & 1) == 0) {
                g.startSprint();
            }
            if ((k & 3) != 3) {
                g.withholdSprint();
            }
            g.axes((k & 4) == 0 ? KEEP : 1, KEEP);
            g.coSteer((k % 360) - 180f, 150, 25, 0.6);
            userRuns();
            if (overlay.apply(f, 1f)) {
                sink[0]++;
            }
            if (SharedInputOverlay.isSprintStartBlocked()) {
                sink[0]++;
            }
            sink[0] += (long) steer.onFrame(k * 0.3f, ((k & 7) - 3) * 0.1, 0.0, 0.007, false);
            if ((k & 15) == 0) {
                nextTick();
            }
            if ((k & 255) == 0) {
                g.end();
            }
        });
        assertTrue(bytes < 1_024, "withhold path allocated " + bytes + " bytes (" + sink[0] + ")");
    }
}
