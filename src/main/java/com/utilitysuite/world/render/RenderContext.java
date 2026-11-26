package com.utilitysuite.world.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;

/**
 * Per-frame state handed to the world modules by the shared renderer. One instance, refilled every frame (no
 * allocation). Positions passed to the kit are camera-relative: {@code (float) (worldX - camX)}.
 */
public final class RenderContext {

    public final WorldRenderKit kit = new WorldRenderKit();
    public PoseStack.Pose pose;
    /** Label target (26.3: the level pass submit collector); valid during the label phase only. */
    public final WorldRenderKit.LabelBatch buffers = new WorldRenderKit.LabelBatch();
    /** Valid during the line phase only. */
    public VertexConsumer lines;
    public Font font;
    public double camX;
    public double camY;
    public double camZ;
    public float partialTick;
    public String dimension;

    public float rx(double x) {
        return (float) (x - camX);
    }

    public float ry(double y) {
        return (float) (y - camY);
    }

    public float rz(double z) {
        return (float) (z - camZ);
    }

    /** Squared distance from the camera to a block centre. */
    public double distSq(int x, int y, int z) {
        double dx = x + 0.5 - camX;
        double dy = y + 0.5 - camY;
        double dz = z + 0.5 - camZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
