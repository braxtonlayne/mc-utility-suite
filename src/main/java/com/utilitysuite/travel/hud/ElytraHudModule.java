package com.utilitysuite.travel.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHudHost;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.TextRole;
import com.utilitysuite.travel.movement.ElytraGlideSim;
import com.utilitysuite.travel.movement.GlideFootprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.List;

/**
 * M2 fighter-jet elytra HUD (off by default). While gliding: a centred artificial horizon with a pitch ladder every 10
 * degrees, a flight-path marker from the velocity, airspeed and vertical speed, a stall chevron below the glide-stall
 * speed, a wave-glide cue arrow when the dive/climb pattern would beat a straight glide, and in the world a zero-rocket
 * glide footprint ring ({@link GlideFootprint}) over the terrain heightmap.
 * <p>
 * The screen part is a {@link HudElement} in the CROSSHAIR slot, drawn through the HUD kit with theme tokens (the host
 * applies the HUD scale, opacity, Clean-Screen and hidden-GUI gates). Budget: the footprint computes
 * {@link #HEADINGS_PER_TICK} headings per client tick (a full ring every few ticks); the ring draws with one batched
 * line buffer in {@link SubmitCustomGeometryEvent}. Nothing is drawn unless gliding.
 */
public final class ElytraHudModule implements IModule, IConfigurable, HudElement {

    public static final String ID = "elytra_hud";
    public static final String K_LADDER = "pitch_ladder";
    public static final String K_FOOTPRINT = "glide_footprint";
    public static final String K_WAVE = "wave_cue";
    public static final String K_STALL = "stall_speed";

    static final int HEADINGS = 32;
    static final int HEADINGS_PER_TICK = 2;
    static final int MAX_GLIDE_TICKS = 1200;
    static final int WAVE_EVERY = 20;
    private static final String SPD = "SPD", VS = "V/S", STALL = "STALL", DIVE = "DIVE -40", CLIMB = "CLIMB +40";

    private final GlideFootprint footprint = new GlideFootprint(HEADINGS, MAX_GLIDE_TICKS);
    private final ElytraGlideSim waveScratch = new ElytraGlideSim();
    private final GlideFootprint.Heightmap heights = this::surfaceY;
    private boolean enabled;
    private boolean ladder = true;
    private boolean showFootprint = true;
    private boolean wave = true;
    private double stallSpeed = 0.5;
    private boolean hosted;

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
        if (!hosted) {
            IVisuals v = Services.visualsOrNull();
            if (v != null && v.hud() != IHudHost.NONE) {
                v.hud().register(this);
                hosted = true;
            }
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
        return enabled && gliding && mc.player != null && !mc.gui.hud.isHidden() && !Services.modState().isCleanScreen();
    }

    // ---- HUD element ------------------------------------------------------------------------------------------------

    @Override
    public HudSlot slot() {
        return HudSlot.CROSSHAIR;
    }

    @Override
    public boolean wantsDraw() {
        return enabled && gliding && Minecraft.getInstance().player != null;
    }

    /** Horizon, ladder, flight-path marker, readouts, stall chevron and wave cue around the crosshair (cx, cy). */
    @Override
    public void draw(IHoloCanvas c, float cx, float cy, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        float h = c.screenHeight(), w = c.screenWidth();
        double fov = Math.max(30, mc.options.fov().get());
        float k = (float) (h / fov); // pixels per degree (vertical)
        float pitch = p.getXRot();
        if (ladder) {
            for (int elev = 90; elev >= -90; elev -= 10) {
                float y = cy - (elev + pitch) * k;
                if (y < cy - h / 3 || y > cy + h / 3) {
                    continue;
                }
                if (elev == 0) {
                    c.hLine(cx - 70, cx - 12, y, HoloToken.ACCENT, alpha);
                    c.hLine(cx + 12, cx + 70, y, HoloToken.ACCENT, alpha);
                    continue;
                }
                float half = 22;
                if (elev > 0) {
                    c.hLine(cx - 30 - half, cx - 30, y, HoloToken.LINE_STRONG, alpha);
                    c.hLine(cx + 30, cx + 30 + half, y, HoloToken.LINE_STRONG, alpha);
                } else { // below the horizon: dashed rungs
                    for (int d = 0; d < half; d += 6) {
                        c.hLine(cx - 30 - half + d, cx - 27 - half + d, y, HoloToken.LINE, alpha);
                        c.hLine(cx + 30 + d, cx + 33 + d, y, HoloToken.LINE, alpha);
                    }
                }
                c.number(TextRole.MONO, Math.abs(elev), cx + 30 + half + 3, y - 4, HoloToken.TEXT_SECONDARY, alpha);
            }
        }
        if (speed > 0.05) {
            double fpa = ElytraGlideSim.flightPathDeg(hSpeed, vy);
            Vec3 v = p.getDeltaMovement();
            double velYaw = Math.toDegrees(Math.atan2(-v.x, v.z));
            float mx = (float) (cx + wrap(velYaw - p.getYRot()) * k);
            float my = (float) (cy - (fpa + pitch) * k);
            if (Math.abs(mx - cx) < w / 3 && Math.abs(my - cy) < h / 3) {
                c.arc(mx, my, 3.5f, 0f, 360f, 1f, HoloToken.ACCENT, alpha);
                c.hLine(mx - 9, mx - 4, my, HoloToken.ACCENT, alpha);
                c.hLine(mx + 4, mx + 9, my, HoloToken.ACCENT, alpha);
                c.vLine(mx, my - 8, my - 4, HoloToken.ACCENT, alpha);
            }
        }
        c.text(TextRole.MICRO, SPD, cx - 110, cy - 12, HoloToken.TEXT_MUTED, alpha);
        c.number(TextRole.MONO, (int) Math.round(speed * 20), cx - 110, cy - 2, HoloToken.TEXT, alpha);
        c.text(TextRole.MICRO, VS, cx + 80, cy - 12, HoloToken.TEXT_MUTED, alpha);
        c.number(TextRole.MONO, (int) Math.round(vy * 20), cx + 80, cy - 2,
                vy < -1.0 ? HoloToken.WARN : HoloToken.TEXT, alpha);
        if (ElytraGlideSim.stalled(speed, pitch, stallSpeed)) {
            float y = cy + 24;
            // a "V" chevron pointing down: push the nose down
            c.ribbon(cx - 12, y, cx, y + 7, 2f, HoloToken.DANGER, alpha);
            c.ribbon(cx, y + 7, cx + 12, y, 2f, HoloToken.DANGER, alpha);
            c.text(TextRole.MICRO, STALL, cx - c.width(TextRole.MICRO, STALL) / 2, y + 10, HoloToken.DANGER, alpha);
        }
        if (cue != GlideFootprint.WaveCue.NONE) {
            boolean dive = cue == GlideFootprint.WaveCue.DIVE;
            float y = cy - 36;
            float tip = dive ? y + 6 : y - 6;
            c.ribbon(cx - 6, y, cx, tip, 1.5f, HoloToken.WARN, alpha);
            c.ribbon(cx, tip, cx + 6, y, 1.5f, HoloToken.WARN, alpha);
            String s = dive ? DIVE : CLIMB;
            c.text(TextRole.MICRO, s, cx - c.width(TextRole.MICRO, s) / 2, y - 16, HoloToken.WARN, alpha);
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
    public void onRenderLevel(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!showFootprint || !visible(mc) || ringReady < HEADINGS) {
            return;
        }
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        footCamX = cam.x;
        footCamY = cam.y;
        footCamZ = cam.z;
        lineWidth = Math.max(1f, mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth);
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.linesTranslucent(),
                drawFootprint);
    }

    /** The deferred draw (26.3 submits run later in the same frame): one instance. */
    private final SubmitNodeCollector.CustomGeometryRenderer drawFootprint = this::drawFootprint;
    private double footCamX;
    private double footCamY;
    private double footCamZ;
    private float lineWidth = 1f;

    private void drawFootprint(PoseStack.Pose last, VertexConsumer lines) {
        double camX = footCamX;
        double camY = footCamY;
        double camZ = footCamZ;
        float w = lineWidth;
        for (int i = 0; i < HEADINGS; i++) {
            int j = (i + 1) % HEADINGS;
            if (!footprint.landed[i] || !footprint.landed[j]) {
                continue;
            }
            float x0 = (float) (footprint.ringX[i] - camX), y0 = (float) (footprint.ringY[i] + 0.1 - camY);
            float z0 = (float) (footprint.ringZ[i] - camZ);
            float x1 = (float) (footprint.ringX[j] - camX), y1 = (float) (footprint.ringY[j] + 0.1 - camY);
            float z1 = (float) (footprint.ringZ[j] - camZ);
            float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-4f) {
                continue;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            lines.addVertex(last, x0, y0, z0).setColor(0.25f, 1f, 0.45f, 0.85f).setNormal(last, nx, ny, nz)
                    .setLineWidth(w);
            lines.addVertex(last, x1, y1, z1).setColor(0.25f, 1f, 0.45f, 0.85f).setNormal(last, nx, ny, nz)
                    .setLineWidth(w);
        }
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
