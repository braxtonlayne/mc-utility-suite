package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tick-phase check: jump on tick N at the current yaw or on N+1 at the yaw the mouse is turning to. */
class TakeoffTimingTest {

    /** Takeoff platform z &lt;= 0, a 1-block target at (-1, 19, 4) to the right-hand side of x = 0.2, a lethal pit. */
    private static Grid course() {
        Grid g = new Grid();
        g.fill(-6, 19, -30, 6, 19, 0);
        g.fill(-1, 19, 4, -1, 19, 4);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    /** Runs toward +Z from z = -6 until the edge is {@code ticksToEdge} ticks away. */
    private static MovementPhysics approach(Grid g, double x, int ticksToEdge) {
        JumpSearch s = new JumpSearch();
        MovementPhysics p = new MovementPhysics().set(x, 20, -6, 0, -0.0784, 0, true);
        for (int i = 0; i < 60; i++) {
            if (s.ticksToEdge(p, 1, 0, 0f, true, 3, g) == ticksToEdge) {
                return p;
            }
            p.tick(1, 0, 0f, true, false, g);
        }
        throw new AssertionError("never reached " + ticksToEdge + " ticks before the edge");
    }

    @Test
    void theYawRateDecidesBetweenNowAndNextTick() {
        Grid g = course();
        MovementPhysics p = approach(g, 0.2, 1);
        JumpSearch s = new JumpSearch();
        s.setTarget(-1, 19, 4);
        TakeoffTiming t = new TakeoffTiming();
        // yaw increasing turns the heading toward -X, i.e. toward the target: waiting a tick lands it better
        assertEquals(TakeoffTiming.Choice.NEXT_TICK, t.choose(p, 0f, 8.0, true, 20f, false, s, g));
        assertTrue(t.nextScore() > t.nowScore(), "N+1 lands closer to the target");
        // turning away: the N+1 jump misses, jumping now still lands
        assertEquals(TakeoffTiming.Choice.NOW, t.choose(p, 0f, -8.0, true, 20f, false, s, g));
        assertTrue(t.nowScore() > JumpSearch.NO_LANDING);
        assertEquals(JumpSearch.NO_LANDING, t.nextScore());
    }

    @Test
    void neverWaitsWhenTheBodyIsUnsupportedOnTheNextTick() {
        Grid g = course();
        MovementPhysics last = approach(g, 0.2, 0);
        JumpSearch s = new JumpSearch();
        s.setTarget(-1, 19, 4);
        TakeoffTiming t = new TakeoffTiming();
        assertEquals(TakeoffTiming.Choice.NOW, t.choose(last, 0f, 8.0, true, 20f, false, s, g),
                "even though turning would land better, waiting walks off the edge");
        assertFalse(t.nextSupported());
    }

    @Test
    void theBrainJumpsEarlyOnlyWhenTheCheckSaysSo() {
        Grid g = course();
        MovementPhysics p = approach(g, 0.2, 1);
        MovementAssistBrain b = new MovementAssistBrain();
        b.setIntentTarget(-1, 19, 4, 0.9);
        b.setYawRate(-8.0);
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertTrue(b.jump() && b.earlyJump(), "turning away: take off on this tick");
        b.setYawRate(8.0);
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.jump(), "turning toward: wait for the last tick");
        b.tickPhase = false;
        b.setYawRate(-8.0);
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.jump(), "setting off: the last-tick jump only");
    }

    @Test
    void aStillMouseWithoutATargetSkipsTheCheck() {
        Grid g = course();
        MovementPhysics p = approach(g, 0.2, 1);
        MovementAssistBrain b = new MovementAssistBrain();
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertFalse(b.earlyJump());
        assertFalse(b.jump(), "today's behaviour: jump on the last tick, not this one");
    }
}
