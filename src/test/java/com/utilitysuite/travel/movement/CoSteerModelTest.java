package com.utilitysuite.travel.movement;

import com.utilitysuite.core.input.YawCoSteer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoSteerModel} is the planner's copy of the core's co-steer follower. A script that turns the view is rolled
 * with the model and flown by the real follower, so the two must agree on where the view is on every tick.
 */
class CoSteerModelTest {

    /** The real follower for one tick: one request, then {@code frames} equal frames with a still mouse. */
    static float real(YawCoSteer s, float yaw, float ideal, double rate, double maxTotal, int frames) {
        s.beginTick();
        s.request(ideal, rate, maxTotal, 1.0);
        for (int k = 0; k < frames; k++) {
            yaw += (float) s.onFrame(yaw, 0.0, 0.0, 0.05 / frames, false);
        }
        return yaw;
    }

    @Test
    void constantsMatchTheCore() {
        assertEquals(YawCoSteer.ACCEL_DEG_PER_S2, CoSteerModel.ACCEL_DEG_PER_S2, 0.0);
        assertEquals(YawCoSteer.OMEGA_PER_SEC, CoSteerModel.OMEGA_PER_SEC, 0.0);
        assertEquals(YawCoSteer.ON_TARGET_DEG, CoSteerModel.ON_TARGET_DEG, 0.0);
    }

    /** Same frames, same view: goal changes, both levels' rates, the allowance running out. */
    @Test
    void sameViewAsTheRealFollowerAtTheModelsFrameRate() {
        double[][] levels = {{150.0, 25.0}, {240.0, 90.0}, {450.0, 180.0}};
        float[][] goals = {{-25f, -25f, -25f, -25f}, {-10f, -10f, -45f, -45f}, {12f, -30f, 70f, 0f}, {3f, 3f, 3f, 3f}};
        for (double[] lv : levels) {
            for (float[] g : goals) {
                YawCoSteer real = new YawCoSteer();
                CoSteerModel model = new CoSteerModel();
                real.reset();
                model.reset();
                float a = 0f;
                float b = 0f;
                for (int t = 0; t < 32; t++) {
                    float ideal = g[t / 8];
                    a = real(real, a, ideal, lv[0], lv[1], CoSteerModel.FRAMES_PER_TICK);
                    b = model.tick(b, ideal, lv[0], lv[1]);
                    assertEquals(a, b, 1.0e-4f, "rate " + lv[0] + " tick " + t);
                    assertEquals(real.totalAddedDeg(), model.total(), 1.0e-3);
                }
            }
        }
    }

    /** The view never moves faster than the level's rate, and never past 450 deg/s. */
    @Test
    void neverFasterThanTheRate() {
        CoSteerModel model = new CoSteerModel();
        for (double rate : new double[] {150.0, 240.0, 450.0, 2000.0}) {
            model.reset();
            float yaw = 0f;
            for (int t = 0; t < 20; t++) {
                float next = model.tick(yaw, 170f, rate, 1.0e6);
                assertTrue(Math.abs(next - yaw) <= Math.min(rate, 450.0) / 20.0 + 1.0e-3, "rate " + rate + " tick " + t);
                yaw = next;
            }
        }
    }

    /**
     * Other frame rates: the real follower at 40, 100 and 160 fps stays within 1.5 degrees of the model on every tick
     * of a 25-degree and a 70-degree turn (about 0.007 block of sideways drift per tick at sprint speed).
     */
    @Test
    void closeToTheRealFollowerAtOtherFrameRates() {
        double[][] cases = {{150.0, 25.0, -25.0}, {240.0, 90.0, -70.0}};
        for (double[] c : cases) {
            for (int frames : new int[] {2, 5, 8}) {
                YawCoSteer real = new YawCoSteer();
                CoSteerModel model = new CoSteerModel();
                real.reset();
                model.reset();
                float a = 0f;
                float b = 0f;
                for (int t = 0; t < 24; t++) {
                    a = real(real, a, (float) c[2], c[0], c[1], frames);
                    b = model.tick(b, (float) c[2], c[0], c[1]);
                    assertEquals(a, b, 1.5f, frames * 20 + " fps, goal " + c[2] + ", tick " + t);
                }
            }
        }
    }

    /** A tick without a request stops the follower; no request, no turn. */
    @Test
    void aLapsedTickHoldsTheView() {
        CoSteerModel model = new CoSteerModel();
        model.reset();
        float yaw = model.tick(0f, -40f, 240.0, 90.0);
        yaw = model.tick(yaw, -40f, 240.0, 90.0);
        model.lapse();
        assertEquals(yaw, model.tick(yaw, Float.NaN, 240.0, 90.0), 0f);
        assertEquals(yaw, model.tick(yaw, -40f, 0.0, 90.0), 0f);
    }
}
