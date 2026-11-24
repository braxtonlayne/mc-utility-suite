package com.utilitysuite.travel.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

/**
 * Item 2.3, standing-start target flicker: the estimator's heading corridor and its same-direction hysteresis. From a
 * standing start with a shallow downward pitch, the look point slides along the heading (with the body, and with 1-2
 * degrees of pitch wobble) across the cells of the landing top; the target must stay on one of them.
 */
class IntentCorridorTest {

    /** Open strip: the run-up z -1..1, a two-block gap, the landing strip z 4..6 (one wide), the floor far below. */
    static ShapeGrid openStrip() {
        return new ShapeGrid().fill(0, 19, -1, 0, 19, 1).fill(0, 19, 4, 0, 19, 6).fill(-8, 5, -12, 8, 5, 12);
    }

    /** Pitch wobble: a smooth one (phases 0, 1) and a +-2 degree square wave flipping every 2 ticks (phases 2, 3). */
    static float wobble(float base, int t, int phase) {
        if (phase >= 2) {
            return base + (((t + phase) / 2) % 2 == 0 ? 2f : -2f);
        }
        return base + (float) (1.5 * Math.sin((t + phase * 1.3) * 1.7) + 0.5 * Math.sin((t + phase) * 4.1));
    }

    /**
     * Runs straight along +Z from standing starts (W + sprint, view on the line, the pitch wobbling) until the edge.
     *
     * @return {runs, target switches, ground ticks without a target, start configurations whose first lock depends on
     *     the wobble phase}
     */
    static int[] sweep(boolean neo) {
        int runs = 0;
        int switches = 0;
        int empty = 0;
        int phaseDependent = 0;
        for (double x = 0.3; x <= 0.71; x += 0.2) {
            for (double z = -0.8; z <= 0.41; z += 0.3) {
                for (float base = 10f; base <= 25.1f; base += 1f) {
                    int firstOfPhase0 = Integer.MIN_VALUE;
                    boolean differs = false;
                    for (int phase = 0; phase < 4; phase++) {
                        ShapeGrid g = neo ? TechniqueLibraryTest.courseNeoScene() : openStrip();
                        IntentEstimator e = new IntentEstimator();
                        MovementPhysics p = new MovementPhysics().set(x, 20, z, 0, -0.0784, 0, true);
                        runs++;
                        int last = Integer.MIN_VALUE;
                        int first = Integer.MIN_VALUE;
                        double edge = neo ? 2.6 : 1.6;
                        for (int t = 0; t < 30 && p.onGround && p.z < edge; t++) {
                            boolean ok = e.estimate(p, 0f, wobble(base, t, phase), 1, 0, true, g) && e.hasTarget();
                            if (ok) {
                                int k = e.targetZ() * 64 + e.targetX();
                                if (last != Integer.MIN_VALUE && k != last) {
                                    switches++;
                                }
                                if (first == Integer.MIN_VALUE) {
                                    first = k;
                                }
                                last = k;
                            } else {
                                empty++;
                            }
                            p.tick(1, 0, 0f, true, false, false, g);
                        }
                        if (phase == 0) {
                            firstOfPhase0 = first;
                        } else if (first != firstOfPhase0) {
                            differs = true;
                        }
                    }
                    if (differs) {
                        phaseDependent++;
                    }
                }
            }
        }
        return new int[] {runs, switches, empty, phaseDependent};
    }

    /**
     * Course J36 ("NEO LOOP x0.5 z0.4 p15"): the pick walked z4 -> z5 -> z6 on the run-up and, with the brain's
     * region keep not yet there, the script dropped and the body walked into the pillar. Before the corridor: 487
     * switches in 960 run-ups and 1935 ground ticks without a target; after: 0 and 0.
     */
    @Test
    void theNeoRunUpKeepsOneCellBehindThePillar() {
        int[] r = sweep(true);
        System.out.printf("CORRIDOR neo: %d run-ups, %d target switches, %d ground ticks without a target, "
                + "%d starts whose first lock depends on the wobble%n", r[0], r[1], r[2], r[3]);
        assertTrue(r[1] * 100 <= r[0], r[1] + " switches in " + r[0] + " run-ups");
        assertEquals(0, r[2], "ground ticks without a target");
        assertEquals(0, r[3], "first lock depends on the wobble phase");
    }

    /** The same over an open gap (before: 623 switches in 960 run-ups, 106 ground ticks without a target). */
    @Test
    void theOpenStripRunUpKeepsOneCell() {
        int[] r = sweep(false);
        System.out.printf("CORRIDOR open: %d run-ups, %d target switches, %d ground ticks without a target, "
                + "%d starts whose first lock depends on the wobble%n", r[0], r[1], r[2], r[3]);
        assertEquals(0, r[1], "target switches");
        assertEquals(0, r[2], "ground ticks without a target");
        assertEquals(0, r[3], "first lock depends on the wobble phase");
    }

    /** The exact regression start: no hop on any ground tick, the target never empty. */
    @Test
    void theRegressionStartHoldsTheFirstCell() {
        ShapeGrid g = TechniqueLibraryTest.courseNeoScene();
        IntentEstimator e = new IntentEstimator();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, 0.4, 0, -0.0784, 0, true);
        StringBuilder s = new StringBuilder();
        for (int t = 0; t < 30 && p.onGround && p.z < 2.6; t++) {
            boolean ok = e.estimate(p, 0f, 15f, 1, 0, true, g) && e.hasTarget();
            s.append(ok ? " " + e.targetZ() : " -");
            assertTrue(ok && e.targetX() == 0 && e.targetZ() == 4, "tick " + t + ":" + s);
            p.tick(1, 0, 0f, true, false, false, g);
        }
    }

    /**
     * The hysteresis delays a switch along the line, it never blocks one: looking from the near pillar to the far one
     * (both reachable, same direction) moves the target once the gaze average has settled on it and the lead held for
     * {@link IntentEstimator#CHALLENGE_TICKS_ALONG} ticks (12 ticks here; before the corridor the estimator had no
     * confident target at all on this look).
     */
    @Test
    void aDeliberateLookFurtherAlongTheLineStillSwitches() {
        // the near post is one lower, so the flight to the far one passes over it; the far one is 11.3 degrees to the
        // side (one direction for the hysteresis, but not occluded on the roll's straight line)
        ShapeGrid g = new ShapeGrid().fill(0, 19, -1, 0, 19, 1).cube(0, 18, 3).cube(1, 19, 5)
                .fill(-8, 5, -12, 8, 5, 12);
        IntentEstimator e = new IntentEstimator();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, 0.5, 0, -0.0784, 0, true);
        float nearPitch = (float) Math.toDegrees(Math.atan2(2.62, 3.0));
        float farYaw = (float) Math.toDegrees(Math.atan2(-1.0, 5.0));
        float farPitch = (float) Math.toDegrees(Math.atan2(1.62, Math.hypot(1.0, 5.0)));
        for (int t = 0; t < 10; t++) {
            e.estimate(p, 0f, nearPitch, 1, 0, true, g);
        }
        assertTrue(e.hasTarget() && e.targetZ() == 3, "near pillar first: " + e.targetZ() + e.debugCandidates());
        assertEquals(1, e.feasibility(0, 18, 3), "near pillar feasible");
        int switchedAt = -1;
        StringBuilder s = new StringBuilder();
        for (int t = 0; t < 20; t++) {
            boolean ok = e.estimate(p, farYaw, farPitch, 1, 0, true, g);
            s.append(String.format("%n %d %s%s", t, ok ? e.targetZ() : "-", e.debugCandidates()));
            if (ok && e.targetX() == 1 && e.targetZ() == 5) {
                switchedAt = t;
                break;
            }
        }
        System.out.println("CORRIDOR deliberate look: far pillar after " + switchedAt + " ticks");
        assertTrue(switchedAt >= 0 && switchedAt <= 15, "far pillar after " + switchedAt + " ticks" + s);
    }

    /** The corridor and the same-direction hysteresis active (slow, shallow pitch): no allocation per estimate. */
    @Test
    void estimateWithTheCorridorAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        ShapeGrid g = openStrip();
        g.cube(-1, 19, 5).cube(1, 19, 4);
        MovementPhysics[] stances = new MovementPhysics[4];
        for (int i = 0; i < stances.length; i++) {
            stances[i] = new MovementPhysics().set(0.3 + 0.2 * (i & 1), 20, -0.5 + 0.4 * i, 0, -0.0784,
                    0.02 * i, true);
        }
        IntentEstimator e = new IntentEstimator();
        int[] n = new int[1];
        Runnable body = () -> {
            int i = n[0]++;
            e.estimate(stances[(i >> 3) & 3], (i % 5) - 2f, wobble(14f, i, i & 3), 1, 0, true, g);
        };
        long bytes = AllocationProbe.measure(100_000, body);
        assertEquals(0L, bytes, "estimate() with the corridor must not allocate");
    }
}
