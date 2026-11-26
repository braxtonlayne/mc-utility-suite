package com.utilitysuite.world.minimap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * A reusable custom GUI element for arbitrary 2D geometry (discs, rings, arrows, rotated textured fans, iso quads,
 * thick lines). The GUI pipelines are quad-only, so triangles are emitted as degenerate quads (last vertex repeated)
 * and a fan is emitted as quads that each cover two of its triangles. Positions are transformed by the GUI pose when
 * they are added (no per-vertex allocation, unlike {@code addVertexWith2DPose}) and the element's bounds are the
 * tight screen-space box of its vertices, so the GUI layering puts later overlapping elements above it.
 * <p>
 * Usage per frame: {@link Pool#reset()} once, then {@code pool.begin(g, pipeline, textures)}, add geometry and
 * {@link #submit(GuiGraphicsExtractor)}. The GUI builds the vertices later in the same frame, so an element must not be
 * reused before the next frame: the pool hands out a fresh (recycled) element for each draw call. Steady state the
 * only allocation per submit is the bounds record (two when a scissor is active), like vanilla fills.
 */
public final class GuiMesh implements GuiElementRenderState {

    private RenderPipeline pipeline = RenderPipelines.GUI;
    private TextureSetup textures = TextureSetup.noTexture();
    private boolean textured;
    private @Nullable ScreenRectangle scissor;
    private @Nullable ScreenRectangle bounds;
    // pose (2x3 affine) captured at begin()
    private float m00;
    private float m01;
    private float m10;
    private float m11;
    private float m20;
    private float m21;
    // vertices: screen x, y, u, v and colour
    private float[] xy = new float[64 * 2];
    private float[] uv = new float[64 * 2];
    private int[] col = new int[64];
    private int count;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;

    /** Starts the element: untextured with {@link RenderPipelines#GUI}, textured with {@link RenderPipelines#GUI_TEXTURED}. */
    public GuiMesh begin(GuiGraphicsExtractor g, RenderPipeline pipeline, TextureSetup textures, boolean textured) {
        this.pipeline = pipeline;
        this.textures = textures;
        this.textured = textured;
        Matrix3x2fc p = g.pose();
        m00 = p.m00();
        m01 = p.m01();
        m10 = p.m10();
        m11 = p.m11();
        m20 = p.m20();
        m21 = p.m21();
        scissor = g.peekScissorStack();
        bounds = null;
        count = 0;
        minX = Float.POSITIVE_INFINITY;
        minY = Float.POSITIVE_INFINITY;
        maxX = Float.NEGATIVE_INFINITY;
        maxY = Float.NEGATIVE_INFINITY;
        return this;
    }

    public int vertexCount() {
        return count;
    }

    /** One vertex in GUI coordinates (before the pose). Vertices go in groups of four (one quad). */
    public void vertex(float x, float y, float u, float v, int argb) {
        screenVertex(m00 * x + m10 * y + m20, m01 * x + m11 * y + m21, u, v, argb);
    }

    private void screenVertex(float sx, float sy, float u, float v, int argb) {
        if (count == col.length) {
            grow();
        }
        int i = count * 2;
        xy[i] = sx;
        xy[i + 1] = sy;
        uv[i] = u;
        uv[i + 1] = v;
        col[count] = argb;
        count++;
        if (sx < minX) {
            minX = sx;
        }
        if (sx > maxX) {
            maxX = sx;
        }
        if (sy < minY) {
            minY = sy;
        }
        if (sy > maxY) {
            maxY = sy;
        }
    }

    public void vertex(float x, float y, int argb) {
        vertex(x, y, 0f, 0f, argb);
    }

    /** A triangle (as a degenerate quad). */
    public void tri(float ax, float ay, float bx, float by, float cx, float cy, int argb) {
        vertex(ax, ay, argb);
        vertex(bx, by, argb);
        vertex(cx, cy, argb);
        vertex(cx, cy, argb);
    }

    public void quad(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int argb) {
        vertex(ax, ay, argb);
        vertex(bx, by, argb);
        vertex(cx, cy, argb);
        vertex(dx, dy, argb);
    }

    /** Thick segment in GUI pixels, as one quad. */
    public void segment(float ax, float ay, float bx, float by, float thickness, int argb) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) {
            return;
        }
        float px = -dy / len * thickness * 0.5f;
        float py = dx / len * thickness * 0.5f;
        quad(ax - px, ay - py, bx - px, by - py, bx + px, by + py, ax + px, ay + py, argb);
    }

    private void grow() {
        int n = col.length * 2;
        xy = java.util.Arrays.copyOf(xy, n * 2);
        uv = java.util.Arrays.copyOf(uv, n * 2);
        col = java.util.Arrays.copyOf(col, n);
    }

    /** Hands the element to the GUI (no-op when empty or fully scissored away). */
    public void submit(GuiGraphicsExtractor g) {
        // pad to whole quads (a stray vertex becomes a degenerate quad, not a corrupt batch)
        while (count > 0 && (count & 3) != 0) {
            int last = count - 1;
            screenVertex(xy[last * 2], xy[last * 2 + 1], uv[last * 2], uv[last * 2 + 1], col[last]);
        }
        if (count == 0) {
            return;
        }
        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX);
        int y1 = (int) Math.ceil(maxY);
        ScreenRectangle b = new ScreenRectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
        bounds = scissor != null ? scissor.intersection(b) : b;
        if (bounds == null) {
            return;
        }
        g.submitGuiElementRenderState(this);
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < count; i++) {
            VertexConsumer v = vc.addVertex(xy[i * 2], xy[i * 2 + 1], 0f);
            if (textured) {
                v.setUv(uv[i * 2], uv[i * 2 + 1]);
            }
            v.setColor(col[i]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return textures;
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }

    /**
     * Recycles meshes across frames: {@link #reset()} at the start of the owner's frame, then one {@link #begin} per
     * draw call. Grows to the peak number of draw calls per frame and then allocates nothing.
     */
    public static final class Pool {
        private GuiMesh[] meshes = new GuiMesh[8];
        private int used;

        public void reset() {
            used = 0;
        }

        public GuiMesh begin(GuiGraphicsExtractor g) {
            return next().begin(g, RenderPipelines.GUI, TextureSetup.noTexture(), false);
        }

        public GuiMesh beginTextured(GuiGraphicsExtractor g, TextureSetup textures) {
            return next().begin(g, RenderPipelines.GUI_TEXTURED, textures, true);
        }

        private GuiMesh next() {
            if (used == meshes.length) {
                meshes = java.util.Arrays.copyOf(meshes, used * 2);
            }
            GuiMesh m = meshes[used];
            if (m == null) {
                m = new GuiMesh();
                meshes[used] = m;
            }
            used++;
            return m;
        }
    }
}
