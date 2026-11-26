package com.utilitysuite.jobs.schematic.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Optional ghost preview: outlines the schematic's missing (cyan) and wrong (red) blocks at the anchor, in one batched
 * line submit per frame. Iterates a precomputed cell list (published once per analysis pass), skips cells placed since
 * then and cells beyond the distance limit, and draws at most {@link #MAX_BOXES} boxes. The visible boxes are staged
 * into preallocated arrays at submit time and emitted by one reusable geometry renderer: no allocation per frame. The
 * caller skips it entirely when disabled or while Clean-Screen is on.
 * <p>
 * 26.3: drawn through {@link SubmitCustomGeometryEvent} (the {@code MultiBufferSource} immediate path is gone).
 */
public final class GhostPreviewRenderer {

    public static final int MAX_BOXES = 4096;

    private static final float[] BOX = new float[MAX_BOXES * 3];
    private static final boolean[] WRONG = new boolean[MAX_BOXES];
    private static final int WRONG_ARGB = LineBoxes.argb(1f, 0.3f, 0.3f, 0.8f);
    private static final int MISSING_ARGB = LineBoxes.argb(0.25f, 0.85f, 1f, 0.8f);
    private static int staged;
    private static float width;

    private static final SubmitNodeCollector.CustomGeometryRenderer EMIT = GhostPreviewRenderer::emit;

    private GhostPreviewRenderer() {
    }

    public static void render(SubmitCustomGeometryEvent event, PlacementScan scan, int[] cells, int maxDistance) {
        if (scan == null || cells == null || cells.length == 0) {
            return;
        }
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        double camX = cam.x, camY = cam.y, camZ = cam.z;
        SchematicVolume v = scan.volume();
        double max2 = (double) maxDistance * maxDistance;
        int drawn = 0;
        for (int i = 0; i < cells.length && drawn < MAX_BOXES; i++) {
            int c = cells[i];
            byte s = scan.status(c);
            if (!PlacementStatus.needsPlacing(s)) {
                continue;
            }
            int x = scan.originX() + v.xOf(c), y = scan.originY() + v.yOf(c), z = scan.originZ() + v.zOf(c);
            double dx = x + 0.5 - camX, dy = y + 0.5 - camY, dz = z + 0.5 - camZ;
            if (dx * dx + dy * dy + dz * dz > max2) {
                continue;
            }
            int o = drawn * 3;
            BOX[o] = (float) (x - camX);
            BOX[o + 1] = (float) (y - camY);
            BOX[o + 2] = (float) (z - camZ);
            WRONG[drawn] = s == PlacementStatus.WRONG;
            drawn++;
        }
        staged = drawn;
        if (drawn == 0) {
            return;
        }
        width = LineBoxes.lineWidth();
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.lines(), EMIT);
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer lines) {
        float w = width;
        for (int i = 0, n = staged; i < n; i++) {
            int o = i * 3;
            float x = BOX[o], y = BOX[o + 1], z = BOX[o + 2];
            LineBoxes.box(lines, pose, x + 0.02f, y + 0.02f, z + 0.02f, x + 0.98f, y + 0.98f, z + 0.98f,
                    WRONG[i] ? WRONG_ARGB : MISSING_ARGB, w);
        }
    }
}
