package com.utilitysuite.ui.holo;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.AuthorityReason;
import com.utilitysuite.core.api.visual.AuthorityState;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import com.utilitysuite.core.api.visual.WorldMarkTable;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The per-frame paths (projecting 16 marks, drawing the reticles and the authority bar) allocate nothing. */
class ReticleAllocationTest {

    private static final long LIMIT = 1_024;

    @Test
    void projectingSixteenMarksAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        WorldMarkTable marks = new WorldMarkTable();
        for (int i = 0; i < IWorldMarks.SLOTS; i++) {
            int s = marks.claim("m" + i);
            if (i % 2 == 0) {
                marks.setEntity(s, MarkKind.PRIMARY_TARGET, i, 1f, 0.5f, 4f, "T", null, 0);
            } else {
                marks.setBox(s, MarkKind.THREAT, i, 0, i % 4 == 1 ? -5 : 5, i + 1, 1, i % 4 == 1 ? -4 : 6, null, 0);
            }
        }
        ScreenProjector p = new ScreenProjector();
        Matrix4f view = ScreenProjectorTest.view();
        Matrix4f proj = ScreenProjectorTest.proj();
        ScreenProjector.EntityBounds bounds = ReticleLayerTest.AHEAD;
        long bytes = AllocationProbe.measure(50_000, () -> p.project(marks, view, proj, 0.5, 64.0, -0.5, bounds));
        assertTrue(bytes < LIMIT, "project allocated " + bytes + " bytes");
    }

    @Test
    void drawingTheReticlesAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        WorldMarkTable marks = new WorldMarkTable();
        ReticleLayerTest.fillMix(marks);
        int extra = marks.claim("dim");
        marks.setEntity(extra, MarkKind.PRIMARY_TARGET, 99, 0.2f, 1f, 6f, "Far", "RETREAT",
                IWorldMarks.FLAG_TAG | IWorldMarks.FLAG_NO_LOS);
        ScreenProjector p = new ScreenProjector();
        p.project(marks, ScreenProjectorTest.view(), ScreenProjectorTest.proj(), 0, 0, 0, ReticleLayerTest.AHEAD);
        ReticleLayer layer = new ReticleLayer(p, marks, new AnimStore(256));
        RecordingCanvas canvas = new RecordingCanvas();
        long bytes = AllocationProbe.measure(50_000, () -> {
            if (layer.wantsDraw()) {
                layer.draw(canvas, 0f, 0f, 1f);
            }
        });
        assertTrue(canvas.calls > 0);
        assertTrue(bytes < LIMIT, "reticle draw allocated " + bytes + " bytes");
    }

    @Test
    void drawingTheAuthorityBarAllocatesNothing() {
        assumeTrue(AllocationProbe.supported());
        AuthorityState state = new AuthorityState();
        state.publish("movement_assist", 1f, false, AuthorityReason.LANDING, 7, 20, 3);
        state.noteOverride(1_000_000, true);
        AuthorityIndicator indicator = new AuthorityIndicator(state, new AnimStore(64));
        RecordingCanvas canvas = new RecordingCanvas();
        long bytes = AllocationProbe.measure(50_000, () -> {
            if (indicator.wantsDraw()) {
                indicator.draw(canvas, 240f, 135f, 1f);
            }
        });
        assertTrue(canvas.calls > 0);
        assertTrue(bytes < LIMIT, "authority draw allocated " + bytes + " bytes");
    }
}
