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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Crop harvest and replant: while enabled and the player stands still (look where you move: it never turns the head
 * while walking), fully grown crops within reach are broken one per turn and replanted from the inventory. The reach
 * box is scanned over several ticks into a packed-position snapshot ({@link CropPlanner}); a new scan starts only when
 * the player's block position changed or the last scan's work is done. Legit: smoothed visible look and line of sight;
 * Relaxed: silent rotation.
 */
public final class CropWorkModule extends FieldModule {

    public static final String ID = "crop_work";
    private static final int SCAN_PER_TICK = 64;
    private static final int RADIUS = 4;
    private static final int Y_MIN = -2;
    private static final int Y_MAX = 1;
    private static final int SIDE = 2 * RADIUS + 1;
    private static final int LAYERS = Y_MAX - Y_MIN + 1;
    private static final int BOX = SIDE * SIDE * LAYERS;
    private static final int RESCAN_TICKS = 40;

    private final MinecraftInventoryView inventory;
    private final CropPlanner planner = new CropPlanner();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final boolean[] hasSeed = new boolean[CropPlanner.CROP_BLOCKS.length];
    private final Item[] seedItems = new Item[CropPlanner.CROP_BLOCKS.length];

    private boolean replant = true;
    private boolean onlyReplantable = true;

    private long tick;
    // scan
    private int scanIndex = -1;
    private int ox, oy, oz;
    private long scanOrigin = Long.MIN_VALUE;
    private long lastScanDone;
    // work
    private int target = -1;
    private long targetCell;
    private int targetKind;
    private int phase;
    private int previousSlot = -1;
    // replant aim: a visible point on the soil's top face, chosen before the crop is broken
    private final double[] aimOut = new double[2];
    private final CropPlanner.AimTest aimTest = this::aimVisible;
    private LocalPlayer aimPlayer;
    private int aimX, aimZ;
    private double soilTop;
    private long aimCrop, aimSoil;
    private double aimFx = 0.5, aimFz = 0.5;
    private int hiddenSkips;

    public CropWorkModule(MinecraftInventoryView inventory) {
        super(ID, ControlPriority.GOAL);
        this.inventory = inventory;
    }

    /** Ripe crops left standing because their soil could not be seen for the replant (diagnostics, playtests). */
    public int hiddenSkips() {
        return hiddenSkips;
    }

    /** Finds a visible aim point on the soil under the crop at {@code cell} (into {@link #aimOut}); per target. */
    private boolean replantVisible(LocalPlayer player, ClientLevel level, long cell) {
        int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
        cursor.set(x, y - 1, z);
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(cursor).getShape(level, cursor);
        soilTop = y - 1 + (shape.isEmpty() ? 1.0 : shape.max(net.minecraft.core.Direction.Axis.Y));
        aimPlayer = player;
        aimX = x;
        aimZ = z;
        aimCrop = cell;
        aimSoil = BlockPos.asLong(x, y - 1, z);
        boolean ok = CropPlanner.replantAim(player.getX(), player.getZ(), x, z, aimTest, aimOut);
        aimPlayer = null;
        return ok;
    }

    private boolean aimVisible(double fx, double fz) {
        return FieldHands.visiblePoint(Minecraft.getInstance(), aimPlayer, aimX + fx, soilTop, aimZ + fz, aimCrop,
                aimSoil);
    }

    @Override
    public void onTick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.screen != null) {
            return;
        }
        if (target >= 0) {
            tickWork(mc, player, level);
            return;
        }
        int fx = Mth.floor(player.getX()), fy = Mth.floor(player.getY()), fz = Mth.floor(player.getZ());
        long feet = BlockPos.asLong(fx, fy, fz);
        if (scanIndex < 0) {
            if (feet == scanOrigin && tick - lastScanDone < RESCAN_TICKS) {
                return;
            }
            scanIndex = 0;
            scanOrigin = feet;
            ox = fx;
            oy = fy;
            oz = fz;
            planner.clear();
        }
        scanSome(level);
        if (scanIndex >= 0 || planner.size() == 0) {
            return;
        }
        if (player.getDeltaMovement().horizontalDistanceSqr() > 1e-4) {
            return; // walking: look where you move, no crop turns
        }
        refreshSeeds();
        int i = planner.next(player.getX(), player.getEyeY(), player.getZ(), player.blockInteractionRange() - 0.25,
                replant && onlyReplantable, hasSeed);
        if (i < 0) {
            return;
        }
        long cell = planner.cell(i);
        int kind = planner.kind(i);
        aimOut[0] = 0.5;
        aimOut[1] = 0.5;
        if (!relaxed && replant && kind != CropPlanner.COCOA && hasSeed[kind] && !replantVisible(player, level, cell)) {
            // from here the seed could not be put back (a taller crop hides the soil): leave it standing for now,
            // a later scan (or another spot) tries again; harvesting it would leave bare soil (playtest find)
            planner.remove(cell);
            hiddenSkips++;
            return;
        }
        target = i;
        targetCell = cell;
        targetKind = kind;
        aimFx = aimOut[0];
        aimFz = aimOut[1];
        phase = 0;
        previousSlot = player.getInventory().selected; // read only, restored through the core
    }

    /** Budgeted scan of the reach box: {@link #SCAN_PER_TICK} cells per tick. */
    private void scanSome(ClientLevel level) {
        int end = Math.min(BOX, scanIndex + SCAN_PER_TICK);
        for (int i = scanIndex; i < end; i++) {
            int dx = i % SIDE - RADIUS;
            int dz = (i / SIDE) % SIDE - RADIUS;
            int dy = i / (SIDE * SIDE) + Y_MIN;
            cursor.set(ox + dx, oy + dy, oz + dz);
            BlockState s = level.getBlockState(cursor);
            int kind = kindOf(s.getBlock());
            if (kind >= 0) {
                planner.offer(cursor.asLong(), kind, ageOf(s, kind));
            }
        }
        scanIndex = end >= BOX ? -1 : end;
        if (scanIndex < 0) {
            lastScanDone = tick;
        }
    }

    static int kindOf(Block b) {
        if (b == Blocks.WHEAT) {
            return 0;
        } else if (b == Blocks.CARROTS) {
            return 1;
        } else if (b == Blocks.POTATOES) {
            return 2;
        } else if (b == Blocks.BEETROOTS) {
            return 3;
        } else if (b == Blocks.NETHER_WART) {
            return 4;
        } else if (b == Blocks.COCOA) {
            return CropPlanner.COCOA;
        }
        return -1;
    }

    private static int ageOf(BlockState s, int kind) {
        if (kind == 4) {
            return s.getValue(NetherWartBlock.AGE);
        }
        if (kind == CropPlanner.COCOA) {
            return s.getValue(CocoaBlock.AGE);
        }
        return s.getBlock() instanceof CropBlock c ? c.getAge(s) : 0;
    }

    private void refreshSeeds() {
        for (int k = 0; k < hasSeed.length; k++) {
            hasSeed[k] = CropPlanner.canReplant(k, inventory);
        }
    }

    private void tickWork(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (hands.consumePreempted() || player.getDeltaMovement().horizontalDistanceSqr() > 1e-3) {
            endTarget(false);
            return;
        }
        if (!hands.ensureLease()) {
            endTarget(false);
            return;
        }
        if (hands.coolingDown()) {
            return;
        }
        int x = BlockPos.getX(targetCell), y = BlockPos.getY(targetCell), z = BlockPos.getZ(targetCell);
        switch (phase) {
            case 0 -> {
                int r = hands.tickBreak(mc, player, x, y, z, relaxed, !relaxed);
                if (r == FieldHands.DONE) {
                    hands.clearLook();
                    hands.pauseHuman(3);
                    phase = replant && targetKind != CropPlanner.COCOA && hasSeed[targetKind] ? 1 : 3;
                } else if (r == FieldHands.FAILED) {
                    endTarget(true);
                }
            }
            case 1 -> {
                Item seed = seedItem(targetKind);
                int r = seed == null ? FieldHands.FAILED : hands.holdItem(player, seed);
                if (r == FieldHands.DONE) {
                    phase = 2;
                } else if (r == FieldHands.FAILED) {
                    endTarget(true);
                }
            }
            case 2 -> {
                cursor.set(x, y, z);
                if (!level.getBlockState(cursor).isAir()) {
                    phase = 3; // already replanted (or something else is there)
                    return;
                }
                int r = hands.tickPlace(mc, player, x, y - 1, z, Direction.UP, relaxed, aimFx, aimFz);
                if (r != FieldHands.RUNNING) {
                    hands.pauseHuman(3);
                    phase = 3;
                }
            }
            default -> endTarget(true);
        }
    }

    private Item seedItem(int kind) {
        Item i = seedItems[kind];
        if (i == null) {
            i = inventory.itemOf(CropPlanner.SEED_ITEMS[kind]);
            seedItems[kind] = i;
        }
        return i;
    }

    private void endTarget(boolean restoreSlot) {
        if (restoreSlot && previousSlot >= 0) {
            hands.selectSlot(previousSlot);
        }
        planner.remove(targetCell);
        target = -1;
        previousSlot = -1;
        hands.release();
    }

    @Override
    protected void stopWork() {
        target = -1;
        scanIndex = -1;
        previousSlot = -1;
        planner.clear();
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        replant = p.getBool(ID, "replant", true);
        onlyReplantable = p.getBool(ID, "only_replantable", true);
        planner.setIncludeCocoa(p.getBool(ID, "cocoa", false));
    }

    @Override
    public String displayName() {
        return "Crop Work";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("replant", "Replant", true, "", "Replant each harvested crop from the inventory"),
                SettingSpec.bool("only_replantable", "Only crops I can replant", true, "",
                        "Leave a crop standing when no seed for it is carried"),
                SettingSpec.bool("cocoa", "Include cocoa", false, "", "Harvest ripe cocoa pods (not replanted)"),
                relaxedSpec());
    }
}
