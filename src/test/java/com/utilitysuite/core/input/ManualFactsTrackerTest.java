package com.utilitysuite.core.input;

import com.utilitysuite.core.api.SimKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Key facts from scripted hardware press/release events. */
class ManualFactsTrackerTest {

    @Test
    void neverPressedKeysReportNothing() {
        ManualFactsTracker f = new ManualFactsTracker();
        for (SimKey k : SimKey.all()) {
            assertEquals(0, f.heldTicks(k));
            assertEquals(-1L, f.lastPressTick(k));
            assertEquals(-1L, f.lastReleaseTick(k));
            assertFalse(f.heldSince(k, 100));
        }
        assertEquals(0.0, f.handYawDegLastTick());
    }

    @Test
    void heldTicksCountFromThePress() {
        ManualFactsTracker f = new ManualFactsTracker();
        f.beginTick(); // tick 1
        f.onPress(SimKey.FORWARD); // between ticks 1 and 2
        assertEquals(1L, f.lastPressTick(SimKey.FORWARD));
        assertEquals(1, f.heldTicks(SimKey.FORWARD), "down counts at least 1");
        f.beginTick(); // tick 2: the game's movement input sees the key for the first time
        assertEquals(1, f.heldTicks(SimKey.FORWARD));
        f.beginTick();
        f.beginTick(); // tick 4
        assertEquals(3, f.heldTicks(SimKey.FORWARD));
        f.onRelease(SimKey.FORWARD);
        assertEquals(0, f.heldTicks(SimKey.FORWARD));
        assertEquals(4L, f.lastReleaseTick(SimKey.FORWARD));
        assertEquals(1L, f.lastPressTick(SimKey.FORWARD));
    }

    @Test
    void heldSinceTellsAStaleKeyFromAFreshOne() {
        ManualFactsTracker f = new ManualFactsTracker();
        f.beginTick();
        f.onPress(SimKey.LEFT); // stale: before takeoff at tick 5
        for (int i = 0; i < 5; i++) {
            f.beginTick();
        }
        long takeoff = f.tick(); // 6
        f.onPress(SimKey.RIGHT); // fresh: after takeoff
        f.beginTick();
        f.beginTick();
        assertTrue(f.heldSince(SimKey.LEFT, takeoff));
        assertFalse(f.heldSince(SimKey.RIGHT, takeoff));
        assertEquals(2, f.heldTicks(SimKey.RIGHT));
        // release and re-press: fresh again
        f.onRelease(SimKey.LEFT);
        f.onPress(SimKey.LEFT);
        f.beginTick();
        assertFalse(f.heldSince(SimKey.LEFT, takeoff));
        assertEquals(1, f.heldTicks(SimKey.LEFT));
    }

    @Test
    void releaseOfAKeyThatIsUpIsIgnored() {
        ManualFactsTracker f = new ManualFactsTracker();
        f.beginTick();
        f.onRelease(SimKey.JUMP);
        assertEquals(-1L, f.lastReleaseTick(SimKey.JUMP));
    }

    @Test
    void releaseAllClearsEveryHeldKey() {
        ManualFactsTracker f = new ManualFactsTracker();
        f.beginTick();
        f.onPress(SimKey.FORWARD);
        f.onPress(SimKey.SNEAK);
        f.beginTick();
        f.releaseAll(); // a screen opened
        assertEquals(0, f.heldTicks(SimKey.FORWARD));
        assertEquals(0, f.heldTicks(SimKey.SNEAK));
        assertEquals(2L, f.lastReleaseTick(SimKey.SNEAK));
        assertEquals(-1L, f.lastReleaseTick(SimKey.JUMP));
    }

    @Test
    void handYawRollsOverPerTick() {
        ManualFactsTracker f = new ManualFactsTracker();
        f.beginTick();
        f.addHandYaw(1.5);
        f.addHandYaw(-0.5);
        f.addHandYaw(Double.NaN);
        assertEquals(1.0, f.handYawDegThisTick(), 1e-12);
        assertEquals(0.0, f.handYawDegLastTick());
        f.beginTick();
        assertEquals(1.0, f.handYawDegLastTick(), 1e-12);
        f.beginTick();
        assertEquals(0.0, f.handYawDegLastTick());
    }
}
