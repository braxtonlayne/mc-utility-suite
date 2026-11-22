package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HudHostTest {

    /** A box of a fixed size that records where the host put it. */
    static final class Box implements HudElement {
        final String id;
        final HudSlot slot;
        final int priority;
        final float w;
        final float h;
        boolean wants = true;
        float x = Float.NaN;
        float y = Float.NaN;
        float alpha = Float.NaN;
        int draws;

        Box(String id, HudSlot slot, int priority, float w, float h) {
            this.id = id;
            this.slot = slot;
            this.priority = priority;
            this.w = w;
            this.h = h;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public HudSlot slot() {
            return slot;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean wantsDraw() {
            return wants;
        }

        @Override
        public float width(IHoloCanvas c) {
            return w;
        }

        @Override
        public float height(IHoloCanvas c) {
            return h;
        }

        @Override
        public void draw(IHoloCanvas c, float px, float py, float a) {
            x = px;
            y = py;
            alpha = a;
            draws++;
            c.plate(px, py, w, h, HoloToken.GLASS_HUD, a);
        }
    }

    private long nanos;
    private HudHost host;
    private RecordingCanvas canvas;

    @BeforeEach
    void setUp() {
        FrameClock.resetForTests();
        HoloTheme.motion = Motion.FULL;
        nanos = 5_000_000_000L;
        FrameClock.sampleAt(nanos);
        host = new HudHost(new AnimStore(256));
        host.sampleClock = false;
        canvas = new RecordingCanvas();
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = Motion.FULL;
        FrameClock.resetForTests();
    }

    private void frame(long ms, boolean clean) {
        nanos += ms * 1_000_000L;
        FrameClock.sampleAt(nanos);
        host.frame(canvas, clean);
    }

    @Test
    void nothingWantingMeansNoCalls() {
        Box a = new Box("a", HudSlot.TOP_LEFT, 0, 50, 10);
        a.wants = false;
        host.register(a);
        assertFalse(host.frame(canvas, false));
        assertEquals(0, canvas.calls);
        host.enabled = false;
        a.wants = true;
        assertFalse(host.frame(canvas, false));
        assertEquals(0, canvas.calls);
    }

    @Test
    void cleanScreenDrawsOnlyExemptElements() {
        Box a = new Box("a", HudSlot.TOP_CENTER, 0, 50, 10);
        Box badge = new Box("badge", HudSlot.TOP_RIGHT, 0, 30, 10);
        host.register(a);
        host.register(badge);
        badge.wants = false;
        assertFalse(host.frame(canvas, true));
        assertEquals(0, canvas.calls);
        host.allowInCleanScreen(badge, true);
        badge.wants = true;
        assertTrue(host.frame(canvas, true));
        assertEquals(1, canvas.calls);
        assertEquals(0, a.draws);
        assertEquals(1, badge.draws);
    }

    @Test
    void slotsStackByPriorityAndAnchorToTheirEdges() {
        Box low = new Box("low", HudSlot.TOP_LEFT, 1, 40, 10);
        Box high = new Box("high", HudSlot.TOP_LEFT, 5, 60, 20);
        Box right = new Box("right", HudSlot.TOP_RIGHT, 0, 70, 12);
        Box bottom1 = new Box("b1", HudSlot.BOTTOM_RIGHT, 2, 50, 16);
        Box bottom2 = new Box("b2", HudSlot.BOTTOM_RIGHT, 1, 40, 10);
        Box center = new Box("c", HudSlot.TOP_CENTER, 0, 100, 20);
        Box cross = new Box("x", HudSlot.CROSSHAIR, 0, 0, 0);
        host.register(low);
        host.register(high);
        host.register(right);
        host.register(bottom1);
        host.register(bottom2);
        host.register(center);
        host.register(cross);
        frame(16, false);
        assertEquals(6f, high.x);
        assertEquals(6f, high.y);
        assertEquals(6f + 20 + HudHost.GAP, low.y);
        assertEquals(480f - 6f - 70f, right.x);
        assertEquals(6f, right.y);
        assertEquals(270f - 6f - 16f, bottom1.y);
        assertEquals(270f - 6f - 16f - HudHost.GAP - 10f, bottom2.y);
        assertEquals(190f, center.x);
        assertEquals(240f, cross.x);
        assertEquals(135f, cross.y);
    }

    @Test
    void neighboursReflowSmoothlyWhenOneLeaves() {
        Box top = new Box("top", HudSlot.TOP_LEFT, 5, 60, 20);
        Box below = new Box("below", HudSlot.TOP_LEFT, 1, 40, 10);
        host.register(top);
        host.register(below);
        frame(16, false);
        float start = below.y;
        assertEquals(30f, start);
        top.wants = false;
        frame(16, false);
        assertTrue(below.y < start && below.y > 6f, "glides, y=" + below.y);
        for (int i = 0; i < 60; i++) {
            frame(16, false);
        }
        assertEquals(6f, below.y);
    }

    @Test
    void reflowIsInstantWithMotionOff() {
        HoloTheme.motion = Motion.OFF;
        Box top = new Box("top", HudSlot.TOP_LEFT, 5, 60, 20);
        Box below = new Box("below", HudSlot.TOP_LEFT, 1, 40, 10);
        host.register(top);
        host.register(below);
        frame(16, false);
        top.wants = false;
        frame(16, false);
        assertEquals(6f, below.y);
        assertEquals(1f, below.alpha);
    }

    @Test
    void appearFadesInUnlessInstant() {
        Box a = new Box("fade", HudSlot.TOP_LEFT, 0, 40, 10);
        Box danger = new Box("danger", HudSlot.TOP_CENTER, 0, 40, 10);
        host.register(a);
        host.register(danger);
        host.setInstant(danger, true);
        frame(16, false);
        assertTrue(a.alpha < 0.5f, "alpha " + a.alpha);
        assertEquals(1f, danger.alpha);
        for (int i = 0; i < 20; i++) {
            frame(16, false);
        }
        assertEquals(1f, a.alpha);
        // leaving and coming back fades in again
        a.wants = false;
        frame(16, false);
        a.wants = true;
        frame(16, false);
        assertTrue(a.alpha < 0.5f, "re-appear alpha " + a.alpha);
    }

    @Test
    void zeroCallsAfterTheLastTransientEnds() {
        Box t = new Box("transient", HudSlot.TOP_CENTER, 0, 40, 10);
        host.register(t);
        for (int i = 0; i < 10; i++) {
            frame(16, false);
        }
        assertTrue(canvas.calls > 0);
        t.wants = false;
        canvas.reset();
        for (int i = 0; i < 10; i++) {
            frame(16, false);
        }
        assertEquals(0, canvas.calls);
    }

    @Test
    void unregisterRemovesAndKeepsOrder() {
        Box a = new Box("a", HudSlot.TOP_LEFT, 3, 10, 10);
        Box b = new Box("b", HudSlot.TOP_LEFT, 2, 10, 10);
        Box c = new Box("c", HudSlot.TOP_LEFT, 1, 10, 10);
        host.register(a);
        host.register(c);
        host.register(b);
        host.register(b); // twice is once
        assertEquals(3, host.size());
        host.unregister(a);
        assertEquals(2, host.size());
        frame(16, false);
        assertEquals(6f, b.y);
        assertEquals(20f, c.y);
    }
}
