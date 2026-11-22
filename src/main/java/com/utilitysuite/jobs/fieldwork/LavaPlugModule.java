package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.goal.MinecraftInventoryView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Lava Plug: while tunnelling (by hand or a job), when a block break exposes a lava source within reach, places a cheap
 * filler block into it ({@link FieldRules#PLUG_ITEMS}: never ores or valuables). Event driven: the block being attacked
 * is remembered from the attack event, and only when it turns to air are the 6 neighbours of that one cell checked; no
 * area polling. Reports {@link HazardKind#LAVA} to the danger model while lava is open. Lease at SURVIVAL priority.
 */
public final class LavaPlugModule extends FieldModule {

    public static final String ID = "lava_plug";
    private static final int WATCH_TICKS = 400;
    private static final Direction[] DIRS = Direction.values();

    private final MinecraftInventoryView inventory;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();

    private long tick;
    private long watched = Long.MIN_VALUE;
    private long watchedSince;

    // plug run
    private long lavaCell = Long.MIN_VALUE;
    private int phase;
    private int runTicks;
    private int previousSlot = -1;
    private Item plug;
    private int supX, supY, supZ;
    private Direction supFace;
    private boolean warnedNoBlocks;

    public LavaPlugModule(MinecraftInventoryView inventory) {
        super(ID, ControlPriority.SURVIVAL);
        this.inventory = inventory;
    }

    /** NeoForge client event: the block being attacked is the one whose break may open lava. */
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (enabled && event.getLevel().isClientSide()) {
            watched = event.getPos().asLong();
            watchedSince = tick;
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
        if (lavaCell != Long.MIN_VALUE) {
            tickPlug(mc, player, level);
            return;
        }
        if (watched == Long.MIN_VALUE) {
            return;
        }
        if (tick - watchedSince > WATCH_TICKS) {
            watched = Long.MIN_VALUE;
            return;
        }
        cursor.set(watched);
        if (!level.getBlockState(cursor).isAir()) {
            return; // still being mined: one block-state read per tick while watching
        }
        long broken = watched;
        watched = Long.MIN_VALUE;
        onBroken(player, level, broken);
    }

    /** Budgeted re-scan: the 6 neighbours of the broken cell only. */
    private void onBroken(LocalPlayer player, ClientLevel level, long broken) {
        int bx = BlockPos.getX(broken), by = BlockPos.getY(broken), bz = BlockPos.getZ(broken);
        double reach = player.blockInteractionRange();
        for (Direction d : DIRS) {
            side.set(bx + d.getStepX(), by + d.getStepY(), bz + d.getStepZ());
            FluidState f = level.getFluidState(side);
            if (!f.is(FluidTags.LAVA) || !f.isSource()) {
                continue;
            }
            double dx = side.getX() + 0.5 - player.getX(), dy = side.getY() + 0.5 - player.getEyeY();
            double dz = side.getZ() + 0.5 - player.getZ();
            float bearing = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            Services.danger().report(HazardKind.LAVA, 20, 4f, bearing);
            if (dx * dx + dy * dy + dz * dz > (reach - 0.5) * (reach - 0.5)) {
                continue;
            }
            String plugId = FieldRules.plugItem(inventory);
            if (plugId == null) {
                if (!warnedNoBlocks && !Services.modState().isCleanScreen()) {
                    warnedNoBlocks = true;
                    Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.WARN,
                            net.minecraft.network.chat.Component.translatable(
                                    "message.utilitysuite.lava_plug_no_blocks").getString(), null);
                }
                return;
            }
            if (!chooseSupport(level, player, side.getX(), side.getY(), side.getZ())) {
                continue;
            }
            warnedNoBlocks = false;
            plug = inventory.itemOf(plugId);
            lavaCell = side.asLong();
            previousSlot = player.getInventory().selected; // read only, restored through the core
            phase = 0;
            runTicks = 0;
            return;
        }
    }

    /**
     * A solid neighbour of the lava cell whose face toward the lava looks most toward the player (the crosshair passes
     * through the lava and lands on that face).
     */
    private boolean chooseSupport(ClientLevel level, LocalPlayer player, int lx, int ly, int lz) {
        double best = 0.05;
        boolean found = false;
        for (Direction d : DIRS) {
            int nx = lx + d.getStepX(), ny = ly + d.getStepY(), nz = lz + d.getStepZ();
            cursor.set(nx, ny, nz);
            if (level.getBlockState(cursor).canBeReplaced()) {
                continue;
            }
            Direction face = d.getOpposite(); // face of the support pointing into the lava cell
            double fx = nx + 0.5 + face.getStepX() * 0.5, fy = ny + 0.5 + face.getStepY() * 0.5;
            double fz = nz + 0.5 + face.getStepZ() * 0.5;
            double vx = player.getX() - fx, vy = player.getEyeY() - fy, vz = player.getZ() - fz;
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len < 1e-6) {
                continue;
            }
            double dot = (vx * face.getStepX() + vy * face.getStepY() + vz * face.getStepZ()) / len;
            if (dot > best) {
                best = dot;
                supX = nx;
                supY = ny;
                supZ = nz;
                supFace = face;
                found = true;
            }
        }
        return found;
    }

    private void tickPlug(Minecraft mc, LocalPlayer player, ClientLevel level) {
        cursor.set(lavaCell);
        if (!level.getFluidState(cursor).is(FluidTags.LAVA)) {
            Services.danger().clear(HazardKind.LAVA);
            finish(phase >= 2);
            return;
        }
        if (hands.consumePreempted() || ++runTicks > 80) {
            finish(false);
            return;
        }
        if (!hands.ensureLease()) {
            return;
        }
        switch (phase) {
            case 0 -> {
                int r = hands.holdItem(player, plug);
                if (r == FieldHands.FAILED) {
                    finish(false);
                } else if (r == FieldHands.DONE) {
                    phase = 1;
                }
            }
            case 1 -> {
                int r = hands.tickPlace(mc, player, supX, supY, supZ, supFace, relaxed);
                if (r == FieldHands.DONE) {
                    hands.pauseHuman(3);
                    phase = 2;
                } else if (r == FieldHands.FAILED) {
                    finish(false);
                }
            }
            default -> {
                if (!hands.coolingDown()) {
                    finish(true); // still lava after the placement: give up rather than spam blocks
                }
            }
        }
    }

    private void finish(boolean restoreSlot) {
        if (restoreSlot && previousSlot >= 0) {
            hands.selectSlot(previousSlot);
        }
        lavaCell = Long.MIN_VALUE;
        previousSlot = -1;
        hands.release();
    }

    @Override
    protected void stopWork() {
        lavaCell = Long.MIN_VALUE;
        watched = Long.MIN_VALUE;
        previousSlot = -1;
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
    }

    @Override
    public String displayName() {
        return "Lava Plug";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(relaxedSpec());
    }
}
