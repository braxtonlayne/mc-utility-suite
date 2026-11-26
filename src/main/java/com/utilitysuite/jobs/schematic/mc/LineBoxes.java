package com.utilitysuite.jobs.schematic.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;

/**
 * Line-box emission for the 26.3 {@code POSITION_COLOR_NORMAL_LINE_WIDTH} lines format (the replacement for the
 * removed {@code LevelRenderer.renderLineBox}). Writes straight into the buffer handed to a
 * {@code SubmitNodeCollector.CustomGeometryRenderer}: no allocation.
 */
public final class LineBoxes {

    private LineBoxes() {
    }

    /** The window's line width (what vanilla uses for block outlines). Render thread. */
    public static float lineWidth() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
    }

    /** Packs float RGBA (0..1) into ARGB. */
    public static int argb(float r, float g, float b, float a) {
        return ((int) (a * 255f + 0.5f) & 0xFF) << 24 | ((int) (r * 255f + 0.5f) & 0xFF) << 16
                | ((int) (g * 255f + 0.5f) & 0xFF) << 8 | ((int) (b * 255f + 0.5f) & 0xFF);
    }

    /** The 12 edges of an axis-aligned box (camera-relative coordinates). */
    public static void box(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1,
                           float z1, int argb, float width) {
        // four edges along X
        edge(c, p, x0, y0, z0, x1, y0, z0, 1, 0, 0, argb, width);
        edge(c, p, x0, y1, z0, x1, y1, z0, 1, 0, 0, argb, width);
        edge(c, p, x0, y0, z1, x1, y0, z1, 1, 0, 0, argb, width);
        edge(c, p, x0, y1, z1, x1, y1, z1, 1, 0, 0, argb, width);
        // four edges along Y
        edge(c, p, x0, y0, z0, x0, y1, z0, 0, 1, 0, argb, width);
        edge(c, p, x1, y0, z0, x1, y1, z0, 0, 1, 0, argb, width);
        edge(c, p, x0, y0, z1, x0, y1, z1, 0, 1, 0, argb, width);
        edge(c, p, x1, y0, z1, x1, y1, z1, 0, 1, 0, argb, width);
        // four edges along Z
        edge(c, p, x0, y0, z0, x0, y0, z1, 0, 0, 1, argb, width);
        edge(c, p, x1, y0, z0, x1, y0, z1, 0, 0, 1, argb, width);
        edge(c, p, x0, y1, z0, x0, y1, z1, 0, 0, 1, argb, width);
        edge(c, p, x1, y1, z0, x1, y1, z1, 0, 0, 1, argb, width);
    }

    /** One segment; the normal is the (unit) line direction, as the lines shader expects. */
    public static void edge(VertexConsumer c, PoseStack.Pose p, float ax, float ay, float az, float bx, float by,
                            float bz, float nx, float ny, float nz, int argb, float width) {
        c.addVertex(p, ax, ay, az).setColor(argb).setNormal(p, nx, ny, nz).setLineWidth(width);
        c.addVertex(p, bx, by, bz).setColor(argb).setNormal(p, nx, ny, nz).setLineWidth(width);
    }

    /** A segment with its direction computed (zero-length segments are skipped). */
    public static void segment(VertexConsumer c, PoseStack.Pose p, float ax, float ay, float az, float bx, float by,
                               float bz, int argb, float width) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) {
            return;
        }
        edge(c, p, ax, ay, az, bx, by, bz, dx / len, dy / len, dz / len, argb, width);
    }
}
