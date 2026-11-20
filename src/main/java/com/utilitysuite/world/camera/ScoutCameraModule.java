package com.utilitysuite.world.camera;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldHooks;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Scout Camera: a detached free camera, smooth zoom, fullbright and declutter.
 * <ul>
 *   <li><b>Free camera</b>: a client-only marker entity (never added to the level, so it never sends packets) becomes
 *       the camera entity. The user's movement input is read from {@code MovementInputUpdateEvent} and applied to the
 *       camera; the player's input is zeroed for that tick, and since the player is no longer the camera entity,
 *       vanilla stops driving it ({@code LocalPlayer.isControlledCamera}). Mouse look turns the camera (entity turn
 *       mixin). No key mapping is ever pressed or released by this module, so nothing can stay held when it ends.
 *       Attack/use/pick are cancelled while detached so nothing acts from the player's position unseen.</li>
 *   <li><b>Zoom</b>: hold the zoom key; scroll changes the factor; eased in {@code ViewportEvent.ComputeFov}.</li>
 *   <li><b>Fullbright</b>: the lightmap is computed as with full night vision (mixin); the gamma option is untouched.</li>
 *   <li><b>Declutter</b>: fire overlay, pumpkin blur, portal overlay, blindness/darkness fog.</li>
 * </ul>
 */
public final class ScoutCameraModule extends WorldModule {

    public static final String ID = "scout_camera";

    private final FreecamMotion motion = new FreecamMotion();
    private final ZoomState zoom = new ZoomState();
    private KeyMapping zoomKey;

    private Marker camera;
    private boolean freecam;
    private float inForward;
    private float inLeft;
    private boolean inUp;
    private boolean inDown;
    private long lastFovNanos;

    private double freecamSpeed;
    private boolean zoomEnabled;
    private boolean fullbright;
    private boolean noFire;
    private boolean noPumpkin;
    private boolean noPortal;
    private boolean noEffectFog;

    public ScoutCameraModule() {
        super(ID, "Scout Camera", false);
    }

    public void setZoomKey(KeyMapping key) {
        this.zoomKey = key;
    }

    public boolean isFreecamActive() {
        return freecam;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.WORLD;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal("freecam_speed", "Camera speed", 0.8, 0.1, 5.0, "Free camera",
                        "Blocks per tick; hold Left Ctrl for 2.5x."),
                SettingSpec.bool("zoom", "Zoom", true, "Zoom", "Hold the zoom key (Controls > Utility Suite); scroll "
                        + "while holding changes the factor."),
                SettingSpec.decimal("zoom_factor", "Zoom factor", 4.0, 1.5, 20.0, "Zoom", "Starting magnification."),
                SettingSpec.bool("fullbright", "Fullbright", false, "View",
                        "Full brightness everywhere. Your gamma setting is not changed."),
                SettingSpec.bool("no_fire", "Hide fire overlay", true, "Declutter", "While burning."),
                SettingSpec.bool("no_pumpkin", "Hide pumpkin blur", true, "Declutter", "While wearing a carved pumpkin."),
                SettingSpec.bool("no_portal", "Hide portal overlay", true, "Declutter",
                        "The purple overlay while standing in a nether portal."),
                SettingSpec.bool("no_effect_fog", "Clear blindness/darkness fog", true, "Declutter",
                        "Keeps the normal view distance under blindness or darkness."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        freecamSpeed = decimal(p, "freecam_speed");
        zoomEnabled = bool(p, "zoom");
        zoom.configure(decimal(p, "zoom_factor"), 50.0, 1.25, 0.08);
        fullbright = bool(p, "fullbright");
        noFire = bool(p, "no_fire");
        noPumpkin = bool(p, "no_pumpkin");
        noPortal = bool(p, "no_portal");
        noEffectFog = bool(p, "no_effect_fog");
        motion.configure(freecamSpeed, 2.5, 0.55);
        WorldHooks.setFullbright(isEnabled() && fullbright);
    }

    @Override
    protected void onEnable() {
        WorldHooks.setFullbright(fullbright);
    }

    @Override
    protected void onDisable() {
        stopFreecam();
        zoom.reset();
        WorldHooks.setFullbright(false);
    }

    @Override
    public void onStopAll() {
        stopFreecam();
    }

    @Override
    public void onWorldUnload() {
        stopFreecam();
        camera = null;
        zoom.reset();
    }

    // ---- free camera --------------------------------------------------------------------------------------------

    /** TOGGLE_SCOUT_CAMERA key: toggles the free camera (enabling the module if needed). */
    public void toggleFreecam() {
        if (freecam) {
            stopFreecam();
            return;
        }
        if (!isEnabled()) {
            toggleFromKey();
        }
        startFreecam();
    }

    private void startFreecam() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || player.isDeadOrDying()) {
            return;
        }
        if (camera == null || camera.level() != level) {
            camera = new Marker(EntityType.MARKER, level);
        }
        double x = player.getX();
        double y = player.getEyeY();
        double z = player.getZ();
        camera.moveTo(x, y, z, player.getYRot(), player.getXRot());
        camera.yRotO = player.getYRot();
        camera.xRotO = player.getXRot();
        motion.reset(x, y, z);
        inForward = inLeft = 0f;
        inUp = inDown = false;
        freecam = true;
        mc.setCameraEntity(camera);
    }

    private void stopFreecam() {
        if (!freecam) {
            return;
        }
        freecam = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.getCameraEntity() == camera) {
            mc.setCameraEntity(mc.player);
        }
    }

    /** Mixin redirect: mouse look turns the camera while detached. */
    public boolean turn(double yRot, double xRot) {
        if (!freecam || camera == null) {
            return false;
        }
        camera.turn(yRot, xRot);
        return true;
    }

    /** Game bus: capture the user's movement for the camera and keep the player still. */
    public void onMovementInput(MovementInputUpdateEvent event) {
        if (!freecam) {
            return;
        }
        Input in = event.getInput();
        inForward = in.forwardImpulse;
        inLeft = in.leftImpulse;
        inUp = in.jumping;
        inDown = in.shiftKeyDown;
        in.forwardImpulse = 0f;
        in.leftImpulse = 0f;
        in.jumping = false;
        in.shiftKeyDown = false;
        in.up = false;
        in.down = false;
        in.left = false;
        in.right = false;
    }

    @Override
    public void onTick() {
        if (!freecam) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isDeadOrDying() || camera == null || camera.level() != mc.level
                || mc.getCameraEntity() != camera) {
            stopFreecam();
            return;
        }
        boolean boost = InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL);
        motion.tick(inForward, inLeft, inUp, inDown, boost, camera.getYRot());
        camera.xo = camera.xOld = motion.prevX();
        camera.yo = camera.yOld = motion.prevY();
        camera.zo = camera.zOld = motion.prevZ();
        camera.setPos(motion.x(), motion.y(), motion.z());
        camera.yRotO = camera.getYRot();
        camera.xRotO = camera.getXRot();
    }

    /** Game bus: attack / use / pick do nothing while detached. */
    public void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (freecam) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    public void onRenderHand(RenderHandEvent event) {
        if (freecam) {
            event.setCanceled(true);
        }
    }

    // ---- zoom ---------------------------------------------------------------------------------------------------

    public void onComputeFov(ViewportEvent.ComputeFov event) {
        long now = System.nanoTime();
        double dt = lastFovNanos == 0L ? 0.0 : (now - lastFovNanos) * 1.0e-9;
        lastFovNanos = now;
        if (!isEnabled() || !zoomEnabled) {
            if (zoom.affectsView()) {
                zoom.reset();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        zoom.setActive(zoomKey != null && zoomKey.isDown() && mc.screen == null);
        zoom.update(Math.min(dt, 0.25));
        if (zoom.affectsView() && event.usedConfiguredFov()) {
            event.setFOV(zoom.apply(event.getFOV()));
        }
    }

    public void onScroll(InputEvent.MouseScrollingEvent event) {
        if (isEnabled() && zoomEnabled && zoom.active()) {
            zoom.scroll(event.getScrollDeltaY());
            event.setCanceled(true);
        }
    }

    public void onTurnSensitivity(CalculatePlayerTurnEvent event) {
        if (isEnabled() && zoom.affectsView()) {
            event.setMouseSensitivity(event.getMouseSensitivity() / Math.sqrt(zoom.factor()));
        }
    }

    // ---- declutter ----------------------------------------------------------------------------------------------

    public void onScreenEffect(RenderBlockScreenEffectEvent event) {
        if (isEnabled() && noFire && event.getOverlayType() == RenderBlockScreenEffectEvent.OverlayType.FIRE) {
            event.setCanceled(true);
        }
    }

    /**
     * The camera-overlay layer draws vignette, spyglass, pumpkin blur, powder snow and the portal overlay together; it
     * is skipped only while the pumpkin blur or the portal overlay would actually be on screen (and never while
     * looking through a spyglass).
     */
    public void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!isEnabled() || !VanillaGuiLayers.CAMERA_OVERLAYS.equals(event.getName())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.isScoping()) {
            return;
        }
        boolean pumpkin = noPumpkin && p.getItemBySlot(EquipmentSlot.HEAD).is(Blocks.CARVED_PUMPKIN.asItem());
        float spin = Mth.lerp(event.getPartialTick().getGameTimeDeltaPartialTick(false), p.oSpinningEffectIntensity,
                p.spinningEffectIntensity);
        boolean portal = noPortal && spin > 0f && !p.hasEffect(MobEffects.CONFUSION);
        if (pumpkin || portal) {
            event.setCanceled(true);
        }
    }

    public void onFog(ViewportEvent.RenderFog event) {
        if (!isEnabled() || !noEffectFog || event.getType() != FogType.NONE) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !(p.hasEffect(MobEffects.BLINDNESS) || p.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        float far = Minecraft.getInstance().gameRenderer.getRenderDistance();
        if (event.getMode() == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_SKY) {
            event.setNearPlaneDistance(0f);
        } else {
            event.setNearPlaneDistance(far - Mth.clamp(far / 10f, 4f, 64f));
        }
        event.setFarPlaneDistance(far);
        event.setCanceled(true);
    }
}
