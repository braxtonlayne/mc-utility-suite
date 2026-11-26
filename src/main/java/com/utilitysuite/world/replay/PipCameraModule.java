package com.utilitysuite.world.replay;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SecondaryView;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.mixin.world.GameRendererMainTargetAccessor;
import com.utilitysuite.mixin.world.LevelExtractorCullStateAccessor;
import com.utilitysuite.mixin.world.SectionOcclusionGraphAccessor;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.minimap.GuiMesh;
import com.utilitysuite.world.replay.PipRenderPolicy.Fallback;
import com.utilitysuite.world.replay.PipRenderPolicy.RenderMode;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.joml.Matrix4f;

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
 *   <li><b>Full render</b>: a true second world render. 26.3 splits a frame into extract (level render state) and
 *       render, and {@code RenderFrameEvent.Pre} fires after the main extract, so the second pass runs at
 *       {@code RenderFrameEvent.Post} instead (the main frame is finished; its level state may be overwritten because
 *       the next frame extracts again). The pass: a private {@link Camera} pinned to the second pose is updated and
 *       extracted into the shared {@code CameraRenderState}, {@code LevelExtractor.extract} fills the level state from
 *       it, then {@code LevelRenderer.render} draws into an offscreen {@link TextureTarget} at reduced resolution (the
 *       {@code GameRenderer} main target is swapped for it through an accessor mixin, so every frame-graph target the
 *       level renderer creates is sized to it), at most once every N frames, with its own projection buffer, fog
 *       buffer and resource pool. The HUD layer blits the last image (opaque) into the kit frame every frame, so the
 *       panel shows the image of the previous frame. {@link SecondaryView} is active for the pass (our world overlays
 *       skip themselves), entity outlines are off for it, and the culling bookkeeping is primed so the second camera
 *       neither invalidates the main section-occlusion graph nor leaves its frustum-culled section list to the main
 *       pass (and a frame is skipped while a full occlusion rebuild is pending, so that rebuild is always scheduled
 *       from the main camera). Everything is restored in a {@code finally}; a failure switches the panel to the
 *       wireframe for the session.</li>
 *   <li><b>Wireframe</b> (default, and the fallback): entity boxes coloured by kind, velocity ticks and a ground grid
 *       projected with {@link ViewProjector} from the second pose into a preallocated segment cache.</li>
 * </ul>
 * Full render is unavailable with Sodium, Embeddium, Iris or Oculus loaded (they replace the level renderer's
 * internals) and with Fabulous / improved transparency (kept from 1.21.1; untested in 26.3); the panel then draws
 * the wireframe and says why. Off by default; nothing is drawn or rendered when off, in Clean-Screen, with F1 or F3,
 * or with a screen open, and the offscreen target is freed when the module turns off.
 */
public final class PipCameraModule extends WorldModule implements GuiLayer {

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

    // Full render state; created lazily on the render thread (never in the constructor, so unit tests stay GPU free).
    private TextureTarget target;
    private PipCamera camera;
    private Projection projection;
    private ProjectionMatrixBuffer projectionBuffer;
    private FogRenderer fog;
    private CrossFrameResourcePool pool;
    private final GuiMesh blit = new GuiMesh();
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
        if (pool != null) {
            pool.clear(); // frees the pass's frame-graph targets
        }
        // The fog renderer and projection buffer are kept for the session (a few hundred bytes): the fog renderer's
        // constructor points the global shader fog at its own buffer, so closing it could leave that pointing at a
        // freed buffer until the main pass sets its own again (not at all on the title screen).
    }

    @Override
    public void onTick() {
        while (parkKey != null && parkKey.consumeClick()) {
            if (parked) {
                parked = false; // second press: back to the chase drone
                continue;
            }
            Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
            Vec3 pos = cam.position(); // the camera's own field
            parkX = pos.x;
            parkY = pos.y;
            parkZ = pos.z;
            parkYaw = cam.yRot();
            parkPitch = cam.xRot();
            parked = true;
        }
        if (camera != null && renderMode == RenderMode.FULL) {
            camera.tick(); // eye height, fov easing and the environment-attribute probe (sky / fog colours)
        }
    }

    /** True when the panel would be drawn this frame. */
    private boolean panelVisible(Minecraft mc) {
        return isEnabled() && renderMode != RenderMode.OFF && !cleanScreen() && mc.player != null && mc.level != null
                && !mc.gui.hud.isHidden() && mc.gui.screen() == null && !mc.getDebugOverlay().showDebugScreen();
    }

    private boolean rendererModLoaded() {
        if (rendererMod < 0) {
            ModList ml = ModList.get();
            rendererMod = ml != null && PipRenderPolicy.anyIncompatible(ml::isLoaded) ? 1 : 0;
        }
        return rendererMod == 1;
    }

    private Fallback fallback() {
        return PipRenderPolicy.fallback(renderMode, rendererModLoaded(),
                Minecraft.getInstance().gameRenderer.useImprovedTransparency(),
                fullFailed);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Full render: the second level pass (RenderFrameEvent.Post, after the main frame)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Game bus, every frame. Early-outs unless the full render is on, visible and due. Takes the base event so the
     * bootstrap can subscribe either phase; only {@code Post} renders (in 26.3 {@code Pre} fires after the main
     * frame's extract, so a pass there would overwrite the state the main frame is about to draw).
     */
    public void onRenderFrame(RenderFrameEvent e) {
        if (!(e instanceof RenderFrameEvent.Post) || !isEnabled() || renderMode != RenderMode.FULL
                || SecondaryView.active()) {
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
        if (((SectionOcclusionGraphAccessor) mc.levelRenderer.sectionOcclusionGraph()).utilitysuite$needsFullUpdate()) {
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
            target = new TextureTarget("utilitysuite pip", tw, th, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (target.width != tw || target.height != th) {
            target.resize(tw, th);
        }
        if (camera == null) {
            camera = new PipCamera();
            projection = new Projection();
            projectionBuffer = new ProjectionMatrixBuffer("utilitysuite pip");
            fog = new FogRenderer();
            pool = new CrossFrameResourcePool(3);
        }
        float pt = delta.getGameTimeDeltaPartialTick(false);
        computePose(p, pt);
        LevelRenderState levelState = gr.gameRenderState().levelRenderState;
        CameraRenderState cs = levelState.cameraRenderState;
        SectionOcclusionGraphAccessor occlusion = (SectionOcclusionGraphAccessor) lr.sectionOcclusionGraph();
        LevelExtractorCullStateAccessor extractorCull = (LevelExtractorCullStateAccessor) mc.levelExtractor;
        GameRendererMainTargetAccessor targets = (GameRendererMainTargetAccessor) gr;
        RenderTarget main = gr.mainRenderTarget();
        double savedX = occlusion.utilitysuite$prevCamX();
        double savedY = occlusion.utilitysuite$prevCamY();
        double savedZ = occlusion.utilitysuite$prevCamZ();
        ProfilerFiller profiler = Profiler.get();
        profiler.push("utilitysuite_pip");
        RenderSystem.backupProjectionMatrix();
        SecondaryView.begin();
        boolean swapped = false;
        try {
            camera.place(mc, level, p, mode == Mode.DRONE, delta, poseX, poseY, poseZ, poseYaw, posePitch, poseFov,
                    tw / (float) th);
            camera.extractRenderState(cs, delta);
            cs.cullFrustum.set(camera.getCullFrustum());
            cs.fogType = camera.getFluidInCamera();
            cs.fogData = fog.setupFog(camera, mc.options.getEffectiveRenderDistance(), delta,
                    gr.bossOverlayWorldDarkening(pt), level);
            projection.setupPerspective(Camera.PROJECTION_Z_NEAR, cs.depthFar, poseFov, tw, th);
            projection.getMatrix(cs.projectionMatrix);
            // NaN rotation: the extractor re-culls the section list for our frustum instead of keeping the main one.
            extractorCull.utilitysuite$setPrevCamRotX(Double.NaN);
            extractorCull.utilitysuite$setPrevCamRotY(Double.NaN);
            mc.levelExtractor.extract(delta, camera, pt);
            levelState.shouldShowEntityOutlines = false; // the outline target is window sized
            // Same 8-block cell as our camera: the occlusion update won't invalidate the main occlusion graph.
            occlusion.utilitysuite$setPrevCamX(Math.floor(poseX / 8.0));
            occlusion.utilitysuite$setPrevCamY(Math.floor(poseY / 8.0));
            occlusion.utilitysuite$setPrevCamZ(Math.floor(poseZ / 8.0));
            targets.utilitysuite$setMainRenderTarget(target);
            swapped = true;
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(cs.projectionMatrix), ProjectionType.PERSPECTIVE);
            fog.updateBuffer(cs.fogData);
            lr.render(pool, false, cs, fog.getBuffer(FogRenderer.FogMode.WORLD), cs.fogData.color, true, false);
            fullValid = true;
        } catch (RuntimeException ex) {
            fullFailed = true;
            fullValid = false;
            UtilitySuiteMod.LOGGER.warn("Picture-in-picture full render failed; using the wireframe from now on", ex);
        } finally {
            if (swapped) {
                targets.utilitysuite$setMainRenderTarget(main);
            }
            SecondaryView.end();
            RenderSystem.restoreProjectionMatrix();
            occlusion.utilitysuite$setPrevCamX(savedX);
            occlusion.utilitysuite$setPrevCamY(savedY);
            occlusion.utilitysuite$setPrevCamZ(savedZ);
            // Force the main pass to re-cull its section list for its own frustum.
            extractorCull.utilitysuite$setPrevCamRotX(Double.NaN);
            extractorCull.utilitysuite$setPrevCamRotY(Double.NaN);
            fog.endFrame();
            pool.endFrame();
            profiler.pop();
        }
    }

    /**
     * A camera pinned to an arbitrary pose (vanilla's only follows an entity). {@code update} still runs (depth far,
     * fov, initialised flag, the entity link), but the position / rotation setters keep the pinned pose, and the cull
     * frustum is our own, built for the panel's fov and aspect. {@code isDetached} decides whether the entity itself is
     * drawn (drone: yes; rear view from the eye: no).
     */
    private static final class PipCamera extends Camera {
        private boolean pinned;
        private boolean showEntity;
        private Vec3 pinPos = Vec3.ZERO;
        private float pinYaw;
        private float pinPitch;
        private Frustum frustum;
        private final Matrix4f viewRot = new Matrix4f();
        private final Matrix4f cullProjection = new Matrix4f();

        void place(Minecraft mc, ClientLevel level, Entity entity, boolean detached, DeltaTracker delta, double x,
                   double y, double z, float yaw, float pitch, float fov, float aspect) {
            showEntity = detached;
            pinPos = new Vec3(x, y, z); // once per pass (every N frames)
            pinYaw = yaw;
            pinPitch = pitch;
            pinned = true;
            setLevel(level);
            setEntity(entity);
            update(delta);
            // the same culling projection vanilla builds, for our fov / aspect
            float depthFar = Math.max(mc.options.getEffectiveRenderDistance() * 16 * 4.0F,
                    mc.options.cloudRange().get() * 16);
            cullProjection.setPerspective(fov * DEG_TO_RAD, aspect, Camera.PROJECTION_Z_NEAR, depthFar,
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            frustum = new Frustum(getViewRotationMatrix(viewRot), cullProjection);
            frustum.prepare(x, y, z);
        }

        private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

        @Override
        protected void setPosition(Vec3 position) {
            super.setPosition(pinned ? pinPos : position);
        }

        @Override
        protected void setRotation(float yRot, float xRot, float roll) {
            if (pinned) {
                super.setRotation(pinYaw, pinPitch, 0f);
            } else {
                super.setRotation(yRot, xRot, roll);
            }
        }

        @Override
        public Frustum getCullFrustum() {
            return frustum != null ? frustum : super.getCullFrustum();
        }

        @Override
        public boolean isDetached() {
            return showEntity;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // HUD layer
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
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

    /**
     * Draws the offscreen texture opaque (the level target's alpha is not meaningful), bottom-left origin like
     * vanilla's own picture-in-picture blit, so v runs 1 to 0 down the panel. One recycled element.
     */
    private void blitTarget(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean flip) {
        if (target.getColorTextureView() == null) {
            return;
        }
        float u0 = flip ? 1f : 0f;
        float u1 = flip ? 0f : 1f;
        blit.begin(g, RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
                TextureSetup.singleTexture(target.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)), true);
        blit.vertex(x, y, u0, 1f, -1);
        blit.vertex(x, y + h, u0, 0f, -1);
        blit.vertex(x + w, y + h, u1, 0f, -1);
        blit.vertex(x + w, y, u1, 1f, -1);
        blit.submit(g);
    }

    private void drawWireframe(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int w, int h, boolean flip, float pt) {
        if (gate.tick()) {
            refresh(mc.player, mc.level, pt, w / (double) h);
        }
        HoloFrames.plate(g, x, y, w, h, HoloToken.VOID, 0.7f);
        lines.begin(g, x, y, w, h);
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
