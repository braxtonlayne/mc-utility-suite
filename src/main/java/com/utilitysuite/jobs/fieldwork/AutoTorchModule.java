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
import net.minecraft.world.level.LightLayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Auto-Torch: while mining (a job or by hand), when block light at the player's feet falls below the threshold, places
 * a torch on the floor behind the player (or at their feet) like a player: the torch is selected through the core, the
 * look is smoothed (Legit), and the previous hotbar slot is selected again afterwards. Light is read only when the
 * player's block position changes, never every tick.
 */
public final class AutoTorchModule extends FieldModule {

    public static final String ID = "auto_torch";
    /** "Mining" means a block was attacked within this many ticks. */
    private static final int MINING_WINDOW_TICKS = 200;

    private final MinecraftInventoryView inventory;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int threshold = 8;
    private int minGap = 5;
    private boolean onlyWhileMining = true;

    private long tick;
    private long lastMineTick = Long.MIN_VALUE / 2;
    private long lastFeet = Long.MIN_VALUE;
    private long lastTorch = Long.MIN_VALUE;

    // placement run
    private boolean placing;
    private int sx, sy, sz;
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
        torch = inventory.itemOf(torchId);
        previousSlot = player.getInventory().selected; // read only, restored through the core afterwards
        placing = true;
        phase = 0;
        runTicks = 0;
    }

    /** Floor cell behind the player (away from where they look), else their own feet cell. */
    private boolean chooseSpot(ClientLevel level, LocalPlayer player, int fx, int fy, int fz) {
        Direction back = player.getDirection().getOpposite();
        if (floorFree(level, fx + back.getStepX(), fy, fz + back.getStepZ())) {
            sx = fx + back.getStepX();
            sy = fy;
            sz = fz + back.getStepZ();
            return true;
        }
        if (floorFree(level, fx, fy, fz)) {
            sx = fx;
            sy = fy;
            sz = fz;
            return true;
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
                int r = hands.tickPlace(mc, player, sx, sy - 1, sz, Direction.UP, relaxed);
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
        previousSlot = -1;
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        threshold = p.getInt(ID, "light_threshold", 8);
        minGap = p.getInt(ID, "min_gap_blocks", 5);
        onlyWhileMining = p.getBool(ID, "only_while_mining", true);
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
                relaxedSpec());
    }
}
