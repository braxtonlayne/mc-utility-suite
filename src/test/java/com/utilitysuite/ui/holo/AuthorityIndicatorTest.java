package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.AuthorityReason;
import com.utilitysuite.core.api.visual.AuthorityState;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityIndicatorTest {

    private static final String OWNER = "movement_assist";

    private Motion savedMotion;
    private AuthorityState state;
    private AuthorityIndicator indicator;
    private RecordingCanvas canvas;

    @BeforeEach
    void setUp() {
        savedMotion = HoloTheme.motion;
        HoloTheme.motion = Motion.OFF;
        FrameClock.resetForTests();
        state = new AuthorityState();
        indicator = new AuthorityIndicator(state, new AnimStore(64));
        canvas = new RecordingCanvas();
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = savedMotion;
        FrameClock.resetForTests();
    }

    private void frame() {
        if (indicator.wantsDraw()) {
            indicator.draw(canvas, 240f, 135f, 1f);
        }
    }

    @Test
    void sitsOnTheCrosshair() {
        assertEquals(HudSlot.CROSSHAIR, indicator.slot());
    }

    @Test
    void inactiveStateDrawsNothing() {
        frame();
        assertFalse(indicator.wantsDraw());
        assertEquals(0, canvas.calls);
    }

    @Test
    void assistHoldShowsBothSharesAndTheReason() {
        state.publish(OWNER, 0.6f, false, AuthorityReason.CORNER, 0, 0, 0);
        frame();
        assertEquals(2, canvas.plates); // manual share + assist share
        assertEquals(1, canvas.textRuns);
        assertEquals("CORNER", canvas.lastText);
    }

    @Test
    void handBackDrainsWithTheTicksLeft() {
        assertEquals(1f, AuthorityIndicator.shownAssist(1f, 20, 20), 1e-6f);
        assertEquals(0.5f, AuthorityIndicator.shownAssist(1f, 10, 20), 1e-6f);
        assertEquals(0.25f, AuthorityIndicator.shownAssist(0f, 5, 20), 1e-6f); // publisher already reports 0
        assertEquals(0.3f, AuthorityIndicator.shownAssist(0.3f, 15, 20), 1e-6f); // never above the reported share
        assertEquals(0.4f, AuthorityIndicator.shownAssist(0.4f, 0, 20), 1e-6f);

        state.publish(OWNER, 1f, false, AuthorityReason.LANDING, 10, 20, 0);
        frame();
        assertEquals(2, canvas.plates);
        assertEquals("LANDING", canvas.lastText);
        // the last hand-back tick still shows a sliver of assist; after it only the user's share is left
        state.publish(OWNER, 0f, false, AuthorityReason.LANDING, 0, 20, 0);
        state.clear(OWNER);
        assertFalse(state.active());
    }

    @Test
    void fullTakeoverUsesTheSyncHue() {
        state.publish(OWNER, 1f, true, AuthorityReason.REFLEX, 0, 0, 0);
        indicator.wantsDraw();
        indicator.draw(canvas, 240f, 135f, 1f);
        assertEquals(1, canvas.plates); // assist only
        canvas.reset();
        state.publish(OWNER, 1f, true, AuthorityReason.NONE, 0, 0, 0);
        indicator.wantsDraw();
        indicator.draw(canvas, 240f, 135f, 1f);
        assertEquals(HoloToken.SYNC, canvas.lastToken);
        assertEquals(0, canvas.textRuns); // no reason, no label
    }

    @Test
    void overrideFlashesAndPlanLostTintsDanger() {
        state.publish(OWNER, 0.5f, false, AuthorityReason.EDGE_HOLD, 0, 0, 0);
        state.noteOverride(6, true);
        frame();
        assertEquals(2 + 2 + 1, canvas.calls); // two shares, two outline lines, label
        canvas.reset();
        state.noteOverride(6, false);
        frame();
        assertEquals(5, canvas.calls);
    }

    @Test
    void latchedKeysAddTheLock() {
        state.publish(OWNER, 0.5f, false, AuthorityReason.NONE, 0, 0, 1);
        frame();
        assertEquals(2 + 4, canvas.calls);
    }

    @Test
    void fadesOutAfterItEnds() {
        HoloTheme.motion = Motion.FULL;
        long t = 1_000_000_000L;
        FrameClock.sampleAt(t);
        state.publish(OWNER, 1f, false, AuthorityReason.LANDING, 0, 0, 0);
        assertTrue(indicator.wantsDraw());
        state.clear(OWNER);
        FrameClock.sampleAt(t += 50_000_000L);
        assertTrue(indicator.wantsDraw(), "still fading 50 ms after");
        indicator.draw(canvas, 240f, 135f, 1f);
        assertTrue(canvas.lastAlpha < 1f && canvas.lastAlpha > 0f);
        for (int i = 0; i < 4; i++) {
            FrameClock.sampleAt(t += 50_000_000L);
            indicator.wantsDraw();
        }
        assertFalse(indicator.wantsDraw(), "gone 200 ms after");
    }
}
