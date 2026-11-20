package com.utilitysuite.jobs.schematic.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Optional ghost preview: outlines the schematic's missing (cyan) and wrong (red) blocks at the anchor, in one batched
 * line buffer after translucent blocks. Iterates a precomputed cell list (published once per analysis pass), skips
 * cells placed since then and cells beyond the distance limit, and draws at most {@link #MAX_BOXES} boxes. No
 * allocation per frame. The caller skips it entirely when disabled or while Clean-Screen is on.
 */
public final class GhostPreviewRenderer {

    public static final int MAX_BOXES = 4096;

    private GhostPreviewRenderer() {
    }

    public static void render(RenderLevelStageEvent event, PlacementScan scan, int[] cells, int maxDistance) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || scan == null || cells == null
                || cells.length == 0) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        double camX = cam.x, camY = cam.y, camZ = cam.z;
        SchematicVolume v = scan.volume();
        double max2 = (double) maxDistance * maxDistance;
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = null;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camX, -camY, -camZ);
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
            if (lines == null) {
                lines = buffers.getBuffer(RenderType.lines());
            }
            boolean wrong = s == PlacementStatus.WRONG;
            LevelRenderer.renderLineBox(pose, lines, x + 0.02, y + 0.02, z + 0.02, x + 0.98, y + 0.98, z + 0.98,
                    wrong ? 1f : 0.25f, wrong ? 0.3f : 0.85f, wrong ? 0.3f : 1f, 0.8f);
            drawn++;
        }
        pose.popPose();
        if (lines != null) {
            buffers.endBatch(RenderType.lines());
        }
    }
}
