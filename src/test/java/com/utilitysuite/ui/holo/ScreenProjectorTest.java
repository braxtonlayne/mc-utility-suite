package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.core.api.visual.WorldMarkTable;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Projection math against hand-computed points. The fixture camera looks down -Z with a 90 degree vertical field of
 * view and a square screen, so a camera-space point (x, y, -d) lands at ndc (x/d, y/d): screen x = 0.5 + x/(2d) and
 * screen y = 0.5 - y/(2d) (normalised, y down).
 */
class ScreenProjectorTest {

    static final float EPS = 1.0e-4f;

    static Matrix4f view() {
        return new Matrix4f();
    }

    static Matrix4f proj() {
        return new Matrix4f().setPerspective((float) (Math.PI / 2), 1f, 0.05f, 100f);
    }

    static Matrix4f mvp() {
        return proj().mul(view());
    }

    @Test
    void pointBoxInFrontLandsWhereHandComputed() {
        float[] out = new float[ScreenProjector.STRIDE];
        // degenerate box = one point at (1, 0.5, -2)
        int st = ScreenProjector.projectBox(mvp(), view(), 1f, 0.5f, -2f, 1f, 0.5f, -2f, out, 0);
        assertEquals(ScreenProjector.ON_SCREEN, st);
        assertEquals(0.75f, out[ScreenProjector.MIN_X], EPS);
        assertEquals(0.75f, out[ScreenProjector.MAX_X], EPS);
        assertEquals(0.375f, out[ScreenProjector.MIN_Y], EPS);
        assertEquals((float) Math.sqrt(1 + 0.25 + 4), out[ScreenProjector.DISTANCE], EPS);
    }

    @Test
    void boxRectangleUsesNearestCornersForExtent() {
        float[] out = new float[ScreenProjector.STRIDE];
        int st = ScreenProjector.projectBox(mvp(), view(), -0.1f, -0.1f, -2.1f, 0.1f, 0.1f, -1.9f, out, 0);
        assertEquals(ScreenProjector.ON_SCREEN, st);
        float half = 0.1f / 1.9f * 0.5f; // the near face is the widest
        assertEquals(0.5f - half, out[ScreenProjector.MIN_X], EPS);
        assertEquals(0.5f + half, out[ScreenProjector.MAX_X], EPS);
        assertEquals(0.5f - half, out[ScreenProjector.MIN_Y], EPS);
        assertEquals(0.5f + half, out[ScreenProjector.MAX_Y], EPS);
    }

    @Test
    void behindTheCameraReportsBehindWithBearing() {
        float[] out = new float[ScreenProjector.STRIDE];
        int st = ScreenProjector.projectBox(mvp(), view(), 0.9f, -0.1f, 1.9f, 1.1f, 0.1f, 2.1f, out, 0);
        assertEquals(ScreenProjector.BEHIND, st);
        assertEquals(90f, out[ScreenProjector.BEARING], 0.01f); // to the right
    }

    @Test
    void offScreenInFrontReportsOffScreenAndBearing() {
        float[] out = new float[ScreenProjector.STRIDE];
        int st = ScreenProjector.projectBox(mvp(), view(), 4.9f, -0.1f, -2.1f, 5.1f, 0.1f, -1.9f, out, 0);
        assertEquals(ScreenProjector.OFF_SCREEN, st);
        assertEquals(90f, out[ScreenProjector.BEARING], 0.01f);
        st = ScreenProjector.projectBox(mvp(), view(), -0.1f, 4.9f, -2.1f, 0.1f, 5.1f, -1.9f, out, 0);
        assertEquals(ScreenProjector.OFF_SCREEN, st);
        assertEquals(0f, out[ScreenProjector.BEARING], 0.01f); // straight up
        st = ScreenProjector.projectBox(mvp(), view(), -5.1f, -0.1f, -2.1f, -4.9f, 0.1f, -1.9f, out, 0);
        assertEquals(-90f, out[ScreenProjector.BEARING], 0.01f);
    }

    @Test
    void boxStraddlingTheNearPlaneIsClampedToTheScreen() {
        float[] out = new float[ScreenProjector.STRIDE];
        int st = ScreenProjector.projectBox(mvp(), view(), -0.5f, -1f, -1f, 0.5f, 1f, 1f, out, 0);
        assertEquals(ScreenProjector.ON_SCREEN, st);
        assertEquals(0.25f, out[ScreenProjector.MIN_X], EPS);
        assertEquals(0.75f, out[ScreenProjector.MAX_X], EPS);
        assertEquals(0f, out[ScreenProjector.MIN_Y], EPS);
        assertEquals(1f, out[ScreenProjector.MAX_Y], EPS);
    }

    @Test
    void viewRotationMovesTheMark() {
        // view rotated 90 degrees about +Y: world (2, 0, 0) maps to camera (0, 0, -2), straight ahead
        Matrix4f view = new Matrix4f().rotationY((float) (Math.PI / 2));
        Matrix4f mvp = proj().mul(view);
        float[] out = new float[ScreenProjector.STRIDE];
        int st = ScreenProjector.projectBox(mvp, view, 2f, 0f, 0f, 2f, 0f, 0f, out, 0);
        assertEquals(ScreenProjector.ON_SCREEN, st);
        assertEquals(0.5f, out[ScreenProjector.MIN_X], EPS);
        assertEquals(0.5f, out[ScreenProjector.MIN_Y], EPS);
    }

    @Test
    void minimumSizeGrowsAroundTheCentre() {
        float[] r = {10f, 10f, 12f, 30f};
        ScreenProjector.expandToMin(r, 0, 14f);
        assertEquals(4f, r[0], EPS);
        assertEquals(18f, r[2], EPS);
        assertEquals(10f, r[1], EPS); // already tall enough
        assertEquals(30f, r[3], EPS);
    }

    @Test
    void instanceProjectsOccupiedSlotsAndSkipsUnknownEntities() {
        WorldMarkTable marks = new WorldMarkTable();
        ScreenProjector p = new ScreenProjector();
        int a = marks.claim("a");
        int b = marks.claim("b");
        int c = marks.claim("c");
        marks.setBox(a, MarkKind.LANDING, 9.5, 63, -12.5, 10.5, 64, -11.5, null, 0);
        marks.setEntity(b, MarkKind.PRIMARY_TARGET, 7, 1f, -1f, 3f, "Zombie", null, 0);
        marks.setEntity(c, MarkKind.PRIMARY_TARGET, 99, 1f, -1f, 3f, "Ghost", null, 0);
        ScreenProjector.EntityBounds bounds = (id, out) -> {
            if (id != 7) {
                return false;
            }
            out[0] = 9.7;
            out[1] = 64;
            out[2] = -6.3;
            out[3] = 10.3;
            out[4] = 65.95;
            out[5] = -5.7;
            return true;
        };
        p.project(marks, view(), proj(), 10, 64, 0, bounds);
        assertEquals(ScreenProjector.ON_SCREEN, p.state(a, marks.generation(a)));
        assertEquals(ScreenProjector.ON_SCREEN, p.state(b, marks.generation(b)));
        assertEquals(ScreenProjector.NONE, p.state(c, marks.generation(c)));
        assertEquals(ScreenProjector.NONE, p.state(3, 0));
        // landing top 12 blocks ahead, 0..1 below the eye: centred horizontally, below the screen centre
        assertEquals(0.5f, (p.value(a, ScreenProjector.MIN_X) + p.value(a, ScreenProjector.MAX_X)) * 0.5f, 1e-3f);
        assertEquals(0.5f, p.value(a, ScreenProjector.MIN_Y), 1e-3f); // the top is at eye height
        assertEquals(true, p.value(a, ScreenProjector.MAX_Y) > 0.5f);
        // a new generation of the slot is not valid until projected again
        marks.clear(a);
        marks.setBox(a, MarkKind.LANDING, 0, 0, 0, 1, 1, 1, null, 0);
        assertEquals(ScreenProjector.NONE, p.state(a, marks.generation(a)));
    }
}
