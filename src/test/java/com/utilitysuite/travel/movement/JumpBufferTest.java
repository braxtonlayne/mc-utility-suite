package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JumpBufferTest {

    @Test
    void offByDefaultAndThenEveryPressPassesThrough() {
        JumpBuffer j = new JumpBuffer();
        assertFalse(j.enabled());
        assertEquals(JumpBuffer.DEFAULT_TICKS, j.maxTicks());
        assertTrue(j.update(true, true, 2), "an early press is not held back while off");
        assertFalse(j.withholding());
    }

    @Test
    void anEarlyPressFiresOnTheLastGroundTick() {
        JumpBuffer j = new JumpBuffer();
        j.setEnabled(true);
        assertFalse(j.update(true, true, 2), "pressed two ticks early: held back");
        assertTrue(j.withholding());
        assertFalse(j.update(true, true, 1));
        assertTrue(j.update(true, true, 0), "fires on the last ground tick");
        assertTrue(j.fired());
        assertTrue(j.update(true, true, -1), "after that the held key is the user's again");
    }

    @Test
    void aBufferedPressFiresEvenIfTheKeyIsReleased() {
        JumpBuffer j = new JumpBuffer();
        j.setEnabled(true);
        assertFalse(j.update(true, true, 1));
        assertTrue(j.update(false, true, 0), "a quick tap still jumps at the edge");
    }

    @Test
    void neverHeldLongerThanTheSetting() {
        JumpBuffer j = new JumpBuffer();
        j.setEnabled(true);
        j.setMaxTicks(1);
        assertFalse(j.update(true, true, 1));
        assertTrue(j.update(true, true, 1), "the edge did not come (slowed down): released after one tick");
        JumpBuffer k = new JumpBuffer();
        k.setEnabled(true);
        assertTrue(k.update(true, true, 3), "three ticks early with a 2-tick buffer: not buffered at all");
        j.setMaxTicks(9);
        assertEquals(JumpBuffer.MAX_TICKS, j.maxTicks(), "clamped to 150 ms");
    }

    @Test
    void flatGroundAndAirAreUntouched() {
        JumpBuffer j = new JumpBuffer();
        j.setEnabled(true);
        assertTrue(j.update(true, true, -1), "no edge close: the jump goes now");
        assertTrue(j.update(true, true, -1), "held on flat ground: untouched");
        JumpBuffer a = new JumpBuffer();
        a.setEnabled(true);
        assertTrue(a.update(true, false, 1), "airborne: untouched");
        assertFalse(a.update(false, true, 0), "nothing pending, nothing fired");
    }

    @Test
    void theBrainWithholdsAndFiresAtTheEdge() {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 0);
        g.fill(-3, 19, 4, 3, 19, 30);
        JumpSearch s = new JumpSearch();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
        while (s.ticksToEdge(p, 1, 0, 0f, true, 3, g) != 2) {
            p.tick(1, 0, 0f, true, false, g);
        }
        MovementAssistBrain b = new MovementAssistBrain();
        b.jumpBuffer().setEnabled(true);
        b.decide(p, 0f, true, 1, 0, true, 20f, g);
        assertTrue(b.withholdJump(), "two ticks early: withheld");
        p.tick(1, 0, 0f, true, false, g);
        b.decide(p, 0f, true, 1, 0, false, 20f, g); // the user let go already
        assertTrue(b.withholdJump() || !b.jump(), "still waiting");
        p.tick(1, 0, 0f, true, false, g);
        b.decide(p, 0f, true, 1, 0, false, 20f, g);
        assertTrue(b.jump(), "fired on the last ground tick");
        assertFalse(b.withholdJump());
    }

    @Test
    void bufferAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        JumpBuffer j = new JumpBuffer();
        j.setEnabled(true);
        long bytes = AllocationProbe.measure(1000, () -> {
            j.update(true, true, 2);
            j.update(true, true, 1);
            j.update(false, true, 0);
            j.update(false, true, -1);
        });
        assertTrue(bytes < 256, "buffer allocated " + bytes + " bytes");
    }
}
