package com.utilitysuite.world.replay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SecondaryView;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.mixin.world.LevelRendererCullStateAccessor;
import com.utilitysuite.mixin.world.MinecraftMainTargetAccessor;
import com.utilitysuite.mixin.world.SectionOcclusionGraphAccessor;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.replay.PipRenderPolicy.Fallback;
import com.utilitysuite.world.replay.PipRenderPolicy.RenderMode;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Picture-in-picture camera (F1): a HUD corner panel showing the scene from a second camera pose.
 * <ul>
 *   <li><b>Rear view</b>: from your eye looking backwards (optionally mirrored like a real mirror).</li>
 *   <li><b>Drone</b>: a chase drone above and behind you until the park key pins the current camera pose; the panel
 *       then keeps showing the scene from there.</li>
 * </ul>
 * Render modes:
 * <ul>
 *   <li><b>Full render</b>: a true second world render. At {@code RenderFrameEvent.Pre} (before the main frame, so
 *       everything the second pass touches is set up again by the main pass) the level is rendered with
 *       {@code LevelRenderer.renderLevel} from a private {@link Camera} into an offscreen {@link TextureTarget} at
 *       reduced resolution, at most once every N frames; the HUD layer blits that texture into the kit frame every
 *       frame. During the pass: {@code Minecraft.mainRenderTarget} is swapped for the offscreen target (accessor
 *       mixin; the target forces its own viewport on every bind, so a post chain resizing the viewport cannot spill
 *       out of it), {@code RenderLevelStageEvent} is not dispatched ({@link SecondaryView}, so no overlay of ours or
 *       anyone's recurses or double-draws), the projection matrix is backed up and restored, and the level
 *       renderer's culling bookkeeping is primed so the second camera neither invalidates the main section-occlusion
 *       graph nor leaves its frustum-culled section list to the main pass (and a frame is skipped while a full
 *       occlusion rebuild is pending, so that rebuild is always scheduled from the main camera). Everything is restored in a
 *       {@code finally}; a failure switches the panel to the wireframe for the session.</li>
 *   <li><b>Wireframe</b> (default, and the fallback): entity boxes coloured by kind, velocity ticks and a ground grid
 *       projected with {@link ViewProjector} from the second pose into a preallocated segment cache.</li>
 * </ul>
 * Full render is unavailable with Sodium, Embeddium, Iris or Oculus loaded (they replace the level renderer's
 * internals) and with Fabulous graphics (its transparency post chain is sized to the window); the panel then draws
 * the wireframe and says why. Off by default; nothing is drawn or rendered when off, in Clean-Screen, with F1 or F3,
 * or with a screen open, and the offscreen target is freed when the module turns off.
 */
public final class PipCameraModule extends WorldModule implements LayeredDraw.Layer {

    public static final String ID = "pip_camera";

    public enum Mode { REAR_VIEW, DRONE }

    public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    private static final int MAX_SEGS = 64 * 13 + 40;
    private static final String NOTE_RENDERER = "full render off: Sodium/Iris";
    private static final String NOTE_FABULOUS = "full render off: Fabulous";
    private static final String NOTE_FAILED = "full render failed";

    private final ViewProjector view = new ViewProjector();
    private final FrameGate gate = new FrameGate();
    private final FrameGate fullGate = new FrameGate();
    private final GuiLines lines = new GuiLines();
    private final float[] segs = new float[MAX_SEGS * 4];
    private final int[] segColor = new int[MAX_SEGS];
    private int segCount;

    private KeyMapping parkKey;
    private Mode mode = Mode.REAR_VIEW;
    private RenderMode renderMode = RenderMode.WIREFRAME;
    private Corner corner = Corner.TOP_RIGHT;
    private int refreshEvery = 3;
    private int resolutionPct = 50;
    private int panelWidth = 160;
    private double range = 24;
    private boolean mirror = true;

    private boolean parked;
    private double parkX;
    private double parkY;
    private double parkZ;
    private float parkYaw;
    private float parkPitch;

    // Second camera pose (shared by both render modes), recomputed when a refresh is due.
    private double poseX;
    private double poseY;
    private double poseZ;
    private float poseYaw;
    private float posePitch;
    private float poseFov;

    // Full render state; created lazily on the render thread (never in the constructor, so unit tests stay GL free).
    private PipTarget target;
    private PipCamera camera;
    private Matrix4f projection;
    private Matrix4f viewRotation;
    private Quaternionf scratchRotation;
    private boolean fullValid;
    private boolean fullFailed;
    private int rendererMod = -1;

    public PipCameraModule() {
        super(ID, "Picture-in-Picture", false);
    }

    public void setParkKey(KeyMapping key) {
        parkKey = key;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("mode", "View", Mode.REAR_VIEW, "Camera",
                        "Rear view looks back from your eye; Drone chases you until the park key pins it."),
                SettingSpec.choice("render", "Render mode", RenderMode.WIREFRAME, "Camera",
                        "Off hides the panel; Wireframe draws entity boxes; Full renders the real world from the "
                                + "second camera (costs a second world render every N frames). Full needs the "
                                + "vanilla renderer: with Sodium, Embeddium or Iris loaded, or Fabulous graphics, it "
                                + "falls back to the wireframe."),
                SettingSpec.bool("mirror", "Mirror rear view", true, "Camera",
                        "Flip the rear view left-right, like a real mirror."),
                SettingSpec.decimal("range", "Range", 24.0, 8.0, 64.0, "Camera",
                        "Wireframe: entities within this many blocks are drawn."),
                SettingSpec.integer("refresh_every", "Refresh every N frames", 3, 1, 10, "Performance",
                        "The view is re-rendered once every N frames (drawn every frame from the last image)."),
                SettingSpec.integer("resolution_pct", "Full render resolution (%)", 50, 10, 100, "Performance",
                        "Offscreen resolution of the full render, as a share of the panel's on-screen pixels."),
                SettingSpec.integer("panel_width", "Panel width", 160, 80, 320, "Panel", "In GUI pixels (16:9)."),
                SettingSpec.choice("corner", "Corner", Corner.TOP_RIGHT, "Panel", "Screen corner of the panel."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        mode = choice(p, "mode", Mode.class);
        renderMode = choice(p, "render", RenderMode.class);
        mirror = bool(p, "mirror");
        range = decimal(p, "range");
        refreshEvery = integer(p, "refresh_every");
        gate.setEvery(refreshEvery);
        fullGate.setEvery(refreshEvery);
        resolutionPct = integer(p, "resolution_pct");
        panelWidth = integer(p, "panel_width");
        corner = choice(p, "corner", Corner.class);
        fullValid = false;
        fullGate.invalidate();
    }

    @Override
    protected void onDisable() {
        parked = false;
        segCount = 0;
        releaseTarget();
    }

    @Override
    public void onWorldUnload() {
        parked = false;
        segCount = 0;
        releaseTarget();
    }

    private void releaseTarget() {
        fullValid = false;
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    @Override
    public void onTick() {
        while (parkKey != null && parkKey.consumeClick()) {
            if (parked) {
                parked = false; // second press: back to the chase drone
                continue;
            }
            Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 pos = cam.getPosition(); // the camera's own field
            parkX = pos.x;
            parkY = pos.y;
            parkZ = pos.z;
            parkYaw = cam.getYRot();
            parkPitch = cam.getXRot();
            parked = true;
        }
    }

    /** True when the panel would be drawn this frame. */
    private boolean panelVisible(Minecraft mc) {
        return isEnabled() && renderMode != RenderMode.OFF && !cleanScreen() && mc.player != null && mc.level != null
                && !mc.options.hideGui && mc.screen == null && !mc.getDebugOverlay().showDebugScreen();
    }

    private boolean rendererModLoaded() {
        if (rendererMod < 0) {
            ModList ml = ModList.get();
            rendererMod = ml != null && PipRenderPolicy.anyIncompatible(ml::isLoaded) ? 1 : 0;
        }
        return rendererMod == 1;
    }

    private Fallback fallback() {
        return PipRenderPolicy.fallback(renderMode, rendererModLoaded(), Minecraft.useShaderTransparency(),
                fullFailed);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Full render: the second level pass (RenderFrameEvent.Pre, before the main frame)
    // ---------------------------------------------------------------------------------------------------------------

    /** Game bus, every frame. Early-outs unless the full render is on, visible and due. */
    public void onRenderFrame(RenderFrameEvent.Pre e) {
        if (!isEnabled() || renderMode != RenderMode.FULL || SecondaryView.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!panelVisible(mc) || !mc.isGameLoadFinished() || fallback() != Fallback.NONE) {
            fullValid = false;
            fullGate.invalidate(); // render at once when the panel shows again
            return;
        }
        if (!fullGate.tick()) {
            return;
        }
        LevelRendererCullStateAccessor lr = (LevelRendererCullStateAccessor) mc.levelRenderer;
        if (((SectionOcclusionGraphAccessor) lr.utilitysuite$occlusionGraph()).utilitysuite$needsFullUpdate()) {
            // A full occlusion rebuild is pending and would be scheduled from our camera: keep the last image and
            // let the main pass schedule it from the main camera; try again next frame.
            fullGate.invalidate();
            return;
        }
        renderSecondPass(mc, e.getPartialTick());
    }

    private void renderSecondPass(Minecraft mc, DeltaTracker delta) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        GameRenderer gr = mc.gameRenderer;
        LevelRenderer lr = mc.levelRenderer;
        int tw = PipRenderPolicy.targetWidth(panelWidth, mc.getWindow().getGuiScale(), resolutionPct);
        int th = PipRenderPolicy.targetHeight(tw);
        if (target == null) {
            target = new PipTarget(tw, th);
            target.setFilterMode(GL11.GL_LINEAR);
        } else if (target.width != tw || target.height != th) {
            target.resize(tw, th, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }
        if (camera == null) {
            camera = new PipCamera();
            projection = new Matrix4f();
            viewRotation = new Matrix4f();
            scratchRotation = new Quaternionf();
        }
        float pt = delta.getGameTimeDeltaPartialTick(false);
        computePose(p, pt);
        camera.place(level, p, mode == Mode.DRONE, pt, poseX, poseY, poseZ, poseYaw, posePitch);
        projection.setPerspective((float) Math.toRadians(poseFov), tw / (float) th, 0.05f, gr.getDepthFar());
        viewRotation.rotation(camera.rotation().conjugate(scratchRotation));

        RenderTarget main = mc.getMainRenderTarget();
        MinecraftMainTargetAccessor targets = (MinecraftMainTargetAccessor) mc;
        LevelRendererCullStateAccessor cull = (LevelRendererCullStateAccessor) lr;
        double savedX = cull.utilitysuite$prevCamX();
        double savedY = cull.utilitysuite$prevCamY();
        double savedZ = cull.utilitysuite$prevCamZ();
        // Same 8-block cell as our camera: setupRender won't invalidate the main occlusion graph. NaN rotation: it
        // re-culls the section list for our frustum instead of keeping the main view's.
        cull.utilitysuite$setPrevCamX(Math.floor(poseX / 8.0));
        cull.utilitysuite$setPrevCamY(Math.floor(poseY / 8.0));
        cull.utilitysuite$setPrevCamZ(Math.floor(poseZ / 8.0));
        cull.utilitysuite$setPrevCamRotX(Double.NaN);
        cull.utilitysuite$setPrevCamRotY(Double.NaN);
        ProfilerFiller profiler = mc.getProfiler();
        profiler.push("utilitysuite_pip");
        RenderSystem.backupProjectionMatrix();
        SecondaryView.begin();
        targets.utilitysuite$setMainRenderTarget(target);
        try {
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            lr.prepareCullFrustum(camera.getPosition(), viewRotation, projection);
            lr.renderLevel(delta, false, camera, gr, gr.lightTexture(), viewRotation, projection);
            fullValid = true;
        } catch (RuntimeException ex) {
            fullFailed = true;
            fullValid = false;
            UtilitySuiteMod.LOGGER.warn("Picture-in-picture full render failed; using the wireframe from now on", ex);
        } finally {
            targets.utilitysuite$setMainRenderTarget(main);
            SecondaryView.end();
            main.bindWrite(true);
            RenderSystem.restoreProjectionMatrix();
            cull.utilitysuite$setPrevCamX(savedX);
            cull.utilitysuite$setPrevCamY(savedY);
            cull.utilitysuite$setPrevCamZ(savedZ);
            // Force the main pass to re-cull its section list for its own frustum.
            cull.utilitysuite$setPrevCamRotX(Double.NaN);
            cull.utilitysuite$setPrevCamRotY(Double.NaN);
            // The state Minecraft set before RenderFrameEvent.Pre.
            FogRenderer.setupNoFog();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            profiler.pop();
        }
    }

    /** Offscreen level target. Every bind restores its own viewport (a post pass may have set the window's). */
    private static final class PipTarget extends TextureTarget {
        PipTarget(int w, int h) {
            super(w, h, true, Minecraft.ON_OSX);
        }

        @Override
        public void bindWrite(boolean setViewport) {
            super.bindWrite(true);
        }
    }

    /** A camera placed at an arbitrary pose (vanilla's only follows an entity). */
    private static final class PipCamera extends Camera {
        /**
         * @param detached true when the entity should be drawn (drone); false hides it (rear view from the eye)
         */
        void place(BlockGetter level, Entity entity, boolean detached, float pt, double x, double y, double z,
                   float yaw, float pitch) {
            setup(level, entity, detached, false, pt);
            setRotation(yaw, pitch, 0f);
            setPosition(x, y, z);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // HUD layer
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!panelVisible(mc)) {
            return;
        }
        int w = panelWidth;
        int h = w * 9 / 16;
        int x = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT ? 6 : g.guiWidth() - w - 6;
        int y = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT ? 6 : g.guiHeight() - h - 6;
        Fallback fb = fallback();
        boolean flip = mode == Mode.REAR_VIEW && mirror;
        HoloFrames.frame(g, x - 2, y - 2, w + 4, h + 4, FrameLevel.QUIET, false, HoloToken.GLASS_HUD,
                HoloToken.LINE, 1f);
        HoloFrames.brackets(g, x - 2, y - 2, w + 4, h + 4, HoloTheme.bracketArm(w + 4, h + 4),
                HoloTheme.argb(HoloToken.LINE_STRONG));
        if (renderMode == RenderMode.FULL && fb == Fallback.NONE) {
            if (fullValid && target != null) {
                blitTarget(g, x, y, w, h, flip);
            } else {
                HoloFrames.plate(g, x, y, w, h, HoloToken.VOID, 0.7f);
            }
        } else {
            drawWireframe(g, mc, x, y, w, h, flip, delta.getGameTimeDeltaPartialTick(false));
        }
        UiRenderUtils.text(g, mc.font, mode == Mode.REAR_VIEW ? "REAR" : parked ? "DRONE (parked)" : "DRONE",
                x + 3, y + 3, HoloTheme.argb(HoloToken.TEXT_SECONDARY));
        String note = fb == Fallback.RENDERER_MOD ? NOTE_RENDERER : fb == Fallback.FABULOUS ? NOTE_FABULOUS
                : fb == Fallback.FAILED ? NOTE_FAILED : null;
        if (note != null) {
            UiRenderUtils.text(g, mc.font, note, x + 3, y + h - 11, HoloTheme.argb(HoloToken.WARN));
        }
    }

    /** Draws the offscreen texture (GL origin bottom-left, so v runs 1 to 0 down the panel). */
    private void blitTarget(GuiGraphics g, int x, int y, int w, int h, boolean flip) {
        g.flush();
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        Matrix4f m = g.pose().last().pose();
        float u0 = flip ? 1f : 0f;
        float u1 = flip ? 0f : 1f;
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(m, x, y, 0).setUv(u0, 1f);
        buf.addVertex(m, x, y + h, 0).setUv(u0, 0f);
        buf.addVertex(m, x + w, y + h, 0).setUv(u1, 0f);
        buf.addVertex(m, x + w, y, 0).setUv(u1, 1f);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    private void drawWireframe(GuiGraphics g, Minecraft mc, int x, int y, int w, int h, boolean flip, float pt) {
        if (gate.tick()) {
            refresh(mc.player, mc.level, pt, w / (double) h);
        }
        HoloFrames.plate(g, x, y, w, h, HoloToken.VOID, 0.7f);
        g.flush();
        lines.begin(g.pose().last().pose(), x, y, w, h);
        for (int s = 0; s < segCount; s++) {
            int k = s * 4;
            float ax = flip ? -segs[k] : segs[k];
            float bx = flip ? -segs[k + 2] : segs[k + 2];
            lines.segment(ax, segs[k + 1], bx, segs[k + 3], 1f, segColor[s]);
        }
        lines.end();
    }

    /** The second camera pose for this frame (rear view, parked drone or chase drone). */
    private void computePose(LocalPlayer p, float pt) {
        double px = p.xOld + (p.getX() - p.xOld) * pt;
        double py = p.yOld + (p.getY() - p.yOld) * pt;
        double pz = p.zOld + (p.getZ() - p.zOld) * pt;
        float yaw = p.getViewYRot(pt);
        if (mode == Mode.REAR_VIEW) {
            poseX = px;
            poseY = py + p.getEyeHeight();
            poseZ = pz;
            poseYaw = ViewProjector.wrap(yaw + 180f);
            posePitch = 8f;
            poseFov = 80f;
        } else if (parked) {
            poseX = parkX;
            poseY = parkY;
            poseZ = parkZ;
            poseYaw = parkYaw;
            posePitch = parkPitch;
            poseFov = 70f;
        } else {
            double ya = Math.toRadians(yaw);
            double back = 7.0;
            poseX = px + Math.sin(ya) * back;
            poseY = py + 6.0;
            poseZ = pz - Math.cos(ya) * back;
            poseYaw = yaw;
            posePitch = 38f;
            poseFov = 70f;
        }
    }

    /** Recomputes the projected wireframe from the second pose. Allocation free apart from the level iterator. */
    private void refresh(LocalPlayer p, ClientLevel level, float pt, double aspect) {
        computePose(p, pt);
        view.set(poseX, poseY, poseZ, poseYaw, posePitch, poseFov, aspect);
        double px = p.xOld + (p.getX() - p.xOld) * pt;
        double py = p.yOld + (p.getY() - p.yOld) * pt;
        double pz = p.zOld + (p.getZ() - p.zOld) * pt;
        int grid = HoloTheme.argb(HoloToken.LINE_FAINT);
        int n = 0;
        int count = 0;
        double gy = Math.floor(py);
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        for (int k = -6; k <= 6; k += 2) {
            int before = n;
            n = view.projectSegment(cx + k, gy, cz - 6, cx + k, gy, cz + 6, segs, n);
            if (n > before) {
                segColor[count++] = grid;
            }
            before = n;
            n = view.projectSegment(cx - 6, gy, cz + k, cx + 6, gy, cz + k, segs, n);
            if (n > before) {
                segColor[count++] = grid;
            }
        }
        double r2 = range * range;
        boolean includeSelf = mode == Mode.DRONE;
        int velocity = HoloTheme.argb(HoloToken.SYNC);
        for (Entity e : level.entitiesForRendering()) {
            if (n + 13 * 4 > segs.length) {
                break;
            }
            if ((e == p && !includeSelf) || e.isRemoved() || e.distanceToSqr(px, py, pz) > r2) {
                continue;
            }
            HoloToken tok = e == p ? HoloToken.ACCENT : e instanceof Enemy ? HoloToken.DANGER
                    : e instanceof Player ? HoloToken.WARN
                    : e instanceof LivingEntity ? HoloToken.TEXT_SECONDARY : HoloToken.TEXT_FAINT;
            int color = HoloTheme.argb(tok);
            double ex = e.xOld + (e.getX() - e.xOld) * pt;
            double ey = e.yOld + (e.getY() - e.yOld) * pt;
            double ez = e.zOld + (e.getZ() - e.zOld) * pt;
            double hw = e.getBbWidth() * 0.5;
            int before = n;
            n = view.projectBox(ex - hw, ey, ez - hw, ex + hw, ey + e.getBbHeight(), ez + hw, segs, n);
            for (int k = before; k < n; k += 4) {
                segColor[count++] = color;
            }
            Vec3 v = e.getDeltaMovement();
            before = n;
            double mid = ey + e.getBbHeight() * 0.5;
            n = view.projectSegment(ex, mid, ez, ex + v.x * 10, mid + v.y * 10, ez + v.z * 10, segs, n);
            if (n > before) {
                segColor[count++] = velocity;
            }
        }
        segCount = count;
    }
}
