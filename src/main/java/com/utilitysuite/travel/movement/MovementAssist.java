package com.utilitysuite.travel.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.LegitPresets;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.visual.AuthorityReason;
import com.utilitysuite.core.api.visual.AuthorityState;
import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.world.render.WorldRenderKit;
import com.utilitysuite.core.api.IManualLookAssist;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.travel.Packed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
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

    /** NATURAL: automated looks stay along the way you move. FREE: look wherever the job needs (less legit). */
    public enum LookStyle { NATURAL, FREE }

    /** Control mode (design 2026-11-22, section 3): Corrections = NUDGE, Co-pilot = GUIDE, Takeover = CARRY. */
    public enum ControlMode {
        CORRECTIONS(JumpCommit.Mode.NUDGE), CO_PILOT(JumpCommit.Mode.GUIDE), TAKEOVER(JumpCommit.Mode.CARRY);

        public final JumpCommit.Mode mode;

        ControlMode(JumpCommit.Mode mode) {
            this.mode = mode;
        }

        /** Profile value to mode; an absent or unknown value reads CO_PILOT. */
        public static ControlMode parse(String s) {
            for (ControlMode m : values()) {
                if (m.name().equals(s)) {
                    return m;
                }
            }
            return CO_PILOT;
        }
    }

    public static final String ID = "movement_assist";
    static final String K_CONTROL_MODE = "control_mode";
    static final String K_SENSITIVITY = "override_sensitivity";
    static final String K_LANDING_HOLD = "landing_hold";
    static final String K_HOLD_RELEASE = "hold_release";
    static final String K_HOLD_LIMIT = "hold_limit_ticks";
    /** Per-level key-change limits (LegitPresets knobs). */
    public static final String K_KEY_CHANGES = "assist_key_changes";
    public static final String K_KEY_HOLD = "assist_key_hold_ticks";
    public static final String K_REACTION = "assist_reaction_ticks";
    /** Status line strings (constant: nothing built per tick). */
    public static final String STATUS_LOCKED = "Parkour: locked";
    public static final String STATUS_HOLD = "Parkour: hold (release W to move)";
    public static final String STATUS_YOURS = "Parkour: yours";
    static final String REASON_PARKOUR = "parkour";
    static final String MARK_LABEL = "LANDING";
    static final int OVERRIDE_FLASH_TICKS = 6;
    static final String K_EDGE = "edge_jump";
    static final String K_STEER = "air_steer";
    static final String K_HOP = "sprint_hop";
    static final String K_OVERLAY = "overlay";
    static final String K_REACH = "reach_overlay";
    static final String K_AUTOMATION = "assist_automation";
    static final String K_BLOCKS = "parkour_blocks";
    static final String K_LOOK = "look_style";
    static final String K_ESCAPE = "escape_path";
    static final String K_ESCAPE_PLAYERS = "escape_players";
    static final String K_CLUTCH = "water_clutch";
    static final String K_CLUTCH_MIN = "clutch_min_damage";
    static final String K_CLUTCH_PICKUP = "clutch_pickup";
    static final String K_CLUTCH_BLOCKS = "clutch_blocks";
    static final String K_CLUTCH_STAGE = "clutch_stage";
    static final String K_CLUTCH_ELYTRA = "clutch_elytra";
    static final String K_GLIDE_GUARD = "glide_guard";
    static final String K_WATER_STEER = "clutch_water_steer";
    static final String K_INTENT = "intent_assist";
    static final String K_TURN_GAIN = "turn_gain";
    static final String K_TURN_GAIN_STRENGTH = "turn_gain_strength";
    static final String K_STICK = "stick_landing";
    static final String K_TICK_PHASE = "tick_phase";
    static final String K_TAKEOFF_SHAPING = "takeoff_shaping";
    static final String K_BUDGET = "authorship_budget";
    static final String K_JUMP_BUFFER = "jump_buffer";
    static final String K_JUMP_BUFFER_TICKS = "jump_buffer_ticks";
    /** Turn-gain requests lapse after this many ticks unless renewed. */
    static final int TURN_GAIN_TTL = 2;
    /** A position change larger than this in one tick is a teleport: the jump history is forgotten. */
    static final double TELEPORT_DIST = 8.0;
    static final int ESCAPE_EVERY_TICKS = 10;
    static final double MOB_RANGE = 16.0;
    static final double PLAYER_RANGE = 32.0;
    static final double MOB_SPEED = 0.2;
    static final double PLAYER_SPEED = 0.28;
    static final int REACH_EVERY_TICKS = 5;

    private final MovementAssistBrain brain = new MovementAssistBrain();
    private ControlMode controlMode = ControlMode.CO_PILOT;
    /** IWorldMarks slot for the locked target top: -2 = not claimed yet, -1 = the table was full. */
    private int markSlot = -2;
    private boolean markShown;
    private boolean authorityShown;
    private boolean autopilotActing;
    /** One of the STATUS_* constants while a commitment or a hold is live, else null. */
    private String parkourStatus;
    private final SupportRegion landingTop = new SupportRegion();
    private double topX0, topZ0, topX1, topZ1;
    private final IntentEstimator intent = new IntentEstimator();
    private boolean turnGain = true;
    private double turnGainStrength = 0.25;
    /** Turn gain is allowed by the legit level (never on Legit, also for a Legit profile without the key). */
    private boolean turnGainLevelOk = true;
    private boolean turnGainRequested;
    private boolean haveTakeoff;
    private double takeoffX, takeoffZ;
    private boolean havePrevPos;
    private double prevX, prevY, prevZ;
    private double groundX, groundZ;
    private final JumpReach reach = new JumpReach();
    private final MovementPhysics now = new MovementPhysics();
    private final LevelShapes voxels = new LevelShapes();
    private final EscapePlanner escape = new EscapePlanner();
    private final ParkourBlocks blocks = new ParkourBlocks();
    private boolean blocksOn;
    private final FallClutch clutch = new FallClutch();
    private boolean clutchOn;
    private final MovementPhysics hop = new MovementPhysics();
    private boolean wasOnGround = true;
    /** Airborne because of a jump (left the ground going up), not a fall or knockback. */
    private boolean jumpAir;
    private final java.util.List<net.minecraft.world.entity.LivingEntity> threatBuffer = new java.util.ArrayList<>();
    private static final net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity,
            net.minecraft.world.entity.LivingEntity> LIVING =
            net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.LivingEntity.class);
    private boolean escapeOverlay = true;
    private boolean escapePlayers = true;
    private int escapeClock;
    private int escapeCount;
    private final long[] escapeCells = new long[EscapePlanner.MAX_PATH];
    private final boolean[] escapeJumps = new boolean[EscapePlanner.MAX_PATH];

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
        // the autopilot runs the reflexes it is allowed even while this module is off (no overlays then)
        IAutopilot ap = enabled ? null : Services.autopilotOrNull();
        boolean auto = ap != null && ap.isOn();
        if (!enabled && !auto) {
            if (clutch.active() || clutch.staging.staged() || blocks.active()) {
                clutch.releaseAll();
                blocks.release();
            }
            return;
        }
        boolean useClutch = enabled ? clutchOn : ap.allows(IAutopilot.Reflex.FALL_CLUTCH);
        boolean useParkour = enabled || ap.allows(IAutopilot.Reflex.PARKOUR);
        boolean useBlocks = enabled ? blocksOn : ap.allows(IAutopilot.Reflex.PARKOUR_BLOCKS);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (event.getEntity() != p || p == null || mc.level == null) {
            status = "no player";
            showLanding = false;
            reachCount = 0;
            escapeCount = 0;
            return;
        }
        Input in = event.getInput();
        fillNow(mc, p);
        if (havePrevPos) {
            double dx = now.x - prevX;
            double dy = now.y - prevY;
            double dz = now.z - prevZ;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_DIST * TELEPORT_DIST) {
                forgetJumps(); // teleport: the line of recent jumps means nothing here
            }
        }
        havePrevPos = true;
        prevX = now.x;
        prevY = now.y;
        prevZ = now.z;
        if (useClutch) {
            // before the skip checks: it runs while sneaking, in water (scooping it back up) and under automation
            clutch.tick(mc, p, now, sign(in.forwardImpulse), sign(in.leftImpulse), voxels);
            clutch.compensate(in, p.getYRot());
            if (clutch.active()) {
                blocks.release();
            }
        } else if (clutch.active() || clutch.staging.staged()) {
            clutch.releaseAll();
        }
        if (!useParkour && !useBlocks) {
            blocks.release();
            showLanding = false;
            reachCount = 0;
            escapeCount = 0;
            status = "autopilot: clutch only";
            return;
        }
        String skipped = skip(p);
        if (skipped == null && (mc.screen != null || emergencyMovementLease())) {
            skipped = "control taken";
        }
        if (skipped != null) {
            // Stop-All, a screen, a higher lease, not on foot: the commitment and the hold end at once
            brain.abortCommitment();
            endCommitView(ap);
            blocks.release();
            status = skipped;
            showLanding = false;
            reachCount = 0;
            escapeCount = 0;
            return;
        }
        if (p.onGround()) {
            jumpAir = false;
        } else if (wasOnGround && p.getDeltaMovement().y > 0.2) {
            jumpAir = true;
        }
        wasOnGround = p.onGround();
        if (in.shiftKeyDown) {
            // sneak is the documented "let go" key: the commitment and the landing hold hand everything back now
            brain.noteUserSneak();
            endCommitView(ap);
            status = "sneaking";
            blocks.release();
            showLanding = false;
            return;
        }
        boolean sprinting = p.isSprinting();
        int userF = sign(in.forwardImpulse);
        int userS = sign(in.leftImpulse);
        float health = p.getHealth() + p.getAbsorptionAmount();
        brain.safeOnly = safeWalk.getAsBoolean();
        float yaw = p.getYRot();
        IManualLookAssist look = Services.manualLook();
        brain.setYawRate(look.manualYawRateDegPerTick());
        brain.noteTurnGain(look.assistedYawDegLastTick()); // last tick's turn gain was a corrected tick
        if (now.onGround) {
            groundX = now.x;
            groundZ = now.z;
        }
        if (brain.intentAssist && (userF != 0 || userS != 0 || !now.onGround)
                && intent.estimate(now, yaw, p.getXRot(), userF, userS, sprinting, voxels) && intent.hasTarget()) {
            brain.setIntentTarget(intent.targetX(), intent.targetY(), intent.targetZ(), intent.confidence());
        } else {
            brain.clearIntentTarget();
        }
        // takeoff shaping: a yaw shape only as a turn-gain yaw (same gate as turn gain), sprint only when vanilla could
        brain.shapeYawAllowed = !auto && turnGain && turnGainLevelOk;
        boolean canSprint = !sprinting && canStartSprint(p, in);
        brain.sprintFillAllowed = canSprint;
        if (auto) {
            // the autopilot's parkour reflex is edge jumps and air steering, whatever this module's own toggles say
            boolean e = brain.edgeJump;
            boolean s = brain.airSteer;
            boolean h = brain.sprintHop;
            boolean ts = brain.takeoffShaping;
            brain.edgeJump = useParkour;
            brain.airSteer = useParkour;
            brain.sprintHop = false;
            brain.takeoffShaping = false;
            // the autopilot's parkour reflex runs the selected mode, at least Co-pilot
            brain.mode = controlMode.mode == JumpCommit.Mode.NUDGE ? JumpCommit.Mode.GUIDE : controlMode.mode;
            brain.decide(now, p.getYRot(), sprinting, userF, userS, in.jumping, health, voxels);
            brain.edgeJump = e;
            brain.airSteer = s;
            brain.sprintHop = h;
            brain.takeoffShaping = ts;
            status = userF > 0 ? "autopilot" : "autopilot: no forward key";
        } else {
            brain.mode = controlMode.mode;
            brain.decide(now, p.getYRot(), sprinting, userF, userS, in.jumping, health, voxels);
            status = userF > 0 ? "assisting" : "no forward key";
        }
        publishCommit(ap, auto);

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
        if (brain.sprint() && canSprint) {
            // takeoff shaping's sprint timing: the same state change vanilla makes for a sprint key press later in
            // this aiStep (the user's own sprint key was not down, or they would already be sprinting). Only ever on:
            // vanilla's own rules end it (forward released, hunger, a wall).
            p.setSprinting(true);
        }
        if (brain.withholdJump()) {
            in.jumping = false; // opt-in jump buffer: the press fires on the last ground tick instead
        }
        if (brain.sneak()) {
            in.shiftKeyDown = true; // the landing hold's sneak guard (the user is not sneaking: that returned above)
        }
        if (brain.tookOff()) {
            haveTakeoff = true;
            takeoffX = groundX;
            takeoffZ = groundZ;
        } else if (brain.touchedDown() && haveTakeoff) {
            intent.noteJump(takeoffX, takeoffZ, now.x, now.z);
            haveTakeoff = false;
        }
        if (!auto && turnGain && turnGainLevelOk) {
            double help = brain.turnGainHelp(now);
            if (help > 0.0) {
                // scales only the user's own mouse motion toward the ideal takeoff yaw; nothing with a still mouse
                look.setYawGain(ID, brain.idealTakeoffYaw(), 1.0 + turnGainStrength * help,
                        1.0 - turnGainStrength * help, TURN_GAIN_TTL);
                turnGainRequested = true;
            }
        }
        if (useBlocks && brain.edgeNoLanding() && !blocks.active() && now.onGround
                && ParkourBlocks.blockSlot(p.getInventory(), mc.level) >= 0) {
            // an edge nothing clears: jump anyway when the jump plus one block keeps the run going
            hop.copyFrom(now).tick(userF, userS, p.getYRot(), sprinting, true, voxels);
            if (!hop.onGround && blocks.planner.plan(hop, p.getYRot(), sprinting, userF, userS, health,
                    p.getYRot(), p.getXRot(), ParkourBlocks.PLAN_DEG_PER_TICK, 1, voxels)) {
                in.jumping = true;
                jumpingThisTick = true;
                edgeJumps++;
                brain.noteApplied(true);
            }
        }
        if (useBlocks && !clutch.active()) {
            blocks.tick(mc, p, now, sprinting, userF, userS, health, jumpAir, voxels);
            blocks.compensate(in, p.getYRot());
        }
        if (brain.corrected() && !wasCorrecting) {
            corrections++;
        }
        wasCorrecting = brain.corrected();

        if (auto) {
            showLanding = false; // HUD discipline: the autopilot acts, it draws nothing
            reachCount = 0;
            escapeCount = 0;
            return;
        }
        // overlay state
        moving = !now.onGround || now.vx * now.vx + now.vz * now.vz > 0.0025;
        showLanding = overlay && moving && (brain.hasLanding() || !now.onGround);
        landingLethal = brain.lethal();
        landingDamage = brain.landDamage();
        landX = brain.landX();
        landY = brain.landY();
        landZ = brain.landZ();
        if (showLanding && brain.hasLanding() && landingTopUnder(landX, landY, landZ)) {
            // the real top under the predicted landing (a fence post gets a small bracket at 1.5 high)
            topX0 = landingTop.cellTopBound(0);
            topZ0 = landingTop.cellTopBound(1);
            topX1 = landingTop.cellTopBound(2);
            topZ1 = landingTop.cellTopBound(3);
        } else {
            topX0 = Math.floor(landX);
            topZ0 = Math.floor(landZ);
            topX1 = topX0 + 1;
            topZ1 = topZ0 + 1;
        }
        if (overlay && escapeOverlay && ++escapeClock >= ESCAPE_EVERY_TICKS) {
            escapeClock = 0;
            planEscape(mc, p);
        }
        if (overlay && reachOverlay && now.onGround && ++reachClock >= REACH_EVERY_TICKS) {
            reachClock = 0;
            reachCount = reach.compute(now, voxels);
            for (int i = 0; i < reachCount; i++) {
                reachCells[i] = reach.cell(i);
                reachUp[i] = reach.isUp(i);
            }
        } else if (!now.onGround) {
            reachCount = 0;
            escapeCount = 0;
        }
    }

    /**
     * Threat scan + escape plan (every {@link #ESCAPE_EVERY_TICKS}). Hostile mobs within {@link #MOB_RANGE} (they
     * cannot follow a parkour jump) and, if enabled, other players within {@link #PLAYER_RANGE} (they can). The AABB
     * is the one small allocation, twice a second; the entity list is reused.
     */
    private void planEscape(Minecraft mc, LocalPlayer p) {
        escape.clearThreats();
        threatBuffer.clear();
        mc.level.getEntities(LIVING, p.getBoundingBox().inflate(escapePlayers ? PLAYER_RANGE : MOB_RANGE),
                MovementAssist::threatCandidate, threatBuffer, EscapePlanner.MAX_THREATS * 2);
        for (int i = 0; i < threatBuffer.size(); i++) {
            net.minecraft.world.entity.LivingEntity e = threatBuffer.get(i);
            if (e == p) {
                continue;
            }
            boolean player = e instanceof net.minecraft.world.entity.player.Player;
            if (player ? !escapePlayers : e.distanceToSqr(p) > MOB_RANGE * MOB_RANGE) {
                continue;
            }
            escape.addThreat(e.getX(), e.getY(), e.getZ(), player ? PLAYER_SPEED : MOB_SPEED, player);
        }
        threatBuffer.clear();
        if (escape.threatCount() == 0) {
            escapeCount = 0;
            return;
        }
        if (!p.onGround()) {
            return; // keep the last path while airborne (no standable start cell)
        }
        escapeCount = 0;
        if (escape.plan(p.getX(), p.getY(), p.getZ(), voxels)) {
            escapeCount = escape.pathLength();
            for (int i = 0; i < escapeCount; i++) {
                escapeCells[i] = escape.pathCell(i);
                escapeJumps[i] = escape.pathJump(i);
            }
        }
    }

    private static boolean threatCandidate(net.minecraft.world.entity.LivingEntity e) {
        if (!e.isAlive() || e.isSpectator()) {
            return false;
        }
        if (e instanceof net.minecraft.world.entity.player.Player pl) {
            return !pl.isCreative();
        }
        return e instanceof net.minecraft.world.entity.monster.Enemy;
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
        // a latched toggle-sprint is the user's own choice, not automation driving the movement
        if (!assistAutomation && arbiter.holder(ControlChannel.MOVEMENT).isPresent()
                && !arbiter.isHeldBy(ControlChannel.MOVEMENT, com.utilitysuite.travel.mobility.MoveToggles.ID)) {
            return "automation drives movement";
        }
        return null;
    }

    /** The physics replica's view of the player this tick. */
    private void fillNow(Minecraft mc, LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        double attr = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        now.set(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.onGround());
        now.crouching = p.isCrouching(); // set() keeps the shape-path state: mirror the real body
        now.sprinting = p.isSprinting();
        now.speed = p.isSprinting() ? attr / 1.3 : attr; // the attribute already carries the sprint modifier
        now.jumpBoost = p.getJumpBoostPower();
        now.fallDistance = p.fallDistance;
        BlockPos below = p.getBlockPosBelowThatAffectsMyMovement();
        now.slipperiness = mc.level.getBlockState(below).getBlock().getFriction();
        voxels.begin(mc.level);
    }

    /**
     * Vanilla's conditions for starting a sprint ({@code LocalPlayer.canStartSprinting} plus the forward impulse), from
     * public state: on foot, forward held hard enough, food above 6 (or may fly), no item in use, not blind, not
     * gliding, not sneaking or crawling.
     */
    private static boolean canStartSprint(LocalPlayer p, Input in) {
        return in.forwardImpulse >= 0.8f && !in.shiftKeyDown && p.onGround()
                && (p.getFoodData().getFoodLevel() > 6 || p.mayFly())
                && !p.isUsingItem() && !p.hasEffect(MobEffects.BLINDNESS) && !p.isFallFlying() && !p.isMovingSlowly()
                && !p.isPassenger() && !p.isInWater();
    }

    private static int sign(float f) {
        return f > 1.0e-3f ? 1 : (f < -1.0e-3f ? -1 : 0);
    }

    // ---- overlay ----------------------------------------------------------------------------------------------------

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !overlay || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || (!showLanding && reachCount == 0 && escapeCount == 0) || Services.modState().isCleanScreen()) {
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
            double y = landY - cam.y;
            int argb = 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (0.15f * 255);
            brackets(lines, pose.last(), (float) (topX0 - cam.x), (float) (y + 0.02), (float) (topZ0 - cam.z),
                    (float) (topX1 - cam.x), (float) (topZ1 - cam.z), argb);
            double px = landX - cam.x;
            double pz = landZ - cam.z;
            LevelRenderer.renderLineBox(pose, lines, px - 0.3, y + 0.01, pz - 0.3, px + 0.3, y + 1.8, pz + 0.3,
                    r, g, 0.15f, 0.6f);
        }
        renderEscape(pose, lines, cam);
        buffers.endBatch(RenderType.lines());
    }

    /**
     * Four corner brackets on a landing top (replacing the full box): each corner gets two short arms along the edges,
     * a quarter of the shorter side long, so a fence post reads as a small bracket and a platform as a large one.
     */
    static void brackets(VertexConsumer lines, PoseStack.Pose pose, float x0, float y, float z0, float x1, float z1,
                         int argb) {
        float arm = Math.max(0.04f, Math.min(x1 - x0, z1 - z0) * 0.25f);
        WorldRenderKit.line(lines, pose, x0, y, z0, x0 + arm, y, z0, argb);
        WorldRenderKit.line(lines, pose, x0, y, z0, x0, y, z0 + arm, argb);
        WorldRenderKit.line(lines, pose, x1, y, z0, x1 - arm, y, z0, argb);
        WorldRenderKit.line(lines, pose, x1, y, z0, x1, y, z0 + arm, argb);
        WorldRenderKit.line(lines, pose, x0, y, z1, x0 + arm, y, z1, argb);
        WorldRenderKit.line(lines, pose, x0, y, z1, x0, y, z1 - arm, argb);
        WorldRenderKit.line(lines, pose, x1, y, z1, x1 - arm, y, z1, argb);
        WorldRenderKit.line(lines, pose, x1, y, z1, x1, y, z1 - arm, argb);
    }

    // ---- commitment view (authority bar, landing mark, status line, autopilot) ------------------------------------

    /** The block whose top the feet land on (its own cell or, for 1.5-high fences and walls, the one below). */
    private boolean landingTopUnder(double x, double feetY, double z) {
        int bx = MovementPhysics.floor(x);
        int bz = MovementPhysics.floor(z);
        int by = MovementPhysics.floor(feetY - 0.01);
        for (int y = by; y >= by - 1; y--) {
            double t = landingTop.topOf(voxels, bx, y, bz);
            if (!Double.isNaN(t) && Math.abs(t - feetY) < 1.0e-3) {
                return true;
            }
        }
        return false;
    }

    /** A lease at EMERGENCY or above holds MOVEMENT (fall clutch): the commitment yields at once. */
    private static boolean emergencyMovementLease() {
        var h = Services.arbiter().holder(ControlChannel.MOVEMENT);
        return h.isPresent() && h.get().priority().level() >= ControlPriority.EMERGENCY.level();
    }

    /**
     * Publishes the commitment of this tick: the authority share and hand-back for the HUD bar, an override flash, the
     * locked target top as a LANDING mark, one constant status string, and the autopilot's acted/done notes.
     */
    private void publishCommit(IAutopilot ap, boolean auto) {
        JumpCommit c = brain.commit();
        LandingHold h = brain.hold();
        boolean live = c.live() || h.active() || h.latched() != 0;
        if (auto && ap != null) {
            if (c.phase() == JumpCommit.Phase.TAKEOFF && !autopilotActing) {
                ap.noteActed(IAutopilot.Reflex.PARKOUR, REASON_PARKOUR);
                autopilotActing = true;
            } else if (autopilotActing && c.phase() == JumpCommit.Phase.IDLE) {
                ap.noteDone(IAutopilot.Reflex.PARKOUR);
                autopilotActing = false;
            }
        }
        if (!live) {
            endCommitView(null);
            return;
        }
        AuthorityState a = Services.visuals().authority();
        AuthorityReason why = h.active() ? AuthorityReason.LANDING
                : h.latched() != 0 ? AuthorityReason.EDGE_HOLD : AuthorityReason.JUMP;
        float share = Math.max(c.authority(), h.active() ? 0.6f : (h.latched() != 0 ? 0.3f : 0f));
        a.publish(ID, share, c.mode == JumpCommit.Mode.CARRY && c.driving(), why, c.releaseLeft(), c.releaseTotal(),
                h.latched());
        authorityShown = true;
        if (c.overrideThisTick()) {
            a.noteOverride(OVERRIDE_FLASH_TICKS, c.hardOverrideThisTick() && !c.driving());
        }
        parkourStatus = h.latched() != 0 ? STATUS_HOLD
                : c.phase() == JumpCommit.Phase.RELEASE || c.phase() == JumpCommit.Phase.ABORT ? STATUS_YOURS
                : STATUS_LOCKED;
        status = parkourStatus;
        boolean locked = c.live() && brain.targetRegionValid() && !auto;
        if (locked && markSlot == -2) {
            markSlot = Services.visuals().marks().claim(ID);
        }
        if (locked && markSlot >= 0) {
            SupportRegion t = brain.targetRegion();
            Services.visuals().marks().setBox(markSlot, MarkKind.LANDING, t.cellTopBound(0), t.top(),
                    t.cellTopBound(1),
                    t.cellTopBound(2), t.top() + 0.02, t.cellTopBound(3), MARK_LABEL, 0);
            markShown = true;
        } else if (markShown) {
            Services.visuals().marks().clear(markSlot);
            markShown = false;
        }
    }

    /** Idle: nothing published, nothing marked, no status. */
    private void endCommitView(IAutopilot ap) {
        if (authorityShown) {
            Services.visuals().authority().clear(ID);
            authorityShown = false;
        }
        if (markShown && markSlot >= 0) {
            Services.visuals().marks().clear(markSlot);
        }
        markShown = false;
        parkourStatus = null;
        if (ap != null && autopilotActing) {
            ap.noteDone(IAutopilot.Reflex.PARKOUR);
            autopilotActing = false;
        }
    }

    /** Escape path: orange steps along the ground, magenta for sprint jumps, a taller marker at the end. */
    private void renderEscape(PoseStack pose, VertexConsumer lines, Vec3 cam) {
        if (escapeCount < 2) {
            return;
        }
        PoseStack.Pose last = pose.last();
        for (int i = 1; i < escapeCount; i++) {
            long a = escapeCells[i - 1];
            long b = escapeCells[i];
            float ax = (float) (Packed.x(a) + 0.5 - cam.x);
            float ay = (float) (Packed.y(a) + 0.1 - cam.y);
            float az = (float) (Packed.z(a) + 0.5 - cam.z);
            float bx = (float) (Packed.x(b) + 0.5 - cam.x);
            float by = (float) (Packed.y(b) + 0.1 - cam.y);
            float bz = (float) (Packed.z(b) + 0.5 - cam.z);
            boolean jump = escapeJumps[i];
            float r = jump ? 0.95f : 1.0f;
            float g = jump ? 0.25f : 0.55f;
            float bl = jump ? 0.95f : 0.1f;
            if (jump) {
                // two straight pieces through a raised midpoint read as a hop
                float mx = (ax + bx) * 0.5f;
                float my = Math.max(ay, by) + 1.1f;
                float mz = (az + bz) * 0.5f;
                segment(lines, last, ax, ay, az, mx, my, mz, r, g, bl);
                segment(lines, last, mx, my, mz, bx, by, bz, r, g, bl);
            } else {
                segment(lines, last, ax, ay, az, bx, by, bz, r, g, bl);
            }
        }
        long end = escapeCells[escapeCount - 1];
        double ex = Packed.x(end) - cam.x;
        double ey = Packed.y(end) - cam.y;
        double ez = Packed.z(end) - cam.z;
        LevelRenderer.renderLineBox(pose, lines, ex + 0.2, ey + 0.01, ez + 0.2, ex + 0.8, ey + 1.0, ez + 0.8,
                1.0f, 0.55f, 0.1f, 1.0f);
    }

    private static void segment(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1,
                                float z1, float r, float g, float b) {
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
        c.addVertex(p, x0, y0, z0).setColor(r, g, b, 1.0f).setNormal(p, nx, ny, nz);
        c.addVertex(p, x1, y1, z1).setColor(r, g, b, 1.0f).setNormal(p, nx, ny, nz);
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
            releaseTurnGain();
            brain.abortCommitment();
            endCommitView(null);
            clutch.release();
            blocks.release();
            showLanding = false;
            reachCount = 0;
            escapeCount = 0;
        }
    }

    @Override
    public void onTick() {
        // all work happens in the movement-input event (once per client tick, right where it applies)
    }

    @Override
    public void onWorldUnload() {
        releaseTurnGain();
        forgetJumps();
        endCommitView(Services.autopilotOrNull());
        havePrevPos = false;
        blocks.release();
        clutch.releaseAll();
        showLanding = false;
        reachCount = 0;
        escapeCount = 0;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read(profile);
    }

    private void releaseTurnGain() {
        if (turnGainRequested) {
            Services.manualLook().clearYawGain(ID);
            turnGainRequested = false;
        }
    }

    /** World change or teleport: forget the line of recent jumps and the authorship history. */
    private void forgetJumps() {
        intent.reset();
        brain.reset();
        haveTakeoff = false;
    }

    /** Turn gain per the legit level; an absent key on a profile left on Legit reads as off. */
    private boolean turnGainFromProfile(ProfileConfig p) {
        ProfileConfig global = null;
        try {
            global = Services.modState().global();
        } catch (RuntimeException e) {
            // no mod state yet (early load): the world profile alone decides
        }
        boolean on = LegitPresets.boolKnob(p, global, ID, K_TURN_GAIN);
        turnGainLevelOk = LegitPresets.current(p, global) != LegitPresets.Level.LEGIT
                && LegitPresets.currentIgnoring(p, global, ID, K_TURN_GAIN) != LegitPresets.Level.LEGIT;
        return on;
    }

    private static OverrideClassifier.Sensitivity sensitivity(String s) {
        for (OverrideClassifier.Sensitivity v : OverrideClassifier.Sensitivity.values()) {
            if (v.name().equals(s)) {
                return v;
            }
        }
        return OverrideClassifier.Sensitivity.NORMAL;
    }

    /** Key-change limits of the legit level (LegitPresets knobs; absent keys read as BALANCED). */
    private void readKeyChangeLimits(ProfileConfig p) {
        ProfileConfig global = null;
        try {
            global = Services.modState().global();
        } catch (RuntimeException e) {
            // early load: the world profile alone decides
        }
        brain.setKeyChangeLimits(p.getInt(ID, K_KEY_CHANGES, LegitPresets.KEY_CHANGES_BALANCED),
                p.getInt(ID, K_KEY_HOLD, LegitPresets.KEY_HOLD_BALANCED),
                p.getInt(ID, K_REACTION, LegitPresets.REACTION_BALANCED));
        brain.commit().classifier.relaxed = LegitPresets.current(p, global) == LegitPresets.Level.RELAXED;
    }

    private void read(ProfileConfig p) {
        brain.edgeJump = p.getBool(ID, K_EDGE, true);
        brain.airSteer = p.getBool(ID, K_STEER, true);
        brain.sprintHop = p.getBool(ID, K_HOP, false);
        overlay = p.getBool(ID, K_OVERLAY, true);
        reachOverlay = p.getBool(ID, K_REACH, true);
        assistAutomation = p.getBool(ID, K_AUTOMATION, false);
        blocksOn = p.getBool(ID, K_BLOCKS, true);
        blocks.setNaturalLook(!"FREE".equals(p.getString(ID, K_LOOK, LookStyle.NATURAL.name())));
        if (!blocksOn) {
            blocks.release();
        }
        clutchOn = p.getBool(ID, K_CLUTCH, true);
        clutch.minDamage = (float) p.getInt(ID, K_CLUTCH_MIN, 6);
        clutch.pickup = p.getBool(ID, K_CLUTCH_PICKUP, true);
        clutch.useBlocks = p.getBool(ID, K_CLUTCH_BLOCKS, true);
        clutch.staging.enabled = p.getBool(ID, K_CLUTCH_STAGE, true);
        clutch.elytraDeploy = p.getBool(ID, K_CLUTCH_ELYTRA, true);
        clutch.glideGuard = p.getBool(ID, K_GLIDE_GUARD, true);
        clutch.waterSteer = p.getBool(ID, K_WATER_STEER, true);
        if (!clutchOn) {
            clutch.releaseAll();
        }
        brain.intentAssist = p.getBool(ID, K_INTENT, true);
        if (!brain.intentAssist) {
            brain.clearIntentTarget();
        }
        turnGain = turnGainFromProfile(p);
        turnGainStrength = Math.max(0.0, Math.min(0.5, p.getDouble(ID, K_TURN_GAIN_STRENGTH, 0.25)));
        if (!turnGain || !turnGainLevelOk) {
            releaseTurnGain();
        }
        controlMode = ControlMode.parse(p.getString(ID, K_CONTROL_MODE, ControlMode.CO_PILOT.name()));
        brain.mode = controlMode.mode;
        brain.commit().classifier.sensitivity = sensitivity(p.getString(ID, K_SENSITIVITY, "NORMAL"));
        // migration: a profile that had "Stick the landing" off reads the landing hold off
        brain.landingHold = p.getBool(ID, K_LANDING_HOLD, p.getBool(ID, K_STICK, true));
        brain.hold().release = "TIMER".equals(p.getString(ID, K_HOLD_RELEASE, "FRESH_INPUT"))
                ? LandingHold.Release.TIMER : LandingHold.Release.FRESH_INPUT;
        brain.hold().holdLimitTicks = Math.max(2, Math.min(40, p.getInt(ID, K_HOLD_LIMIT,
                LandingHold.DEFAULT_HOLD_LIMIT)));
        if (!brain.landingHold) {
            brain.hold().reset();
        }
        readKeyChangeLimits(p);
        brain.tickPhase = p.getBool(ID, K_TICK_PHASE, true);
        brain.takeoffShaping = p.getBool(ID, K_TAKEOFF_SHAPING, true);
        brain.budget().setCap(Math.max(0, Math.min(20, p.getInt(ID, K_BUDGET, AuthorshipBudget.DEFAULT_CAP))));
        brain.jumpBuffer().setEnabled(p.getBool(ID, K_JUMP_BUFFER, false));
        brain.jumpBuffer().setMaxTicks(p.getInt(ID, K_JUMP_BUFFER_TICKS, JumpBuffer.DEFAULT_TICKS));
        escapeOverlay = p.getBool(ID, K_ESCAPE, true);
        escapePlayers = p.getBool(ID, K_ESCAPE_PLAYERS, true);
        if (!escapeOverlay) {
            escapeCount = 0;
        }
    }

    @Override
    public String displayName() {
        return "Movement Assist";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice(K_CONTROL_MODE, "Control", ControlMode.CO_PILOT, "",
                        "CORRECTIONS: small per-tick corrections, your keys always stand. CO_PILOT (default): a jump"
                                + " at a locked block is held from takeoff to a stable landing; it fills keys you are"
                                + " not using and lets go of a key you held since before the jump, never one you"
                                + " just pressed. TAKEOVER: hold a movement key toward the block and it flies the"
                                + " jump."),
                SettingSpec.choice(K_SENSITIVITY, "Override sensitivity", OverrideClassifier.Sensitivity.NORMAL, "",
                        "How quickly your own input takes a jump back. HIGH: sooner. LOW: the assist holds on longer."
                                + " Sneak always hands everything back at once."),
                SettingSpec.bool(K_EDGE, "Edge jump", true, "",
                        "While you run forward, jump on the last tick before an edge when the jump lands better than"
                                + " dropping (parkour timing)."),
                SettingSpec.bool(K_STEER, "Air steering", true, "",
                        "In the air, if your keys would miss the landing, take fall damage or land much lower, press"
                                + " the strafe or forward key that makes it. Never overrides a key you hold."),
                SettingSpec.bool(K_INTENT, "Follow my intent", true, "",
                        "Work out which block you are going for (where you look, your keys, your line of jumps) and"
                                + " aim the jump timing and air steering at landing on it, not just at going far."),
                SettingSpec.bool(K_TURN_GAIN, "Turn gain", true, "",
                        "While you turn your mouse toward the best takeoff direction for that block, it turns a little"
                                + " faster; turning away, a little slower. It only scales your own mouse: with a still"
                                + " mouse nothing moves. Off on the Legit level."),
                SettingSpec.decimal(K_TURN_GAIN_STRENGTH, "Turn gain strength", 0.25, 0.0, 0.5, "",
                        "How much turn gain scales your mouse (0.25 = up to 25% faster toward, 25% slower away)."),
                SettingSpec.bool(K_LANDING_HOLD, "Landing hold", true, "",
                        "On a narrow landing (a fence post, a wall, a pane, a 1-block pillar) sneak from the last air"
                                + " tick, and every key you held through the jump stays off until you release it. Press"
                                + " a key again, jump or sneak and it is yours at once. Never while you hold jump."),
                SettingSpec.choice(K_HOLD_RELEASE, "Release hold on", LandingHold.Release.FRESH_INPUT, "",
                        "FRESH_INPUT: the sneak lifts once you stand still and press something new. TIMER: it lifts"
                                + " after the limit below. Either way a key held through the jump stays off until"
                                + " you release it."),
                SettingSpec.integer(K_HOLD_LIMIT, "Hold limit ticks", LandingHold.DEFAULT_HOLD_LIMIT, 2, 40, "",
                        "Ticks (50 ms each) the sneak stays on after you are stable before it lifts by itself."),
                SettingSpec.bool(K_TICK_PHASE, "Tick-perfect takeoff", true, "",
                        "One tick before an edge, jump a tick early when that lands better than the usual last-tick"
                                + " jump (for example while you are turning away). Never waits past the edge."),
                SettingSpec.bool(K_TAKEOFF_SHAPING, "Shape short takeoffs", true, "",
                        "When your run-up would miss the block you are going for, take off like a strafe jumper: press"
                                + " the free strafe key for a tick or two, or start your sprint at the tick that lands it."
                                + " Never changes a key you hold. Counts against corrections per jump."),
                SettingSpec.integer(K_BUDGET, "Corrections per jump", AuthorshipBudget.DEFAULT_CAP, 0, 20, "",
                        "Most ticks per jump the assist may correct (jump, keys, sneak, turn gain). Help shrinks as"
                                + " you land more jumps on your own. 0 = no corrections."),
                SettingSpec.bool(K_JUMP_BUFFER, "Jump buffer", false, "",
                        "A jump pressed a tick or two before an edge is held and fired on the last ground tick. This"
                                + " can delay your jump key by up to 150 ms."),
                SettingSpec.integer(K_JUMP_BUFFER_TICKS, "Jump buffer ticks", JumpBuffer.DEFAULT_TICKS, 1,
                        JumpBuffer.MAX_TICKS, "",
                        "How early (in ticks, 50 ms each) a jump press may be buffered."),
                SettingSpec.bool(K_HOP, "Sprint hop", false, "",
                        "On open flat ground, jump whenever a sprint jump outruns sprinting (faster travel)."),
                SettingSpec.bool(K_OVERLAY, "Landing overlay", true, "Overlay",
                        "Show where you will land: green safe, yellow fall damage, red nothing survivable."),
                SettingSpec.bool(K_REACH, "Jump reach", true, "Overlay",
                        "Outline the blocks a sprint jump from here can land on (brighter: higher than you)."),
                SettingSpec.bool(K_BLOCKS, "Parkour blocks", true, "",
                        "Mid-jump, if one block keeps you going (a wall you cannot clear, a gap one block too long),"
                                + " turn your head, place a block from the hotbar and turn back. Uses your blocks."),
                SettingSpec.choice(K_LOOK, "Look style", LookStyle.NATURAL, "",
                        "NATURAL: automated looks stay along the way you are moving (aim ahead, turn back after)."
                                + " FREE: look wherever is quickest for the job (less legit)."),
                SettingSpec.bool(K_CLUTCH, "Fall clutch", true, "",
                        "Falling toward a landing that would kill you or cost the damage below: look and use a clutch"
                                + " item from the hotbar the last moment it reaches. A water bucket first (not in the"
                                + " Nether), then the blocks below if allowed."),
                SettingSpec.bool(K_CLUTCH_BLOCKS, "Clutch with blocks", true, "",
                        "Without a water bucket (or in the Nether): powder snow, cobweb, slime (not while sneaking),"
                                + " or a hay bale (a fifth of the damage)."),
                SettingSpec.integer(K_CLUTCH_MIN, "Clutch at damage", 6, 1, 20, "",
                        "Fall damage (half-hearts) that is worth a clutch. Lethal falls are always clutched."),
                SettingSpec.bool(K_CLUTCH_PICKUP, "Pick the clutch back up", true, "",
                        "After landing in clutch water or powder snow, scoop it back into the bucket."),
                SettingSpec.bool(K_CLUTCH_STAGE, "Stage the clutch early", true, "",
                        "Near a deep drop when a fall gets likely (sprinting at the edge, knockback, a fight on a"
                                + " ledge): move the clutch item into an empty hotbar slot and select it. The camera"
                                + " only moves once you are airborne; the slot comes back when the risk passes."),
                SettingSpec.bool(K_CLUTCH_ELYTRA, "Deploy a worn elytra", true, "",
                        "Falling with an elytra on: open it with a jump tap when that saves more than a clutch."),
                SettingSpec.bool(K_WATER_STEER, "Steer into water", true, "",
                        "Falling hard with water a few blocks from the landing: hold the movement keys that carry"
                                + " you into it (keys only, the camera stays)."),
                SettingSpec.bool(K_GLIDE_GUARD, "Glide pull-up", true, "",
                        "Gliding toward the ground fast enough to get hurt: raise the nose (smoothed) until safe."),
                SettingSpec.bool(K_ESCAPE, "Escape path", true, "Overlay",
                        "When hostile mobs (or players) are near, draw the route that leaves the most room between you"
                                + " and them, using sprint jumps mobs cannot follow (magenta = jump)."),
                SettingSpec.bool(K_ESCAPE_PLAYERS, "Players count as threats", true, "Overlay",
                        "Plan escapes from other players too (they can follow jumps, so only distance helps)."),
                SettingSpec.bool(K_AUTOMATION, "Assist automation too", false, "",
                        "Also assist while an automated job (goal, travel, combat) drives your movement.")
                        .asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        read(Services.modState().profile());
    }

    // ---- accessors (status, playtest) ----------------------------------------------------------------------------

    /**
     * The one HUD status line of a parkour commitment or landing hold: {@link #STATUS_LOCKED}, {@link #STATUS_HOLD},
     * {@link #STATUS_YOURS}; null when idle (HUD rule 6: nothing when idle). Constant strings.
     */
    public String parkourStatus() {
        return enabled ? parkourStatus : null;
    }

    public ControlMode controlMode() {
        return controlMode;
    }

    /** Why the assist did or did not act on the last movement tick (status, playtest). */
    public String status() {
        return status;
    }

    /** Blocks placed by Parkour blocks, placements planned, and plans given up. */
    public int blocksPlaced() {
        return blocks.placed();
    }

    public int blockPlans() {
        return blocks.plans();
    }

    public int blockAborts() {
        return blocks.aborted();
    }

    /** Fall clutches done (any item), buckets scooped back up, and clutches started. */
    public int fallClutches() {
        return clutch.clutches();
    }

    /** Fall clutches done with one item kind. */
    public int fallClutches(FallClutchPlanner.Kind k) {
        return clutch.clutches(k);
    }

    public int clutchPickups() {
        return clutch.pickups();
    }

    /** What the clutch did last (diagnostics). */
    public String clutchNote() {
        return clutch.note();
    }

    public int clutchPlans() {
        return clutch.plans();
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

    /** Steps of the current escape path (0 = none shown). */
    public int escapeLength() {
        return escapeCount;
    }

    public long escapeCell(int i) {
        return escapeCells[i];
    }

    public boolean escapeJump(int i) {
        return escapeJumps[i];
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
