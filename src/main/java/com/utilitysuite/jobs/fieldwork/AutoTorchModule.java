package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.goal.MinecraftInventoryView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.LightLayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Auto-Torch: while mining (a job or by hand), when block light at the player's feet falls below the threshold, places
 * a torch like a player. FLOOR (default): on the floor behind the player, or at their feet. RIGHT_WALL: on a sturdy
 * wall to the player's right (yaw + 90) at head height, then feet height, falling back to the floor. The torch is
 * selected through the core, the look is smoothed (Legit), and the previous hotbar slot is selected again afterwards.
 * When the offhand already holds torches and the main hand cannot take the click (empty, pickaxe, sword), the torch is
 * placed from the offhand with a USE tap, without touching the hotbar. Light is read only when the player's block
 * position changes, never every tick.
 */
public final class AutoTorchModule extends FieldModule {

    public static final String ID = "auto_torch";
    /** "Mining" means a block was attacked within this many ticks. */
    private static final int MINING_WINDOW_TICKS = 200;
    static final String KEY_PLACEMENT = "placement_mode";

    /** Where the torch goes. */
    public enum PlacementMode { FLOOR, RIGHT_WALL }

    private final MinecraftInventoryView inventory;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int threshold = 8;
    private int minGap = 5;
    private boolean onlyWhileMining = true;
    private PlacementMode placementMode = PlacementMode.FLOOR;

    private long tick;
    private long lastMineTick = Long.MIN_VALUE / 2;
    private long lastFeet = Long.MIN_VALUE;
    private long lastTorch = Long.MIN_VALUE;

    // placement run
    private boolean placing;
    /** Cell the torch ends up in. */
    private int sx, sy, sz;
    /** Support block and the face of it that is clicked. */
    private int bx, by, bz;
    private Direction face = Direction.UP;
    /** Placing from the offhand: no hotbar selection, nothing to restore. */
    private boolean offhand;
    private int previousSlot = -1;
    private int phase;
    private int runTicks;
    private Item torch;

    public AutoTorchModule(MinecraftInventoryView inventory) {
        super(ID, ControlPriority.TRAVEL); // outranks a job (GOAL): the job pauses for the torch and resumes
        this.inventory = inventory;
    }

    /** NeoForge client event: a block is being attacked (by hand or by a job). */
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (enabled && event.getLevel().isClientSide()) {
            lastMineTick = tick;
        }
    }

    @Override
    public void onTick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        if (placing) {
            tickPlacing(mc, player);
            return;
        }
        int fx = Mth.floor(player.getX()), fy = Mth.floor(player.getY()), fz = Mth.floor(player.getZ());
        long feet = BlockPos.asLong(fx, fy, fz);
        if (feet == lastFeet) {
            return; // light is only checked when the block position changes
        }
        lastFeet = feet;
        if (onlyWhileMining && tick - lastMineTick > MINING_WINDOW_TICKS) {
            return;
        }
        int light = level.getBrightness(LightLayer.BLOCK, cursor.set(fx, fy, fz));
        int since = lastTorch == Long.MIN_VALUE ? Integer.MAX_VALUE : chebyshev(lastTorch, fx, fy, fz);
        String torchId = FieldRules.torchItem(inventory);
        if (!FieldRules.shouldPlaceTorch(light, threshold, torchId == null ? 0 : 1, since, minGap)) {
            return;
        }
        if (!chooseSpot(level, player, fx, fy, fz)) {
            return;
        }
        Item offTorch = offhandTorch(player);
        // A USE tap goes to the main hand first: only an item that passes on a block lets it fall through to the
        // offhand. Otherwise the torch is selected in the hotbar as usual.
        offhand = offTorch != null && mainHandPassesUse(player.getMainHandItem());
        torch = offhand ? offTorch : inventory.itemOf(torchId);
        previousSlot = offhand ? -1 : player.getInventory().selected; // read only, restored through the core
        placing = true;
        phase = offhand ? 1 : 0;
        runTicks = 0;
    }

    /** The torch item in the offhand, or null. */
    private Item offhandTorch(LocalPlayer player) {
        ItemStack off = player.getOffhandItem();
        if (off.isEmpty()) {
            return null;
        }
        for (String id : FieldRules.TORCH_ITEMS) {
            Item it = inventory.itemOf(id);
            if (off.is(it)) {
                return it;
            }
        }
        return null;
    }

    /** True when a USE on a plain block with this main-hand item does nothing, so vanilla moves on to the offhand. */
    private static boolean mainHandPassesUse(ItemStack main) {
        return main.isEmpty() || main.getItem() instanceof PickaxeItem || main.getItem() instanceof SwordItem;
    }

    /** RIGHT_WALL tries the wall to the right first; then the floor cell behind the player, else their feet cell. */
    private boolean chooseSpot(ClientLevel level, LocalPlayer player, int fx, int fy, int fz) {
        if (placementMode == PlacementMode.RIGHT_WALL && wallSpot(level, player, fx, fy, fz)) {
            return true;
        }
        Direction back = player.getDirection().getOpposite();
        if (floorFree(level, fx + back.getStepX(), fy, fz + back.getStepZ())) {
            floorSpot(fx + back.getStepX(), fy, fz + back.getStepZ());
            return true;
        }
        if (floorFree(level, fx, fy, fz)) {
            floorSpot(fx, fy, fz);
            return true;
        }
        return false;
    }

    private void floorSpot(int x, int y, int z) {
        sx = x;
        sy = y;
        sz = z;
        bx = x;
        by = y - 1;
        bz = z;
        face = Direction.UP;
    }

    /**
     * Wall to the player's right (yaw + 90, snapped to a side): the torch goes into the player's own column at head
     * height, else feet height, against the facing side of the wall block when that side is sturdy.
     */
    private boolean wallSpot(ClientLevel level, LocalPlayer player, int fx, int fy, int fz) {
        Direction right = Direction.fromYRot(player.getYRot() + 90.0f);
        Direction side = right.getOpposite(); // the wall face that looks back at the player
        for (int ty = fy + 1; ty >= fy; ty--) {
            cursor.set(fx, ty, fz);
            if (!level.getBlockState(cursor).isAir() || !level.getFluidState(cursor).isEmpty()) {
                continue;
            }
            int wx = fx + right.getStepX(), wz = fz + right.getStepZ();
            cursor.set(wx, ty, wz);
            if (level.getBlockState(cursor).isFaceSturdy(level, cursor, side)) {
                sx = fx;
                sy = ty;
                sz = fz;
                bx = wx;
                by = ty;
                bz = wz;
                face = side;
                return true;
            }
        }
        return false;
    }

    private boolean floorFree(ClientLevel level, int x, int y, int z) {
        cursor.set(x, y, z);
        if (!level.getBlockState(cursor).isAir() || !level.getFluidState(cursor).isEmpty()) {
            return false;
        }
        cursor.set(x, y - 1, z);
        return level.getBlockState(cursor).isFaceSturdy(level, cursor, Direction.UP);
    }

    private void tickPlacing(Minecraft mc, LocalPlayer player) {
        if (hands.consumePreempted() || ++runTicks > 100) {
            finish(false);
            return;
        }
        if (!hands.ensureLease()) {
            return; // manual input or a higher module holds the hands; try next tick (gives up with the window)
        }
        switch (phase) {
            case 0 -> {
                int r = hands.holdItem(player, torch);
                if (r == FieldHands.FAILED) {
                    finish(false);
                } else if (r == FieldHands.DONE) {
                    phase = 1;
                }
            }
            case 1 -> {
                if (offhand && !player.getOffhandItem().is(torch)) {
                    finish(false); // the offhand torches ran out or were moved
                    return;
                }
                // The relaxed path's direct game call uses the main hand, so the offhand always goes through the
                // visible look and a USE tap, which vanilla hands on to the offhand.
                int r = hands.tickPlace(mc, player, bx, by, bz, face, relaxed && !offhand);
                if (r == FieldHands.DONE) {
                    lastTorch = BlockPos.asLong(sx, sy, sz);
                    hands.pauseHuman(4);
                    phase = 2;
                } else if (r == FieldHands.FAILED) {
                    finish(false);
                }
            }
            default -> {
                if (hands.coolingDown()) {
                    return;
                }
                finish(true);
            }
        }
    }

    private void finish(boolean restoreSlot) {
        if (restoreSlot && previousSlot >= 0) {
            hands.selectSlot(previousSlot);
        }
        placing = false;
        offhand = false;
        previousSlot = -1;
        hands.release();
    }

    private static int chebyshev(long a, int x, int y, int z) {
        return Math.max(Math.abs(BlockPos.getX(a) - x), Math.max(Math.abs(BlockPos.getY(a) - y),
                Math.abs(BlockPos.getZ(a) - z)));
    }

    @Override
    protected void stopWork() {
        placing = false;
        offhand = false;
        previousSlot = -1;
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        threshold = p.getInt(ID, "light_threshold", 8);
        minGap = p.getInt(ID, "min_gap_blocks", 5);
        onlyWhileMining = p.getBool(ID, "only_while_mining", true);
        try {
            placementMode = PlacementMode.valueOf(p.getString(ID, KEY_PLACEMENT, PlacementMode.FLOOR.name()));
        } catch (IllegalArgumentException e) {
            placementMode = PlacementMode.FLOOR;
        }
    }

    /** Current placement mode (tests, HUD). */
    public PlacementMode placementMode() {
        return placementMode;
    }

    @Override
    public String displayName() {
        return "Auto-Torch";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("light_threshold", "Place below block light", 8, 1, 15, "",
                        "A torch goes down when block light at the feet is below this (mobs spawn at 0)"),
                SettingSpec.integer("min_gap_blocks", "Blocks between torches", 5, 1, 32, "",
                        "Never place a torch closer than this to the last one"),
                SettingSpec.bool("only_while_mining", "Only while mining", true, "",
                        "Only place torches while a block was mined recently (by hand or a job)"),
                SettingSpec.choice(KEY_PLACEMENT, "Placement", PlacementMode.FLOOR, "",
                        "FLOOR: on the floor behind you. RIGHT_WALL: on the wall to your right at head height, "
                                + "falling back to the floor when there is no wall"),
                relaxedSpec());
    }
}
