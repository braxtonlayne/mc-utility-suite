package com.utilitysuite.travel.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.travel.Packed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Movement augmentation for the user's own movement: edge jumps, mid-air trajectory correction, an optional sprint-hop
 * speed mode, and an overlay with the predicted landing and the blocks within sprint-jump reach. Decisions come from
 * the engine-free {@link MovementAssistBrain}; this class only feeds it the player's state and applies its answer to
 * the movement input in {@link MovementInputUpdateEvent} (after the game read the keys, before it moves the player),
 * so it adds to whatever the user presses and never replaces a key the user holds.
 * <p>
 * Off while the user sneaks, swims, climbs, flies, glides or rides, after Stop-All, and (unless "Assist automation" is
 * on) while an automation lease drives MOVEMENT. Pure client-side input, like a player's own reflexes; no packets.
 */
public final class MovementAssist implements IModule, IConfigurable {

    public static final String ID = "movement_assist";
    static final String K_EDGE = "edge_jump";
    static final String K_STEER = "air_steer";
    static final String K_HOP = "sprint_hop";
    static final String K_OVERLAY = "overlay";
    static final String K_REACH = "reach_overlay";
    static final String K_AUTOMATION = "assist_automation";
    static final int REACH_EVERY_TICKS = 5;

    private final MovementAssistBrain brain = new MovementAssistBrain();
    private final JumpReach reach = new JumpReach();
    private final MovementPhysics now = new MovementPhysics();
    private final LevelVoxels voxels = new LevelVoxels();

    private boolean enabled;
    private boolean overlay = true;
    private boolean reachOverlay = true;
    private boolean assistAutomation;
    /** SafeWalk is guarding edges: only jumps that cost no health are valid (the user can still jump themselves). */
    private java.util.function.BooleanSupplier safeWalk = () -> false;
    private boolean jumpingThisTick;

    // overlay state (client thread)
    private boolean showLanding;
    private boolean landingLethal;
    private float landingDamage;
    private double landX, landY, landZ;
    private final long[] reachCells = new long[JumpReach.HEADINGS];
    private final boolean[] reachUp = new boolean[JumpReach.HEADINGS];
    private int reachCount;
    private int reachClock;
    private boolean moving;

    // counters (status, playtest)
    private int edgeJumps;
    private int hops;
    private int corrections;
    private boolean wasCorrecting;
    private String status = "off";

    public void onMovementInput(MovementInputUpdateEvent event) {
        jumpingThisTick = false;
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (event.getEntity() != p || p == null || mc.level == null) {
            status = "no player";
            showLanding = false;
            reachCount = 0;
            return;
        }
        String skipped = skip(p);
        if (skipped != null) {
            status = skipped;
            showLanding = false;
            reachCount = 0;
            return;
        }
        Input in = event.getInput();
        if (in.shiftKeyDown) {
            status = "sneaking";
            showLanding = false;
            return;
        }
        Vec3 v = p.getDeltaMovement();
        boolean sprinting = p.isSprinting();
        double attr = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        now.set(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.onGround());
        now.speed = sprinting ? attr / 1.3 : attr; // the attribute already carries the sprint modifier
        now.jumpBoost = p.getJumpBoostPower();
        now.fallDistance = p.fallDistance;
        BlockPos below = p.getBlockPosBelowThatAffectsMyMovement();
        now.slipperiness = mc.level.getBlockState(below).getBlock().getFriction();
        voxels.begin(mc.level);
        int userF = sign(in.forwardImpulse);
        int userS = sign(in.leftImpulse);
        float health = p.getHealth() + p.getAbsorptionAmount();
        brain.safeOnly = safeWalk.getAsBoolean();
        brain.decide(now, p.getYRot(), sprinting, userF, userS, in.jumping, health, voxels);
        status = userF > 0 ? "assisting" : "no forward key";

        if (brain.jump()) {
            in.jumping = true;
            jumpingThisTick = true;
            if (userF > 0 && now.onGround) {
                if (brain.sprintHop && !brain.search().shouldEdgeJump(now, p.getYRot(), sprinting, health, voxels)) {
                    hops++;
                } else {
                    edgeJumps++;
                }
            }
        }
        if (brain.forward() != MovementAssistBrain.KEEP) {
            in.forwardImpulse = brain.forward();
            in.up = brain.forward() > 0;
            in.down = brain.forward() < 0;
        }
        if (brain.strafe() != MovementAssistBrain.KEEP) {
            in.leftImpulse = brain.strafe();
            in.left = brain.strafe() > 0;
            in.right = brain.strafe() < 0;
        }
        if (brain.corrected() && !wasCorrecting) {
            corrections++;
        }
        wasCorrecting = brain.corrected();

        // overlay state
        moving = !now.onGround || v.x * v.x + v.z * v.z > 0.0025;
        showLanding = overlay && moving && (brain.hasLanding() || !now.onGround);
        landingLethal = brain.lethal();
        landingDamage = brain.landDamage();
        landX = brain.landX();
        landY = brain.landY();
        landZ = brain.landZ();
        if (overlay && reachOverlay && now.onGround && ++reachClock >= REACH_EVERY_TICKS) {
            reachClock = 0;
            reachCount = reach.compute(now, voxels);
            for (int i = 0; i < reachCount; i++) {
                reachCells[i] = reach.cell(i);
                reachUp[i] = reach.isUp(i);
            }
        } else if (!now.onGround) {
            reachCount = 0;
        }
    }

    /** Why the assist is idle this tick, or null when it runs. Constant strings: nothing allocated. */
    private String skip(LocalPlayer p) {
        if (p.isPassenger() || p.getAbilities().flying || p.isFallFlying() || p.isInWater() || p.isInLava()
                || p.onClimbable() || p.isSpectator()) {
            return "not on foot";
        }
        var arbiter = Services.arbiter();
        if (arbiter.isAutomationStopped()) {
            return "automation stopped";
        }
        if (!assistAutomation && arbiter.holder(ControlChannel.MOVEMENT).isPresent()) {
            return "automation drives movement";
        }
        return null;
    }

    private static int sign(float f) {
        return f > 1.0e-3f ? 1 : (f < -1.0e-3f ? -1 : 0);
    }

    // ---- overlay ----------------------------------------------------------------------------------------------------

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !overlay || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || (!showLanding && reachCount == 0) || Services.modState().isCleanScreen()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        if (pose == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (int i = 0; i < reachCount; i++) {
            long c = reachCells[i];
            double x = Packed.x(c) - cam.x;
            double y = Packed.y(c) + 1 - cam.y;
            double z = Packed.z(c) - cam.z;
            float g = reachUp[i] ? 0.95f : 0.75f;
            LevelRenderer.renderLineBox(pose, lines, x + 0.1, y + 0.01, z + 0.1, x + 0.9, y + 0.03, z + 0.9,
                    0.25f, g, 1.0f, reachUp[i] ? 0.85f : 0.5f);
        }
        if (showLanding) {
            float r = landingLethal ? 1.0f : (landingDamage > 0 ? 1.0f : 0.2f);
            float g = landingLethal ? 0.15f : (landingDamage > 0 ? 0.8f : 1.0f);
            double x = Math.floor(landX) - cam.x;
            double y = landY - cam.y;
            double z = Math.floor(landZ) - cam.z;
            LevelRenderer.renderLineBox(pose, lines, x, y + 0.01, z, x + 1, y + 0.06, z + 1, r, g, 0.15f, 1.0f);
            double px = landX - cam.x;
            double pz = landZ - cam.z;
            LevelRenderer.renderLineBox(pose, lines, px - 0.3, y + 0.01, pz - 0.3, px + 0.3, y + 1.8, pz + 0.3,
                    r, g, 0.15f, 0.6f);
        }
        buffers.endBatch(RenderType.lines());
    }

    // ---- IModule / IConfigurable -------------------------------------------------------------------------------------

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
            showLanding = false;
            reachCount = 0;
        }
    }

    @Override
    public void onTick() {
        // all work happens in the movement-input event (once per client tick, right where it applies)
    }

    @Override
    public void onWorldUnload() {
        showLanding = false;
        reachCount = 0;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read(profile);
    }

    private void read(ProfileConfig p) {
        brain.edgeJump = p.getBool(ID, K_EDGE, true);
        brain.airSteer = p.getBool(ID, K_STEER, true);
        brain.sprintHop = p.getBool(ID, K_HOP, false);
        overlay = p.getBool(ID, K_OVERLAY, true);
        reachOverlay = p.getBool(ID, K_REACH, true);
        assistAutomation = p.getBool(ID, K_AUTOMATION, false);
    }

    @Override
    public String displayName() {
        return "Movement Assist";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_EDGE, "Edge jump", true, "",
                        "While you run forward, jump on the last tick before an edge when the jump lands better than"
                                + " dropping (parkour timing)."),
                SettingSpec.bool(K_STEER, "Air steering", true, "",
                        "In the air, if your keys would miss the landing, take fall damage or land much lower, press"
                                + " the strafe or forward key that makes it. Never overrides a key you hold."),
                SettingSpec.bool(K_HOP, "Sprint hop", false, "",
                        "On open flat ground, jump whenever a sprint jump outruns sprinting (faster travel)."),
                SettingSpec.bool(K_OVERLAY, "Landing overlay", true, "Overlay",
                        "Show where you will land: green safe, yellow fall damage, red nothing survivable."),
                SettingSpec.bool(K_REACH, "Jump reach", true, "Overlay",
                        "Outline the blocks a sprint jump from here can land on (brighter: higher than you)."),
                SettingSpec.bool(K_AUTOMATION, "Assist automation too", false, "",
                        "Also assist while an automated job (goal, travel, combat) drives your movement.")
                        .asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        read(Services.modState().profile());
    }

    // ---- accessors (status, playtest) ----------------------------------------------------------------------------

    /** Why the assist did or did not act on the last movement tick (status, playtest). */
    public String status() {
        return status;
    }

    public int edgeJumps() {
        return edgeJumps;
    }

    public int hops() {
        return hops;
    }

    public int corrections() {
        return corrections;
    }

    public boolean showingLanding() {
        return showLanding;
    }

    public int reachCount() {
        return reachCount;
    }

    /** True when this tick's input carries an edge jump or hop from the assist (SafeWalk yields to it). */
    public boolean jumpingThisTick() {
        return enabled && jumpingThisTick;
    }

    public void setSafeWalk(java.util.function.BooleanSupplier safeWalk) {
        this.safeWalk = safeWalk;
    }

    public MovementAssistBrain brain() {
        return brain;
    }
}
