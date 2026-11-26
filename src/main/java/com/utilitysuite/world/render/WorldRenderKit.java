package com.utilitysuite.world.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.utilitysuite.UtilitySuiteMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shared drawing helpers for every world overlay (X-ray highlights, ghost predictions, radar, waypoint beacons).
 * <p>
 * One extra render type: vanilla's line pipeline without the depth test (translucent, no depth write), so outlines
 * show through terrain. 26.3 has no immediate-mode buffers any more: the shared world pass submits ONE custom-geometry
 * node per frame ({@code SubmitCustomGeometryEvent}) with this render type, and every module's lines are written into
 * that one batched buffer when the feature dispatcher builds it. Chunk meshes are never touched, so it stays
 * Sodium-safe. Labels are submitted as see-through text nodes through {@link LabelBatch}. All coordinates passed in are
 * camera-relative (world minus camera position); the submitted pose is the level pass's pose (camera-relative, the
 * view rotation lives in the model-view matrix).
 * <p>
 * Allocation-free per call on our side: one reused matrix/quaternion/pose stack for labels, numbers are drawn from a
 * lazily filled string table and label strings map to cached {@link FormattedCharSequence}s (vanilla's submit nodes
 * themselves still allocate one small record per submit; that is inside the game).
 */
public final class WorldRenderKit {

    /** Width of every overlay line, in pixels (26.3 lines carry their width per vertex). */
    public static final float LINE_WIDTH = 2.0f;

    /** Our pipelines (pure data, built once; registered in {@link #registerPipelines}). */
    private static final class Pipelines {
        static final RenderPipeline LINES_SEE_THROUGH = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "pipeline/lines_see_through"))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .build();
        /** Improved-transparency (OIT) variant: the translucent feature phase draws through these when OIT is on. */
        static final OitPipelineSet OIT_LINES_SEE_THROUGH = OitPipelineSet.builder(
                        Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "lines_see_through"),
                        RenderPipeline.builder(RenderPipelines.OIT_LINES_SNIPPET))
                .withoutDepthTest()
                .build();
    }

    /**
     * Holder so the render type loads on the first frame, on the render thread, not while mods are being constructed.
     */
    private static final class Types {
        /** Lines that ignore depth (see-through), {@link #LINE_WIDTH} px wide. */
        static final RenderType LINES_NO_DEPTH = RenderType.create("utilitysuite_lines_nodepth",
                RenderSetup.builder(Pipelines.LINES_SEE_THROUGH)
                        .setOitPipelines(Pipelines.OIT_LINES_SEE_THROUGH)
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup());
    }

    /** Mod-bus listener: registers the see-through line pipelines (wired by {@code WorldBootstrap}). */
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(Pipelines.LINES_SEE_THROUGH);
        event.registerOitPipelineSet(Pipelines.OIT_LINES_SEE_THROUGH);
    }

    public static RenderType linesNoDepth() {
        return Types.LINES_NO_DEPTH;
    }

    /**
     * Label target of one frame: the level pass's submit collector plus one reused pose stack. Replaces 1.21.1's
     * {@code MultiBufferSource.BufferSource} in {@link RenderContext#buffers}; a null collector drops labels.
     */
    public static final class LabelBatch {
        private final PoseStack scratch = new PoseStack();
        private final String[] keys = new String[1024];
        private final FormattedCharSequence[] seqs = new FormattedCharSequence[1024];
        private SubmitNodeCollector collector;

        public void bind(SubmitNodeCollector collector) {
            this.collector = collector;
        }

        public void unbind() {
            this.collector = null;
        }

        /** Identity-keyed direct-mapped cache: label strings are constants or cached numbers, so steady state is free. */
        FormattedCharSequence seq(String s) {
            int slot = System.identityHashCode(s) & (keys.length - 1);
            if (keys[slot] == s) {
                return seqs[slot];
            }
            FormattedCharSequence f = FormattedCharSequence.forward(s, Style.EMPTY);
            keys[slot] = s;
            seqs[slot] = f;
            return f;
        }

        void draw(String s, float x, Matrix4f matrix, int argb) {
            SubmitNodeCollector c = collector;
            if (c == null) {
                return;
            }
            scratch.last().pose().set(matrix);
            c.submitText(scratch, x, 0, seq(s), false, Font.DisplayMode.SEE_THROUGH, LightCoordsUtil.FULL_BRIGHT, argb,
                    0x40000000, 0);
        }
    }

    /** Render thread only (every caller draws inside the world render pass). */
    private static final Vector3f SCRATCH = new Vector3f();
    private static final int DASHES_PER_EDGE = 3;
    private static final String[] NUMBERS = new String[20_000];

    private final Matrix4f labelMatrix = new Matrix4f();
    private final Quaternionf labelRotation = new Quaternionf();

    /** Cached decimal string for a non-negative int (allocates once per distinct value, then never). */
    public static String number(int value) {
        if (value < 0 || value >= NUMBERS.length) {
            return Integer.toString(value);
        }
        String s = NUMBERS[value];
        if (s == null) {
            s = Integer.toString(value);
            NUMBERS[value] = s;
        }
        return s;
    }

    public static void line(VertexConsumer vc, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2,
                            float z2, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // Transform through one scratch vector: vanilla's addVertex(Pose, ..) / setNormal(Pose, ..) allocate a Vector3f
        // per call, which the playtest measured as the biggest render-thread allocation site with overlays on.
        Vector3f v = SCRATCH;
        pose.transformNormal(dx, dy, dz, v);
        float nx = v.x;
        float ny = v.y;
        float nz = v.z;
        pose.pose().transformPosition(x1, y1, z1, v);
        vc.addVertex(v.x, v.y, v.z).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(LINE_WIDTH);
        pose.pose().transformPosition(x2, y2, z2, v);
        vc.addVertex(v.x, v.y, v.z).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(LINE_WIDTH);
    }

    /** Dashed line: {@code dashes} drawn pieces with equal gaps between them. */
    public static void dashedLine(VertexConsumer vc, PoseStack.Pose pose, float x1, float y1, float z1, float x2,
                                  float y2, float z2, int argb, int dashes) {
        int pieces = dashes * 2 - 1;
        float sx = (x2 - x1) / pieces;
        float sy = (y2 - y1) / pieces;
        float sz = (z2 - z1) / pieces;
        for (int i = 0; i < pieces; i += 2) {
            line(vc, pose, x1 + sx * i, y1 + sy * i, z1 + sz * i, x1 + sx * (i + 1), y1 + sy * (i + 1),
                    z1 + sz * (i + 1), argb);
        }
    }

    /** Solid box outline (12 edges). */
    public static void box(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1,
                           float z1, int argb) {
        line(vc, pose, x0, y0, z0, x1, y0, z0, argb);
        line(vc, pose, x0, y0, z1, x1, y0, z1, argb);
        line(vc, pose, x0, y1, z0, x1, y1, z0, argb);
        line(vc, pose, x0, y1, z1, x1, y1, z1, argb);
        line(vc, pose, x0, y0, z0, x0, y1, z0, argb);
        line(vc, pose, x1, y0, z0, x1, y1, z0, argb);
        line(vc, pose, x0, y0, z1, x0, y1, z1, argb);
        line(vc, pose, x1, y0, z1, x1, y1, z1, argb);
        line(vc, pose, x0, y0, z0, x0, y0, z1, argb);
        line(vc, pose, x1, y0, z0, x1, y0, z1, argb);
        line(vc, pose, x0, y1, z0, x0, y1, z1, argb);
        line(vc, pose, x1, y1, z0, x1, y1, z1, argb);
    }

    /** Dashed box outline: the "ghost" style used for predictions, never for confirmed blocks. */
    public static void dashedBox(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0, float x1,
                                 float y1, float z1, int argb) {
        int d = DASHES_PER_EDGE;
        dashedLine(vc, pose, x0, y0, z0, x1, y0, z0, argb, d);
        dashedLine(vc, pose, x0, y0, z1, x1, y0, z1, argb, d);
        dashedLine(vc, pose, x0, y1, z0, x1, y1, z0, argb, d);
        dashedLine(vc, pose, x0, y1, z1, x1, y1, z1, argb, d);
        dashedLine(vc, pose, x0, y0, z0, x0, y1, z0, argb, d);
        dashedLine(vc, pose, x1, y0, z0, x1, y1, z0, argb, d);
        dashedLine(vc, pose, x0, y0, z1, x0, y1, z1, argb, d);
        dashedLine(vc, pose, x1, y0, z1, x1, y1, z1, argb, d);
        dashedLine(vc, pose, x0, y0, z0, x0, y0, z1, argb, d);
        dashedLine(vc, pose, x1, y0, z0, x1, y0, z1, argb, d);
        dashedLine(vc, pose, x0, y1, z0, x0, y1, z1, argb, d);
        dashedLine(vc, pose, x1, y1, z0, x1, y1, z1, argb, d);
    }

    /** Horizontal square outline at height y (chunk trails). */
    public static void square(VertexConsumer vc, PoseStack.Pose pose, float x0, float y, float z0, float x1, float z1,
                              int argb) {
        line(vc, pose, x0, y, z0, x1, y, z0, argb);
        line(vc, pose, x1, y, z0, x1, y, z1, argb);
        line(vc, pose, x1, y, z1, x0, y, z1, argb);
        line(vc, pose, x0, y, z1, x0, y, z0, argb);
    }

    /**
     * Begins a billboard label at a camera-relative position; returns the matrix to pass to the draw calls. Scales up
     * with distance so far labels stay readable.
     */
    public Matrix4f beginLabel(PoseStack.Pose base, float x, float y, float z) {
        float dist = (float) Math.sqrt(x * x + y * y + z * z);
        float scale = 0.025f * Math.max(1.0f, dist / 10.0f);
        labelRotation.set(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
        return labelMatrix.set(base.pose()).translate(x, y, z).rotate(labelRotation).scale(scale, -scale, scale);
    }

    /** Draws one text piece (see-through, full bright) at an x offset; returns the x after it. */
    public static float text(Font font, LabelBatch labels, Matrix4f matrix, String s, float x, int argb) {
        if (labels != null) {
            labels.draw(s, x, matrix, argb);
        }
        return x + font.width(s);
    }
}
