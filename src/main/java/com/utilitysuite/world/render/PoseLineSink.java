package com.utilitysuite.world.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * {@link LineSink} over the shared see-through line batch. One instance per module, re-aimed every frame with
 * {@link #bind(RenderContext)} (no allocation).
 */
public final class PoseLineSink implements LineSink {

    private VertexConsumer lines;
    private PoseStack.Pose pose;

    public PoseLineSink bind(RenderContext ctx) {
        this.lines = ctx.lines;
        this.pose = ctx.pose;
        return this;
    }

    @Override
    public void line(float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        WorldRenderKit.line(lines, pose, x1, y1, z1, x2, y2, z2, argb);
    }
}
