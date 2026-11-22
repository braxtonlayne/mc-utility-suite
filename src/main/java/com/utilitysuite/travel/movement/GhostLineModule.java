package com.utilitysuite.travel.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.travel.pursuit.MinecraftRouteRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * "Ghost Line": a translucent player-sized box (0.6 x 1.8, outline only) about a second ahead of the player along the
 * route the route runner follows, or, when no route runs, at the landing Movement Assist predicts for the current jump
 * (with the arc drawn). The samples are computed once per client tick in {@link GhostLine}; the render callback only
 * interpolates and pushes lines into one batched buffer. Off by default; nothing is drawn when neither source is
 * active, in Clean-Screen, or when the ghost is within 1.5 blocks of the camera.
 */
public final class GhostLineModule implements IModule, IConfigurable {

    public static final String ID = "ghost_line";
    public static final String K_LEAD = "lead_seconds";
    public static final String K_OPACITY = "opacity";
    public static final String K_TRAIL = "show_trail";
    static final double HALF_WIDTH = 0.3;
    static final double HEIGHT = 1.8;

    private final MovementAssist movement;
    private final MinecraftRouteRunner routeRunner;
    private final GhostLine ghost = new GhostLine();
    private final long[] routeBuf = new long[RouteFollower.MAX_ROUTE];
    private final float[] pos = new float[3];

    private boolean enabled;
    private double leadSeconds = 1.0;
    private float opacity = 0.5f;
    private boolean showTrail = true;

    public GhostLineModule(MovementAssist movement, MinecraftRouteRunner routeRunner) {
        this.movement = movement;
        this.routeRunner = routeRunner;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            ghost.clear();
            return;
        }
        Vec3 v = p.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (routeRunner.isFollowingRoute()) {
            int n = routeRunner.copyRoute(routeBuf);
            ghost.updateRoute(routeBuf, n, routeRunner.routeIndex(), p.getX(), p.getY(), p.getZ(), speed,
                    (int) Math.round(leadSeconds * 20.0));
            return;
        }
        MovementAssistBrain brain = movement.brain();
        boolean moving = !p.onGround() || speed * speed > 0.0025;
        if (movement.isEnabled() && moving && brain.hasLanding()) {
            ghost.updateJump(p.getX(), p.getY(), p.getZ(), brain.landX(), brain.landY(), brain.landZ());
        } else {
            ghost.clear();
        }
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !ghost.active() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        if (pose == null || mc.player == null || Services.modState().isCleanScreen()) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        ghost.interpolate(pt, pos);
        double bx = pos[0] - cam.x;
        double by = pos[1] - cam.y;
        double bz = pos[2] - cam.z;
        if (bx * bx + by * by + bz * bz < GhostLine.MIN_SHOW * GhostLine.MIN_SHOW) {
            return;
        }
        boolean jump = ghost.mode() == GhostLine.JUMP;
        float r = jump ? 0.55f : 0.35f;
        float g = jump ? 0.85f : 0.8f;
        float b = 1.0f;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, bx - HALF_WIDTH, by, bz - HALF_WIDTH, bx + HALF_WIDTH, by + HEIGHT,
                bz + HALF_WIDTH, r, g, b, opacity);
        if (jump || showTrail) {
            PoseStack.Pose last = pose.last();
            int n = ghost.trailCount();
            // the first point follows the player's rendered position so the line does not step at tick rate
            float sx = (float) (Mth.lerp(pt, mc.player.xo, mc.player.getX()) - cam.x);
            float sy = (float) (Mth.lerp(pt, mc.player.yo, mc.player.getY()) - cam.y);
            float sz = (float) (Mth.lerp(pt, mc.player.zo, mc.player.getZ()) - cam.z);
            float ax = sx;
            float ay = sy + 0.1f;
            float az = sz;
            float la = opacity * 0.7f;
            for (int i = 1; i < n; i++) {
                float x = (float) (ghost.trailX(i) - cam.x);
                float y = (float) (ghost.trailY(i) - cam.y) + 0.1f;
                float z = (float) (ghost.trailZ(i) - cam.z);
                segment(lines, last, ax, ay, az, x, y, z, r, g, b, la);
                ax = x;
                ay = y;
                az = z;
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void segment(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1,
                                float z1, float r, float g, float b, float a) {
        float nx = x1 - x0;
        float ny = y1 - y0;
        float nz = z1 - z0;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-4f) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        c.addVertex(p, x0, y0, z0).setColor(r, g, b, a).setNormal(p, nx, ny, nz);
        c.addVertex(p, x1, y1, z1).setColor(r, g, b, a).setNormal(p, nx, ny, nz);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TRAVEL;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            ghost.clear();
        }
    }

    @Override
    public void onWorldUnload() {
        ghost.clear();
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        leadSeconds = Mth.clamp(p.getDouble(ID, K_LEAD, 1.0), 0.5, 2.0);
        opacity = (float) p.getDouble(ID, K_OPACITY, 0.5);
        showTrail = p.getBool(ID, K_TRAIL, true);
    }

    @Override
    public String displayName() {
        return "Ghost Line";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal(K_LEAD, "Lead time (s)", 1.0, 0.5, 2.0, "",
                        "How far ahead of you the ghost stands, in seconds at your current speed."),
                SettingSpec.decimal(K_OPACITY, "Opacity", 0.5, 0.1, 1.0, "", "How solid the ghost is drawn."),
                SettingSpec.bool(K_TRAIL, "Show trail", true, "",
                        "Draw the line from you to the ghost along the route (a jump arc is always drawn)."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
