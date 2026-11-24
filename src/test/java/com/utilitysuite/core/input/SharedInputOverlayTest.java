package com.utilitysuite.core.input;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlPriority;
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

/** The overlay's writes: what each one does, the one-tick lapse, co-steer gating, sprint rules, allocation. */
class SharedInputOverlayTest {

    private ActionArbiter arbiter;
    private ManualFactsTracker facts;
    private YawCoSteer steer;
    private SharedInputOverlay overlay;
    private final boolean[] legit = {false};
    private SharedInputOverlay.MoveFields f;

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, null);
        facts = new ManualFactsTracker();
        steer = new YawCoSteer();
        overlay = new SharedInputOverlay(arbiter, facts, steer, () -> false, () -> legit[0]);
        f = new SharedInputOverlay.MoveFields();
        facts.beginTick();
    }

    /** The user's own keys: W and sneak held. */
    private void userInput() {
        f.forwardImpulse = 1f;
        f.leftImpulse = 0f;
        f.up = true;
        f.down = false;
        f.left = false;
        f.right = false;
        f.jumping = true;
        f.shiftKeyDown = true;
    }

    private void nextTick() {
        facts.beginTick();
        overlay.beginTick(false);
    }

    @Test
    void axesSetImpulsesAndKeyFlagsTogether() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        userInput();
        g.axes(-1, 1);
        overlay.apply(f, 1f);
        assertEquals(-1f, f.forwardImpulse);
        assertFalse(f.up);
        assertTrue(f.down);
        assertEquals(1f, f.leftImpulse);
        assertTrue(f.left);
        assertFalse(f.right);

        userInput();
        g.axes(0, -1);
        overlay.apply(f, 0.3f); // moving slowly: vanilla's sneak multiplier applies to the written impulse too
        assertEquals(0f, f.forwardImpulse);
        assertFalse(f.up);
        assertFalse(f.down);
        assertEquals(-0.3f, f.leftImpulse, 1e-6f);
        assertTrue(f.right);
    }

    @Test
    void keepLeavesTheUsersInput() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        userInput();
        g.axes(KEEP, KEEP);
        g.jump(KEEP);
        g.sneak(false);
        overlay.apply(f, 1f);
        assertEquals(1f, f.forwardImpulse);
        assertTrue(f.up);
        assertTrue(f.jumping);
        assertTrue(f.shiftKeyDown, "sneak(false) never lifts the user's sneak");
    }

    @Test
    void jumpOnAndWithhold() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        userInput();
        g.jump(OFF);
        overlay.apply(f, 1f);
        assertFalse(f.jumping);
        userInput();
        f.jumping = false;
        g.jump(ON);
        overlay.apply(f, 1f);
        assertTrue(f.jumping);
    }

    @Test
    void sneakOnSetsTheKey() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        userInput();
        f.shiftKeyDown = false;
        g.sneak(true);
        overlay.apply(f, 1f);
        assertTrue(f.shiftKeyDown);
    }

    @Test
    void everyWriteLapsesAfterOneApply() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.axes(-1, -1);
        g.jump(OFF);
        g.sneak(true);
        g.startSprint();
        userInput();
        f.shiftKeyDown = false;
        assertTrue(overlay.apply(f, 1f), "sprint asked for");
        nextTick();
        userInput();
        f.shiftKeyDown = false;
        assertFalse(overlay.apply(f, 1f), "sprint lapsed");
        assertEquals(1f, f.forwardImpulse);
        assertEquals(0f, f.leftImpulse);
        assertTrue(f.jumping);
        assertFalse(f.shiftKeyDown);
    }

    @Test
    void writeFromThePreviousTickAppliesOlderOnesAreDropped() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.axes(-1, KEEP); // written in tick Post
        nextTick();
        userInput();
        overlay.apply(f, 1f); // applies in the next tick's movement input
        assertEquals(-1f, f.forwardImpulse);

        g.axes(-1, KEEP);
        nextTick();
        nextTick(); // no movement input for two ticks (e.g. a lag spike)
        userInput();
        overlay.apply(f, 1f);
        assertEquals(1f, f.forwardImpulse, "a write two ticks old is stale");
    }

    @Test
    void endedGrantWritesNothing() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.axes(-1, -1);
        g.end();
        g.axes(-1, -1);
        userInput();
        overlay.apply(f, 1f);
        assertEquals(1f, f.forwardImpulse);
        assertEquals(0f, f.leftImpulse);
    }

    @Test
    void coSteerOnlyWithWantsYawAndNotOnLegit() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, false);
        g.coSteer(90f, 200, 45, 1);
        assertFalse(steer.isLive(), "grant begun without wantsYaw");
        g.end();

        legit[0] = true;
        g = overlay.begin("m", ControlPriority.TRAVEL, true);
        g.coSteer(90f, 200, 45, 1);
        assertFalse(steer.isLive(), "LEGIT: the core refuses");
        assertEquals(0.0, steer.onFrame(0f, 0.0, 0.0, 0.016, false));
        g.end();

        legit[0] = false;
        g = overlay.begin("m", ControlPriority.TRAVEL, true);
        g.coSteer(90f, 200, 45, 1);
        assertTrue(steer.isLive());
        assertTrue(steer.onFrame(0f, 0.0, 0.0, 0.016, false) > 0.0);
        g.end();
        assertFalse(steer.isLive(), "ending the grant stops the follower");
    }

    @Test
    void coSteerLapsesWhenNotRenewed() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, true);
        g.coSteer(90f, 200, 45, 1);
        nextTick();
        assertTrue(steer.isLive(), "the frames of the tick after the call still steer");
        nextTick();
        assertFalse(steer.isLive());
        assertEquals(0.0, steer.onFrame(0f, 0.0, 0.0, 0.016, false));
    }

    @Test
    void stopAllZeroesCoSteerAtOnce() {
        SharedGrant g = overlay.begin("m", ControlPriority.TRAVEL, true);
        g.coSteer(90f, 200, 45, 1);
        steer.onFrame(0f, 0.0, 0.0, 0.016, false);
        arbiter.stopAll("test");
        assertFalse(g.isActive());
        assertFalse(steer.isLive());
        assertEquals(0.0, steer.onFrame(0f, 0.0, 0.0, 0.016, false));
        assertEquals(0.0, g.opposingYawDeg());
        assertEquals(0.0, g.coSteerAddedDeg(), "an ended grant has nothing spent");
    }

    @Test
    void sprintFollowsVanillaConditions() {
        // sprinting, impulse, underWater, inWater, canSwim, food, passenger, mayFly, using, blind, vehicleOk, glide, wall
        assertTrue(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 20, false, false, false, false,
                true, false, false));
        assertFalse(SharedInputOverlay.sprintAllowed(true, 1f, false, false, false, 20, false, false, false, false,
                true, false, false), "already sprinting");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 0.79f, false, false, false, 20, false, false, false, false,
                true, false, false), "impulse below 0.8");
        assertTrue(SharedInputOverlay.sprintAllowed(false, 0.1f, true, true, true, 20, false, false, false, false,
                true, false, false), "under water any forward impulse");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, true, false, 20, false, false, false, false,
                true, false, false), "surface water");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 6, false, false, false, false,
                true, false, false), "food 6");
        assertTrue(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 6, false, true, false, false,
                true, false, false), "may fly");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 20, false, false, true, false,
                true, false, false), "using an item");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 20, false, false, false, true,
                true, false, false), "blind");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 20, true, false, false, false,
                false, false, false), "vehicle cannot sprint");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 20, false, false, false, false,
                true, true, false), "gliding");
        assertFalse(SharedInputOverlay.sprintAllowed(false, 1f, false, false, false, 20, false, false, false, false,
                true, false, true), "against a wall");
    }

    @Test
    void beginWritesAndApplyAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        int[] i = {0};
        long[] sink = {0};
        long bytes = AllocationProbe.measure(1_000_000, () -> {
            int k = i[0]++;
            SharedGrant g = overlay.begin("travel.movement", ControlPriority.TRAVEL, true);
            if (g == null) {
                return;
            }
            g.axes((k & 1) == 0 ? KEEP : 1, (k % 3) - 1);
            g.jump((k & 2) == 0 ? ON : OFF);
            g.sneak((k & 4) == 0);
            if ((k & 8) == 0) {
                g.startSprint();
            }
            g.coSteer((k % 360) - 180f, 300, 60, 0.5 + (k & 1) * 0.5);
            userInput();
            if (overlay.apply(f, 1f)) {
                sink[0]++;
            }
            sink[0] += (long) steer.onFrame(k * 0.3f, ((k & 7) - 3) * 0.1, 0.0, 0.007, false);
            sink[0] += (long) g.opposingYawDeg();
            if ((k & 15) == 0) {
                nextTick();
            }
            if ((k & 255) == 0) {
                g.end();
            }
        });
        assertTrue(bytes < 1_024, "overlay path allocated " + bytes + " bytes (" + sink[0] + ")");
    }
}
