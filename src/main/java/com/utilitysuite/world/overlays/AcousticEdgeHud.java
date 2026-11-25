package com.utilitysuite.world.overlays;

import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.packetlab.AcousticRadar;
import com.utilitysuite.tools.packetlab.PacketLab;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.world.phys.Vec3;

/**
 * Screen-edge direction cues for acoustic pings outside the field of view: a small marker on the screen border on the
 * side of the sound, with a dot pointing back toward the centre, fading with age. Draws nothing unless World Radar's
 * "Off-screen sound cues" is on and a recent ping is off-screen; hidden by Clean-Screen. Allocates nothing per frame.
 */
public final class AcousticEdgeHud implements LayeredDraw.Layer {

    private static final long MAX_AGE_MS = 3000L;

    private final WorldRadarModule module;
    private final float[] edge = new float[3];
    private final AcousticRadar.Visitor visitor = this::cue;
    private GuiGraphics g;
    private double camX;
    private double camZ;
    private float camYaw;
    private float hFov;
    private long now;

    public AcousticEdgeHud(WorldRadarModule module) {
        this.module = module;
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!module.wantsEdgeCues()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        IModState st = Services.modState();
        if (st != null && st.isCleanScreen()) {
            return;
        }
        AcousticRadar radar = PacketLab.acousticRadar();
        if (radar == null || radar.size() == 0) {
            return;
        }
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 p = cam.getPosition();
        camX = p.x;
        camZ = p.z;
        camYaw = cam.getYRot();
        hFov = AcousticRipples.horizontalFov(mc.options.fov().get(),
                (float) graphics.guiWidth() / Math.max(1, graphics.guiHeight()));
        now = System.currentTimeMillis();
        g = graphics;
        radar.forEachActive(now, MAX_AGE_MS, visitor);
        g = null;
    }

    private void cue(boolean global, double x, double y, double z, double dx, double dz, long ts, int cat) {
        double ox = global ? dx : x - camX;
        double oz = global ? dz : z - camZ;
        float rel = AcousticRipples.relativeYaw(camYaw, ox, oz);
        if (!AcousticRipples.offScreen(rel, hFov)) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        AcousticRipples.edgePoint(rel, w, h, 8f, edge);
        float fade = 1f - (float) (now - ts) / MAX_AGE_MS;
        int alpha = Math.max(0x30, Math.min(0xFF, (int) (fade * 255f)));
        int argb = (alpha << 24) | (AcousticRadar.categoryColor(cat) & 0x00FFFFFF);
        int ex = (int) edge[0];
        int ey = (int) edge[1];
        g.fill(ex - 3, ey - 3, ex + 3, ey + 3, argb);
        // inner dot toward the screen centre, so the marker reads as "this way"
        int ix = (int) (edge[0] - (float) Math.sin(edge[2]) * 7f);
        int iy = (int) (edge[1] + (float) Math.cos(edge[2]) * 7f);
        g.fill(ix - 1, iy - 1, ix + 1, iy + 1, argb);
    }
}
