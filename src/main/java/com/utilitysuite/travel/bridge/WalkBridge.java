package com.utilitysuite.travel.bridge;

import com.utilitysuite.core.ServerAllowList;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.LeaseSlot;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.TravelMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bridge Assist WALK mode: the player walks (or sprints, jumps) with their own keys and camera; this fills the deck
 * cells ahead of them. Never takes MOVEMENT, so the user's own keys never preempt it, and the camera only in the
 * packet sense (SILENT look, server pose).
 * <ul>
 *   <li><b>Pace</b> LEGIT: one block when the feet come within the edge margin, gaussian spacing, waits for the server
 *       look to settle. INSTANT: every missing cell along the next ticks of travel, up to {@link #MAX_PER_TICK} a tick,
 *       no waiting.</li>
 *   <li><b>Server look</b> REAL: the server sees your camera. BRIDGER: the server sees you facing back down at the
 *       deck like a backward bridger. FACE: the server sees you aim at each block's face.</li>
 *   <li><b>Server sneak</b> OFF / AT_EDGE (crouch for a few ticks around each placement, like ninja bridging) /
 *       ALWAYS; <b>hide sprint</b> reports walking while you sprint. Local movement is unchanged by any of these.</li>
 *   <li><b>Tower</b>: jumping straight up places under you.</li>
 * </ul>
 * Packet options and INSTANT pace run only in single-player or on allow-listed servers (Tools tab).
 */
final class WalkBridge {

    enum Pace { LEGIT, INSTANT }

    enum ServerLook { REAL, BRIDGER, FACE }

    enum ServerSneak { OFF, AT_EDGE, ALWAYS }

    static final int MAX_PER_TICK = 3;
    private static final float BRIDGER_PITCH_MIN = 72f;
    private static final float BRIDGER_PITCH_MAX = 84f;
    private static final float SNAP_SPEED = 12f;

    // settings (set by BridgeAssist)
    Pace pace = Pace.LEGIT;
    ServerLook look = ServerLook.BRIDGER;
    ServerSneak sneak = ServerSneak.AT_EDGE;
    boolean hideSprint = true;
    boolean tower = false;
    int lookahead = 3;
    double delayMean = 4.0;
    double delayStd = 1.2;

    private final LeaseSlot slot = new LeaseSlot(BridgeAssist.ID + ".walk", Services::arbiter);
    private final LeaseRequest request = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.CAMERA,
            ControlChannel.WORLD_INTERACT, ControlChannel.HOTBAR);
    private final WalkBridgePlanner planner = new WalkBridgePlanner();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final WalkBridgePlanner.Cells cells = this::replaceable;
    private Level level;

    private boolean running;
    private int deckY;
    private int groundedElsewhere;
    private int delay;
    private int sneakTicks;
    private int placed;
    private int previousSlot = -1;
    private boolean allowedCached;
    private int allowedCheckIn;
    private boolean pausedNotified;
    private boolean aimed;
    private boolean urgent;
    private float aimYaw;
    private float aimPitch;

    boolean isRunning() {
        return running;
    }

    int placed() {
        return placed;
    }

    /** @return a message key when the start is refused, else null */
    String start(LocalPlayer player) {
        if (needsAllowList() && !allowed(Minecraft.getInstance())) {
            return "message.utilitysuite.bridge.walk_not_allowed";
        }
        deckY = player.getBlockY() - 1;
        groundedElsewhere = 0;
        delay = 0;
        sneakTicks = 0;
        placed = 0;
        previousSlot = player.getInventory().selected;
        allowedCheckIn = 0;
        pausedNotified = false;
        aimed = false;
        slot.reset();
        running = true;
        return null;
    }

    /** Stops and hands everything back (look, pose, the slot the user had selected). */
    void stop() {
        ControlLease lease = slot.active();
        IInputSmoother input = Services.input();
        if (lease != null) {
            input.clearRotation(lease);
            input.setServerPose(lease, null, null);
            if (previousSlot >= 0) {
                input.selectHotbarSlot(lease, previousSlot);
            }
        }
        slot.reset();
        running = false;
        previousSlot = -1;
    }

    void worldUnload() {
        running = false;
        slot.reset();
        level = null;
    }

    boolean terminated() {
        return slot.terminated();
    }

    /** Packet options and the instant pace are the parts that need single-player or the allow list. */
    boolean needsAllowList() {
        return pace == Pace.INSTANT || look != ServerLook.REAL || sneak != ServerSneak.OFF || hideSprint;
    }

    /**
     * One client tick (only while running).
     *
     * @return a message key to finish with, or null to keep going
     */
    String tick(Minecraft mc, LocalPlayer player, int hotbarBlocks, int reserve, int budget,
                java.util.function.IntSupplier placeableSlot) {
        if (slot.terminated()) {
            return "message.utilitysuite.bridge.stopped";
        }
        if (needsAllowList() && --allowedCheckIn <= 0) {
            allowedCheckIn = 100; // the server can't change mid-session often; re-check every 5 s
            if (!allowed(mc)) {
                return "message.utilitysuite.bridge.walk_not_allowed";
            }
        }
        ControlLease lease = slot.ensure(request);
        if (lease == null) {
            if (slot.isSuspended() && !pausedNotified) {
                pausedNotified = true;
                TravelMessages.status("message.utilitysuite.bridge.paused");
            }
            return null;
        }
        pausedNotified = false;
        if (budget > 0 && placed >= budget) {
            return "message.utilitysuite.bridge.budget";
        }
        if (hotbarBlocks <= reserve) {
            return "message.utilitysuite.bridge.hotbar_low";
        }
        level = mc.level;
        IInputSmoother input = Services.input();

        // Re-base the deck when the player settles on ground at another height (stairs up, a drop).
        int under = player.getBlockY() - 1;
        if (player.onGround() && under != deckY) {
            if (++groundedElsewhere > 10) {
                deckY = under;
                groundedElsewhere = 0;
            }
        } else {
            groundedElsewhere = 0;
        }

        Vec3 v = player.getDeltaMovement();
        double vx = v.x, vz = v.z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        boolean moving = speed > 0.03;
        double moveYaw = moving ? Angles.yawTo(vx, vz) : player.getYRot();

        // Server pose: crouch around placements (AT_EDGE), always, or not; optionally hide sprinting.
        if (sneakTicks > 0) {
            sneakTicks--;
        }
        Boolean sneakState = switch (sneak) {
            case OFF -> null;
            case ALWAYS -> Boolean.TRUE;
            case AT_EDGE -> sneakTicks > 0;
        };
        input.setServerPose(lease, sneakState, hideSprint ? Boolean.FALSE : null);

        // Plan the cells to fill (tower: straight up while jumping in place).
        int n;
        urgent = false;
        boolean towering = tower && !player.onGround() && v.y > 0.05 && speed < 0.08;
        if (towering) {
            int ty = (int) Math.floor(player.getY()) - 1;
            n = 0;
            if (player.getY() - ty >= 1.05 && replaceable(player.getBlockX(), ty, player.getBlockZ())) {
                deckY = ty;
                n = planner.plan(cells, player.getX(), player.getZ(), 0, 0, deckY, 0, 0);
            }
        } else if (pace == Pace.INSTANT) {
            n = planner.plan(cells, player.getX(), player.getZ(), vx, vz, deckY, lookahead, 0.6);
        } else {
            // LEGIT: only at the edge; use the facing of the movement even when slowed by sneaking. "Urgent" = the
            // feet are about to lose the deck: then spacing and the look wait are skipped so the player never drops.
            double ux = moving ? vx / speed : 0, uz = moving ? vz / speed : 0;
            urgent = planner.plan(cells, player.getX(), player.getZ(), ux * 0.05, uz * 0.05, deckY, 0,
                    0.12 + speed) > 0;
            n = planner.plan(cells, player.getX(), player.getZ(), ux * 0.05, uz * 0.05, deckY, 0, 0.35 + speed);
        }

        // The look the server sees.
        if (look == ServerLook.BRIDGER && (moving || n > 0)) {
            double back = moveYaw + 180.0;
            float pitch = (float) Math.max(BRIDGER_PITCH_MIN, Math.min(BRIDGER_PITCH_MAX,
                    Angles.pitchTo(0.9, deckY + 1.0 - player.getEyeY())));
            aimSilent(input, lease, (float) back, pitch);
        } else if (look == ServerLook.REAL || (!moving && n == 0)) {
            if (aimed) {
                input.clearRotation(lease);
                aimed = false;
            }
        }

        if (delay > 0) {
            delay--;
        }
        if (n == 0) {
            return null;
        }
        if (pace == Pace.LEGIT && delay > 0 && !urgent) {
            return null;
        }
        int wanted = placeableSlot.getAsInt();
        if (player.getInventory().selected != wanted) {
            input.selectHotbarSlot(lease, wanted);
            return null; // lands next tick
        }
        int budgetLeft = budget > 0 ? budget - placed : Integer.MAX_VALUE;
        int perTick = pace == Pace.INSTANT ? Math.min(MAX_PER_TICK, budgetLeft) : 1;
        int done = 0;
        double reach = player.blockInteractionRange();
        for (int i = 0; i < n && done < perTick; i++) {
            long c = planner.cell(i);
            int tx = Packed.x(c), ty = Packed.y(c), tz = Packed.z(c);
            if (!replaceable(tx, ty, tz)) {
                continue; // filled by an earlier placement this tick
            }
            int face = supportFace(tx, ty, tz, player);
            if (face < 0) {
                break; // nothing to click against yet: the cells before it come first
            }
            Direction toSupport = Direction.from3DDataValue(face);
            double hx = tx + 0.5 + toSupport.getStepX() * 0.5;
            double hy = ty + 0.5 + toSupport.getStepY() * 0.5;
            double hz = tz + 0.5 + toSupport.getStepZ() * 0.5;
            double ex = hx - player.getX(), ey = hy - player.getEyeY(), ez = hz - player.getZ();
            if (ex * ex + ey * ey + ez * ez > reach * reach) {
                break;
            }
            if (look == ServerLook.FACE) {
                double horiz = Math.sqrt(ex * ex + ez * ez);
                aimSilent(input, lease, (float) Angles.yawTo(ex, ez), (float) Angles.pitchTo(horiz, ey));
                if (pace == Pace.LEGIT && !urgent && !input.isRotationSettled(3f)) {
                    return null; // let the server see the aim before the click
                }
            } else if (look == ServerLook.BRIDGER && pace == Pace.LEGIT && !urgent && !input.isRotationSettled(4f)) {
                return null;
            }
            // Direct game call, so re-check the lease here (it may have been revoked earlier this tick).
            if (!lease.isActive() || !lease.covers(ControlChannel.WORLD_INTERACT)) {
                return null;
            }
            BlockPos support = new BlockPos(tx + toSupport.getStepX(), ty + toSupport.getStepY(),
                    tz + toSupport.getStepZ());
            InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(hx, hy, hz), toSupport.getOpposite(), support, false));
            if (r.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
                placed++;
                done++;
                if (sneak == ServerSneak.AT_EDGE) {
                    sneakTicks = input.nextGaussianTicks(4, 1.2, 2, 8);
                }
            } else {
                break;
            }
        }
        if (done > 0 && pace == Pace.LEGIT) {
            delay = input.nextGaussianTicks(delayMean, delayStd, 1, (int) Math.ceil(delayMean * 3) + 1);
        }
        return null;
    }

    /** Sets the SILENT look only when it moved by more than half a degree (no per-tick target allocation). */
    private void aimSilent(IInputSmoother input, ControlLease lease, float yaw, float pitch) {
        if (aimed && Math.abs(Angles.delta(aimYaw, yaw)) < 0.5 && Math.abs(aimPitch - pitch) < 0.5) {
            return;
        }
        aimYaw = yaw;
        aimPitch = pitch;
        aimed = input.setRotationTarget(lease, new RotationTarget(yaw, pitch, RotationMode.SILENT,
                pace == Pace.INSTANT ? SNAP_SPEED : 2f));
    }

    /**
     * A solid neighbour to click against, as a 3D data value (Direction order DOWN, UP, NORTH, SOUTH, WEST, EAST), or
     * -1. Prefers the side facing the player (the block they came from), then below.
     */
    private int supportFace(int tx, int ty, int tz, LocalPlayer player) {
        double dx = player.getX() - (tx + 0.5), dz = player.getZ() - (tz + 0.5);
        Direction toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (solid(tx + toward.getStepX(), ty, tz + toward.getStepZ())) {
            return toward.get3DDataValue();
        }
        for (Direction d : HORIZONTAL_THEN_DOWN) {
            if (solid(tx + d.getStepX(), ty + d.getStepY(), tz + d.getStepZ())) {
                return d.get3DDataValue();
            }
        }
        return -1;
    }

    private static final Direction[] HORIZONTAL_THEN_DOWN = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};

    private boolean replaceable(int x, int y, int z) {
        Level l = level;
        return l != null && l.getBlockState(pos.set(x, y, z)).canBeReplaced();
    }

    private boolean solid(int x, int y, int z) {
        Level l = level;
        return l != null && !l.getBlockState(pos.set(x, y, z)).canBeReplaced();
    }

    private static boolean allowed(Minecraft mc) {
        try {
            String list = Services.modState().profile().getString(ServerAllowList.SOURCE_MODULE_ID,
                    ServerAllowList.SETTING_KEY, ServerAllowList.DEFAULT_LIST);
            ServerData server = mc.getCurrentServer();
            return ServerAllowList.isAllowed(mc.hasSingleplayerServer(), server == null ? null : server.ip, list);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
