package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AfterimagesTest {

    @Test
    void keepsTheLastTenPosesOldestFirst() {
        Afterimages a = new Afterimages();
        for (int i = 0; i < 12; i++) {
            a.push(7, i, 64, -i, 1000L + i * 50L);
        }
        assertEquals(Afterimages.CAPACITY, a.size());
        assertEquals(2.0, a.x(0));
        assertEquals(11.0, a.x(9));
        assertEquals(-11.0, a.z(9));
        assertEquals(1000L + 11 * 50L, a.time(9));
    }

    @Test
    void posesFadeOutOverHalfASecond() {
        Afterimages a = new Afterimages();
        a.push(1, 0, 0, 0, 1000L);
        assertEquals(1f, a.alpha(0, 1000L), 1e-6f);
        assertEquals(0.5f, a.alpha(0, 1250L), 1e-6f);
        assertEquals(0f, a.alpha(0, 1500L), 1e-6f);
        assertEquals(0f, a.alpha(0, 9000L), 1e-6f);
    }

    @Test
    void newTargetStartsAFreshTrail() {
        Afterimages a = new Afterimages();
        a.push(1, 0, 0, 0, 0L);
        a.push(1, 1, 0, 0, 50L);
        a.push(2, 5, 0, 0, 100L);
        assertEquals(1, a.size());
        assertEquals(2, a.owner());
        assertEquals(5.0, a.x(0));
        a.clear();
        assertEquals(0, a.size());
    }

    @Test
    void pushAndReadAllocateNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        Afterimages a = new Afterimages();
        long[] t = {0L};
        long bytes = AllocationProbe.measure(5000, () -> {
            a.push(3, t[0], 64, 0, t[0]);
            t[0] += 50L;
            for (int i = 0; i < a.size(); i++) {
                a.alpha(i, t[0]);
                a.x(i);
            }
        });
        assertEquals(0L, bytes);
    }
}
