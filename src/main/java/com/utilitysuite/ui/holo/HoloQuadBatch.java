package com.utilitysuite.ui.holo;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * One run of holo-kit quads (a ribbon, an arc with its glow, a glyph) handed to the game's GUI render state as a
 * single element: the 26.3 replacement for writing straight into the GUI buffer source. Vertices (x, y, u, v, argb)
 * are kept in growable primitive arrays; the batch is drawn by the GUI renderer later in the same frame through
 * {@link #buildVertices}.
 * <p>
 * Batches are pooled per frame ({@link Pool}): the game creates one {@link GuiGraphicsExtractor} per frame and draws
 * everything extracted into it before the next one exists, so the pool rewinds when it sees a new extractor. Only the
 * screen-bounds rectangle (a record the render state needs for layering) is created per batch, like every vanilla
 * fill. Render thread only.
 */
public final class HoloQuadBatch implements GuiElementRenderState {

    private RenderPipeline pipeline;
    private TextureSetup textureSetup;
    private final Matrix3x2f pose = new Matrix3x2f();
    private @Nullable ScreenRectangle scissor;
    private @Nullable ScreenRectangle bounds;
    private float[] xyuv = new float[64 * 4];
    private int[] argb = new int[64];
    private int vertices;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;

    void open(RenderPipeline pipeline, TextureSetup textures, GuiGraphicsExtractor g) {
        this.pipeline = pipeline;
        this.textureSetup = textures;
        this.pose.set(g.pose());
        this.scissor = g.peekScissorStack();
        this.bounds = null;
        this.vertices = 0;
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
    }

    void vertex(float x, float y, float u, float v, int colour) {
        if (vertices == argb.length) {
            int n = argb.length * 2;
            float[] f = new float[n * 4];
            System.arraycopy(xyuv, 0, f, 0, xyuv.length);
            xyuv = f;
            int[] c = new int[n];
            System.arraycopy(argb, 0, c, 0, argb.length);
            argb = c;
        }
        int o = vertices * 4;
        xyuv[o] = x;
        xyuv[o + 1] = y;
        xyuv[o + 2] = u;
        xyuv[o + 3] = v;
        argb[vertices] = colour;
        vertices++;
        if (x < minX) {
            minX = x;
        }
        if (x > maxX) {
            maxX = x;
        }
        if (y < minY) {
            minY = y;
        }
        if (y > maxY) {
            maxY = y;
        }
    }

    /** Closes the run; returns false when it holds no complete quad (nothing to submit). */
    boolean close() {
        vertices -= vertices & 3;
        if (vertices == 0) {
            return false;
        }
        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX);
        int y1 = (int) Math.ceil(maxY);
        ScreenRectangle b = new ScreenRectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0))
                .transformMaxBounds(pose);
        bounds = scissor != null ? scissor.intersection(b) : b;
        return bounds != null;
    }

    int vertexCount() {
        return vertices;
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < vertices; i++) {
            int o = i * 4;
            vc.addVertexWith2DPose(pose, xyuv[o], xyuv[o + 1]).setUv(xyuv[o + 2], xyuv[o + 3]).setColor(argb[i]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }

    /** Per-frame pool of batches, rewound when a new frame's extractor shows up. Render thread only. */
    public static final class Pool {
        private HoloQuadBatch[] batches = new HoloQuadBatch[32];
        private int used;
        private Object frame;

        /** A cleared batch for this frame, opened on the extractor's current pose and scissor. */
        public HoloQuadBatch next(GuiGraphicsExtractor g, RenderPipeline pipeline, TextureSetup textures) {
            if (g != frame) {
                frame = g;
                used = 0;
            }
            if (used == batches.length) {
                HoloQuadBatch[] grown = new HoloQuadBatch[batches.length * 2];
                System.arraycopy(batches, 0, grown, 0, batches.length);
                batches = grown;
            }
            HoloQuadBatch b = batches[used];
            if (b == null) {
                b = new HoloQuadBatch();
                batches[used] = b;
            }
            used++;
            b.open(pipeline, textures, g);
            return b;
        }

        /** Hands a filled batch to the frame (dropped when empty or fully clipped). */
        public void submit(GuiGraphicsExtractor g, HoloQuadBatch b) {
            if (b.close()) {
                g.submitGuiElementRenderState(b);
            }
        }
    }
}
