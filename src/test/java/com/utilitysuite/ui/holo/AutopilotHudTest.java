package com.utilitysuite.ui.holo;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.INoticeBoard;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AutopilotHudTest {

    private final long[] now = {100_000L};
    private final List<String> posted = new ArrayList<>();
    private final List<NoticeKind> kinds = new ArrayList<>();
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
        INoticeBoard board = (kind, title, detail) -> {
            kinds.add(kind);
            posted.add(title);
        };
        hud = new AutopilotHud(() -> now[0], () -> board);
        canvas = new RecordingCanvas();
        hud.update(false, null); // seeds: the autopilot is off when the world loads
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
    void placesItselfAndIsIdleWhileOff() {
        assertEquals(HudSlot.FREE, hud.slot());
        frame();
        assertFalse(hud.wantsDraw());
        assertEquals(0, canvas.calls);
    }

    @Test
    void engagePulsesFromTheCrosshairFadesAndPostsOneNotice() {
        hud.update(true, null);
        assertEquals(List.of("AUTOPILOT ONLINE"), posted);
        assertEquals(NoticeKind.INFO, kinds.get(0));
        now[0] += 300;
        frame();
        assertEquals(2, canvas.calls, "one segment each side of the crosshair");
        assertEquals(HoloToken.ACCENT, canvas.lastToken);
        float mid = canvas.lastAlpha;
        assertTrue(mid > 0f && mid < 1f, "fading: " + mid);
        now[0] += 299;
        frame();
        assertTrue(canvas.calls > 0 && canvas.lastAlpha < mid);
        now[0] += 2;
        frame();
        assertFalse(hud.wantsDraw(), "the pulse is over after 600 ms");
        assertEquals(0, canvas.calls);
        assertEquals(1, posted.size());
    }

    @Test
    void switchOffPostsOfflineWithoutAPulse() {
        hud.update(true, null);
        now[0] += 700;
        hud.update(false, null);
        assertEquals(List.of("AUTOPILOT ONLINE", "AUTOPILOT OFFLINE"), posted);
        frame();
        assertEquals(0, canvas.calls);
    }

    @Test
    void anAutopilotThatWasAlreadyOnWhenTheWorldLoadedDoesNotEngage() {
        AutopilotHud fresh = new AutopilotHud(() -> now[0], () -> (k, t, d) -> posted.add(t));
        fresh.update(true, null);
        assertTrue(posted.isEmpty());
        assertFalse(fresh.wantsDraw());
        fresh.reset();
        fresh.update(true, null);
        assertTrue(posted.isEmpty());
    }

    @Test
    void armedMarkerOnlyWithTheFlag() {
        hud.update(true, null);
        now[0] += 1_000;
        frame();
        assertEquals(0, canvas.calls, "armed is not a reason to draw");
        HoloTheme.armedMarker = true;
        frame();
        assertEquals(1, canvas.calls);
        assertEquals(8, canvas.quads, "four corner brackets");
        assertEquals(HoloToken.ACCENT, canvas.lastToken);
        assertTrue(canvas.lastAlpha < 0.5f, "dim");
        hud.update(false, null);
        frame();
        assertEquals(0, canvas.calls, "off: no marker");
    }

    @Test
    void takeoverBracketsSnapInAtTheInsetInSync() {
        hud.update(true, null);
        now[0] += 1_000;
        hud.update(true, Reflex.FALL_CLUTCH);
        frame();
        assertEquals(1, canvas.calls);
        assertEquals(HoloToken.SYNC, canvas.lastToken);
        assertTrue(canvas.lastX > AutopilotHud.INSET, "starts wider and snaps in: " + canvas.lastX);
        now[0] += AutopilotHud.SNAP_MS;
        frame();
        assertEquals(AutopilotHud.INSET, canvas.lastX, 0.01f);
        assertEquals(1f, canvas.lastAlpha, 1e-6f);
        // the brackets stay while the reflex acts
        now[0] += 5_000;
        frame();
        assertEquals(1, canvas.calls);
    }

    @Test
    void releaseFadesOverFourHundredMillisecondsThenZeroCalls() {
        hud.update(true, null);
        now[0] += 1_000;
        hud.update(true, Reflex.SHIELD);
        now[0] += 500;
        hud.update(true, null);
        now[0] += 200;
        frame();
        assertEquals(1, canvas.calls);
        assertEquals(0.5f, canvas.lastAlpha, 0.01f);
        now[0] += 199;
        frame();
        assertEquals(1, canvas.calls);
        now[0] += 2;
        frame();
        assertFalse(hud.wantsDraw(), "gone 400 ms after the release");
        assertEquals(0, canvas.calls);
    }

    @Test
    void aReflexChangeKeepsTheBracketsAndSwitchingOffEndsTheTakeover() {
        hud.update(true, null);
        now[0] += 1_000;
        hud.update(true, Reflex.PARKOUR);
        now[0] += 300;
        hud.update(true, Reflex.DODGE);
        frame();
        assertEquals(1, canvas.calls);
        assertEquals(1f, canvas.lastAlpha, 1e-6f);
        hud.update(false, null); // switched off while acting
        now[0] += 401;
        frame();
        assertEquals(0, canvas.calls);
    }

    @Test
    void motionOffShowsTheBracketsAtOnceAndDropsThemAtOnce() {
        HoloTheme.motion = Motion.OFF;
        hud.update(true, null);
        frame();
        assertEquals(0, canvas.calls, "no pulse without motion");
        hud.update(true, Reflex.TOTEM);
        frame();
        assertEquals(1, canvas.calls);
        assertEquals(AutopilotHud.INSET, canvas.lastX, 0.01f);
        assertEquals(1f, canvas.lastAlpha, 1e-6f);
        hud.update(true, null);
        frame();
        assertEquals(0, canvas.calls);
    }

    @Test
    void reducedMotionDropsThePulseAndTheSnapButFadesOnRelease() {
        HoloTheme.motion = Motion.REDUCED;
        hud.update(true, null);
        frame();
        assertEquals(0, canvas.calls);
        hud.update(true, Reflex.TOTEM);
        frame();
        assertEquals(AutopilotHud.INSET, canvas.lastX, 0.01f);
        hud.update(true, null);
        now[0] += 200;
        frame();
        assertEquals(1, canvas.calls);
    }

    @Test
    void stemLinesAreConstantsPerReflex() {
        assertNull(AutopilotHud.stemLine(null));
        assertEquals("STEM - FALL CLUTCH", AutopilotHud.stemLine(Reflex.FALL_CLUTCH));
        assertEquals("STEM - PARKOUR BLOCKS", AutopilotHud.stemLine(Reflex.PARKOUR_BLOCKS));
        assertSame(AutopilotHud.stemLine(Reflex.TOTEM), AutopilotHud.stemLine(Reflex.TOTEM));
        for (Reflex r : Reflex.values()) {
            assertTrue(AutopilotHud.stemLine(r).startsWith("STEM - "));
            for (Reflex o : Reflex.values()) {
                if (o != r) {
                    assertNotSame(AutopilotHud.stemLine(o), AutopilotHud.stemLine(r));
                }
            }
        }
    }

    @Test
    void budgetAndAllocation() {
        HoloTheme.armedMarker = true;
        hud.update(true, null);
        hud.update(true, Reflex.FALL_CLUTCH);
        frame();
        assertTrue(canvas.quads <= 16 && canvas.textRuns == 0, "quads " + canvas.quads);
        assumeTrue(AllocationProbe.supported());
        int[] i = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            int n = ++i[0];
            if (n % 1_000 == 0) {
                hud.update(true, (n / 1_000) % 2 == 0 ? Reflex.FALL_CLUTCH : null);
            }
            now[0]++;
            canvas.reset();
            if (hud.wantsDraw()) {
                hud.draw(canvas, 0f, 0f, 1f);
            }
        });
        assertTrue(canvas.calls > 0);
        assertTrue(bytes < 1_024, "autopilot HUD allocated " + bytes + " bytes");
    }
}
