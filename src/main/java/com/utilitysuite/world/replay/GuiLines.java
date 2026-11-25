package com.utilitysuite.world.replay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Thick 2D line batches for GUI panels (replay orbit view, picture-in-picture panel): segments in ndc (-1..1, y up)
 * mapped into a screen rectangle, drawn as thin quads in one buffer (the same immediate GUI path the minimap uses, so
 * Sodium and shader packs are not involved). {@link #begin} / {@link #segment} / {@link #end}; allocation is only the
 * GUI's own vertex builder.
 */
final class GuiLines {

    private BufferBuilder buf;
    private Matrix4f m;
    private float x0;
    private float y0;
    private float w;
    private float h;
    private int quads;

    void begin(Matrix4f matrix, float left, float top, float width, float height) {
        m = matrix;
        x0 = left;
        y0 = top;
        w = width;
        h = height;
        quads = 0;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
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
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        float px = -dy / len * thickness * 0.5f;
        float py = dx / len * thickness * 0.5f;
        buf.addVertex(m, ax - px, ay - py, 0).setColor(argb);
        buf.addVertex(m, bx - px, by - py, 0).setColor(argb);
        buf.addVertex(m, bx + px, by + py, 0).setColor(argb);
        buf.addVertex(m, ax - px, ay - py, 0).setColor(argb);
        buf.addVertex(m, bx + px, by + py, 0).setColor(argb);
        buf.addVertex(m, ax + px, ay + py, 0).setColor(argb);
        quads++;
    }

    void end() {
        if (quads > 0) {
            BufferUploader.drawWithShader(buf.buildOrThrow());
        } else {
            var mesh = buf.build();
            if (mesh != null) {
                mesh.close();
            }
        }
        buf = null;
        RenderSystem.disableBlend();
    }
}
