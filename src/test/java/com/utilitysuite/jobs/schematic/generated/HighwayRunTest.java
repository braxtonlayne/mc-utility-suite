package com.utilitysuite.jobs.schematic.generated;

import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighwayRunTest {

    @Test
    void publishesSegmentsInOrderAsThePlayerAdvances() {
        HighwayRun r = new HighwayRun(0, 64, 0, 100, 0, 32);
        assertEquals(4, r.segments);
        assertEquals(0, r.nextToPublish(0, 16));
        r.markPublished(0);
        assertEquals(-1, r.nextToPublish(10, 16));
        assertEquals(1, r.nextToPublish(r.progress(20.5, 3), 16));
        r.markPublished(1);
        // A teleport far ahead still publishes one at a time, in order.
        assertEquals(2, r.nextToPublish(100, 16));
        r.markPublished(2);
        assertFalse(r.allPublished());
        r.markPublished(3);
        assertEquals(-1, r.nextToPublish(100, 16));
        assertFalse(r.finished(99));
        assertTrue(r.finished(100));
        assertTrue(r.allPublished());
    }

    @Test
    void projectsOntoDiagonalsAndMeasuresSidewaysDrift() {
        HighwayRun r = new HighwayRun(0, 64, 0, -40, 40, 32);
        assertEquals(10, r.progress(-9.5, 10.5));
        assertEquals(0, r.progress(30, -30));
        assertEquals(0.0, r.offPath(-9.5, 10.5), 1e-9);
        assertEquals(Math.sqrt(2), r.offPath(-8.5, 11.5), 1e-9);
    }

    @Test
    void centrePathFollowsTheSteps() {
        HighwayRun r = new HighwayRun(5, 70, 5, 5, -60, 32);
        long[] p = r.centrePath(32, 34);
        assertEquals(3, p.length);
        assertEquals(PackedPos.pack(5, 70, -27), p[0]);
        assertEquals(PackedPos.pack(5, 70, -29), p[2]);
    }

    @Test
    void boatHandOffTargetsTheEndOfEachNewlyCompletedSegmentOnce() {
        HighwayRun r = new HighwayRun(0, 64, 0, 100, 0, 32);
        assertEquals(-1, r.boatHandoffStep(0, 6, -1), "nothing published yet");
        r.markPublished(0);
        assertEquals(31, r.publishedEndStep());
        assertEquals(31, r.boatHandoffStep(0, 6, -1));
        assertEquals(-1, r.boatHandoffStep(5, 6, 31), "already handed off");
        assertEquals(-1, r.boatHandoffStep(28, 6, -1), "too close to the end to be worth it");
        r.markPublished(1);
        assertEquals(63, r.boatHandoffStep(30, 6, 31));
        r.markPublished(3);
        assertEquals(100, r.publishedEndStep(), "clamped to the last step");
        assertEquals(100, r.stepX(100));
        assertEquals(0, r.stepZ(100));
    }
}
