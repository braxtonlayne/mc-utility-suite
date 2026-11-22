package com.utilitysuite.ui.holo;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NoticeLayerTest {

    private final long[] now = {10_000L};
    private final boolean[] clean = {false};
    private Motion savedMotion;
    private NoticeBoard board;
    private NoticeLayer layer;
    private RecordingCanvas canvas;
    private long nanos;

    @BeforeEach
    void setUp() {
        savedMotion = HoloTheme.motion;
        HoloTheme.motion = Motion.OFF;
        FrameClock.resetForTests();
        nanos = 2_000_000_000L;
        FrameClock.sampleAt(nanos);
        board = new NoticeBoard(() -> now[0], () -> clean[0]);
        layer = new NoticeLayer(board, new AnimStore(256));
        canvas = new RecordingCanvas();
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = savedMotion;
        FrameClock.resetForTests();
    }

    private void frame() {
        if (layer.wantsDraw()) {
            layer.draw(canvas, 0f, 0f, 1f);
        }
    }

    private void tick(long ms) {
        now[0] += ms;
        nanos += ms * 1_000_000L;
        FrameClock.sampleAt(nanos);
    }

    @Test
    void placesItselfAndDrawsNothingWithoutNotices() {
        assertEquals(HudSlot.FREE, layer.slot());
        frame();
        assertFalse(layer.wantsDraw());
        assertEquals(0, canvas.calls);
    }

    @Test
    void oneToastIsAFramedPlateWithEdgeGlyphTitleAndHairline() {
        board.post(NoticeKind.DONE, "Job done", null);
        frame();
        assertEquals(1, canvas.frames);
        assertEquals(1, canvas.plates, "the kind-coloured left edge");
        assertEquals(1, canvas.glyphs);
        assertEquals(1, canvas.textRuns);
        assertEquals("Job done", canvas.lastText);
        assertTrue(canvas.calls <= 6);
        assertEquals(HoloToken.SYNC, NoticeLayer.token(NoticeKind.DONE));
        assertEquals(HoloToken.WARN, NoticeLayer.token(NoticeKind.WARN));
        assertEquals(HoloToken.DANGER, NoticeLayer.token(NoticeKind.ERROR));
        assertEquals(HoloToken.ACCENT, NoticeLayer.token(NoticeKind.INFO));
    }

    @Test
    void detailAddsOneLineAndAMergedCounterShowsInTheTitle() {
        board.post(NoticeKind.WARN, "Auto-Fish stopped", "no rod");
        board.post(NoticeKind.WARN, "Auto-Fish stopped", "no rod");
        frame();
        assertEquals(2, canvas.textRuns);
        assertEquals("no rod", canvas.lastText);
        assertEquals("Auto-Fish stopped x2", board.at(0).shown);
    }

    @Test
    void sitsInTheBottomRightCornerAndStacksUpward() {
        board.post(NoticeKind.ERROR, "one", null);
        frame();
        float y1 = canvas.lastY;
        assertTrue(canvas.screenH - y1 < 40f);
        canvas.reset();
        board.post(NoticeKind.ERROR, "two", null);
        frame();
        // the newest is nearest the bottom edge, the older one sits above it
        assertTrue(canvas.lastY < canvas.screenH);
        assertEquals(2, canvas.frames);
    }

    @Test
    void atMostThreeAreVisible() {
        for (int i = 0; i < 5; i++) {
            board.post(NoticeKind.ERROR, "error " + i, "detail " + i);
        }
        assertEquals(5, board.count());
        frame();
        assertEquals(NoticeBoard.MAX_VISIBLE, canvas.frames);
        assertEquals(NoticeBoard.MAX_VISIBLE * 2, canvas.textRuns);
    }

    @Test
    void expiredNoticesStopTheLayerAndEveryCallAgain() {
        board.post(NoticeKind.INFO, "short", null);
        board.post(NoticeKind.WARN, "long", null);
        frame();
        assertEquals(2, canvas.frames);
        tick(NoticeBoard.LIFE_MS);
        canvas.reset();
        frame();
        assertEquals(1, canvas.frames, "the INFO is gone, the WARN lives 4 s");
        tick(2_000L);
        canvas.reset();
        frame();
        assertEquals(0, canvas.calls);
        assertFalse(layer.wantsDraw());
        assertEquals(0, board.count());
    }

    @Test
    void cleanScreenDropsToastsAndTheHostGateDrawsNothing() {
        HudHost host = new HudHost(new AnimStore(64));
        host.sampleClock = false;
        host.register(layer);
        clean[0] = true;
        board.post(NoticeKind.ERROR, "dropped", null);
        assertEquals(0, board.count());
        // a toast that was already alive when Clean-Screen came on is not drawn by the host either
        clean[0] = false;
        board.post(NoticeKind.ERROR, "alive", null);
        assertFalse(host.frame(canvas, true));
        assertEquals(0, canvas.calls);
        assertTrue(host.frame(canvas, false));
        assertTrue(canvas.calls > 0);
    }

    @Test
    void fullMotionSlidesInFadesInAndOutAndReflows() {
        HoloTheme.motion = Motion.FULL;
        board.post(NoticeKind.WARN, "slide", null);
        frame(); // the first frame is fully transparent: nothing is drawn yet
        assertEquals(0, canvas.calls);
        tick(16);
        canvas.reset();
        frame();
        float x0 = canvas.lastX;
        float a0 = canvas.lastAlpha;
        for (int i = 0; i < 20; i++) {
            tick(16);
            canvas.reset();
            frame();
        }
        float xs = canvas.lastX;
        assertTrue(a0 < 0.5f, "fading in: " + a0);
        assertTrue(xs < x0 - 5f, "slid in from the right: " + x0 + " -> " + xs);
        assertEquals(1f, canvas.lastAlpha, 0.25f);
        // the last 220 ms fade it out again
        tick(NoticeBoard.LIFE_LONG_MS - 20 * 16 - 100);
        canvas.reset();
        frame();
        assertTrue(canvas.calls > 0);
        assertTrue(canvas.lastAlpha < 0.6f, "leaving: " + canvas.lastAlpha);
    }

    @Test
    void reducedMotionKeepsTheFadeAndDropsTheSlide() {
        HoloTheme.motion = Motion.REDUCED;
        board.post(NoticeKind.WARN, "fade", null);
        frame();
        tick(16);
        canvas.reset();
        frame();
        float x0 = canvas.lastX;
        assertTrue(canvas.calls > 0);
        for (int i = 0; i < 20; i++) {
            tick(16);
            canvas.reset();
            frame();
        }
        assertEquals(x0, canvas.lastX, 0.01f);
    }

    @Test
    void longDetailIsCutToFit() {
        board.post(NoticeKind.INFO, "t", "x".repeat(200));
        frame();
        assertTrue(board.at(0).detail.endsWith("..."));
        assertTrue(board.at(0).detail.length() * 6f <= NoticeLayer.MAX_W);
    }

    @Test
    void threeToastFrameStaysInBudgetAndAllocatesNothing() {
        board.post(NoticeKind.ERROR, "Auto-Fish stopped: no rod", "3 catches");
        board.post(NoticeKind.WARN, "Pearl on cooldown", "wait 4 s");
        board.post(NoticeKind.DONE, "Queued job #4", "gather 32 iron_ingot");
        frame();
        assertTrue(canvas.quads < 80, "quads " + canvas.quads);
        assertEquals(6, canvas.textRuns);
        assumeTrue(AllocationProbe.supported());
        HoloTheme.motion = Motion.FULL;
        long[] t = {nanos};
        long bytes = AllocationProbe.measure(50_000, () -> {
            t[0] += 16_000_000L;
            FrameClock.sampleAt(t[0]); // the board clock stands still: the toasts live on
            canvas.reset();
            if (layer.wantsDraw()) {
                layer.draw(canvas, 0f, 0f, 1f);
            }
        });
        assertTrue(canvas.calls > 0);
        assertTrue(bytes < 1_024, "3-toast frame allocated " + bytes + " bytes");
    }
}
