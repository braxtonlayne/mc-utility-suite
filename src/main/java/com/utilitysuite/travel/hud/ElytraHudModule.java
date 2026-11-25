package com.utilitysuite.travel.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.travel.movement.ElytraGlideSim;
import com.utilitysuite.travel.movement.GlideFootprint;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * M2 fighter-jet elytra HUD (off by default). While gliding: a centred artificial horizon with a pitch ladder every 10
 * degrees, a flight-path marker from the velocity, airspeed and sink rate, a stall chevron below the glide-stall speed,
 * a wave-glide cue arrow when the dive/climb pattern would beat a straight glide, and in the world a zero-rocket glide
 * footprint ring ({@link GlideFootprint}) over the terrain heightmap.
 * <p>
 * Budget: the footprint computes {@link #HEADINGS_PER_TICK} headings per client tick (a full ring every few ticks);
 * the ring draws with one batched line buffer in {@link RenderLevelStageEvent}. Every HUD string is precomputed, so
 * the render path allocates nothing. Nothing is drawn unless gliding; Clean-Screen and F1 hide everything.
 */
public final class ElytraHudModule implements IModule, IConfigurable {

    public static final String ID = "elytra_hud";
    public static final String K_LADDER = "pitch_ladder";
    public static final String K_FOOTPRINT = "glide_footprint";
    public static final String K_WAVE = "wave_cue";
    public static final String K_STALL = "stall_speed";

    static final int HEADINGS = 32;
    static final int HEADINGS_PER_TICK = 2;
    static final int MAX_GLIDE_TICKS = 1200;
    static final int WAVE_EVERY = 20;
    private static final int MAX_SPEED_LABEL = 200;
    private static final String[] SPEED = new String[MAX_SPEED_LABEL + 1];
    private static final String[] SINK = new String[2 * MAX_SPEED_LABEL + 1];
    private static final String[] RUNG = new String[19];

    static {
        for (int i = 0; i <= MAX_SPEED_LABEL; i++) {
            SPEED[i] = "SPD " + i + " m/s";
        }
        for (int i = -MAX_SPEED_LABEL; i <= MAX_SPEED_LABEL; i++) {
            SINK[i + MAX_SPEED_LABEL] = "V/S " + (i > 0 ? "+" : "") + i + " m/s";
        }
        for (int i = 0; i < RUNG.length; i++) {
            RUNG[i] = Integer.toString(Math.abs(i * 10 - 90));
        }
    }

    private final GlideFootprint footprint = new GlideFootprint(HEADINGS, MAX_GLIDE_TICKS);
    private final ElytraGlideSim waveScratch = new ElytraGlideSim();
    private final GlideFootprint.Heightmap heights = this::surfaceY;
    private boolean enabled;
    private boolean ladder = true;
    private boolean showFootprint = true;
    private boolean wave = true;
    private double stallSpeed = 0.5;

    // tick state (client thread)
    private boolean gliding;
    private int nextHeading;
    private int ringReady;
    private int waveTimer;
    private boolean waveGood;
    private GlideFootprint.WaveCue cue = GlideFootprint.WaveCue.NONE;
    private double speed, vy, hSpeed;

    public ElytraHudModule() {
    }

    @Override
    public void onTick() {
        if (!enabled) {
            gliding = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying()) {
            if (gliding) {
                gliding = false;
                ringReady = 0;
                nextHeading = 0;
                cue = GlideFootprint.WaveCue.NONE;
            }
            return;
        }
        gliding = true;
        Vec3 v = p.getDeltaMovement();
        hSpeed = Math.sqrt(v.x * v.x + v.z * v.z);
        vy = v.y;
        speed = Math.sqrt(hSpeed * hSpeed + vy * vy);
        if (showFootprint) {
            for (int n = 0; n < HEADINGS_PER_TICK; n++) {
                footprint.computeHeading(nextHeading, p.getX(), p.getY(), p.getZ(), hSpeed, vy, heights);
                nextHeading = (nextHeading + 1) % HEADINGS;
                if (ringReady < HEADINGS) {
                    ringReady++;
                }
            }
        }
        if (wave) {
            if (--waveTimer <= 0) {
                waveTimer = WAVE_EVERY;
                double height = p.getY() - surfaceY(p.getBlockX(), p.getBlockZ());
                waveGood = GlideFootprint.waveBeneficial(waveScratch, hSpeed, vy, height);
            }
            cue = waveGood ? GlideFootprint.waveCue(cue, speed, vy) : GlideFootprint.WaveCue.NONE;
        }
    }

    private int surfaceY(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE / 2; // unknown terrain: keep gliding (the ring point stays "airborne")
        }
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    private boolean visible(Minecraft mc) {
        return enabled && gliding && mc.player != null && !mc.options.hideGui && !Services.modState().isCleanScreen();
    }

    /** GUI layer: horizon, ladder, flight-path marker, readouts, stall chevron, wave cue. */
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) {
            return;
        }
        LocalPlayer p = mc.player;
        int w = g.guiWidth(), h = g.guiHeight();
        int cx = w / 2, cy = h / 2;
        double fov = Math.max(30, mc.options.fov().get());
        double k = h / fov; // pixels per degree (vertical)
        float pitch = p.getXRot();
        int green = 0xC040FF70;
        if (ladder) {
            for (int i = 0; i < RUNG.length; i++) {
                int elev = 90 - i * 10;
                int y = (int) Math.round(cy - (elev + pitch) * k);
                if (y < cy - h / 3 || y > cy + h / 3) {
                    continue;
                }
                if (elev == 0) {
                    g.fill(cx - 70, y, cx - 12, y + 1, green);
                    g.fill(cx + 12, y, cx + 70, y + 1, green);
                } else {
                    int half = 22;
                    if (elev > 0) {
                        g.fill(cx - 30 - half, y, cx - 30, y + 1, green);
                        g.fill(cx + 30, y, cx + 30 + half, y + 1, green);
                    } else { // below the horizon: dashed rungs
                        for (int d = 0; d < half; d += 6) {
                            g.fill(cx - 30 - half + d, y, cx - 30 - half + d + 3, y + 1, green);
                            g.fill(cx + 30 + d, y, cx + 33 + d, y + 1, green);
                        }
                    }
                    g.drawString(mc.font, RUNG[i], cx + 30 + half + 3, y - 4, green, false);
                }
            }
        }
        // flight-path marker
        if (speed > 0.05) {
            double fpa = ElytraGlideSim.flightPathDeg(hSpeed, vy);
            Vec3 v = p.getDeltaMovement();
            double velYaw = Math.toDegrees(Math.atan2(-v.x, v.z));
            double dyaw = wrap(velYaw - p.getYRot());
            int mx = (int) Math.round(cx + dyaw * k);
            int my = (int) Math.round(cy - (fpa + pitch) * k);
            if (Math.abs(mx - cx) < w / 3 && Math.abs(my - cy) < h / 3) {
                g.fill(mx - 3, my - 3, mx + 4, my - 2, green);
                g.fill(mx - 3, my + 3, mx + 4, my + 4, green);
                g.fill(mx - 3, my - 3, mx - 2, my + 4, green);
                g.fill(mx + 3, my - 3, mx + 4, my + 4, green);
                g.fill(mx - 8, my, mx - 3, my + 1, green);
                g.fill(mx + 4, my, mx + 9, my + 1, green);
                g.fill(mx, my - 7, mx + 1, my - 3, green);
            }
        }
        int spd = (int) Math.min(MAX_SPEED_LABEL, Math.round(speed * 20));
        int vs = (int) Math.max(-MAX_SPEED_LABEL, Math.min(MAX_SPEED_LABEL, Math.round(vy * 20)));
        g.drawString(mc.font, SPEED[spd], cx - 110, cy - 4, green, false);
        g.drawString(mc.font, SINK[vs + MAX_SPEED_LABEL], cx + 80, cy - 4, green, false);
        if (ElytraGlideSim.stalled(speed, pitch, stallSpeed)) {
            int red = 0xE0FF4040;
            int y = cy + 24;
            for (int i = 0; i < 6; i++) { // a "V" chevron pointing down: push the nose down
                g.fill(cx - 12 + i * 2, y + i, cx - 10 + i * 2, y + i + 1, red);
                g.fill(cx + 10 - i * 2, y + i, cx + 12 - i * 2, y + i + 1, red);
            }
            g.drawString(mc.font, "STALL", cx - mc.font.width("STALL") / 2, y + 8, red, false);
        }
        if (cue != GlideFootprint.WaveCue.NONE) {
            int amber = 0xE0FFC040;
            boolean dive = cue == GlideFootprint.WaveCue.DIVE;
            int y = cy - 36;
            for (int i = 0; i < 5; i++) {
                int row = dive ? y + i : y + 4 - i;
                g.fill(cx - i, row, cx + i + 1, row + 1, amber);
            }
            String s = dive ? "DIVE -40" : "CLIMB +40";
            g.drawString(mc.font, s, cx - mc.font.width(s) / 2, y - 10, amber, false);
        }
    }

    private static double wrap(double deg) {
        deg %= 360.0;
        if (deg >= 180) {
            deg -= 360;
        } else if (deg < -180) {
            deg += 360;
        }
        return deg;
    }

    /** World pass: the glide footprint as one batched line loop over the landed points. */
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!showFootprint || !visible(mc) || ringReady < HEADINGS) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        if (pose == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose last = pose.last();
        for (int i = 0; i < HEADINGS; i++) {
            int j = (i + 1) % HEADINGS;
            if (!footprint.landed[i] || !footprint.landed[j]) {
                continue;
            }
            float x0 = (float) (footprint.ringX[i] - cam.x), y0 = (float) (footprint.ringY[i] + 0.1 - cam.y);
            float z0 = (float) (footprint.ringZ[i] - cam.z);
            float x1 = (float) (footprint.ringX[j] - cam.x), y1 = (float) (footprint.ringY[j] + 0.1 - cam.y);
            float z1 = (float) (footprint.ringZ[j] - cam.z);
            float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-4f) {
                continue;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            lines.addVertex(last, x0, y0, z0).setColor(0.25f, 1f, 0.45f, 0.85f).setNormal(last, nx, ny, nz);
            lines.addVertex(last, x1, y1, z1).setColor(0.25f, 1f, 0.45f, 0.85f).setNormal(last, nx, ny, nz);
        }
        buffers.endBatch(RenderType.lines());
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
            gliding = false;
            ringReady = 0;
        }
    }

    @Override
    public String displayName() {
        return "Elytra HUD";
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        ladder = p.getBool(ID, K_LADDER, true);
        showFootprint = p.getBool(ID, K_FOOTPRINT, true);
        wave = p.getBool(ID, K_WAVE, true);
        stallSpeed = p.getDouble(ID, K_STALL, 0.5);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_LADDER, "Pitch ladder", true, "",
                        "Artificial horizon with a rung every 10 degrees and the flight-path marker."),
                SettingSpec.bool(K_FOOTPRINT, "Glide footprint", true, "",
                        "Ring on the ground marking the farthest point reachable by gliding without rockets."),
                SettingSpec.bool(K_WAVE, "Wave-glide cue", true, "",
                        "Dive/climb arrow when a wave glide would fly farther than a straight glide."),
                SettingSpec.decimal(K_STALL, "Stall speed", 0.5, 0.2, 1.5, "",
                        "Speed (blocks per tick) below which the stall chevron shows while the nose is up."));
    }
}
