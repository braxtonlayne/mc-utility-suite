package com.utilitysuite.world.replay;

import com.utilitysuite.world.replay.PipRenderPolicy.Fallback;
import com.utilitysuite.world.replay.PipRenderPolicy.RenderMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipRenderPolicyTest {

    @Test
    void fullRenderFallsBackToWireframeWithAReason() {
        assertEquals(RenderMode.FULL, PipRenderPolicy.effective(RenderMode.FULL, false, false, false));
        assertEquals(Fallback.NONE, PipRenderPolicy.fallback(RenderMode.FULL, false, false, false));
        assertEquals(RenderMode.WIREFRAME, PipRenderPolicy.effective(RenderMode.FULL, true, false, false));
        assertEquals(Fallback.RENDERER_MOD, PipRenderPolicy.fallback(RenderMode.FULL, true, true, true),
                "a renderer mod is the first reason named");
        assertEquals(Fallback.FAILED, PipRenderPolicy.fallback(RenderMode.FULL, false, true, true));
        assertEquals(Fallback.FABULOUS, PipRenderPolicy.fallback(RenderMode.FULL, false, true, false));
        assertEquals(RenderMode.WIREFRAME, PipRenderPolicy.effective(RenderMode.FULL, false, true, false));
    }

    @Test
    void otherModesAreUntouched() {
        assertEquals(RenderMode.WIREFRAME, PipRenderPolicy.effective(RenderMode.WIREFRAME, true, true, true));
        assertEquals(RenderMode.OFF, PipRenderPolicy.effective(RenderMode.OFF, true, true, true));
        assertEquals(Fallback.NONE, PipRenderPolicy.fallback(RenderMode.WIREFRAME, true, true, true));
    }

    @Test
    void knownRendererModsAreDetected() {
        assertTrue(PipRenderPolicy.anyIncompatible(Set.of("sodium")::contains));
        assertTrue(PipRenderPolicy.anyIncompatible(Set.of("minecraft", "embeddium")::contains));
        assertTrue(PipRenderPolicy.anyIncompatible(Set.of("iris")::contains));
        assertFalse(PipRenderPolicy.anyIncompatible(Set.of("minecraft", "neoforge", "baritoe")::contains));
    }

    @Test
    void targetSizeIsReducedAndClamped() {
        // 160 GUI px at GUI scale 3 = 480 screen px; 50% = 240.
        assertEquals(240, PipRenderPolicy.targetWidth(160, 3.0, 50));
        assertEquals(135, PipRenderPolicy.targetHeight(240));
        assertEquals(PipRenderPolicy.MIN_TARGET_W, PipRenderPolicy.targetWidth(80, 1.0, 10));
        assertEquals(PipRenderPolicy.MAX_TARGET_W, PipRenderPolicy.targetWidth(320, 6.0, 100));
        assertEquals(PipRenderPolicy.targetWidth(160, 2.0, 100), PipRenderPolicy.targetWidth(160, 2.0, 500),
                "percent is clamped to 100");
    }

    @Test
    void frameGatePassesOnceEveryNFramesAndCanBeForced() {
        FrameGate gate = new FrameGate();
        gate.setEvery(3);
        int passes = 0;
        for (int i = 0; i < 30; i++) {
            if (gate.tick()) {
                passes++;
            }
        }
        assertEquals(10, passes);
        gate.tick(); // pass (frame 31)
        assertFalse(gate.tick());
        gate.invalidate();
        assertTrue(gate.tick(), "invalidate forces the next frame through");
        gate.setEvery(0);
        gate.invalidate();
        assertTrue(gate.tick() && gate.tick(), "every is at least 1");
    }
}
