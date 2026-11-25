package com.utilitysuite.combat.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PendingSpawnsTest {

    @Test
    void spawnWaitsForItsVelocityThenReports() {
        PendingSpawns q = new PendingSpawns(4);
        q.add(7, 2, 3, 1.0, 2.0, 3.0);
        assertEquals(1, q.size());
        assertEquals(7, q.id(0));
        assertEquals(2, q.kind(0));
        assertEquals(3, q.owner(0));
        assertEquals(2.0, q.y(0));
        // the motion packet has arrived by the next tick: report at once
        assertTrue(q.ready(0, true));
    }

    @Test
    void spawnAtRestIsReportedAfterTheWait() {
        PendingSpawns q = new PendingSpawns(4);
        q.add(7, 2, -1, 0, 0, 0);
        for (int t = 1; t < PendingSpawns.MAX_WAIT_TICKS; t++) {
            assertFalse(q.ready(0, false));
        }
        assertTrue(q.ready(0, false));
    }

    @Test
    void fullQueueDropsTheOldestAndRemoveKeepsOrder() {
        PendingSpawns q = new PendingSpawns(2);
        q.add(1, 2, -1, 0, 0, 0);
        q.add(2, 2, -1, 0, 0, 0);
        q.add(3, 2, -1, 0, 0, 0);
        assertEquals(2, q.size());
        assertEquals(2, q.id(0));
        assertEquals(3, q.id(1));
        q.remove(0);
        assertEquals(1, q.size());
        assertEquals(3, q.id(0));
        q.clear();
        assertEquals(0, q.size());
    }
}
