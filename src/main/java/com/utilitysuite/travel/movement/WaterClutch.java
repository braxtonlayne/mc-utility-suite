package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.CameraAim;
import com.utilitysuite.travel.Packed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Water bucket clutch, the in-game half of {@link WaterClutchPlanner}: falling toward a landing that would kill or
 * cost at least the configured damage, with a water bucket in the hotbar, take an EMERGENCY lease (CAMERA, HOTBAR,
 * WORLD_INTERACT), put the bucket in hand, turn the head down to the planned face (VISIBLE, through the capped
 * smoother: no snapping; the use packet carries the look), and pour the moment the face is under the crosshair within
 * reach. After landing in it, scoop the water back up (a pro never leaves a stray source) and turn back.
 * <p>
 * Re-plans every tick (the trajectory moves with the user's keys). Skipped in dimensions where water evaporates, for
 * players who take no fall damage, and while flying or gliding. The user's mouse preempts the lease (manual input
 * wins); Stop-All revokes it. Client thread only.
 */
final class WaterClutch {

    enum Phase { IDLE, POUR, PICKUP, RETURN }

    static final int MAX_PICKUP_TICKS = 20;
    static final int MAX_RETURN_TICKS = 20;
    /** Ticks in the water before scooping it up: the server must see the landing in water first. */
    static final int SETTLE_TICKS = 2;
    static final double REACH = 4.5;

    final WaterClutchPlanner planner = new WaterClutchPlanner();
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

    // settings
    float minDamage = 6f;
    boolean pickup = true;

    // counters
    private int clutches;
    private int pickups;
    private int plans;
    private float lastLandDamage;

    boolean active() {
        return phase != Phase.IDLE;
    }

    /** Once per movement-input tick, before the rest of the assist (it runs in water and while sneaking too). */
    void tick(Minecraft mc, LocalPlayer p, MovementPhysics now, int userF, int userS, MovementPhysics.Voxels w) {
        switch (phase) {
            case IDLE -> {
                if (p.onGround() || p.getDeltaMovement().y >= 0 || !eligible(mc, p)) {
                    return;
                }
                int s = hotbarSlot(p.getInventory(), Items.WATER_BUCKET);
                if (s < 0) {
                    return;
                }
                float health = p.getHealth() + p.getAbsorptionAmount();
                float yaw = p.getYRot();
                if (!planner.plan(now, yaw, p.isSprinting(), userF, userS, health, minDamage, yaw, p.getXRot(),
                        ParkourBlocks.PLAN_DEG_PER_TICK, w)) {
                    return;
                }
                // start turning while there is time: no need for a lease many ticks before the head must move
                double turn = BlockAssistPlanner.angle(yaw, p.getXRot(), planner.aimYaw(), planner.aimPitch());
                if (planner.useTick() > Math.ceil(turn / ParkourBlocks.PLAN_DEG_PER_TICK) + 3) {
                    return;
                }
                var granted = Services.arbiter().request(LeaseRequest.of("water_clutch", ControlPriority.EMERGENCY,
                        null, ControlChannel.CAMERA, ControlChannel.HOTBAR, ControlChannel.WORLD_INTERACT));
                if (granted.isEmpty()) {
                    return;
                }
                lease = granted.get();
                plans++;
                lastLandDamage = planner.landDamage();
                slot = s;
                heading = yaw;
                returnYaw = yaw;
                returnPitch = p.getXRot();
                ticks = 0;
                aim.reset();
                if (p.getInventory().selected != slot) {
                    Services.input().selectHotbarSlot(lease, slot);
                }
                phase = Phase.POUR;
                pourStep(mc, p, now, userF, userS, w);
            }
            case POUR -> pourStep(mc, p, now, userF, userS, w);
            case PICKUP -> pickupStep(mc, p);
            case RETURN -> returnStep();
        }
    }

    private boolean eligible(Minecraft mc, LocalPlayer p) {
        return !p.getAbilities().invulnerable && !p.getAbilities().flying && !p.isFallFlying() && !p.isPassenger()
                && !p.isSpectator() && !p.isInWater() && !p.onClimbable()
                && !mc.level.dimensionType().ultraWarm() && !Services.arbiter().isAutomationStopped();
    }

    private void pourStep(Minecraft mc, LocalPlayer p, MovementPhysics now, int userF, int userS,
                          MovementPhysics.Voxels w) {
        if (!leaseOk(ControlChannel.WORLD_INTERACT) || p.onGround() || p.isInWater()
                || p.getInventory().getItem(slot).getItem() != Items.WATER_BUCKET) {
            finish(p); // landed (too late or in water), or the bucket is gone
            return;
        }
        float health = p.getHealth() + p.getAbsorptionAmount();
        if (!planner.plan(now, heading, p.isSprinting(), userF, userS, health, minDamage, p.getYRot(),
                p.getXRot(), ParkourBlocks.PLAN_DEG_PER_TICK, w)) {
            finish(p); // no longer needed (or no longer possible)
            return;
        }
        long support = planner.support();
        int faceId = planner.face();
        double hx = faceX(support, faceId);
        double hy = faceY(support, faceId);
        double hz = faceZ(support, faceId);
        double dx = hx - p.getX();
        double dy = hy - p.getEyeY();
        double dz = hz - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = horiz < 0.35 ? p.getYRot() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        aim.aim(Services.input(), lease, yaw, pitch, RotationMode.VISIBLE, 10f);
        if (p.getInventory().selected != slot) {
            return;
        }
        // the look right now (the use packet carries it): is the clicked face under the crosshair, within reach?
        HitResult hr = p.pick(REACH, 1.0f, false);
        sup.set(Packed.x(support), Packed.y(support), Packed.z(support));
        if (!(hr instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK
                || !bhr.getBlockPos().equals(sup) || bhr.getDirection() != Direction.from3DDataValue(faceId)) {
            return;
        }
        // direct game call: re-check the lease right before it
        if (!leaseOk(ControlChannel.WORLD_INTERACT)) {
            finish(p);
            return;
        }
        InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
            clutches++;
            poured = planner.waterCell();
            phase = Phase.PICKUP;
            ticks = 0;
            aim.reset();
        }
    }

    /** In (or onto) the poured water: wait for the server to see it, then scoop the source back up. */
    private void pickupStep(Minecraft mc, LocalPlayer p) {
        if (!pickup || !leaseOk(ControlChannel.WORLD_INTERACT) || ++ticks > MAX_PICKUP_TICKS) {
            startReturn();
            return;
        }
        water.set(Packed.x(poured), Packed.y(poured), Packed.z(poured));
        if (!mc.level.getFluidState(water).is(FluidTags.WATER) || !mc.level.getFluidState(water).isSource()) {
            startReturn(); // flowed away or already gone
            return;
        }
        if (!(p.onGround() || p.isInWater()) || ticks <= SETTLE_TICKS) {
            return;
        }
        int s = p.getInventory().getItem(slot).getItem() == Items.BUCKET ? slot
                : hotbarSlot(p.getInventory(), Items.BUCKET);
        if (s < 0) {
            startReturn();
            return;
        }
        if (p.getInventory().selected != s) {
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
        HitResult hr = p.pick(REACH, 1.0f, true);
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
            p.swing(InteractionHand.MAIN_HAND);
            pickups++;
        }
        startReturn();
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
            lease.close();
        }
        lease = null;
        phase = Phase.IDLE;
        aim.reset();
    }

    private boolean leaseOk(ControlChannel ch) {
        return lease != null && lease.isActive() && lease.covers(ch);
    }

    /** While the head is turned for the pour, keep the keys meaning what they meant when the fall was planned. */
    void compensate(Input in, float cameraYaw) {
        if (phase == Phase.POUR) {
            ParkourBlocks.rotateImpulses(in, cameraYaw, heading);
        }
    }

    static int hotbarSlot(Inventory inv, Item item) {
        if (inv.getItem(inv.selected).getItem() == item) {
            return inv.selected;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static double faceX(long support, int face) {
        int dx = Direction.from3DDataValue(face).getStepX();
        return Packed.x(support) + (dx == 0 ? 0.5 : (dx > 0 ? 1.0 : 0.0));
    }

    private static double faceY(long support, int face) {
        int dy = Direction.from3DDataValue(face).getStepY();
        return Packed.y(support) + (dy == 0 ? 0.5 : (dy > 0 ? 1.0 : 0.0));
    }

    private static double faceZ(long support, int face) {
        int dz = Direction.from3DDataValue(face).getStepZ();
        return Packed.z(support) + (dz == 0 ? 0.5 : (dz > 0 ? 1.0 : 0.0));
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

    float lastLandDamage() {
        return lastLandDamage;
    }
}
