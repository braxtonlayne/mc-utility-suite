package com.utilitysuite.combat.coach;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import com.utilitysuite.ui.holo.FrameClock;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The coach HUD element: idle draws nothing, the cooldown arc follows the recharge, drawing allocates nothing. */
class FightCoachHudTest {

    /** One recorded arc call. */
    record Arc(float sweep, float width, HoloToken token, float alpha) {
    }

    private final CoachReadout readout = new CoachReadout();
    private boolean enabled = true;
    private boolean suppressed;
    private FightCoachHud hud;

    @BeforeEach
    void setUp() {
        FrameClock.resetForTests();
        FrameClock.sampleAt(1_000_000_000L);
        hud = new FightCoachHud(() -> enabled, readout, () -> suppressed);
    }

    private static void frameAt(long ms) {
        FrameClock.sampleAt(ms * 1_000_000L);
    }

    /** A canvas that records only the arcs (tests may allocate). */
    private static IHoloCanvas arcRecorder(List<Arc> out) {
        RecordingCanvas inner = new RecordingCanvas();
        return (IHoloCanvas) Proxy.newProxyInstance(IHoloCanvas.class.getClassLoader(),
                new Class<?>[] {IHoloCanvas.class}, (proxy, m, a) -> {
                    if (m.getName().equals("arc")) {
                        out.add(new Arc((Float) a[4], (Float) a[5], (HoloToken) a[6],
                                (Float) a[7]));
                    }
                    return m.invoke(inner, a);
                });
    }

    @Test
    void idleWantsNothing() {
        assertFalse(hud.wantsDraw(), "no fight: not visible");
        readout.visible = true;
        assertTrue(hud.wantsDraw());
        enabled = false;
        assertFalse(hud.wantsDraw(), "module off");
        enabled = true;
        suppressed = true;
        assertFalse(hud.wantsDraw(), "screen open or GUI hidden");
        // the HUD host makes no call for an element that does not want to draw: zero canvas calls
        RecordingCanvas c = new RecordingCanvas();
        if (hud.wantsDraw()) {
            hud.draw(c, 0f, 0f, 1f);
        }
        assertEquals(0, c.calls);
    }

    @Test
    void arcFillProportionFollowsCharge() {
        readout.visible = true;
        readout.charge01 = 0.5f;
        List<Arc> arcs = new ArrayList<>();
        hud.draw(arcRecorder(arcs), 240f, 135f, 1f);
        assertEquals(2, arcs.size());
        assertEquals(360f, arcs.get(0).sweep(), 1e-3f);
        assertEquals(HoloToken.LINE_FAINT, arcs.get(0).token());
        assertEquals(180f, arcs.get(1).sweep(), 1e-3f);
        assertEquals(HoloToken.ACCENT, arcs.get(1).token());

        arcs.clear();
        readout.charge01 = 0.25f;
        hud.draw(arcRecorder(arcs), 240f, 135f, 1f);
        assertEquals(90f, arcs.get(1).sweep(), 1e-3f);

        arcs.clear();
        readout.charge01 = 0f;
        hud.draw(arcRecorder(arcs), 240f, 135f, 1f);
        assertEquals(1, arcs.size(), "nothing filled yet: the track only");
    }

    @Test
    void readyFlashesThenRests() {
        readout.visible = true;
        readout.charge01 = 0.9f;
        frameAt(2000);
        hud.draw(new RecordingCanvas(), 0f, 0f, 1f);

        readout.charge01 = 1f;
        List<Arc> arcs = new ArrayList<>();
        frameAt(2010);
        hud.draw(arcRecorder(arcs), 0f, 0f, 1f);
        assertEquals(1, arcs.size(), "one full ring");
        assertEquals(360f, arcs.get(0).sweep(), 1e-3f);
        assertEquals(HoloToken.ALLY, arcs.get(0).token());
        assertEquals(1f, arcs.get(0).alpha(), 1e-4f);
        assertTrue(arcs.get(0).width() > FightCoachHud.ARC_WIDTH, "flash ring is thicker");

        arcs.clear();
        frameAt(2010 + FightCoachHud.FLASH_MS + 50);
        hud.draw(arcRecorder(arcs), 0f, 0f, 1f);
        assertEquals(FightCoachHud.READY_ALPHA, arcs.get(0).alpha(), 1e-4f, "resting ring after the flash");
        assertEquals(FightCoachHud.ARC_WIDTH, arcs.get(0).width(), 1e-4f);
    }

    @Test
    void critReadyUsesWarn() {
        readout.visible = true;
        readout.charge01 = 1f;
        readout.critReady = true;
        List<Arc> arcs = new ArrayList<>();
        hud.draw(arcRecorder(arcs), 0f, 0f, 1f);
        assertEquals(HoloToken.WARN, arcs.get(0).token());
    }

    @Test
    void textCuesOnlyWhenRaised() {
        readout.visible = true;
        readout.charge01 = 0.5f;
        RecordingCanvas c = new RecordingCanvas();
        hud.draw(c, 0f, 0f, 1f);
        assertEquals(0, c.textRuns);
        readout.shieldBreak = true;
        readout.retreat = true;
        c.reset();
        hud.draw(c, 0f, 0f, 1f);
        assertEquals(2, c.textRuns);
    }

    @Test
    void opponentArcShowsRechargeStillToGo() {
        readout.visible = true;
        readout.charge01 = 0.5f;
        readout.oppCharge01 = 0.25f;
        List<Arc> arcs = new ArrayList<>();
        hud.draw(arcRecorder(arcs), 0f, 0f, 1f);
        assertEquals(3, arcs.size(), "track, our fill, the opponent's arc");
        assertEquals(HoloToken.DANGER, arcs.get(2).token());
        assertEquals(270f, arcs.get(2).sweep(), 1e-3f);

        arcs.clear();
        readout.oppCharge01 = 1f;
        hud.draw(arcRecorder(arcs), 0f, 0f, 1f);
        assertEquals(2, arcs.size(), "opponent ready (or unread): no arc");
    }

    @Test
    void shieldTextOnlyWhileDisabledAndCached() {
        readout.visible = true;
        readout.charge01 = 0.5f;
        RecordingCanvas c = new RecordingCanvas();
        hud.draw(c, 0f, 0f, 1f);
        assertEquals(0, c.textRuns);
        readout.shieldDisabledTicks = 87;
        c.reset();
        hud.draw(c, 0f, 0f, 1f);
        assertEquals(1, c.textRuns);
        String a = hud.shieldText(87);
        assertEquals("SH 87", a);
        assertTrue(a == hud.shieldText(87), "same count: the cached string");
        assertEquals("SH 86", hud.shieldText(86));
    }

    @Test
    void drawingAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        readout.visible = true;
        readout.charge01 = 0.6f;
        readout.oppCharge01 = 0.3f;
        readout.shieldDisabledTicks = 42;
        readout.shieldBreak = true;
        readout.retreat = true;
        RecordingCanvas c = new RecordingCanvas();
        long bytes = AllocationProbe.measure(50_000, () -> {
            if (hud.wantsDraw()) {
                hud.draw(c, 240f, 135f, 1f);
            }
        });
        assertTrue(c.calls > 0);
        assertTrue(bytes < 1_024, "coach draw allocated " + bytes + " bytes");
    }
}
