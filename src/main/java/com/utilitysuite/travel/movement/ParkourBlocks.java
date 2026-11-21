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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Parkour with blocks, the in-game half of {@link BlockAssistPlanner}: while the user is in the air after a jump with
 * forward held, ask the planner whether one block keeps the run going; if so take a short lease (CAMERA, HOTBAR,
 * WORLD_INTERACT), put a block in hand, turn the head to the planned face (VISIBLE, through the capped smoother: no
 * snapping), place it the moment the crosshair is on it, then turn back toward the run and let go.
 * <p>
 * While the camera turns in the air, {@link #compensate} re-expresses the user's movement keys relative to the new yaw
 * (fractional impulses), so turning the head does not bend the jump. The user's own mouse preempts the lease (manual
 * input wins); Stop-All revokes it. Client thread only.
 */
final class ParkourBlocks {

    enum Phase { IDLE, AIM, RETURN }

    static final int MAX_AIM_TICKS = 20;
    static final int MAX_RETURN_TICKS = 20;
    /** Per-tick turn budget the planner assumes: a little under the hard cap (the curve eases in). */
    static final double PLAN_DEG_PER_TICK = IInputSmoother.MAX_TURN_DEG_PER_SEC * 0.05 * 0.75;

    final BlockAssistPlanner planner = new BlockAssistPlanner();
    private final CameraAim aim = new CameraAim(0.3f);
    private final double[] face = new double[3];
    private final BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos sup = new BlockPos.MutableBlockPos();

    private Phase phase = Phase.IDLE;
    private ControlLease lease;
    private long cell;
    private long support;
    private int faceId;
    private int slot = -1;
    private int ticks;
    /** Yaw the user's keys are relative to (their camera yaw when the plan started). */
    private float heading;
    private float returnYaw;
    private float returnPitch;
    private boolean naturalLook = true;

    // counters
    private int placed;
    private int plans;
    private int aborted;

    void setNaturalLook(boolean natural) {
        naturalLook = natural;
        planner.headingWeight = natural ? BlockAssistPlanner.NATURAL_HEADING_WEIGHT : 0.0;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    /**
     * Once per movement-input tick (after the jump/steer decisions).
     *
     * @param jumpAir the player is airborne from a jump (not a fall or a knockback)
     */
    void tick(Minecraft mc, LocalPlayer p, MovementPhysics now, boolean sprinting, int userF, int userS,
              float health, boolean jumpAir, MovementPhysics.Voxels w) {
        switch (phase) {
            case IDLE -> {
                if (!jumpAir || now.onGround || userF <= 0) {
                    return;
                }
                int s = blockSlot(p.getInventory(), mc.level);
                if (s < 0) {
                    return;
                }
                IInputSmoother input = Services.input();
                float yaw = p.getYRot();
                if (!planner.plan(now, yaw, sprinting, userF, userS, health, yaw, p.getXRot(), PLAN_DEG_PER_TICK,
                        p.getInventory().selected == s ? 0 : 1, w)) {
                    return;
                }
                var granted = Services.arbiter().request(LeaseRequest.of("movement_blocks", ControlPriority.TRAVEL,
                        null, ControlChannel.CAMERA, ControlChannel.HOTBAR, ControlChannel.WORLD_INTERACT));
                if (granted.isEmpty()) {
                    return;
                }
                lease = granted.get();
                plans++;
                cell = planner.cell();
                support = planner.support();
                faceId = planner.face();
                slot = s;
                heading = yaw;
                returnYaw = yaw;
                returnPitch = p.getXRot();
                ticks = 0;
                aim.reset();
                if (p.getInventory().selected != slot) {
                    input.selectHotbarSlot(lease, slot);
                }
                phase = Phase.AIM;
                aimStep(mc, p);
            }
            case AIM -> aimStep(mc, p);
            case RETURN -> returnStep(p);
        }
    }

    private void aimStep(Minecraft mc, LocalPlayer p) {
        if (!leaseOk(ControlChannel.WORLD_INTERACT) || ++ticks > MAX_AIM_TICKS || p.onGround()) {
            abort(p);
            return;
        }
        target.set(Packed.x(cell), Packed.y(cell), Packed.z(cell));
        if (!mc.level.getBlockState(target).canBeReplaced()) {
            abort(p); // filled meanwhile
            return;
        }
        // the body may still be in the cell (rising out of it on a wall jump): aim now, place once it is clear
        boolean occupied = p.getBoundingBox().intersects(target.getX(), target.getY(), target.getZ(),
                target.getX() + 1, target.getY() + 1, target.getZ() + 1);
        BlockAssistPlanner.faceCenter(support, faceId, face);
        double dx = face[0] - p.getX();
        double dy = face[1] - p.getEyeY();
        double dz = face[2] - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        IInputSmoother input = Services.input();
        float speed = 10f; // as fast as allowed: the smoother clamps every turn to the no-snap cap
        aim.aim(input, lease, yaw, pitch, RotationMode.VISIBLE, speed);
        if (p.getInventory().selected != slot || occupied) {
            return;
        }
        HitResult hr = mc.hitResult;
        Direction dir = Direction.from3DDataValue(faceId);
        sup.set(Packed.x(support), Packed.y(support), Packed.z(support));
        if (!(hr instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK
                || !bhr.getBlockPos().equals(sup) || bhr.getDirection() != dir) {
            return; // crosshair not on the face yet
        }
        // direct game call: re-check the lease right before it (rule for silent/direct placement)
        if (!leaseOk(ControlChannel.WORLD_INTERACT)) {
            abort(p);
            return;
        }
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
            placed++;
        }
        startReturn();
    }

    private void startReturn() {
        phase = Phase.RETURN;
        ticks = 0;
        aim.reset();
    }

    private void returnStep(LocalPlayer p) {
        if (!leaseOk(ControlChannel.CAMERA) || ++ticks > MAX_RETURN_TICKS) {
            release();
            return;
        }
        // natural: back to facing the run; free: back to where the user was looking
        float pitch = naturalLook ? Math.min(returnPitch, 25f) : returnPitch;
        aim.aim(Services.input(), lease, returnYaw, pitch, RotationMode.VISIBLE, 1.0f);
        if (Services.input().isRotationSettled(2f) && ticks > 1) {
            release();
        }
    }

    private void abort(LocalPlayer p) {
        aborted++;
        if (lease != null && lease.isActive() && lease.covers(ControlChannel.CAMERA)) {
            startReturn();
        } else {
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

    /**
     * While the camera is turned for a placement, keep the keys meaning what they meant at the jump: rotate the
     * (strafe, forward) impulse from the jump yaw into the current yaw.
     */
    void compensate(Input in, float cameraYaw) {
        if (phase != Phase.IDLE) {
            rotateImpulses(in, cameraYaw, heading);
        }
    }

    /** Rotates the (strafe, forward) impulse from yaw {@code heading} into {@code cameraYaw}. */
    static void rotateImpulses(Input in, float cameraYaw, float heading) {
        double d = Math.toRadians(net.minecraft.util.Mth.wrapDegrees(cameraYaw - heading));
        double f = in.forwardImpulse;
        double s = in.leftImpulse;
        double cos = Math.cos(d);
        double sin = Math.sin(d);
        in.forwardImpulse = (float) (f * cos - s * sin);
        in.leftImpulse = (float) (s * cos + f * sin);
        in.up = in.forwardImpulse > 0.05f;
        in.down = in.forwardImpulse < -0.05f;
        in.left = in.leftImpulse > 0.05f;
        in.right = in.leftImpulse < -0.05f;
    }

    /** Hotbar slot with a plain full block (no falling blocks), the selected one first; -1 if none. */
    static int blockSlot(Inventory inv, Level level) {
        if (isPlainBlock(inv.getItem(inv.selected), level)) {
            return inv.selected;
        }
        for (int i = 0; i < 9; i++) {
            if (isPlainBlock(inv.getItem(i), level)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPlainBlock(ItemStack st, Level level) {
        if (st.isEmpty() || !(st.getItem() instanceof BlockItem bi)) {
            return false;
        }
        Block b = bi.getBlock();
        return !(b instanceof FallingBlock) && b.defaultBlockState().isCollisionShapeFullBlock(level, BlockPos.ZERO)
                && !b.defaultBlockState().hasBlockEntity();
    }

    int placed() {
        return placed;
    }

    int plans() {
        return plans;
    }

    int aborted() {
        return aborted;
    }

}
