package com.utilitysuite.travel.bridge;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.CameraAim;
import com.utilitysuite.travel.HeldKeys;
import com.utilitysuite.travel.LeaseSlot;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.TravelMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Bridge Assist: follows a line (from the player's facing, or between two marked points) and places blocks along it at
 * the level of the block the player stands on. Sneaks the whole time (never walks off), walks toward the next gap,
 * and once the side face of the last block is visible from the eye, aims at that face (VISIBLE or SILENT rotation)
 * and places with gaussian spacing between blocks. Placement is verified; a block that does not appear is retried a few
 * times, then the run stops. Stops cleanly at the target marker, at the end of a facing plan, when the block budget is
 * used, or when the hotbar runs low. One TRAVEL lease over MOVEMENT + CAMERA + WORLD_INTERACT + HOTBAR.
 * <p>
 * SILENT mode: the outgoing rotation points at the face while the camera stays with the user, so the crosshair does not
 * target the face; the hit result is computed here and placed with {@code MultiPlayerGameMode.useItemOn}. Movement
 * keys are always chosen relative to the real camera ({@link MoveKeys}).
 */
public final class BridgeAssist implements IModule, IConfigurable {

    public static final String ID = "bridge_assist";

    public enum LineMode { FACING, MARKED }

    /** LINE: the automated line bridge (it walks for you). WALK: you walk, it fills the deck ahead of you. */
    public enum Mode { LINE, WALK }

    static final String K_LINE = "line_mode";
    static final String K_LENGTH = "length";
    static final String K_BUDGET = "budget";
    static final String K_RESERVE = "hotbar_reserve";
    static final String K_ROTATION = "rotation_mode";
    static final String K_DELAY_MEAN = "delay_mean_ticks";
    static final String K_DELAY_STD = "delay_std_ticks";
    static final String K_MODE = "mode";
    static final String K_WALK_PACE = "walk_pace";
    static final String K_SERVER_LOOK = "server_look";
    static final String K_SERVER_SNEAK = "server_sneak";
    static final String K_HIDE_SPRINT = "hide_sprint";
    static final String K_TOWER = "tower";
    static final String K_LOOKAHEAD = "lookahead_ticks";
    static final String GROUP_WALK = "Walk mode";

    private static final double REACH = 4.5;
    private static final int VERIFY_TICKS = 8;
    private static final int MAX_RETRIES = 3;
    private static final float BRIDGE_PITCH = 78f;
    /** Steady-pose tuning: ignore aim changes smaller than this, and turn slower than a normal aim. */
    private static final float AIM_DEAD_BAND_DEG = 1.5f;
    private static final float AIM_SPEED = 0.6f;
    /** Past-the-edge distance the pitch is computed for while the face is still hidden (where it will show). */
    private static final double PITCH_BEYOND_MIN = 0.18;

    private boolean enabled = true;

    // settings
    private Mode mode = Mode.LINE;
    private LineMode lineMode = LineMode.FACING;
    private int length = 32;
    private int budget = 64;
    private int reserve = 4;
    private RotationMode rotationMode = RotationMode.VISIBLE;
    private double delayMean = 4.0;
    private double delayStd = 1.2;

    // markers
    private boolean hasA;
    private boolean hasB;
    private long markA;
    private long markB;

    // run
    private boolean running;
    private BridgePlanner.Plan plan;
    private long start;
    private int index;
    private int placed;
    private boolean awaiting;
    private int awaitIndex;
    private int awaitTicks;
    private int retries;
    private int delay;
    private int airborneTicks;
    private boolean pausedNotified;

    private final LeaseSlot slot = new LeaseSlot(ID, Services::arbiter);
    private final LeaseRequest request = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT,
            ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT, ControlChannel.HOTBAR);
    private final HeldKeys keys = new HeldKeys();
    private final CameraAim aim = new CameraAim(AIM_DEAD_BAND_DEG);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final WalkBridge walk = new WalkBridge();
    private final java.util.function.IntSupplier walkSlot = () -> {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0 : placeableSlot(p.getInventory(), p.level());
    };

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
        this.enabled = enabled;
        if (!enabled && isRunning()) {
            finish(null);
        }
    }

    @Override
    public void onStopAll() {
        if (isRunning()) {
            finish(null);
        }
    }

    @Override
    public void onWorldUnload() {
        walk.worldUnload();
        running = false;
        plan = null;
        hasA = false;
        hasB = false;
        slot.reset();
        keys.reset();
        aim.reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
    }

    @Override
    public void onTick() {
        if (!running && !walk.isRunning()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (walk.isRunning()) {
            String end = walk.tick(mc, player, countPlaceable(player.getInventory(), mc.level), reserve, budget,
                    walkSlot);
            if (end != null) {
                finish(end);
            }
            return;
        }
        if (slot.terminated()) {
            finish("message.utilitysuite.bridge.stopped");
            return;
        }
        ControlLease lease = slot.ensure(request);
        if (lease == null) {
            if (slot.isSuspended() && !pausedNotified) {
                pausedNotified = true;
                TravelMessages.status("message.utilitysuite.bridge.paused");
            }
            return;
        }
        pausedNotified = false;
        Level level = mc.level;
        IInputSmoother input = Services.input();
        int baseY = Packed.y(start);

        // Safety: the bridge never jumps; leaving the ground for long or dropping below the deck ends the run.
        airborneTicks = player.onGround() ? 0 : airborneTicks + 1;
        if (airborneTicks > 10 || player.getY() < baseY + 1 - 0.6) {
            finish("message.utilitysuite.bridge.fell");
            return;
        }

        long[] positions = plan.positions();
        while (index < positions.length && occupied(level, positions[index])) {
            if (awaiting && awaitIndex == index) {
                awaiting = false;
                placed++;
                retries = 0;
                delay = input.nextGaussianTicks(delayMean, delayStd, 1, (int) Math.ceil(delayMean * 3) + 1);
            }
            index++;
        }
        Inventory inv = player.getInventory();
        int hotbarBlocks = countPlaceable(inv, level);
        BridgeStop stop = BridgeStop.check(index, positions.length, plan.toMarker(), placed, budget, hotbarBlocks,
                reserve);
        if (stop != BridgeStop.NONE) {
            finish(stopKey(stop));
            return;
        }
        if (awaiting && ++awaitTicks > VERIFY_TICKS) {
            awaiting = false;
            if (++retries > MAX_RETRIES) {
                finish("message.utilitysuite.bridge.place_failed");
                return;
            }
        }
        if (delay > 0) {
            delay--;
        }

        // Select a placeable hotbar slot (the selection lands next tick).
        int wanted = placeableSlot(inv, level);
        if (inv.selected != wanted) {
            input.selectHotbarSlot(lease, wanted);
            return;
        }

        long p = positions[index];
        long q = index == 0 ? start : positions[index - 1];
        int px = Packed.x(p);
        int pz = Packed.z(p);
        int qx = Packed.x(q);
        int qz = Packed.z(q);
        int dirX = px - qx;
        int dirZ = pz - qz;
        Direction face = dirX > 0 ? Direction.EAST : dirX < 0 ? Direction.WEST : dirZ > 0 ? Direction.SOUTH
                : Direction.NORTH;

        double eyeX = player.getX();
        double eyeY = player.getEyeY();
        double eyeZ = player.getZ();
        // The face of Q toward P lies in the plane between them; it is visible once the eye is past that plane.
        double planeCoord = dirX != 0 ? qx + (dirX > 0 ? 1.0 : 0.0) : qz + (dirZ > 0 ? 1.0 : 0.0);
        double beyond = dirX != 0 ? (eyeX - planeCoord) * dirX : (eyeZ - planeCoord) * dirZ;
        double hitX = dirX != 0 ? planeCoord : qx + 0.5;
        double hitY = baseY + 0.5;
        double hitZ = dirZ != 0 ? planeCoord : qz + 0.5;
        double hx = hitX - eyeX;
        double hy = hitY - eyeY;
        double hz = hitZ - eyeZ;
        double horizontal = Math.sqrt(hx * hx + hz * hz);
        boolean visible = beyond > 0.05;

        double moveYaw = Angles.yawTo(px + 0.5 - player.getX(), pz + 0.5 - player.getZ());
        // One steady bridger pose (no per-block head swings): yaw straight back along the bridge direction (the face
        // spans the lane, so a centred player hits it without yaw corrections), pitch from how far the eye is past the
        // face plane. Aiming at the face point itself made the yaw swing wildly: that point is almost straight down,
        // so centimetre moves changed the horizontal direction by tens of degrees.
        double backYaw = Angles.yawTo(-dirX, -dirZ);
        double pitch = Math.max(BRIDGE_PITCH - 8f, Math.min(88.0,
                Angles.pitchTo(Math.max(beyond, PITCH_BEYOND_MIN), hy)));
        aim.aim(input, lease, backYaw, pitch, rotationMode, AIM_SPEED);

        keys.set(input, lease, SimKey.SNEAK, true);
        int mask = beyond < 0.2 ? MoveKeys.forDirection(player.getYRot(), moveYaw) : 0;
        keys.set(input, lease, SimKey.FORWARD, (mask & MoveKeys.FORWARD) != 0);
        keys.set(input, lease, SimKey.BACK, (mask & MoveKeys.BACK) != 0);
        keys.set(input, lease, SimKey.LEFT, (mask & MoveKeys.LEFT) != 0);
        keys.set(input, lease, SimKey.RIGHT, (mask & MoveKeys.RIGHT) != 0);

        if (awaiting || delay > 0 || !visible || !input.isRotationSettled(2f)
                || Math.sqrt(horizontal * horizontal + hy * hy) > REACH) {
            return;
        }
        if (rotationMode == RotationMode.SILENT) {
            // Direct game call, so re-check the lease here (it may have been revoked earlier this tick).
            if (lease == null || !lease.isActive()
                    || !lease.covers(com.utilitysuite.core.api.ControlChannel.WORLD_INTERACT)) {
                return;
            }
            BlockHitResult hit = new BlockHitResult(new Vec3(hitX, hitY, hitZ), face, new BlockPos(qx, baseY, qz),
                    false);
            InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            if (r.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            HitResult hr = mc.hitResult;
            if (!(hr instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK
                    || bhr.getDirection() != face || bhr.getBlockPos().getX() != qx
                    || bhr.getBlockPos().getY() != baseY || bhr.getBlockPos().getZ() != qz) {
                return; // crosshair not on the face yet
            }
            input.scheduleKeyTap(lease, SimKey.USE, 1, 2);
        }
        awaiting = true;
        awaitIndex = index;
        awaitTicks = 0;
    }

    // ---- control ------------------------------------------------------------------------------------------------------

    public boolean isRunning() {
        return running || walk.isRunning();
    }

    public Mode mode() {
        return mode;
    }

    /** Key handler: start or stop. */
    public void toggle() {
        if (isRunning()) {
            finish("message.utilitysuite.bridge.stopped");
        } else {
            start();
        }
    }

    /** Key handler: marks the block under the player as point A, then B; a third press starts over at A. */
    public void mark() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        BlockPos on = player.getOnPos();
        long p = Packed.pack(on.getX(), on.getY(), on.getZ());
        if (!hasA || hasB) {
            markA = p;
            hasA = true;
            hasB = false;
            TravelMessages.status("message.utilitysuite.bridge.mark_a", on.getX(), on.getY(), on.getZ());
        } else {
            markB = p;
            hasB = true;
            TravelMessages.status("message.utilitysuite.bridge.mark_b", on.getX(), on.getY(), on.getZ());
        }
    }

    public boolean start() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        if (!enabled) {
            TravelMessages.status("message.utilitysuite.bridge.disabled");
            return false;
        }
        if (Services.arbiter().isAutomationStopped()) {
            TravelMessages.status("message.utilitysuite.travel.automation_stopped");
            return false;
        }
        if (mode == Mode.WALK) {
            String refused = walk.start(player);
            if (refused != null) {
                TravelMessages.status(refused);
                return false;
            }
            TravelMessages.status("message.utilitysuite.bridge.walk_started");
            return true;
        }
        if (!player.onGround()) {
            TravelMessages.status("message.utilitysuite.bridge.not_on_ground");
            return false;
        }
        BlockPos on = player.getOnPos();
        long here = Packed.pack(on.getX(), on.getY(), on.getZ());
        int startIndex = 0;
        if (lineMode == LineMode.MARKED) {
            if (!hasA || !hasB) {
                TravelMessages.status("message.utilitysuite.bridge.need_marks");
                return false;
            }
            plan = BridgePlanner.between(Packed.x(markA), Packed.y(markA), Packed.z(markA), Packed.x(markB),
                    Packed.z(markB));
            start = markA;
            if (here != markA) {
                int found = -1;
                long[] ps = plan.positions();
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == here) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    TravelMessages.status("message.utilitysuite.bridge.off_line");
                    return false;
                }
                startIndex = found + 1;
                start = here;
            }
        } else {
            plan = BridgePlanner.facing(on.getX(), on.getY(), on.getZ(), player.getYRot(), length);
            start = here;
        }
        if (startIndex >= plan.length()) {
            TravelMessages.status("message.utilitysuite.bridge.at_marker", 0);
            return false;
        }
        index = startIndex;
        placed = 0;
        awaiting = false;
        retries = 0;
        delay = 0;
        airborneTicks = 0;
        pausedNotified = false;
        slot.reset();
        keys.reset();
        aim.reset();
        running = true;
        TravelMessages.status("message.utilitysuite.bridge.started", plan.length() - startIndex);
        return true;
    }

    private void finish(String messageKey) {
        if (walk.isRunning()) {
            walk.stop();
            if (messageKey != null) {
                TravelMessages.status(messageKey, walk.placed());
            }
            return;
        }
        ControlLease lease = slot.active();
        IInputSmoother input = Services.input();
        keys.releaseAll(input, lease);
        if (lease != null) {
            input.clearRotation(lease);
        }
        aim.reset();
        slot.reset();
        running = false;
        if (messageKey != null) {
            TravelMessages.status(messageKey, placed);
        }
    }

    private static String stopKey(BridgeStop s) {
        return switch (s) {
            case AT_MARKER -> "message.utilitysuite.bridge.at_marker";
            case PLAN_DONE -> "message.utilitysuite.bridge.done";
            case BUDGET -> "message.utilitysuite.bridge.budget";
            case HOTBAR_LOW -> "message.utilitysuite.bridge.hotbar_low";
            default -> "message.utilitysuite.bridge.stopped";
        };
    }

    // ---- world helpers ------------------------------------------------------------------------------------------------

    private boolean occupied(Level level, long packed) {
        return !level.getBlockState(pos.set(Packed.x(packed), Packed.y(packed), Packed.z(packed))).canBeReplaced();
    }

    private static boolean placeable(ItemStack stack, Level level) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi) || bi.getBlock() instanceof FallingBlock) {
            return false;
        }
        return bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(level, BlockPos.ZERO);
    }

    private static int countPlaceable(Inventory inv, Level level) {
        int n = 0;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (placeable(s, level)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** The selected slot if it holds placeable blocks, else the first hotbar slot that does. */
    private static int placeableSlot(Inventory inv, Level level) {
        if (placeable(inv.getItem(inv.selected), level)) {
            return inv.selected;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (placeable(inv.getItem(i), level)) {
                return i;
            }
        }
        return inv.selected;
    }

    // ---- settings -----------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Bridge Assist";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice(K_MODE, "Mode", Mode.LINE, "",
                        "LINE: bridges a line for you (walks, sneaks, places). WALK: you walk or run normally and blocks "
                                + "are placed under you; the Walk mode settings choose how it looks to the server."),
                SettingSpec.choice(K_LINE, "Line", LineMode.FACING, "",
                        "FACING: straight ahead from where you look (8 directions). MARKED: from point A to point B "
                                + "(mark them with the bridge mark key)."),
                SettingSpec.integer(K_LENGTH, "Length (facing)", 32, 1, BridgePlanner.MAX_LENGTH, "",
                        "Blocks to bridge in FACING mode."),
                SettingSpec.integer(K_BUDGET, "Block budget", 64, 0, 4096, "",
                        "Stop after placing this many blocks. 0 = no limit."),
                SettingSpec.integer(K_RESERVE, "Hotbar reserve", 4, 0, 576, "",
                        "Stop when no more than this many placeable blocks are left in the hotbar."),
                SettingSpec.choice(K_ROTATION, "Rotation", RotationMode.VISIBLE, "",
                        "VISIBLE turns your camera; SILENT aims only in what is sent to the server."),
                SettingSpec.choice(K_WALK_PACE, "Pace", WalkBridge.Pace.LEGIT, GROUP_WALK,
                        "LEGIT: one block as you reach the edge, with human spacing. INSTANT: every block along your path "
                                + "the moment you need it, at any speed (single-player / allow-listed servers)."),
                SettingSpec.choice(K_SERVER_LOOK, "Server look", WalkBridge.ServerLook.BRIDGER, GROUP_WALK,
                        "What the server and other players see of your head. REAL: your camera. BRIDGER: facing back "
                                + "down at the deck like a backward bridger while you walk forward. FACE: aimed at each "
                                + "block placed. Your own camera never moves."),
                SettingSpec.choice(K_SERVER_SNEAK, "Server sneak", WalkBridge.ServerSneak.AT_EDGE, GROUP_WALK,
                        "Crouch shown to the server while you keep full speed. AT_EDGE: a short crouch at each block, "
                                + "like ninja bridging. ALWAYS: crouched the whole time."),
                SettingSpec.bool(K_HIDE_SPRINT, "Hide sprint from server", true, GROUP_WALK,
                        "The server sees you walking while you sprint."),
                SettingSpec.bool(K_TOWER, "Tower", false, GROUP_WALK,
                        "Jumping in place places a block under you each jump."),
                SettingSpec.integer(K_LOOKAHEAD, "Look-ahead (ticks)", 3, 1, 10, GROUP_WALK,
                        "INSTANT pace: how far ahead of your movement blocks are placed.").asAdvanced(),
                SettingSpec.decimal(K_DELAY_MEAN, "Delay between blocks (ticks)", 4.0, 1.0, 20.0, "Timing",
                        "Average pause between placements.").asAdvanced(),
                SettingSpec.decimal(K_DELAY_STD, "Delay spread (ticks)", 1.2, 0.0, 10.0, "Timing",
                        "Standard deviation of the pause.").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
    }

    private void readSettings(ProfileConfig p) {
        try {
            lineMode = LineMode.valueOf(p.getString(ID, K_LINE, LineMode.FACING.name()));
        } catch (IllegalArgumentException e) {
            lineMode = LineMode.FACING;
        }
        length = p.getInt(ID, K_LENGTH, 32);
        budget = p.getInt(ID, K_BUDGET, 64);
        reserve = p.getInt(ID, K_RESERVE, 4);
        try {
            rotationMode = RotationMode.valueOf(p.getString(ID, K_ROTATION, RotationMode.VISIBLE.name()));
        } catch (IllegalArgumentException e) {
            rotationMode = RotationMode.VISIBLE;
        }
        delayMean = p.getDouble(ID, K_DELAY_MEAN, 4.0);
        delayStd = p.getDouble(ID, K_DELAY_STD, 1.2);
        mode = parse(Mode.class, p.getString(ID, K_MODE, Mode.LINE.name()), Mode.LINE);
        walk.pace = parse(WalkBridge.Pace.class, p.getString(ID, K_WALK_PACE, "LEGIT"), WalkBridge.Pace.LEGIT);
        walk.look = parse(WalkBridge.ServerLook.class, p.getString(ID, K_SERVER_LOOK, "BRIDGER"),
                WalkBridge.ServerLook.BRIDGER);
        walk.sneak = parse(WalkBridge.ServerSneak.class, p.getString(ID, K_SERVER_SNEAK, "AT_EDGE"),
                WalkBridge.ServerSneak.AT_EDGE);
        walk.hideSprint = p.getBool(ID, K_HIDE_SPRINT, true);
        walk.tower = p.getBool(ID, K_TOWER, false);
        walk.lookahead = Math.max(1, Math.min(10, p.getInt(ID, K_LOOKAHEAD, 3)));
        walk.delayMean = delayMean;
        walk.delayStd = delayStd;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }
}
