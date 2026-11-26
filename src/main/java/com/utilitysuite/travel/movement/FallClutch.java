package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IDangerModel;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.CameraAim;
import com.utilitysuite.travel.Packed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Fall clutch, the in-game half of {@link FallClutchPlanner}: falling toward a landing that would kill or cost at least
 * the configured damage, with a clutch item in the hotbar (a water bucket, or with blocks allowed: powder snow, cobweb,
 * slime, hay bale; ladders and vines are planned but not used yet), take an EMERGENCY lease (CAMERA, HOTBAR,
 * WORLD_INTERACT), put the item in hand, turn the head to the planned face (VISIBLE, through the capped smoother: no
 * snapping; the use packet carries the look), and use it the moment the face is under the crosshair within reach.
 * After a bucket clutch, scoop the water or powder snow back up (a pro never leaves a stray source) and turn back.
 * <p>
 * Re-plans every tick (the trajectory moves with the user's keys). Water is skipped where it evaporates, slime while
 * sneaking (no bounce means full damage); nothing runs for players who take no fall damage, or while flying or
 * gliding. The user's mouse preempts the lease (manual input wins); Stop-All revokes it. Client thread only.
 */
final class FallClutch {

    enum Phase { IDLE, POUR, PICKUP, RETURN, DEPLOY, GLIDE, STEER, FALLBACK }

    /** Fallback chain (C8): boat, twisting vines, wind charge after everything above found nothing. Off by default. */
    boolean fallbackChain = false;
    final ClutchChain chain = new ClutchChain();
    private final double[] chainHeights = new double[FallClutchPlanner.HORIZON + 1];
    /** Hotbar slot per {@link ClutchChain.Fallback} (only the chain's new fallbacks are filled; -1: none). */
    private final int[] chainSlot = new int[ClutchChain.CHAIN.length];
    private ClutchChain.Fallback fallback;
    private int fallbacks;

    /** Horizontal search radius (blocks) for water around the predicted landing. */
    static final int WATER_RADIUS = 4;
    private final com.utilitysuite.travel.HeldKeys steerKeys = new com.utilitysuite.travel.HeldKeys();
    private int waterX;
    private int waterY;
    private int waterZ;
    boolean waterSteer = true;

    static final String MODULE_ID = "fall_clutch";
    /** Ticks the elytra deploy waits for the glide to start before giving up. */
    static final int DEPLOY_TICKS = 6;
    /** Ticks between void column scans while falling with no landing in sight. */
    static final int VOID_SCAN_TICKS = 5;

    final ClutchLadder ladder = new ClutchLadder();
    final FallStaging staging = new FallStaging();
    private final BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
    /** The lease came from the autopilot's chain: end it with noteDone, not close. */
    private boolean chained;
    /** The autopilot drives this run (it gets noteDone at the end). */
    private boolean scheduledRun;
    private boolean fallReported;
    private boolean voidReported;
    private int voidClock;
    private int calmGlideTicks;
    boolean elytraDeploy = true;
    boolean glideGuard = true;

    static final int MAX_PICKUP_TICKS = 40;
    static final int MAX_RETURN_TICKS = 20;
    /** Ticks settled (on the ground or in the water) before the scoop, picked per clutch in this range: a human tap. */
    static final int SETTLE_MIN_TICKS = 4;
    static final int SETTLE_MAX_TICKS = 8;
    /** The user walking farther than this (blocks, horizontal) from where they settled cancels the scoop. */
    static final double SCOOP_MOVE_LIMIT = 1.5;
    static final double REACH = 4.5;
    /** Ticks of slack between starting the turn and the planned use (the smoother eases in; yaw and pitch together). */
    static final int LEAD_TICKS = 6;
    /** Ticks the last plan is kept when a re-plan finds nothing. */
    static final int PLAN_GRACE_TICKS = 2;

    final FallClutchPlanner planner = new FallClutchPlanner();
    /** Hotbar slot per {@link FallClutchPlanner.Kind}, refreshed each idle tick (-1: none). */
    private final int[] kindSlot = new int[FallClutchPlanner.KINDS.length];
    private FallClutchPlanner.Kind kind;
    private final CameraAim aim = new CameraAim(0.3f);
    private final BlockPos.MutableBlockPos sup = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos water = new BlockPos.MutableBlockPos();

    private Phase phase = Phase.IDLE;
    private ControlLease lease;
    private int slot = -1;
    private int ticks;
    private float heading;
    private float returnYaw;
    private float returnPitch;
    private long poured = Long.MIN_VALUE;
    private boolean hasPlan;
    private int graceTicks;
    private int settledTicks;
    private int settleWait;
    private double settleX;
    private double settleZ;
    private long planSupport;
    private int planFace;
    private long planCell;

    // settings
    float minDamage = 6f;
    boolean pickup = true;
    boolean useBlocks = true;

    // counters
    private int clutches;
    private int pickups;
    private int plans;
    private final int[] kindCounts = new int[FallClutchPlanner.KINDS.length];
    private float lastLandDamage;
    /** Last thing that happened (constants only: read by the HUD and the playtest, nothing built per tick). */
    private String note = "idle";
    private InteractionResult lastUse;
    private int dbgUseTick = -9;
    private int dbgTicksToLand = -9;
    private float dbgAngle = -1f;
    private int dbgPick;
    private double dbgY;

    boolean active() {
        return phase != Phase.IDLE;
    }

    /** Once per movement-input tick, before the rest of the assist (it runs in water and while sneaking too). */
    void tick(Minecraft mc, LocalPlayer p, MovementPhysics now, int userF, int userS, MovementPhysics.Voxels w) {
        switch (phase) {
            case IDLE -> {
                feedDanger(mc, p, now, userF, userS, w);
                if (p.onGround()) {
                    staging.tick(mc, p, ITEMS);
                    return;
                }
                if (glideGuard && p.isFallFlying()) {
                    glideCheck(mc, p);
                    return;
                }
                if (p.getDeltaMovement().y >= 0 || !eligible(mc, p)) {
                    return;
                }
                staging.handOff();
                int kinds = kindsAtHand(mc, p);
                float health = p.getHealth() + p.getAbsorptionAmount();
                float yaw = p.getYRot();
                boolean planned = kinds != 0 && planner.plan(now, yaw, p.isSprinting(), userF, userS, health,
                        minDamage, yaw, p.getXRot(), ParkourBlocks.PLAN_DEG_PER_TICK, kinds, w);
                // the option ladder: the placed clutch against deploying a worn elytra and steering into water (the
                // pearl is ranked by ClutchLadder too but has no in-game ledge search yet)
                boolean elytraWorn = elytraDeploy && p.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA
                        && !p.getItemBySlot(EquipmentSlot.CHEST).nextDamageWillBreak();
                if (!planned && (!planner.predictLanding(now, yaw, p.isSprinting(), userF, userS, w)
                        || planner.landDamage() < Math.min(minDamage, health))) {
                    return; // no harmful landing predicted
                }
                ladder.begin(planner.ticksToLand(), planner.landDamage());
                if (planned) {
                    ladder.offer(ClutchLadder.Option.BLOCK, planner.useTick(), planner.damageAfter());
                }
                ladder.offerElytra(elytraWorn);
                if (waterSteer) {
                    double off = findWater(mc, planner.landX(), planner.landY(), planner.landZ(),
                            planner.ticksToLand());
                    ladder.offerWaterSteer(off, off >= 0);
                }
                ClutchLadder.Option best = ladder.best();
                IAutopilot ap = Services.autopilotOrNull();
                boolean scheduled = ap != null && ap.isOn() && ap.allows(IAutopilot.Reflex.FALL_CLUTCH);
                if (best == null) {
                    // everything above found nothing: the fallback chain (boat, twisting vines, wind charge)
                    if (fallbackChain) {
                        tryFallback(mc, p, now, ap, scheduled, w);
                    }
                    return;
                }
                if (best == ClutchLadder.Option.ELYTRA || best == ClutchLadder.Option.WATER_STEER) {
                    if (scheduled && !ap.mayAct(IAutopilot.Reflex.FALL_CLUTCH,
                            ladder.ticksNeeded(best), ladder.damageAverted())) {
                        ap.noteDeclined(IAutopilot.Reflex.FALL_CLUTCH, "another reflex goes first");
                        return;
                    }
                    if (best == ClutchLadder.Option.ELYTRA) {
                        startDeploy(ap, scheduled);
                    } else {
                        startSteer(ap, scheduled);
                    }
                    return;
                }
                // start turning while there is time: no need for a lease many ticks before the head must move
                double turn = BlockAssistPlanner.angle(yaw, p.getXRot(), planner.aimYaw(), planner.aimPitch());
                if (planner.useTick()
                        > FallClutchPlanner.turnTicks(turn, ParkourBlocks.PLAN_DEG_PER_TICK) + LEAD_TICKS) {
                    return;
                }
                if (scheduled && !ap.mayAct(IAutopilot.Reflex.FALL_CLUTCH, planner.useTick() + 1,
                        ladder.damageAverted())) {
                    ap.noteDeclined(IAutopilot.Reflex.FALL_CLUTCH, "another reflex goes first");
                    return;
                }
                if (!takeLease(ap, scheduled, ControlChannel.CAMERA, ControlChannel.HOTBAR,
                        ControlChannel.WORLD_INTERACT)) {
                    return;
                }
                if (scheduled) {
                    ap.noteActed(IAutopilot.Reflex.FALL_CLUTCH, "clutch");
                }
                plans++;
                note = "planned";
                lastLandDamage = planner.landDamage();
                kind = planner.kind();
                slot = kindSlot[kind.ordinal()];
                heading = yaw;
                returnYaw = yaw;
                returnPitch = p.getXRot();
                ticks = 0;
                aim.reset();
                if (p.getInventory().getSelectedSlot() != slot) {
                    Services.input().selectHotbarSlot(lease, slot);
                }
                hasPlan = false;
                phase = Phase.POUR;
                pourStep(mc, p, now, userF, userS, w);
            }
            case POUR -> pourStep(mc, p, now, userF, userS, w);
            case PICKUP -> pickupStep(mc, p);
            case RETURN -> returnStep();
            case DEPLOY -> deployStep(p);
            case STEER -> steerStep(p);
            case GLIDE -> glideStep(mc, p);
            case FALLBACK -> fallbackStep(mc, p, now, userF, userS, w);
        }
    }

    // ---- fallback chain (C8) -------------------------------------------------------------------------------------------

    /** Bit set of the chain's new fallbacks in the hotbar; fills {@link #chainSlot}. */
    private int fallbacksAtHand(LocalPlayer p) {
        Inventory inv = p.getInventory();
        java.util.Arrays.fill(chainSlot, -1);
        int items = 0;
        for (int i = 0; i < 9; i++) {
            Item it = inv.getItem(i).getItem();
            ClutchChain.Fallback f = it instanceof net.minecraft.world.item.BoatItem ? ClutchChain.Fallback.BOAT
                    : it == Items.TWISTING_VINES ? ClutchChain.Fallback.TWISTING_VINES
                    : it == Items.WIND_CHARGE && !p.getCooldowns().isOnCooldown(inv.getItem(i))
                    ? ClutchChain.Fallback.WIND_CHARGE : null;
            if (f != null && chainSlot[f.ordinal()] < 0) {
                chainSlot[f.ordinal()] = i;
                items |= f.bit();
            }
        }
        return items & ClutchChain.NEW_FALLBACKS;
    }

    /** Chooses on the planner's current landing prediction; false when nothing is feasible. */
    private boolean chooseFallback(Minecraft mc, LocalPlayer p, int items, MovementPhysics.Voxels w) {
        if (!planner.lands()) {
            return false;
        }
        planner.heights(chainHeights);
        int lx = Mth.floor(planner.landX());
        int lz = Mth.floor(planner.landZ());
        int sy = Mth.floor(planner.landY() - 1.0e-6);
        int cy = Mth.floor(planner.landY() + 1.0e-6);
        boolean placeable = w.fullBlock(lx, sy, lz) && !w.solid(lx, cy, lz);
        sup.set(lx, sy, lz);
        return chain.choose(items, waterEvaporates(mc), chainHeights, planner.ticksToLand(),
                p.getXRot(), ParkourBlocks.PLAN_DEG_PER_TICK, placeable) != null;
    }

    private void tryFallback(Minecraft mc, LocalPlayer p, MovementPhysics now, IAutopilot ap, boolean scheduled,
                             MovementPhysics.Voxels w) {
        int items = fallbacksAtHand(p);
        if (items == 0 || !chooseFallback(mc, p, items, w)) {
            return;
        }
        // start the look-down only when its time comes (no lease many ticks early)
        int turn = FallClutchPlanner.turnTicks(Math.abs(chain.aimPitch() - p.getXRot()),
                ParkourBlocks.PLAN_DEG_PER_TICK);
        if (chain.useTick() > turn + LEAD_TICKS) {
            return;
        }
        if (scheduled && !ap.mayAct(IAutopilot.Reflex.FALL_CLUTCH, chain.useTick() + 1, planner.landDamage())) {
            ap.noteDeclined(IAutopilot.Reflex.FALL_CLUTCH, "another reflex goes first");
            return;
        }
        if (!takeLease(ap, scheduled, ControlChannel.CAMERA, ControlChannel.HOTBAR, ControlChannel.WORLD_INTERACT)) {
            return;
        }
        if (scheduled) {
            ap.noteActed(IAutopilot.Reflex.FALL_CLUTCH, "fallback clutch");
        }
        fallback = chain.chosen();
        slot = chainSlot[fallback.ordinal()];
        heading = p.getYRot();
        returnYaw = p.getYRot();
        returnPitch = p.getXRot();
        ticks = 0;
        aim.reset();
        note = "fallback planned";
        if (p.getInventory().getSelectedSlot() != slot) {
            Services.input().selectHotbarSlot(lease, slot);
        }
        phase = Phase.FALLBACK;
        fallbackStep(mc, p, now, 0, 0, w);
    }

    /**
     * Looks down (yaw kept, smoothed and capped by the core) and uses the fallback on its tick: a boat or twisting
     * vines on the landing block's top face once the crosshair is on it, a wind charge straight down.
     */
    private void fallbackStep(Minecraft mc, LocalPlayer p, MovementPhysics now, int userF, int userS,
                              MovementPhysics.Voxels w) {
        if (!leaseOk(ControlChannel.WORLD_INTERACT) || p.onGround() || p.isInWater() || ++ticks > 60
                || (1 << chainFallbackOf(p.getInventory().getItem(slot).getItem())) != fallback.bit()) {
            note = p.onGround() ? "landed before fallback" : "fallback item gone or lease lost";
            finish(p);
            return;
        }
        if (!planner.predictLanding(now, heading, p.isSprinting(), userF, userS, w)
                || !chooseFallback(mc, p, fallback.bit(), w)) {
            note = "fallback replan found nothing";
            finish(p);
            return;
        }
        aim.aim(Services.input(), lease, p.getYRot(), chain.aimPitch(), RotationMode.VISIBLE, 10f);
        if (p.getInventory().getSelectedSlot() != slot || chain.useTick() > 0) {
            return;
        }
        BlockHitResult bhr = null;
        if (fallback.placed) {
            HitResult hr = p.pick(REACH, 1.0f, false);
            if (!(hr instanceof BlockHitResult b) || b.getType() != HitResult.Type.BLOCK
                    || !b.getBlockPos().equals(sup) || b.getDirection() != Direction.UP) {
                note = "fallback aiming";
                return;
            }
            bhr = b;
        } else if (p.getXRot() < ClutchChain.THROW_MIN_PITCH) {
            note = "fallback aiming";
            return;
        }
        // direct game call: re-check the lease right before it
        if (!leaseOk(ControlChannel.WORLD_INTERACT)) {
            finish(p);
            return;
        }
        InteractionResult r = bhr != null && fallback == ClutchChain.Fallback.TWISTING_VINES
                ? mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr)
                : mc.gameMode.useItem(p, InteractionHand.MAIN_HAND); // boats and wind charges pick their own target
        lastUse = r;
        note = r.consumesAction() ? "fallback used" : "fallback refused";
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), false);
            fallbacks++;
        }
        startReturn();
    }

    /** Ordinal of the chain fallback an item is, or 31 (no bit) when none. */
    private static int chainFallbackOf(Item it) {
        if (it instanceof net.minecraft.world.item.BoatItem) {
            return ClutchChain.Fallback.BOAT.ordinal();
        }
        if (it == Items.TWISTING_VINES) {
            return ClutchChain.Fallback.TWISTING_VINES.ordinal();
        }
        return it == Items.WIND_CHARGE ? ClutchChain.Fallback.WIND_CHARGE.ordinal() : 31;
    }

    /** Fallback clutches used. */
    int fallbacks() {
        return fallbacks;
    }

    private boolean eligible(Minecraft mc, LocalPlayer p) {
        return !p.getAbilities().invulnerable && !p.getAbilities().flying && !p.isFallFlying() && !p.isPassenger()
                && !p.isSpectator() && !p.isInWater() && !p.onClimbable() && !p.isInPowderSnow
                && !Services.arbiter().isAutomationStopped();
    }

    /** 1.21.1 {@code DimensionType.ultraWarm()}: 26.3 moved it to the WATER_EVAPORATES environment attribute. */
    private static boolean waterEvaporates(Minecraft mc) {
        // positional attribute (biomes can override it): sample where the player is, not the dimension default
        return mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, mc.player.position());
    }

    /** Bit set of the clutch kinds in the hotbar that work here; fills {@link #kindSlot}. */
    private int kindsAtHand(Minecraft mc, LocalPlayer p) {
        int kinds = 0;
        Inventory inv = p.getInventory();
        for (FallClutchPlanner.Kind k : FallClutchPlanner.KINDS) {
            int s = -1;
            if (k == FallClutchPlanner.Kind.WATER ? !waterEvaporates(mc)
                    : useBlocks && !(k == FallClutchPlanner.Kind.SLIME && p.isShiftKeyDown())
                    && k.mode != FallClutchPlanner.Mode.CLIMB) {
                // ladders and vines are planned but not used yet: in the playtest the crosshair sat on the planned wall
                // face (within 0.1 degrees) and the click ray still missed it; see docs/ROADMAP.md
                s = hotbarSlot(inv, ITEMS[k.ordinal()]);
            }
            kindSlot[k.ordinal()] = s;
            if (s >= 0) {
                kinds |= k.bit();
            }
        }
        return kinds;
    }

    private void pourStep(Minecraft mc, LocalPlayer p, MovementPhysics now, int userF, int userS,
                          MovementPhysics.Voxels w) {
        if (!leaseOk(ControlChannel.WORLD_INTERACT) || p.onGround() || p.isInWater()
                || p.getInventory().getItem(slot).getItem() != ITEMS[kind.ordinal()]) {
            note = p.onGround() ? "landed before use" : "item gone or lease lost";
            finish(p); // landed (too late or in water), or the item is gone
            return;
        }
        float health = p.getHealth() + p.getAbsorptionAmount();
        if (!planner.plan(now, heading, p.isSprinting(), userF, userS, health, minDamage, p.getYRot(),
                p.getXRot(), ParkourBlocks.PLAN_DEG_PER_TICK, kind.bit(), w)) {
            // The head eases in slower near the end than planned and the real fall drifts a little from the
            // prediction: keep the last plan a tick or two and use it if the crosshair gets there.
            if (!hasPlan || ++graceTicks > PLAN_GRACE_TICKS) {
                note = "replan found nothing";
                finish(p); // no longer needed (or no longer possible)
                return;
            }
        } else {
            hasPlan = true;
            graceTicks = 0;
            planSupport = planner.support();
            planFace = planner.face();
            planCell = planner.cell();
            dbgUseTick = planner.useTick();
            dbgTicksToLand = planner.ticksToLand();
        }
        long support = planSupport;
        int faceId = planFace;
        dbgY = p.getY();
        dbgAngle = (float) BlockAssistPlanner.angle(p.getYRot(), p.getXRot(), planner.aimYaw(), planner.aimPitch());
        double hx = faceX(support, faceId, p.getX());
        double hy = faceY(support, faceId, p.getEyeY());
        double hz = faceZ(support, faceId, p.getZ());
        double dx = hx - p.getX();
        double dy = hy - p.getEyeY();
        double dz = hz - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = horiz < 0.35 ? p.getYRot() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        aim.aim(Services.input(), lease, yaw, pitch, RotationMode.VISIBLE, 10f);
        if (p.getInventory().getSelectedSlot() != slot) {
            return;
        }
        // the look right now (the use packet carries it): is the clicked face under the crosshair, within reach?
        HitResult hr = p.pick(REACH, 1.0f, false);
        sup.set(Packed.x(support), Packed.y(support), Packed.z(support));
        if (!(hr instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK
                || !bhr.getBlockPos().equals(sup) || bhr.getDirection() != Direction.from3DDataValue(faceId)) {
            note = "aiming";
            dbgPick = hr instanceof BlockHitResult b2 && b2.getType() == HitResult.Type.BLOCK
                    ? (b2.getBlockPos().equals(sup) ? 2 : 1) : 0;
            return;
        }
        // direct game call: re-check the lease right before it
        if (!leaseOk(ControlChannel.WORLD_INTERACT)) {
            finish(p);
            return;
        }
        // a water bucket picks its own target; blocks (and the powder snow bucket) are placed on the face clicked
        InteractionResult r = kind == FallClutchPlanner.Kind.WATER
                ? mc.gameMode.useItem(p, InteractionHand.MAIN_HAND)
                : mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
        lastUse = r;
        note = r.consumesAction() ? "used" : "use refused";
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), false);
            clutches++;
            kindCounts[kind.ordinal()]++;
            poured = planCell;
            boolean bucket = kind == FallClutchPlanner.Kind.WATER || kind == FallClutchPlanner.Kind.POWDER_SNOW;
            if (!bucket) {
                startReturn();
                return;
            }
            phase = Phase.PICKUP;
            ticks = 0;
            settledTicks = 0;
            settleWait = SETTLE_MIN_TICKS + p.getRandom().nextInt(SETTLE_MAX_TICKS - SETTLE_MIN_TICKS + 1);
            aim.reset();
        }
    }

    /** In (or on) the poured water or powder snow: wait for the server to see it, then scoop it back up. */
    private void pickupStep(Minecraft mc, LocalPlayer p) {
        if (!pickup || !leaseOk(ControlChannel.WORLD_INTERACT) || ++ticks > MAX_PICKUP_TICKS) {
            startReturn();
            return;
        }
        water.set(Packed.x(poured), Packed.y(poured), Packed.z(poured));
        boolean snow = kind == FallClutchPlanner.Kind.POWDER_SNOW;
        if (snow ? !mc.level.getBlockState(water).is(Blocks.POWDER_SNOW)
                : !mc.level.getFluidState(water).is(FluidTags.WATER) || !mc.level.getFluidState(water).isSource()) {
            startReturn(); // flowed away or already gone
            return;
        }
        if (!(p.onGround() || p.isInWater() || p.isInPowderSnow)) {
            settledTicks = 0;
            return;
        }
        if (settledTicks++ == 0) {
            settleX = p.getX();
            settleZ = p.getZ();
        }
        if (!scoopStillWanted(p)) {
            startReturn();
            return;
        }
        if (settledTicks <= settleWait) {
            return;
        }
        int s = p.getInventory().getItem(slot).getItem() == Items.BUCKET ? slot
                : hotbarSlot(p.getInventory(), Items.BUCKET);
        if (s < 0) {
            startReturn();
            return;
        }
        if (p.getInventory().getSelectedSlot() != s) {
            Services.input().selectHotbarSlot(lease, s);
            return;
        }
        double dx = water.getX() + 0.5 - p.getX();
        double dy = water.getY() + 0.5 - p.getEyeY();
        double dz = water.getZ() + 0.5 - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = horiz < 0.35 ? p.getYRot() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        aim.aim(Services.input(), lease, yaw, pitch, RotationMode.VISIBLE, 10f);
        HitResult hr = p.pick(REACH, 1.0f, !snow);
        if (!(hr instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK
                || !bhr.getBlockPos().equals(water)) {
            return;
        }
        if (!leaseOk(ControlChannel.WORLD_INTERACT)) {
            startReturn();
            return;
        }
        InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getInteractAnimation(), false);
            pickups++;
        }
        startReturn();
    }

    /** The user stayed put since settling and the source is still within reach (checked from the stored cell). */
    private boolean scoopStillWanted(LocalPlayer p) {
        double mx = p.getX() - settleX;
        double mz = p.getZ() - settleZ;
        if (mx * mx + mz * mz > SCOOP_MOVE_LIMIT * SCOOP_MOVE_LIMIT) {
            return false; // walked away: leave it
        }
        double dx = water.getX() + 0.5 - p.getX();
        double dy = water.getY() + 0.5 - p.getEyeY();
        double dz = water.getZ() + 0.5 - p.getZ();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private void finish(LocalPlayer p) {
        if (lease != null && lease.isActive() && lease.covers(ControlChannel.CAMERA)) {
            startReturn();
        } else {
            release();
        }
    }

    private void startReturn() {
        phase = Phase.RETURN;
        ticks = 0;
        aim.reset();
    }

    private void returnStep() {
        if (!leaseOk(ControlChannel.CAMERA) || ++ticks > MAX_RETURN_TICKS) {
            release();
            return;
        }
        aim.aim(Services.input(), lease, returnYaw, Math.min(returnPitch, 25f), RotationMode.VISIBLE, 1.0f);
        if (Services.input().isRotationSettled(2f) && ticks > 1) {
            release();
        }
    }

    void release() {
        if (lease != null && lease.isActive()) {
            Services.input().clearRotation(lease);
            if (!chained) {
                lease.close();
            }
        }
        IAutopilot ap = Services.autopilotOrNull();
        if (ap != null && phase != Phase.IDLE && (scheduledRun || chained)) {
            ap.noteDone(IAutopilot.Reflex.FALL_CLUTCH); // also ends a chained lease
        }
        chained = false;
        scheduledRun = false;
        lease = null;
        phase = Phase.IDLE;
        aim.reset();
    }

    /** Everything off: the clutch, the staging lease and the hazards this source reported. */
    void releaseAll() {
        release();
        staging.release();
        clearDanger(Services.danger());
    }

    /**
     * The autopilot's chain lease when it offers one covering the channels (clutch and scoop then share one lease),
     * else a lease of our own at EMERGENCY.
     */
    private boolean takeLease(IAutopilot ap, boolean scheduled, ControlChannel first, ControlChannel... rest) {
        scheduledRun = scheduled;
        if (scheduled) {
            ControlLease c = ap.chainLease(IAutopilot.Reflex.FALL_CLUTCH, MODULE_ID);
            boolean covers = c != null && c.isActive() && c.covers(first);
            for (int i = 0; covers && i < rest.length; i++) {
                covers = c.covers(rest[i]);
            }
            if (covers) {
                lease = c;
                chained = true;
                return true;
            }
        }
        var granted = Services.arbiter().request(LeaseRequest.of(MODULE_ID, ControlPriority.EMERGENCY, null,
                first, rest));
        if (granted.isEmpty()) {
            return false;
        }
        lease = granted.get();
        chained = false;
        return true;
    }

    // ---- water steering (clutch ladder) ------------------------------------------------------------------------------

    /**
     * Nearest water at the landing height within {@link #WATER_RADIUS} of the predicted landing (horizontal offset in
     * blocks, -1 when none). Fills {@link #waterX}/{@link #waterY}/{@link #waterZ}. At most 81 x 3 block reads.
     */
    private double findWater(Minecraft mc, double lx, double ly, double lz, int ticksLeft) {
        int cx = (int) Math.floor(lx);
        int cz = (int) Math.floor(lz);
        int cy = (int) Math.floor(ly + 1.0e-6);
        double best = -1.0;
        for (int dx = -WATER_RADIUS; dx <= WATER_RADIUS; dx++) {
            for (int dz = -WATER_RADIUS; dz <= WATER_RADIUS; dz++) {
                double off = Math.sqrt((cx + dx + 0.5 - lx) * (cx + dx + 0.5 - lx)
                        + (cz + dz + 0.5 - lz) * (cz + dz + 0.5 - lz));
                if (best >= 0 && off >= best) {
                    continue;
                }
                for (int y = cy - 1; y <= cy + 1; y++) {
                    column.set(cx + dx, y, cz + dz);
                    if (mc.level.getFluidState(column).is(FluidTags.WATER)
                            && mc.level.getBlockState(column.set(cx + dx, y + 1, cz + dz))
                            .getCollisionShape(mc.level, column).isEmpty()) {
                        best = off;
                        waterX = cx + dx;
                        waterY = y;
                        waterZ = cz + dz;
                        break;
                    }
                }
            }
        }
        return best;
    }

    private void startSteer(IAutopilot ap, boolean scheduled) {
        if (!takeLease(ap, scheduled, ControlChannel.MOVEMENT)) {
            return;
        }
        if (scheduled) {
            ap.noteActed(IAutopilot.Reflex.FALL_CLUTCH, "steer to water");
        }
        note = "steer to water";
        plans++;
        phase = Phase.STEER;
        ticks = 0;
        steerKeys.reset();
    }

    /** Hold the forward/back and strafe keys that move the body toward the water cell (keys only, no camera). */
    private void steerStep(LocalPlayer p) {
        if (!leaseOk(ControlChannel.MOVEMENT) || p.onGround() || p.isInWater() || ++ticks > 100) {
            if (p.isInWater()) {
                clutches++;
            }
            steerKeys.releaseAll(Services.input(), lease);
            release();
            return;
        }
        double dx = waterX + 0.5 - p.getX();
        double dz = waterZ + 0.5 - p.getZ();
        double yaw = Math.toRadians(p.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double fwd = -dx * sin + dz * cos;
        double left = dx * cos + dz * sin;
        IInputSmoother in = Services.input();
        steerKeys.set(in, lease, SimKey.FORWARD, fwd > 0.25);
        steerKeys.set(in, lease, SimKey.BACK, fwd < -0.25);
        steerKeys.set(in, lease, SimKey.LEFT, left > 0.25);
        steerKeys.set(in, lease, SimKey.RIGHT, left < -0.25);
    }

    // ---- elytra deploy (clutch ladder) and glide guard -------------------------------------------------------------

    private void startDeploy(IAutopilot ap, boolean scheduled) {
        if (!takeLease(ap, scheduled, ControlChannel.MOVEMENT)) {
            return;
        }
        if (scheduled) {
            ap.noteActed(IAutopilot.Reflex.FALL_CLUTCH, "elytra deploy");
        }
        note = "elytra deploy";
        plans++;
        phase = Phase.DEPLOY;
        ticks = 0;
        Services.input().scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
    }

    private void deployStep(LocalPlayer p) {
        if (!leaseOk(ControlChannel.MOVEMENT) || p.isFallFlying() || p.onGround() || p.isInWater()
                || ++ticks > DEPLOY_TICKS) {
            if (p.isFallFlying()) {
                clutches++;
            }
            release();
            return;
        }
        if (ticks == 3) {
            Services.input().scheduleKeyTap(lease, SimKey.JUMP, 1, 2); // the first tap can come a tick early
        }
    }

    /** Gliding: start a pull-up when the ground is close and the hit would hurt. */
    private void glideCheck(Minecraft mc, LocalPlayer p) {
        if (p.getDeltaMovement().y >= -0.3 || Services.arbiter().isAutomationStopped()
                || Services.arbiter().holder(ControlChannel.CAMERA).isPresent()) {
            return; // not sinking, or something else (the travel autopilot's elytra driver) flies
        }
        double height = heightAboveGround(mc, p, 24);
        double speed = p.getDeltaMovement().length();
        if (!GlideGuard.shouldPullUp(height, p.getDeltaMovement().y, speed, minDamage)) {
            return;
        }
        IAutopilot ap = Services.autopilotOrNull();
        boolean scheduled = ap != null && ap.isOn() && ap.allows(IAutopilot.Reflex.FALL_CLUTCH);
        if (!takeLease(ap, scheduled, ControlChannel.CAMERA)) {
            return;
        }
        note = "glide pull-up";
        phase = Phase.GLIDE;
        ticks = 0;
        calmGlideTicks = 0;
        aim.reset();
    }

    private void glideStep(Minecraft mc, LocalPlayer p) {
        if (!leaseOk(ControlChannel.CAMERA) || !p.isFallFlying() || ++ticks > 60) {
            release();
            return;
        }
        boolean danger = GlideGuard.shouldPullUp(heightAboveGround(mc, p, 24), p.getDeltaMovement().y,
                p.getDeltaMovement().length(), minDamage);
        calmGlideTicks = danger ? 0 : calmGlideTicks + 1;
        if (calmGlideTicks > 5) {
            release();
            return;
        }
        // keep the heading (look where you move), raise the nose through the capped smoother
        aim.aim(Services.input(), lease, p.getYRot(), GlideGuard.PULL_UP_PITCH, RotationMode.VISIBLE, 1.0f);
    }

    /** Blocks from the feet down to the first collision box, at most {@code max} (allocation free). */
    private double heightAboveGround(Minecraft mc, LocalPlayer p, int max) {
        int x = p.getBlockX();
        int z = p.getBlockZ();
        int y = p.getBlockY();
        int min = mc.level.getMinY();
        for (int d = 0; d <= max && y - d >= min; d++) {
            column.set(x, y - d, z);
            if (!mc.level.getBlockState(column).getCollisionShape(mc.level, column).isEmpty()
                    || !mc.level.getFluidState(column).isEmpty()) {
                return Math.max(0.0, p.getY() - (y - d + 1));
            }
        }
        return max + 1.0;
    }

    // ---- danger model feed: FALL and VOID ----------------------------------------------------------------------------

    private void feedDanger(Minecraft mc, LocalPlayer p, MovementPhysics now, int f, int s,
                            MovementPhysics.Voxels w) {
        IDangerModel d = Services.danger();
        if (d == IDangerModel.NONE) {
            return;
        }
        if (p.onGround() || p.isInWater() || p.getAbilities().invulnerable || p.getAbilities().flying
                || p.isFallFlying() || p.getDeltaMovement().y >= 0 || p.onClimbable()) {
            clearDanger(d);
            return;
        }
        float health = p.getHealth() + p.getAbsorptionAmount();
        int min = mc.level.getMinY();
        if (p.getY() < min) {
            // below the world: void damage until death
            int ticks = (int) Math.max(0.0, (p.getY() - (min - 64)) / Math.max(0.1, -p.getDeltaMovement().y));
            d.report(HazardKind.VOID, ticks, health, IDangerModel.NO_BEARING);
            voidReported = true;
            return;
        }
        if (planner.predictLanding(now, p.getYRot(), p.isSprinting(), f, s, w)) {
            if (voidReported) {
                d.clear(HazardKind.VOID);
                voidReported = false;
            }
            if (planner.landDamage() > 0f) {
                d.report(HazardKind.FALL, planner.ticksToLand(), planner.landDamage(), IDangerModel.NO_BEARING);
                fallReported = true;
            } else if (fallReported) {
                d.clear(HazardKind.FALL);
                fallReported = false;
            }
            return;
        }
        // no landing within the horizon: a void below? (one budgeted column scan every few ticks)
        if (++voidClock < VOID_SCAN_TICKS && voidReported) {
            return;
        }
        voidClock = 0;
        int x = p.getBlockX();
        int z = p.getBlockZ();
        boolean open = true;
        for (int y = p.getBlockY(); y >= min && open; y--) {
            column.set(x, y, z);
            open = mc.level.getBlockState(column).getCollisionShape(mc.level, column).isEmpty()
                    && mc.level.getFluidState(column).isEmpty();
        }
        if (open) {
            int ticks = (int) ((p.getY() - (min - 64)) / Math.max(0.1, -p.getDeltaMovement().y));
            d.report(HazardKind.VOID, ticks, health, IDangerModel.NO_BEARING);
            voidReported = true;
        } else {
            // a long fall onto something far below: lethal at this height
            d.report(HazardKind.FALL, FallClutchPlanner.HORIZON, health, IDangerModel.NO_BEARING);
            fallReported = true;
        }
    }

    private void clearDanger(IDangerModel d) {
        if (fallReported) {
            d.clear(HazardKind.FALL);
            fallReported = false;
        }
        if (voidReported) {
            d.clear(HazardKind.VOID);
            voidReported = false;
        }
    }

    private boolean leaseOk(ControlChannel ch) {
        return lease != null && lease.isActive() && lease.covers(ch);
    }

    /**
     * While the head is turned for the pour, keep the keys meaning what they meant when the fall was planned: the
     * core re-aims this tick's movement keys under the clutch's lease ({@code IInputSmoother.holdMoveHeading}).
     * Called from the movement-input listener, every tick.
     */
    void compensate() {
        if (phase == Phase.POUR) {
            Services.input().holdMoveHeading(lease, heading);
        }
    }

    /** Clutch item per {@link FallClutchPlanner.Kind}, by ordinal. */
    static final Item[] ITEMS = {Items.WATER_BUCKET, Items.POWDER_SNOW_BUCKET, Items.COBWEB, Items.SLIME_BLOCK,
            Items.LADDER, Items.VINE, Items.HAY_BLOCK};

    static int hotbarSlot(Inventory inv, Item item) {
        if (inv.getItem(inv.getSelectedSlot()).getItem() == item) {
            return inv.getSelectedSlot();
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static double faceX(long support, int face, double eye) {
        int dx = Direction.from3DDataValue(face).getStepX();
        return dx == 0 ? FallClutchPlanner.aimOnFace(Packed.x(support), eye) : Packed.x(support) + (dx > 0 ? 1.0 : 0.0);
    }

    private static double faceY(long support, int face, double eye) {
        int dy = Direction.from3DDataValue(face).getStepY();
        return dy == 0 ? FallClutchPlanner.aimOnFace(Packed.y(support), eye) : Packed.y(support) + (dy > 0 ? 1.0 : 0.0);
    }

    private static double faceZ(long support, int face, double eye) {
        int dz = Direction.from3DDataValue(face).getStepZ();
        return dz == 0 ? FallClutchPlanner.aimOnFace(Packed.z(support), eye) : Packed.z(support) + (dz > 0 ? 1.0 : 0.0);
    }

    int clutches() {
        return clutches;
    }

    int pickups() {
        return pickups;
    }

    int plans() {
        return plans;
    }

    /** Clutches done with one kind. */
    int clutches(FallClutchPlanner.Kind k) {
        return kindCounts[k.ordinal()];
    }

    String note() {
        return note + " [use " + dbgUseTick + "/land " + dbgTicksToLand + ", angle " + dbgAngle + ", pick "
                + dbgPick + ", y " + dbgY + ", kind " + kind + ", last use " + lastUse + "]";
    }

    float lastLandDamage() {
        return lastLandDamage;
    }
}
