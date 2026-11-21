package com.utilitysuite.travel.terrain;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.travel.LeaseSlot;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.TravelMessages;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForgeMod;

import java.util.List;

/**
 * Terrain Assist: SafeWalk, Edge Jump, Step &amp; NoSlowdown, hazard overlay and surface traversal ("Water Walk").
 * <ul>
 *   <li><b>SafeWalk</b> clamps horizontal movement at edges like vanilla sneaking (no sneak slowdown), through
 *       {@code PlayerSafeWalkMixin}. Drops shallower than the configured minimum are not edges.</li>
 *   <li><b>Edge Jump</b> taps JUMP on the last tick before a sprint leaves the edge (MOVEMENT lease, IDLE priority, so
 *       any other automation and the user's own keys win).</li>
 *   <li><b>Step</b> raises the local player's step height with a transient attribute modifier; <b>NoSlowdown</b>
 *       pre-scales the movement input in {@link MovementInputUpdateEvent} so vanilla's item-use factor (0.2) cancels
 *       out. Both default off; single-player / own server only.</li>
 *   <li><b>Hazards</b>: budgeted {@link HazardScanner} sweep, drawn as line boxes after particles; nothing while
 *       disabled, empty or in Clean-Screen.</li>
 *   <li><b>Surface traversal</b>, toggled by the "surface mode" key: SOLID_COLLISION through
 *       {@code LivingEntityFluidSurfaceMixin}, or BOBBING by holding JUMP when sunk below the surface. Sneak sinks.
 *       Server position corrections are counted while active; too many and the mode switches off with a warning.</li>
 * </ul>
 */
public final class TerrainAssist implements IModule, IConfigurable {

    public static final String ID = "terrain_assist";

    static final ResourceLocation STEP_MODIFIER_ID = ResourceLocation.fromNamespaceAndPath("utilitysuite", "terrain_step");
    private static final double VANILLA_STEP = 0.6;
    private static final int EDGE_JUMP_COOLDOWN = 8;
    private static final int TAP_HOLD_TICKS = 3;

    // settings keys
    static final String K_SAFEWALK = "safewalk";
    static final String K_MIN_DROP = "safewalk_min_drop";
    static final String K_EDGE_JUMP = "edge_jump";
    static final String K_STEP = "step";
    static final String K_STEP_HEIGHT = "step_height";
    static final String K_NO_SLOWDOWN = "no_slowdown";
    static final String K_HAZARDS = "hazards";
    static final String K_HAZARD_RADIUS = "hazard_radius";
    static final String K_HAZARD_BUDGET = "hazard_budget";
    static final String K_HAZARD_DROP = "hazard_drop";
    static final String K_SURFACE_MODE = "surface_mode";
    static final String K_SURFACE_LAVA = "surface_lava";
    static final String K_CORR_THRESHOLD = "correction_threshold";
    static final String K_CORR_WINDOW = "correction_window";

    private boolean enabled;

    // settings (cached)
    private boolean safeWalk = true;
    private double minDrop = 1.5;
    private boolean edgeJump;
    /** Movement Assist is jumping this tick (parkour takes priority over SafeWalk). */
    private java.util.function.BooleanSupplier parkourJumping = () -> false;
    private boolean step;
    private double stepHeight = 1.0;
    private boolean noSlowdown;
    private boolean hazards = true;
    private SurfaceMode surfaceMode = SurfaceMode.SOLID_COLLISION;
    private boolean surfaceLava;

    // runtime
    private final SurfaceGuard surface = new SurfaceGuard();
    private final LeaseSlot slot = new LeaseSlot(ID, Services::arbiter);
    private final LeaseRequest movementRequest = slot.request(ControlPriority.IDLE, 40, ControlChannel.MOVEMENT);
    private final LevelTerrainView view = new LevelTerrainView();
    private final FootprintSupportProbe probe = new FootprintSupportProbe();
    private final EdgeClamp clamp = new EdgeClamp();
    private final HazardScanner scanner = new HazardScanner();
    private long ticks;
    private int edgeJumpCooldown;
    private int releaseLeaseIn;
    private boolean bobbingUp;
    private LocalPlayer stepAppliedTo;

    public TerrainAssist() {
        surface.configure(3, 100, 8.0);
    }

    // ---- IModule ------------------------------------------------------------------------------------------------------

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
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            shutdown();
        }
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        ticks++;
        if (slot.terminated() && !Services.arbiter().isAutomationStopped()) {
            slot.reset();
        }
        if (edgeJumpCooldown > 0) {
            edgeJumpCooldown--;
        }
        if (releaseLeaseIn > 0 && --releaseLeaseIn == 0 && !bobbingUp) {
            slot.reset();
        }
        applyStep(player);
        if (edgeJump) {
            tickEdgeJump(player);
        }
        if (surface.isActive() && surfaceMode == SurfaceMode.BOBBING) {
            tickBobbing(player);
        } else if (bobbingUp) {
            stopBobbing();
        }
        if (hazards) {
            scanner.tick(view.bind(mc.level), player.getBlockX(), player.getBlockY(), player.getBlockZ());
            feedLava(mc, player);
        }
    }

    private boolean lavaReported;

    /** Danger model: lava at the feet or within a few blocks along the way the player moves. Allocation free. */
    private void feedLava(Minecraft mc, LocalPlayer player) {
        com.utilitysuite.core.api.IDangerModel d = Services.danger();
        if (d == com.utilitysuite.core.api.IDangerModel.NONE || player.fireImmune()) {
            return;
        }
        double vx = player.getDeltaMovement().x;
        double vz = player.getDeltaMovement().z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        int dx = speed < 0.03 ? 0 : (Math.abs(vx) * 2.4 > speed ? (vx > 0 ? 1 : -1) : 0);
        int dz = speed < 0.03 ? 0 : (Math.abs(vz) * 2.4 > speed ? (vz > 0 ? 1 : -1) : 0);
        int k = HazardScanner.lavaAhead(view.bind(mc.level), player.getBlockX(), player.getBlockY(),
                player.getBlockZ(), dx, dz, 3);
        if (k >= 0) {
            int ticks = k == 0 ? 0 : (int) Math.ceil(k / Math.max(0.05, speed));
            float bearing = k == 0 ? com.utilitysuite.core.api.IDangerModel.NO_BEARING
                    : (float) Math.toDegrees(Math.atan2(-dx, dz));
            d.report(com.utilitysuite.core.api.HazardKind.LAVA, ticks, 4f, bearing);
            lavaReported = true;
        } else if (lavaReported) {
            d.clear(com.utilitysuite.core.api.HazardKind.LAVA);
            lavaReported = false;
        }
    }

    @Override
    public void onStopAll() {
        bobbingUp = false;
        releaseLeaseIn = 0;
        slot.reset();
        if (surface.isActive()) {
            surface.deactivate();
        }
    }

    @Override
    public void onWorldUnload() {
        surface.deactivate();
        bobbingUp = false;
        releaseLeaseIn = 0;
        slot.reset();
        scanner.clear();
        stepAppliedTo = null;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
    }

    private void shutdown() {
        surface.deactivate();
        bobbingUp = false;
        releaseLeaseIn = 0;
        slot.reset();
        scanner.clear();
        removeStep();
    }

    // ---- settings -----------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Terrain Assist";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_SAFEWALK, "SafeWalk", true, "SafeWalk",
                        "Stops you at block edges while walking, like sneaking but at full speed."),
                SettingSpec.decimal(K_MIN_DROP, "Minimum drop", 1.5, 0.6, 8.0, "SafeWalk",
                        "Only drops deeper than this many blocks count as edges (1.5 lets you step down one block)."),
                SettingSpec.bool(K_EDGE_JUMP, "Edge Jump", false, "Edge Jump",
                        "While sprinting, jumps on the last tick before you run off an edge."),
                SettingSpec.bool(K_STEP, "Step", false, "Step & NoSlowdown",
                        "Walk up full blocks without jumping. Single-player or your own server only."),
                SettingSpec.decimal(K_STEP_HEIGHT, "Step height", 1.0, 0.6, 2.5, "Step & NoSlowdown",
                        "Blocks you can step up. Single-player or your own server only."),
                SettingSpec.bool(K_NO_SLOWDOWN, "NoSlowdown", false, "Step & NoSlowdown",
                        "No movement slowdown while eating, drawing a bow or blocking. Single-player or your own "
                                + "server only; other servers will correct your position."),
                SettingSpec.bool(K_HAZARDS, "Hazard overlay", true, "Hazards",
                        "Marks steep drops, lava and low ceilings near you."),
                SettingSpec.integer(K_HAZARD_RADIUS, "Scan radius", 12, 4, 32, "Hazards",
                        "Blocks around you that are scanned."),
                SettingSpec.integer(K_HAZARD_DROP, "Steep drop depth", 4, 2, 16, "Hazards",
                        "Drops at least this deep are marked."),
                SettingSpec.integer(K_HAZARD_BUDGET, "Columns per tick", 96, 16, 512, "Hazards",
                        "Scan budget per tick. Lower is lighter, higher refreshes faster.").asAdvanced(),
                SettingSpec.choice(K_SURFACE_MODE, "Surface mode", SurfaceMode.SOLID_COLLISION, "Surface",
                        "SOLID_COLLISION: fluids are solid underfoot (single-player / companion servers). "
                                + "BOBBING: jump timing keeps you at the surface. Toggle with the surface mode key; "
                                + "sneak to sink."),
                SettingSpec.bool(K_SURFACE_LAVA, "Allow lava", false, "Surface",
                        "Also walk on lava. Off by default."),
                SettingSpec.integer(K_CORR_THRESHOLD, "Correction limit", 3, 1, 20, "Surface",
                        "Server position corrections tolerated within the window before the mode switches off.")
                        .asAdvanced(),
                SettingSpec.integer(K_CORR_WINDOW, "Correction window (ticks)", 100, 20, 1200, "Surface",
                        "Window for the correction limit.").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
    }

    private void readSettings(ProfileConfig p) {
        safeWalk = p.getBool(ID, K_SAFEWALK, true);
        minDrop = Math.max(VANILLA_STEP, p.getDouble(ID, K_MIN_DROP, 1.5));
        edgeJump = p.getBool(ID, K_EDGE_JUMP, false);
        boolean newStep = p.getBool(ID, K_STEP, false);
        double newHeight = p.getDouble(ID, K_STEP_HEIGHT, 1.0);
        if (newStep != step || newHeight != stepHeight) {
            removeStep();
        }
        step = newStep;
        stepHeight = newHeight;
        noSlowdown = p.getBool(ID, K_NO_SLOWDOWN, false);
        hazards = p.getBool(ID, K_HAZARDS, true);
        scanner.configure(p.getInt(ID, K_HAZARD_RADIUS, 12), p.getInt(ID, K_HAZARD_BUDGET, 96),
                p.getInt(ID, K_HAZARD_DROP, 4));
        if (!hazards) {
            scanner.clear();
        }
        surfaceMode = parseMode(p.getString(ID, K_SURFACE_MODE, SurfaceMode.SOLID_COLLISION.name()));
        surfaceLava = p.getBool(ID, K_SURFACE_LAVA, false);
        surface.configure(p.getInt(ID, K_CORR_THRESHOLD, 3), p.getInt(ID, K_CORR_WINDOW, 100), 8.0);
    }

    private static SurfaceMode parseMode(String s) {
        try {
            return SurfaceMode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return SurfaceMode.SOLID_COLLISION;
        }
    }

    // ---- SafeWalk (called from PlayerSafeWalkMixin) -------------------------------------------------------------------

    /** True while SafeWalk guards edges (Movement Assist then rejects jumps that would cost health). */
    public boolean isSafeWalkActive() {
        return enabled && safeWalk;
    }

    public void setParkourJumping(java.util.function.BooleanSupplier jumping) {
        this.parkourJumping = jumping;
    }


    /** Returns the clamped movement, or null to keep the game's own result. Client thread, once per player move. */
    public Vec3 backOffFromEdge(Player self, Vec3 movement, MoverType mover) {
        if (!enabled || !safeWalk) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (self != mc.player || (mover != MoverType.SELF && mover != MoverType.PLAYER) || movement.y > 0.0
                || !self.onGround() || self.isShiftKeyDown() || self.getAbilities().flying || self.isPassenger()
                || self.isFallFlying() || self.isSpectator()) {
            return null;
        }
        if (edgeJump && self.isSprinting()) {
            return null; // Edge Jump owns sprinting edges
        }
        if (parkourJumping.getAsBoolean()) {
            return null; // a valid parkour jump goes first; SafeWalk only guards edges with no jump
        }
        AABB bb = self.getBoundingBox();
        probe.configure(view.bind(self.level()), bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxZ, minDrop);
        if (!probe.hasSupport(0.0, 0.0)) {
            return null; // already past an edge (e.g. knocked back); never pull the player back over air
        }
        if (!clamp.clamp(movement.x, movement.z, probe)) {
            return null;
        }
        return new Vec3(clamp.x(), movement.y, clamp.z());
    }

    // ---- surface traversal (called from LivingEntityFluidSurfaceMixin) ------------------------------------------------

    /** True when the local player should stand on this fluid (SOLID_COLLISION mode). */
    public boolean standsOnFluid(LivingEntity self, FluidState fluid) {
        if (!enabled || !surface.isActive() || surfaceMode != SurfaceMode.SOLID_COLLISION || fluid.isEmpty()) {
            return false;
        }
        if (self != Minecraft.getInstance().player || self.isShiftKeyDown() || self.isFallFlying()) {
            return false;
        }
        return fluid.is(FluidTags.WATER) || (surfaceLava && fluid.is(FluidTags.LAVA));
    }

    /** Surface mode key. Enables the module if needed. */
    public void toggleSurfaceMode() {
        if (surface.isActive()) {
            surface.deactivate();
            stopBobbing();
            TravelMessages.status("message.utilitysuite.terrain.surface_off");
            return;
        }
        if (Services.arbiter().isAutomationStopped()) {
            TravelMessages.status("message.utilitysuite.travel.automation_stopped");
            return;
        }
        if (!enabled) {
            setEnabled(true);
            Services.modState().profile().set(ID, "enabled", true);
            Services.modState().markDirty();
        }
        surface.activate();
        TravelMessages.status(surfaceMode == SurfaceMode.SOLID_COLLISION
                ? "message.utilitysuite.terrain.surface_on_solid" : "message.utilitysuite.terrain.surface_on_bobbing");
    }

    public boolean isSurfaceActive() {
        return surface.isActive();
    }

    /** SuiteEvent listener: counts server corrections while surface traversal is active. */
    public void onPositionCorrection(SuiteEvent.PositionCorrection event) {
        if (!enabled) {
            return;
        }
        double d = Math.sqrt(event.dx() * event.dx() + event.dy() * event.dy() + event.dz() * event.dz());
        if (surface.onCorrection(ticks, d)) {
            stopBobbing();
            TravelMessages.warn("message.utilitysuite.terrain.surface_rejected", surface.threshold());
        }
    }

    private void tickBobbing(LocalPlayer player) {
        boolean water = player.isInWater();
        boolean lava = surfaceLava && player.isInLava();
        if (!(water || lava) || player.isShiftKeyDown() || player.isPassenger() || player.isFallFlying()) {
            if (bobbingUp) {
                stopBobbing();
            }
            return;
        }
        double h = Math.max(water ? player.getFluidTypeHeight(NeoForgeMod.WATER_TYPE.value()) : 0.0,
                lava ? player.getFluidTypeHeight(NeoForgeMod.LAVA_TYPE.value()) : 0.0);
        boolean wantUp = bobbingUp ? h > 0.3 : h > 0.55; // hysteresis: rise when sunk, coast near the surface
        if (wantUp == bobbingUp) {
            return;
        }
        ControlLease lease = slot.ensure(movementRequest);
        if (lease == null) {
            return;
        }
        Services.input().setKeyHeld(lease, SimKey.JUMP, wantUp);
        bobbingUp = wantUp;
        if (!wantUp) {
            releaseLeaseIn = TAP_HOLD_TICKS;
        }
    }

    private void stopBobbing() {
        if (bobbingUp) {
            ControlLease lease = slot.active();
            if (lease != null) {
                Services.input().setKeyHeld(lease, SimKey.JUMP, false);
            }
            bobbingUp = false;
        }
        slot.reset();
    }

    // ---- Edge Jump ----------------------------------------------------------------------------------------------------

    private void tickEdgeJump(LocalPlayer player) {
        if (edgeJumpCooldown > 0 || bobbingUp || !player.onGround() || !player.isSprinting() || player.isShiftKeyDown()
                || player.isPassenger() || player.getAbilities().flying) {
            return;
        }
        Vec3 v = player.getDeltaMovement();
        AABB bb = player.getBoundingBox();
        probe.configure(view.bind(player.level()), bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxZ, minDrop);
        if (!EdgeClamp.atEdge(probe, v.x, v.z, 1.5)) {
            return;
        }
        ControlLease lease = slot.ensure(movementRequest);
        if (lease == null) {
            return;
        }
        IInputSmoother input = Services.input();
        if (input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2)) {
            edgeJumpCooldown = EDGE_JUMP_COOLDOWN;
            releaseLeaseIn = TAP_HOLD_TICKS;
        }
    }

    // ---- Step & NoSlowdown --------------------------------------------------------------------------------------------

    private void applyStep(LocalPlayer player) {
        if (!step) {
            if (stepAppliedTo != null) {
                removeStep();
            }
            return;
        }
        if (stepAppliedTo == player) {
            return;
        }
        AttributeInstance attr = player.getAttribute(Attributes.STEP_HEIGHT);
        if (attr == null) {
            return;
        }
        attr.addOrUpdateTransientModifier(new AttributeModifier(STEP_MODIFIER_ID,
                Math.max(0.0, stepHeight - VANILLA_STEP), AttributeModifier.Operation.ADD_VALUE));
        stepAppliedTo = player;
    }

    private void removeStep() {
        LocalPlayer p = stepAppliedTo;
        stepAppliedTo = null;
        if (p == null) {
            return;
        }
        AttributeInstance attr = p.getAttribute(Attributes.STEP_HEIGHT);
        if (attr != null) {
            attr.removeModifier(STEP_MODIFIER_ID);
        }
    }

    /**
     * NeoForge fires this after the movement input was computed and before vanilla multiplies it by 0.2 while an item
     * is in use; pre-scaling by 5 makes that factor a no-op. No mixin needed.
     */
    public void onMovementInput(MovementInputUpdateEvent event) {
        if (!enabled || !noSlowdown) {
            return;
        }
        Player p = event.getEntity();
        if (p != Minecraft.getInstance().player || !p.isUsingItem() || p.isPassenger()) {
            return;
        }
        event.getInput().leftImpulse *= 5.0f;
        event.getInput().forwardImpulse *= 5.0f;
    }

    // ---- hazard overlay -----------------------------------------------------------------------------------------------

    /** Draws the last completed hazard sweep. Nothing when disabled, empty or in Clean-Screen. */
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !hazards || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !scanner.hasMarkers() || Services.modState().isCleanScreen()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        if (pose == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        drawBoxes(pose, lines, scanner.markers(HazardScanner.DROP), cam, 1.0f, 0.55f, 0.1f, 0.0, 0.9);
        drawBoxes(pose, lines, scanner.markers(HazardScanner.LAVA), cam, 1.0f, 0.15f, 0.1f, 0.0, 1.0);
        drawBoxes(pose, lines, scanner.markers(HazardScanner.LOW_CEILING), cam, 1.0f, 0.9f, 0.2f, 1.0, 1.95);
        buffers.endBatch(RenderType.lines());
    }

    private static void drawBoxes(PoseStack pose, VertexConsumer lines, LongArrayList markers, Vec3 cam, float r,
                                  float g, float b, double yLo, double yHi) {
        double cx = cam.x;
        double cy = cam.y;
        double cz = cam.z;
        for (int i = 0, n = markers.size(); i < n; i++) {
            long p = markers.getLong(i);
            double x = Packed.x(p) - cx;
            double y = Packed.y(p) - cy;
            double z = Packed.z(p) - cz;
            LevelRenderer.renderLineBox(pose, lines, x + 0.05, y + yLo + 0.02, z + 0.05, x + 0.95, y + yHi, z + 0.95,
                    r, g, b, 0.8f);
        }
    }

    HazardScanner scanner() {
        return scanner;
    }
}
