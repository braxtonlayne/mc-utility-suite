package com.utilitysuite.travel.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.ControlChannel;
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

    public static final String ID = "movement_assist";
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
    static final int ESCAPE_EVERY_TICKS = 10;
    static final double MOB_RANGE = 16.0;
    static final double PLAYER_RANGE = 32.0;
    static final double MOB_SPEED = 0.2;
    static final double PLAYER_SPEED = 0.28;
    static final int REACH_EVERY_TICKS = 5;

    private final MovementAssistBrain brain = new MovementAssistBrain();
    private final JumpReach reach = new JumpReach();
    private final MovementPhysics now = new MovementPhysics();
    private final LevelVoxels voxels = new LevelVoxels();
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
        if (skipped != null) {
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
        if (auto) {
            // the autopilot's parkour reflex is edge jumps and air steering, whatever this module's own toggles say
            boolean e = brain.edgeJump;
            boolean s = brain.airSteer;
            boolean h = brain.sprintHop;
            brain.edgeJump = useParkour;
            brain.airSteer = useParkour;
            brain.sprintHop = false;
            brain.decide(now, p.getYRot(), sprinting, userF, userS, in.jumping, health, voxels);
            brain.edgeJump = e;
            brain.airSteer = s;
            brain.sprintHop = h;
            status = userF > 0 ? "autopilot" : "autopilot: no forward key";
        } else {
            brain.decide(now, p.getYRot(), sprinting, userF, userS, in.jumping, health, voxels);
            status = userF > 0 ? "assisting" : "no forward key";
        }

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
        if (useBlocks && brain.edgeNoLanding() && !blocks.active() && now.onGround
                && ParkourBlocks.blockSlot(p.getInventory(), mc.level) >= 0) {
            // an edge nothing clears: jump anyway when the jump plus one block keeps the run going
            hop.copyFrom(now).tick(userF, userS, p.getYRot(), sprinting, true, voxels);
            if (!hop.onGround && blocks.planner.plan(hop, p.getYRot(), sprinting, userF, userS, health,
                    p.getYRot(), p.getXRot(), ParkourBlocks.PLAN_DEG_PER_TICK, 1, voxels)) {
                in.jumping = true;
                jumpingThisTick = true;
                edgeJumps++;
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
        now.speed = p.isSprinting() ? attr / 1.3 : attr; // the attribute already carries the sprint modifier
        now.jumpBoost = p.getJumpBoostPower();
        now.fallDistance = p.fallDistance;
        BlockPos below = p.getBlockPosBelowThatAffectsMyMovement();
        now.slipperiness = mc.level.getBlockState(below).getBlock().getFriction();
        voxels.begin(mc.level);
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
            double x = Math.floor(landX) - cam.x;
            double y = landY - cam.y;
            double z = Math.floor(landZ) - cam.z;
            LevelRenderer.renderLineBox(pose, lines, x, y + 0.01, z, x + 1, y + 0.06, z + 1, r, g, 0.15f, 1.0f);
            double px = landX - cam.x;
            double pz = landZ - cam.z;
            LevelRenderer.renderLineBox(pose, lines, px - 0.3, y + 0.01, pz - 0.3, px + 0.3, y + 1.8, pz + 0.3,
                    r, g, 0.15f, 0.6f);
        }
        renderEscape(pose, lines, cam);
        buffers.endBatch(RenderType.lines());
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
        if (!clutchOn) {
            clutch.releaseAll();
        }
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
