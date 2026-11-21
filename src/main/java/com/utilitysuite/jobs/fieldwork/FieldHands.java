package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Optional;

/**
 * The hands of the field modules: one block action at a time, like a player. Holds a WORLD_INTERACT + CAMERA + HOTBAR
 * lease only while it has work, selects the hotbar slot through the core (swapping an item up from the main inventory
 * with a container click when needed), turns with a smoothed, capped rotation and acts when the crosshair is on the
 * target. Relaxed mode (the explicit less-legit setting) uses a silent rotation and a direct game call instead, after
 * re-checking the WORLD_INTERACT lease. Client thread only; allocation only when an action starts (one hit result).
 */
final class FieldHands implements LeaseListener {

    static final int RUNNING = 0;
    static final int DONE = 1;
    static final int FAILED = 2;

    private static final int AIM_TIMEOUT_TICKS = 30;
    private static final int BREAK_TIMEOUT_TICKS = 200;
    private static final int SWAP_HOTBAR_SLOT = 8;

    private final String ownerId;
    private final LeaseRequest request;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private ControlLease lease;
    private boolean preempted;

    // current action
    private long actionCell = Long.MIN_VALUE;
    private BlockPos actionPos;
    private boolean aimSet;
    private int ticks;
    private int cooldown;

    FieldHands(String ownerId, ControlPriority priority) {
        this.ownerId = ownerId;
        this.request = new LeaseRequest(ownerId, priority,
                EnumSet.of(ControlChannel.WORLD_INTERACT, ControlChannel.CAMERA, ControlChannel.HOTBAR), 0, this);
    }

    /** True when the lease is held (requests it when not). False while manual input or a higher module holds it. */
    boolean ensureLease() {
        if (lease != null && lease.isActive()) {
            return true;
        }
        lease = null;
        if (Services.modState().isAutomationStopped()) {
            return false;
        }
        Optional<ControlLease> l = Services.arbiter().request(request);
        if (l.isEmpty()) {
            return false;
        }
        lease = l.get();
        preempted = false;
        return true;
    }

    boolean hasLease() {
        return lease != null && lease.isActive();
    }

    /** Set when manual input or another module took the lease; the module stops its run. Cleared on read. */
    boolean consumePreempted() {
        boolean p = preempted;
        preempted = false;
        return p;
    }

    void release() {
        if (lease != null) {
            IInputSmoother input = Services.input();
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            input.releaseFor(lease);
            lease.close();
        }
        lease = null;
        resetAction();
    }

    void resetAction() {
        actionCell = Long.MIN_VALUE;
        actionPos = null;
        aimSet = false;
        ticks = 0;
    }

    /** Human pause before the next action (from the shared humanizer). */
    void pauseHuman(int medianTicks) {
        cooldown = Services.humanizer().reactionTicks(medianTicks, 1, medianTicks * 3);
    }

    boolean coolingDown() {
        if (cooldown > 0) {
            cooldown--;
            return true;
        }
        return false;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
            // The user's own input or Stop-All ends the run. A higher module taking the hands for a moment (the
            // survival autopilot's auto-tool swap on our attack, combat) only pauses it: the lease is re-requested.
            preempted = reason == LeaseEndReason.MANUAL_OVERRIDE || reason == LeaseEndReason.STOP_ALL;
            resetAction();
        }
    }

    // ---- items --------------------------------------------------------------------------------------------------

    /** Selects a hotbar slot through the core (used to hand the previous tool back after a placement). */
    void selectSlot(int slot) {
        if (hasLease() && slot >= 0 && slot < 9) {
            Services.input().selectHotbarSlot(lease, slot);
        }
    }

    /**
     * Gets the item into the selected hotbar slot. Returns DONE when it is selected, RUNNING while a swap or selection
     * is under way, FAILED when the player carries none.
     */
    int holdItem(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        if (inv.getSelected().is(item)) {
            return DONE;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).is(item)) {
                Services.input().selectHotbarSlot(lease, i);
                return RUNNING;
            }
        }
        IContainerClicker clicker = Services.clicker();
        if (clicker.pending() > 0) {
            return RUNNING;
        }
        for (int i = 9; i < 36; i++) {
            if (inv.items.get(i).is(item)) {
                if (Minecraft.getInstance().screen != null) {
                    return FAILED; // never click into a container the user has open
                }
                // inventory menu slot index of main-inventory slot i is i (9..35)
                clicker.queueClick(lease, i, SWAP_HOTBAR_SLOT, IContainerClicker.Click.SWAP);
                return RUNNING;
            }
        }
        return FAILED;
    }

    // ---- break --------------------------------------------------------------------------------------------------

    /**
     * Breaks the block at (x, y, z) like a player: smoothed visible look, ATTACK held while the crosshair is on it.
     * Relaxed: silent look and a direct destroy call. DONE when the cell is air (or replaceable), FAILED on timeout or
     * when line of sight is required and something else is in the way.
     */
    int tickBreak(Minecraft mc, LocalPlayer player, int x, int y, int z, boolean relaxed, boolean requireLos) {
        long cell = BlockPos.asLong(x, y, z);
        if (cell != actionCell) {
            resetAction();
            actionCell = cell;
        }
        IInputSmoother input = Services.input();
        cursor.set(x, y, z);
        if (mc.level.getBlockState(cursor).canBeReplaced()) {
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            resetAction();
            return DONE;
        }
        if (++ticks > BREAK_TIMEOUT_TICKS) {
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            resetAction();
            return FAILED;
        }
        if (!aimSet) {
            if (requireLos && !visible(mc, player, x, y, z)) {
                resetAction();
                return FAILED;
            }
            aimAt(player, input, x + 0.5, y + 0.5, z + 0.5, relaxed);
        }
        if (relaxed) {
            if (!lease.isActive() || !lease.covers(ControlChannel.WORLD_INTERACT)) {
                return FAILED;
            }
            Direction face = Direction.getNearest(player.getX() - (x + 0.5), player.getEyeY() - (y + 0.5),
                    player.getZ() - (z + 0.5));
            if (ticks == 1) {
                actionPos = cursor.immutable(); // the game keeps a reference: one position per action, not per tick
                mc.gameMode.startDestroyBlock(actionPos, face);
            } else if (actionPos != null) {
                mc.gameMode.continueDestroyBlock(actionPos, face);
            }
            player.swing(InteractionHand.MAIN_HAND);
            return RUNNING;
        }
        if (!input.isRotationSettled(3.0f)) {
            return RUNNING;
        }
        HitResult hr = mc.hitResult;
        boolean onTarget = hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().getX() == x && bhr.getBlockPos().getY() == y && bhr.getBlockPos().getZ() == z;
        if (!onTarget && ticks > AIM_TIMEOUT_TICKS && requireLos) {
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            resetAction();
            return FAILED;
        }
        input.setKeyHeld(lease, SimKey.ATTACK, onTarget);
        return RUNNING;
    }

    // ---- place --------------------------------------------------------------------------------------------------

    /**
     * Places the held item against face {@code face} of the support block at (sx, sy, sz): smoothed look at the face
     * centre, USE tapped once the crosshair is on that face. Relaxed: silent look and a direct use call after
     * re-checking the WORLD_INTERACT lease. DONE after one placement attempt, FAILED on aim timeout.
     */
    int tickPlace(Minecraft mc, LocalPlayer player, int sx, int sy, int sz, Direction face, boolean relaxed) {
        long cell = BlockPos.asLong(sx, sy, sz) ^ ((long) face.ordinal() << 60);
        if (cell != actionCell) {
            resetAction();
            actionCell = cell;
        }
        IInputSmoother input = Services.input();
        double hx = sx + 0.5 + face.getStepX() * 0.5;
        double hy = sy + 0.5 + face.getStepY() * 0.5;
        double hz = sz + 0.5 + face.getStepZ() * 0.5;
        if (!aimSet) {
            aimAt(player, input, hx, hy, hz, relaxed);
        }
        ticks++;
        if (relaxed) {
            if (!input.isRotationSettled(4.0f) && ticks < AIM_TIMEOUT_TICKS) {
                return RUNNING;
            }
            // Direct game call: re-check the lease right before it (it may have been revoked this tick).
            if (lease == null || !lease.isActive() || !lease.covers(ControlChannel.WORLD_INTERACT)) {
                return FAILED;
            }
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(hx, hy, hz), face, new BlockPos(sx, sy, sz), false));
            player.swing(InteractionHand.MAIN_HAND);
            input.clearRotation(lease);
            resetAction();
            return DONE;
        }
        if (!input.isRotationSettled(2.0f)) {
            if (ticks > AIM_TIMEOUT_TICKS) {
                resetAction();
                return FAILED;
            }
            return RUNNING;
        }
        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && bhr.getDirection() == face
                && bhr.getBlockPos().getX() == sx && bhr.getBlockPos().getY() == sy
                && bhr.getBlockPos().getZ() == sz) {
            input.scheduleKeyTap(lease, SimKey.USE, 1, 2);
            resetAction();
            return DONE;
        }
        if (ticks > AIM_TIMEOUT_TICKS) {
            resetAction();
            return FAILED; // something is in the line of sight
        }
        return RUNNING;
    }

    /** Clears the look so the camera goes back to the user between actions. */
    void clearLook() {
        if (lease != null) {
            Services.input().clearRotation(lease);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void aimAt(LocalPlayer player, IInputSmoother input, double hx, double hy, double hz, boolean silent) {
        double dx = hx - player.getX(), dy = hy - player.getEyeY(), dz = hz - player.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        input.setRotationTarget(lease, silent ? RotationTarget.silent(yaw, pitch) : RotationTarget.visible(yaw, pitch));
        aimSet = true;
    }

    /** Line of sight from the eye to the block (ray hits that block first). Allocates one clip; called per action. */
    static boolean visible(Minecraft mc, LocalPlayer player, int x, int y, int z) {
        Vec3 eye = player.getEyePosition();
        Vec3 to = new Vec3(x + 0.5, y + 0.5, z + 0.5);
        BlockHitResult r = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        return r.getType() == HitResult.Type.MISS
                || (r.getBlockPos().getX() == x && r.getBlockPos().getY() == y && r.getBlockPos().getZ() == z);
    }

    String ownerId() {
        return ownerId;
    }
}
