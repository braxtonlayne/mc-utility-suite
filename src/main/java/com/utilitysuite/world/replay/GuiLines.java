package com.utilitysuite.world.replay;

import com.utilitysuite.world.minimap.GuiMesh;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Thick 2D line batches for GUI panels (replay orbit view, picture-in-picture panel): segments in ndc (-1..1, y up)
 * mapped into a screen rectangle, drawn as thin quads in one GUI element ({@link GuiMesh}, the same path the minimap
 * uses, so Sodium and shader packs are not involved). {@link #begin} / {@link #segment} / {@link #end}, at most one
 * batch per frame per instance (the element is recycled on the next frame); no allocation in steady state beyond the
 * element's bounds record.
 */
final class GuiLines {

    private final GuiMesh mesh = new GuiMesh();
    private GuiGraphicsExtractor g;
    private float x0;
    private float y0;
    private float w;
    private float h;

    void begin(GuiGraphicsExtractor graphics, float left, float top, float width, float height) {
        g = graphics;
        x0 = left;
        y0 = top;
        w = width;
        h = height;
        mesh.begin(graphics, RenderPipelines.GUI, TextureSetup.noTexture(), false);
    }

    float sx(float ndcX) {
        return x0 + (ndcX * 0.5f + 0.5f) * w;
    }

    float sy(float ndcY) {
        return y0 + (0.5f - ndcY * 0.5f) * h;
    }

    /** One ndc segment, clipped coarsely: dropped if both ends are far outside the panel. */
    void segment(float ax, float ay, float bx, float by, float thickness, int argb) {
        if ((ax < -1.5f && bx < -1.5f) || (ax > 1.5f && bx > 1.5f) || (ay < -1.5f && by < -1.5f)
                || (ay > 1.5f && by > 1.5f)) {
            return;
        }
        pixelSegment(sx(clamp(ax)), sy(clamp(ay)), sx(clamp(bx)), sy(clamp(by)), thickness, argb);
    }

    private static float clamp(float v) {
        return Math.max(-1.6f, Math.min(1.6f, v));
    }

    /** One segment in screen pixels. */
    void pixelSegment(float ax, float ay, float bx, float by, float thickness, int argb) {
        mesh.segment(ax, ay, bx, by, thickness, argb);
    }

    void end() {
        mesh.submit(g);
        g = null;
    }
}
