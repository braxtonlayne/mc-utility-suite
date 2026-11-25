package com.utilitysuite.world.stronghold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangulatorTest {

    private static final long EXPIRY = 10 * 60 * 20;

    @Test
    void secondThrowFarEnoughAwayLocatesTheStronghold() {
        Triangulator t = new Triangulator();
        double sx = 1800;
        double sz = 600;
        assertEquals(Triangulator.Result.FIRST_STORED, t.addThrow(0, 0, sx, sz, 0, 100, true, EXPIRY));
        assertTrue(t.hasFirst());
        assertEquals(Triangulator.Result.LOCATED, t.addThrow(0, 300, sx, sz - 300, 200, 100, true, EXPIRY));
        assertFalse(t.hasFirst());
        assertEquals(sx, t.last().x(), 1e-6);
        assertEquals(sz, t.last().z(), 1e-6);
        assertEquals(1, t.last().ring());
        assertFalse(t.last().snapped());
    }

    @Test
    void throwTooCloseReplacesTheFirst() {
        Triangulator t = new Triangulator();
        t.addThrow(0, 0, 1, 0, 0, 100, true, EXPIRY);
        assertEquals(Triangulator.Result.TOO_CLOSE, t.addThrow(50, 0, 1, 0.1, 10, 100, true, EXPIRY));
        assertEquals(50, t.firstX(), 1e-9);
        assertTrue(t.hasFirst());
    }

    @Test
    void nearParallelSecondThrowIsRejected() {
        Triangulator t = new Triangulator();
        t.addThrow(0, 0, 1, 0, 0, 100, true, EXPIRY);
        // Walked 200 blocks straight along the eye's path: same direction.
        assertEquals(Triangulator.Result.NEAR_PARALLEL, t.addThrow(200, 0, 1, 0.001, 10, 100, true, EXPIRY));
        assertTrue(t.hasFirst());
    }

    @Test
    void oldFirstThrowExpires() {
        Triangulator t = new Triangulator();
        t.addThrow(0, 0, 1, 0, 0, 100, true, EXPIRY);
        assertFalse(t.expire(EXPIRY, EXPIRY));
        assertTrue(t.expire(EXPIRY + 1, EXPIRY));
        assertFalse(t.hasFirst());
        // And a late second throw starts over instead of intersecting with a stale ray.
        t.addThrow(0, 0, 1, 0, 0, 100, true, EXPIRY);
        assertEquals(Triangulator.Result.FIRST_STORED, t.addThrow(0, 300, 1, -0.1, EXPIRY + 5, 100, true, EXPIRY));
    }

    @Test
    void eyeFlightAveragesTicksTwoToTwelve() {
        EyeFlight f = new EyeFlight();
        f.begin(10, 20);
        boolean ready = false;
        for (int age = 1; age <= 12; age++) {
            // Tick 1 is a jittery snap; afterwards the eye moves (0.6, -0.3) per tick.
            double x = age == 1 ? 99 : 10 + 0.6 * age;
            double z = age == 1 ? -99 : 20 - 0.3 * age;
            boolean r = f.tick(x, z);
            assertEquals(age == 12, r);
            ready |= r;
        }
        assertTrue(ready);
        assertFalse(f.tick(0, 0)); // only once
        assertEquals(0.6, f.dirX(), 1e-9);
        assertEquals(-0.3, f.dirZ(), 1e-9);
        assertEquals(10, f.spawnX(), 1e-9);
        assertTrue(f.moved());
    }
}
