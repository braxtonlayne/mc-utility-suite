package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import com.utilitysuite.core.api.visual.WorldMarkTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReticleLayerTest {

    /** Every entity stands 4 blocks ahead of the camera at the origin (camera looks down -Z). */
    static final ScreenProjector.EntityBounds AHEAD = (id, out) -> {
        out[0] = -0.3;
        out[1] = -1.0;
        out[2] = -4.3;
        out[3] = 0.3;
        out[4] = 0.95;
        out[5] = -3.7;
        return true;
    };

    private Motion savedMotion;
    private WorldMarkTable marks;
    private ScreenProjector projector;
    private ReticleLayer layer;
    private RecordingCanvas canvas;

    @BeforeEach
    void setUp() {
        savedMotion = HoloTheme.motion;
        HoloTheme.motion = Motion.OFF; // lock-on lands at once unless a test turns motion on
        FrameClock.resetForTests();
        marks = new WorldMarkTable();
        projector = new ScreenProjector();
        layer = new ReticleLayer(projector, marks, new AnimStore(256));
        canvas = new RecordingCanvas();
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = savedMotion;
        FrameClock.resetForTests();
    }

    void project() {
        projector.project(marks, ScreenProjectorTest.view(), ScreenProjectorTest.proj(), 0, 0, 0, AHEAD);
    }

    @Test
    void slotIsFree() {
        assertEquals(HudSlot.FREE, layer.slot());
    }

    @Test
    void emptyTableDrawsNothing() {
        project();
        assertFalse(layer.wantsDraw());
        layer.draw(canvas, 0, 0, 1f);
        assertEquals(0, canvas.calls);
    }

    @Test
    void unprojectedMarkIsNotDrawn() {
        int s = marks.claim("t");
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, 7, 1f, -1f, 4f, "Zombie", null, 0);
        assertFalse(layer.wantsDraw()); // not projected yet this frame
        layer.draw(canvas, 0, 0, 1f);
        assertEquals(0, canvas.calls);
    }

    @Test
    void oneTargetWithTagDrawsEveryPart() {
        int s = marks.claim("t");
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, 7, 0.5f, 0.5f, 4.2f, "Zombie", "HOLD GAP",
                IWorldMarks.FLAG_TAG);
        marks.setSide(s, 1);
        project();
        assertTrue(layer.wantsDraw());
        layer.draw(canvas, 0, 0, 1f);
        // brackets 1, health track + 3 segments 4, charge track + fill 2, chevron 1, leader 1, frame 1,
        // texts: name, hp, %, distance, m, state word 6
        assertEquals(16, canvas.calls);
        assertEquals(4, canvas.plates);
        assertEquals(1, canvas.frames);
        assertEquals(1, canvas.glyphs);
        assertEquals(3, canvas.ribbons);
        assertEquals(6, canvas.textRuns);
        assertEquals("HOLD GAP", canvas.lastText);
    }

    @Test
    void noLineOfSightIsDimAndDashed() {
        int s = marks.claim("t");
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, 7, -1f, -1f, 4f, "Zombie", null, IWorldMarks.FLAG_NO_LOS);
        project();
        layer.draw(canvas, 0, 0, 1f);
        assertEquals(16, canvas.calls); // four dashes per corner
        assertEquals(ReticleLayer.NO_LOS_DIM, canvas.lastAlpha, 1e-6f);
    }

    @Test
    void lethalOffScreenThreatIsAnEdgeChevronWithDistance() {
        int s = marks.claim("threat");
        marks.setBox(s, MarkKind.THREAT, 2, -0.5, 2, 3, 0.5, 3, null, IWorldMarks.FLAG_LETHAL); // behind, right
        project();
        assertEquals(ScreenProjector.BEHIND, projector.state(s, marks.generation(s)));
        layer.draw(canvas, 0, 0, 1f);
        assertEquals(3, canvas.calls);
        assertEquals(2, canvas.ribbons);
        assertEquals(1, canvas.textRuns);
        assertEquals(HoloToken.DANGER, canvas.lastToken);
        assertTrue(canvas.lastX > canvas.screenW * 0.5f, "chevron on the right side");
    }

    @Test
    void onScreenThreatIsADiamond() {
        int s = marks.claim("threat");
        marks.setBox(s, MarkKind.THREAT, -0.2, -0.2, -5.2, 0.2, 0.2, -4.8, null, 0);
        project();
        layer.draw(canvas, 0, 0, 1f);
        assertEquals(4, canvas.ribbons);
        assertEquals(HoloToken.WARN, canvas.lastToken);
    }

    @Test
    void allyLandingAndObjectiveAreSmallBrackets() {
        marks.setEntity(marks.claim("ally"), MarkKind.ALLY, 3, 1f, -1f, 4f, "Friend", null, 0);
        marks.setBox(marks.claim("landing"), MarkKind.LANDING, -0.5, -2, -6, 0.5, -1, -5, null, 0);
        marks.setBox(marks.claim("goal"), MarkKind.OBJECTIVE, -0.5, -2, -9, 0.5, -1, -8, "Ore", IWorldMarks.FLAG_TAG);
        project();
        layer.draw(canvas, 0, 0, 1f);
        assertEquals(4, canvas.calls); // three bracket sets and the objective label
        assertEquals(1, canvas.textRuns);
    }

    @Test
    void lockOnContractsFromWideToTheBox() {
        HoloTheme.motion = Motion.FULL;
        long t = 1_000_000_000L;
        FrameClock.sampleAt(t);
        FrameClock.sampleAt(t += 1_000_000L); // 1 ms
        int s = marks.claim("t");
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, 7, -1f, -1f, 4f, null, null, 0);
        project();
        layer.draw(canvas, 0, 0, 1f);
        float firstLeft = canvas.lastX;
        for (int i = 0; i < 6; i++) {
            FrameClock.sampleAt(t += 50_000_000L);
            canvas.reset();
            layer.draw(canvas, 0, 0, 1f);
        }
        float settledLeft = canvas.lastX;
        assertTrue(firstLeft < settledLeft - 5f, "starts wider: " + firstLeft + " vs " + settledLeft);
        // re-acquire: a new generation starts the contraction again
        marks.clear(s);
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, 7, -1f, -1f, 4f, null, null, 0);
        project();
        FrameClock.sampleAt(t += 1_000_000L);
        canvas.reset();
        layer.draw(canvas, 0, 0, 1f);
        assertTrue(canvas.lastX < settledLeft - 5f);
    }

    @Test
    void sixteenMarksStayUnderTheQuadBudget() {
        // worst case: every slot a tagged primary without line of sight
        for (int i = 0; i < IWorldMarks.SLOTS; i++) {
            int s = marks.claim("m" + i);
            marks.setEntity(s, MarkKind.PRIMARY_TARGET, i, 1f, 1f, 4f, "Target " + i, "COMBO",
                    IWorldMarks.FLAG_TAG | IWorldMarks.FLAG_NO_LOS);
            marks.setSide(s, -1);
        }
        project();
        layer.draw(canvas, 0, 0, 1f);
        assertTrue(canvas.quads < 250, "quads " + canvas.quads);
        assertTrue(canvas.textRuns <= 12, "text runs " + canvas.textRuns);
    }

    @Test
    void realisticMixStaysUnderTheQuadBudget() {
        fillMix(marks);
        project();
        layer.draw(canvas, 0, 0, 1f);
        assertTrue(canvas.quads < 250, "quads " + canvas.quads);
    }

    /** One tagged target, six threats (half behind), four allies, three landings, two objectives. */
    static void fillMix(WorldMarkTable marks) {
        int s = marks.claim("target");
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, 1, 0.7f, 0.4f, 4f, "Skeleton", "BAIT", IWorldMarks.FLAG_TAG);
        marks.setSide(s, 1);
        for (int i = 0; i < 6; i++) {
            double z = i % 2 == 0 ? -5 : 5;
            marks.setBox(marks.claim("threat" + i), MarkKind.THREAT, i - 3, 0, z, i - 2.5, 0.5, z + 0.5, null,
                    i == 0 ? IWorldMarks.FLAG_LETHAL : 0);
        }
        for (int i = 0; i < 4; i++) {
            marks.setEntity(marks.claim("ally" + i), MarkKind.ALLY, 10 + i, 1f, -1f, 4f, "Ally", null, 0);
        }
        for (int i = 0; i < 3; i++) {
            marks.setBox(marks.claim("landing" + i), MarkKind.LANDING, i, -2, -6, i + 1, -1, -5, null, 0);
        }
        for (int i = 0; i < 2; i++) {
            marks.setBox(marks.claim("goal" + i), MarkKind.OBJECTIVE, i, -2, -9, i + 1, -1, -8, "Goal",
                    IWorldMarks.FLAG_TAG);
        }
    }
}
