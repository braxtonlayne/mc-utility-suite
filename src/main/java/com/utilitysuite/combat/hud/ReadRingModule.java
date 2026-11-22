package com.utilitysuite.combat.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * "The Read": a ring over the Combat Assistant's target that fills with the target's attack cooldown and tilts toward
 * its predicted strafe. Reads {@link ReadState}; draws in {@link RenderLevelStageEvent} with one batched line buffer;
 * off by default; nothing is drawn when idle or in Clean-Screen. All geometry is in {@link ReadRingGeometry}.
 * <p>
 * The constructor, {@link #ID} and {@link #onRenderLevel} are fixed for the volley (the combat wiring registers the
 * module and the listener).
 */
public final class ReadRingModule implements IModule, IConfigurable {

    public static final String ID = "the_read";
    public static final String K_RADIUS = "radius";
    public static final String K_OPACITY = "opacity";
    public static final String K_TILT = "show_tilt";
    /** Ring height above the top of the target's box. */
    static final float LIFT = 0.25f;

    private final ReadState read;
    private final float[] verts = new float[ReadRingGeometry.MAX_FLOATS];
    private boolean enabled;
    private float radius = 0.6f;
    private float opacity = 0.8f;
    private boolean showTilt = true;

    public ReadRingModule(ReadState read) {
        this.read = read;
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !read.active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Services.modState().isCleanScreen()) {
            return;
        }
        Entity e = mc.level.getEntity(read.targetId);
        PoseStack pose = event.getPoseStack();
        if (e == null || pose == null) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        float cx = (float) (Mth.lerp(pt, e.xo, e.getX()) - cam.x);
        float cy = (float) (Mth.lerp(pt, e.yo, e.getY()) + e.getBbHeight() + LIFT - cam.y);
        float cz = (float) (Mth.lerp(pt, e.zo, e.getZ()) - cam.z);
        float charge = read.charge;
        int arc = ReadRingGeometry.fill(verts, cx, cy, cz, radius, charge, showTilt ? read.tiltX : 0f,
                showTilt ? read.tiltZ : 0f);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose last = pose.last();
        draw(lines, last, 0, ReadRingGeometry.OUTLINE_VERTS, 0.8f, 0.8f, 0.8f, opacity * 0.35f);
        if (arc > 0) {
            float c = Mth.clamp(charge, 0f, 1f);
            float r = Math.min(1f, 2f * (1f - c));
            float g = Math.min(1f, 2f * c);
            float b = 0.1f;
            float a = opacity;
            if (read.steppingIn) {
                r = r * 0.4f + 0.6f;
                g = g * 0.4f + 0.6f;
                b = 0.7f;
                a = 1f;
            }
            draw(lines, last, ReadRingGeometry.OUTLINE_VERTS, arc, r, g, b, a);
        }
        buffers.endBatch(RenderType.lines());
    }

    private void draw(VertexConsumer c, PoseStack.Pose p, int firstVertex, int count, float r, float g, float b,
                      float a) {
        for (int v = firstVertex; v < firstVertex + count; v += 2) {
            int i = v * 3;
            float x0 = verts[i];
            float y0 = verts[i + 1];
            float z0 = verts[i + 2];
            float x1 = verts[i + 3];
            float y1 = verts[i + 4];
            float z1 = verts[i + 5];
            float nx = x1 - x0;
            float ny = y1 - y0;
            float nz = z1 - z0;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0e-5f) {
                continue;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            c.addVertex(p, x0, y0, z0).setColor(r, g, b, a).setNormal(p, nx, ny, nz);
            c.addVertex(p, x1, y1, z1).setColor(r, g, b, a).setNormal(p, nx, ny, nz);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.COMBAT;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void onTick() {
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        radius = (float) p.getDouble(ID, K_RADIUS, 0.6);
        opacity = (float) p.getDouble(ID, K_OPACITY, 0.8);
        showTilt = p.getBool(ID, K_TILT, true);
    }

    @Override
    public String displayName() {
        return "The Read";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal(K_RADIUS, "Ring radius", 0.6, 0.3, 1.5, "",
                        "Radius of the ring over the target, in blocks."),
                SettingSpec.decimal(K_OPACITY, "Opacity", 0.8, 0.1, 1.0, "", "How solid the ring is drawn."),
                SettingSpec.bool(K_TILT, "Show predicted strafe", true, "",
                        "Tilt the ring toward the side the target is predicted to strafe."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    ReadState read() {
        return read;
    }
}
