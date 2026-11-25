package com.utilitysuite.jobs.goal;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRateEstimatorTest {

    @Test
    void unknownUntilAStepWasMeasured() {
        StepRateEstimator e = new StepRateEstimator();
        e.reset(0);
        assertEquals(-1, e.etaSeconds(5, 1_000));
        e.stepCompleted(10_000);
        assertEquals(1, e.samples());
        assertEquals(-1, e.etaSeconds(0, 10_000), "nothing left");
    }

    @Test
    void steadyRateGivesRemainingStepsTimesAverage() {
        StepRateEstimator e = new StepRateEstimator();
        e.reset(0);
        e.stepCompleted(10_000);
        e.stepCompleted(20_000);
        e.stepCompleted(30_000);
        assertEquals(10_000.0, e.averageStepMs(), 1e-6);
        // 4 steps left, current one just started: 40 s
        assertEquals(40, e.etaSeconds(4, 30_000));
        // 3 s into the current step: 37 s
        assertEquals(37, e.etaSeconds(4, 33_000));
        // the current step overran the average: only the later steps count
        assertEquals(30, e.etaSeconds(4, 55_000));
    }

    @Test
    void movingAverageFollowsAChangedRateWithoutJumping() {
        StepRateEstimator e = new StepRateEstimator();
        e.reset(0);
        e.stepCompleted(10_000); // 10 s
        e.stepCompleted(30_000); // 20 s
        assertEquals(10_000 + StepRateEstimator.ALPHA * 10_000, e.averageStepMs(), 1e-6);
        for (int i = 0; i < 30; i++) {
            e.stepCompleted(30_000 + (i + 1) * 20_000L);
        }
        assertEquals(20_000.0, e.averageStepMs(), 50.0);
    }

    @Test
    void pausedTimeIsNotCounted() {
        StepRateEstimator e = new StepRateEstimator();
        e.reset(0);
        e.stepCompleted(10_000);
        e.pause(12_000);
        // the ETA freezes while paused
        assertEquals(e.etaSeconds(3, 12_000), e.etaSeconds(3, 500_000));
        e.resume(112_000); // 100 s paused
        e.stepCompleted(117_000); // 2 s before the pause + 5 s after it = 7 s of real work
        assertEquals(10_000 + StepRateEstimator.ALPHA * (7_000 - 10_000), e.averageStepMs(), 1e-6);
    }

    @Test
    void instantStepsDoNotPullTheAverageDown() {
        StepRateEstimator e = new StepRateEstimator();
        e.reset(0);
        e.stepCompleted(10_000);
        e.stepCompleted(10_050); // a craft from the inventory
        assertEquals(1, e.samples());
        assertEquals(10_000.0, e.averageStepMs(), 1e-6);
    }

    @Test
    void estimateAllocatesNothing() {
        StepRateEstimator e = new StepRateEstimator();
        e.reset(0);
        e.stepCompleted(5_000);
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        for (int i = 0; i < 20_000; i++) { // warm up
            sink += e.etaSeconds(7, 5_000 + i);
        }
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) {
            sink += e.etaSeconds(7, 5_000 + i);
            e.pause(i);
            e.resume(i);
        }
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(used < 1024, "allocated " + used + " bytes (sink " + sink + ")");
    }
}
