package com.utilitysuite.world.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.OptionalDouble;

/**
 * Shared drawing helpers for every world overlay (X-ray highlights, ghost predictions, radar, waypoint beacons).
 * <p>
 * One extra render type: vanilla {@code LINES} without the depth test, so outlines show through terrain. It is built
 * from public vanilla render-state shards, so it works with Sodium/Embeddium (they leave immediate-mode rendering
 * alone). All coordinates passed in are camera-relative (world minus camera position); the pose stack of the
 * {@code AFTER_WEATHER} stage already carries the view rotation.
 * <p>
 * Allocation-free per call: one reused matrix/quaternion for labels, numbers are drawn from a lazily filled string
 * table instead of being formatted per frame.
 */
public final class WorldRenderKit {

    /**
     * Holder so the render type (and the vanilla render-state classes) load on the first frame, on the render thread,
     * not while mods are being constructed.
     */
    private static final class Types {
        /** Lines that ignore depth (see-through), 2 px wide. */
        static final RenderType LINES_NO_DEPTH = RenderType.create("utilitysuite_lines_nodepth",
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .createCompositeState(false));
    }

    public static RenderType linesNoDepth() {
        return Types.LINES_NO_DEPTH;
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

    public MultiBufferSource.BufferSource buffers() {
        return Minecraft.getInstance().renderBuffers().bufferSource();
    }

    public VertexConsumer lines(MultiBufferSource.BufferSource buffers) {
        return buffers.getBuffer(Types.LINES_NO_DEPTH);
    }

    /** Flushes the line batch (call once at the end of the overlay pass). */
    public void endLines(MultiBufferSource.BufferSource buffers) {
        buffers.endBatch(Types.LINES_NO_DEPTH);
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
        vc.addVertex(v.x, v.y, v.z).setColor(r, g, b, a).setNormal(nx, ny, nz);
        pose.pose().transformPosition(x2, y2, z2, v);
        vc.addVertex(v.x, v.y, v.z).setColor(r, g, b, a).setNormal(nx, ny, nz);
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
        labelRotation.set(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        return labelMatrix.set(base.pose()).translate(x, y, z).rotate(labelRotation).scale(scale, -scale, scale);
    }

    /** Draws one text piece (see-through, full bright) at an x offset; returns the x after it. */
    public static float text(Font font, MultiBufferSource buffers, Matrix4f matrix, String s, float x, int argb) {
        font.drawInBatch(s, x, 0, argb, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0x40000000,
                LightTexture.FULL_BRIGHT);
        return x + font.width(s);
    }
}
