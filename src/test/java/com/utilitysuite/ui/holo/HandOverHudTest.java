package com.utilitysuite.ui.holo;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.INoticeBoard;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-over key on the HUD: nothing while idle, the takeover brackets and one line while held. */
class HandOverHudTest {

    private final long[] now = {100_000L};
    private final List<String> posted = new ArrayList<>();
    private Motion savedMotion;
    private boolean savedMarker;
    private AutopilotHud hud;
    private RecordingCanvas canvas;

    @BeforeEach
    void setUp() {
        savedMotion = HoloTheme.motion;
        savedMarker = HoloTheme.armedMarker;
        HoloTheme.motion = Motion.FULL;
        HoloTheme.armedMarker = false;
        INoticeBoard board = (kind, title, detail) -> posted.add(title);
        hud = new AutopilotHud(() -> now[0], () -> board);
        canvas = new RecordingCanvas();
        hud.update(false, null, false); // seeds: off, key up
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = savedMotion;
        HoloTheme.armedMarker = savedMarker;
    }

    private void frame() {
        canvas.reset();
        if (hud.wantsDraw()) {
            hud.draw(canvas, 0f, 0f, 1f);
        }
    }

    @Test
    void nothingIsDrawnAndNoLineIsShownWhileIdle() {
        for (int i = 0; i < 40; i++) {
            hud.update(false, null, false);
            now[0] += 50;
            frame();
            assertFalse(hud.wantsDraw());
            assertEquals(0, canvas.calls);
        }
        assertNull(AutopilotHud.handOverLine(false));
        assertTrue(posted.isEmpty());
    }

    @Test
    void heldShowsTheBracketsAndOneLineWithTheToggleOff() {
        hud.update(false, null, true);
        assertEquals(List.of(AutopilotHud.HAND_OVER_ON), posted, "one notice, and no AUTOPILOT ONLINE");
        assertEquals("STEM - FULL CONTROL", AutopilotHud.handOverLine(true));
        now[0] += AutopilotHud.SNAP_MS + 20;
        frame();
        assertTrue(canvas.calls > 0, "takeover brackets while held");
        assertEquals(HoloToken.SYNC, canvas.lastToken);
        int calls = canvas.calls;
        // a reflex acting and finishing under the key does not blink the brackets
        hud.update(false, Reflex.FALL_CLUTCH, true);
        hud.update(false, null, true);
        frame();
        assertEquals(calls, canvas.calls);
        assertEquals(1f, canvas.lastAlpha, 1e-4f);
        assertEquals(1, posted.size());
    }

    @Test
    void releaseFadesAndThenDrawsNothing() {
        hud.update(false, null, true);
        now[0] += 1_000;
        hud.update(false, null, false);
        assertEquals(List.of(AutopilotHud.HAND_OVER_ON, AutopilotHud.HAND_OVER_OFF), posted);
        now[0] += AutopilotHud.RELEASE_MS / 2;
        frame();
        assertTrue(canvas.calls > 0 && canvas.lastAlpha < 1f, "fading out");
        now[0] += AutopilotHud.RELEASE_MS;
        frame();
        assertFalse(hud.wantsDraw(), "idle again: zero draw calls");
        assertEquals(0, canvas.calls);
    }

    @Test
    void theOldTwoArgumentUpdateNeverShowsTheKey() {
        hud.update(false, null);
        frame();
        assertEquals(0, canvas.calls);
        assertTrue(posted.isEmpty());
    }

    @Test
    void updateAndDrawDoNotAllocateWhileHeld() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        hud.update(false, null, true);
        long bytes = AllocationProbe.measure(100_000, () -> {
            now[0] += 3;
            hud.update(false, (now[0] & 64) == 0 ? Reflex.SHIELD : null, true);
            canvas.reset();
            if (hud.wantsDraw()) {
                hud.draw(canvas, 0f, 0f, 1f);
            }
        });
        assertTrue(bytes < 16_384, "hand-over HUD allocated " + bytes + " bytes");
    }
}
