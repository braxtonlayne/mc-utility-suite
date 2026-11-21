package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.LeaseSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Elytra Swap: on a fall longer than the threshold with a usable elytra in the inventory, swap it into the chest slot
 * with ordinary inventory clicks (through the core clicker), open it with a jump tap, and after landing swap the
 * chestplate back. Off by default; also runs as the autopilot's {@code ELYTRA_SWAP} reflex. The click plan and state
 * machine are {@link ElytraSwapPlanner} (engine free). EMERGENCY lease over HOTBAR (inventory clicks) and MOVEMENT
 * (the tap); the camera is never touched. Not while a screen is open.
 */
public final class ElytraSwap implements IModule, IConfigurable {

    public static final String ID = "elytra_swap";
    static final String K_MIN_FALL = "min_fall_blocks";
    static final String K_SWAP_BACK = "swap_back";
    /** Height scanned below the feet for the ground (blocks). */
    static final int SCAN_DEPTH = 96;

    private final ElytraSwapPlanner planner = new ElytraSwapPlanner();
    private final LeaseSlot slot = new LeaseSlot(ID, Services::arbiter);
    private final LeaseRequest request = slot.request(ControlPriority.EMERGENCY, 400, ControlChannel.HOTBAR,
            ControlChannel.MOVEMENT);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private boolean enabled;
    private int minFallBlocks = 20;
    private boolean swapBack = true;
    private boolean underAutopilot;

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
        if (!enabled && planner.phase() != ElytraSwapPlanner.Phase.GLIDING) {
            stop();
        }
    }

    /** Client tick (called by the travel bootstrap so the reflex also runs while this module is off). */
    public void tick() {
        IAutopilot ap = Services.autopilotOrNull();
        boolean reflex = !enabled && ap != null && ap.allows(IAutopilot.Reflex.ELYTRA_SWAP);
        if (!enabled && !reflex && planner.phase() == ElytraSwapPlanner.Phase.IDLE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return;
        }
        if (slot.terminated()) {
            stop();
            return;
        }
        boolean idle = planner.phase() == ElytraSwapPlanner.Phase.IDLE;
        boolean grounded = p.onGround() || p.isInWater() || p.isInLava();
        if (idle && (grounded || p.getDeltaMovement().y >= -0.2 || p.getAbilities().flying
                || p.getAbilities().invulnerable || p.isPassenger() || mc.screen != null)) {
            return; // cheap early-out: nothing to do unless falling
        }
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        boolean wearing = chest.getItem() == Items.ELYTRA;
        int fallTicks = idle ? fallTicks(mc, p) : 0;
        int elytra = idle ? findElytra(p.getInventory()) : -1;
        // the same ticks measure as fallTicks: a fall of minFallBlocks from the current speed
        int threshold = idle ? ticksToFall(minFallBlocks, p.getDeltaMovement().y) : 0;
        if (idle && reflex && fallTicks >= threshold && elytra >= 0 && !wearing
                && !ap.mayAct(IAutopilot.Reflex.ELYTRA_SWAP, 3, 0f + minFallBlocks - 3)) {
            ap.noteDeclined(IAutopilot.Reflex.ELYTRA_SWAP, "another reflex goes first");
            return;
        }
        ControlLease l = slot.active();
        boolean pending = Services.clicker().pending() > 0;
        switch (planner.step(fallTicks, threshold, elytra, wearing, p.isFallFlying(), grounded, pending)) {
            case CLICK_SWAP_ON -> {
                l = slot.ensure(request);
                if (l == null || !queuePlan(l, planner.swappedFrom())) {
                    planner.reset();
                    slot.reset();
                    return;
                }
                underAutopilot = reflex;
                if (reflex) {
                    ap.noteActed(IAutopilot.Reflex.ELYTRA_SWAP, "elytra on");
                }
            }
            case TAP_JUMP -> {
                if (l != null) {
                    Services.input().scheduleKeyTap(l, SimKey.JUMP, 1, 2);
                }
            }
            case CLICK_SWAP_BACK -> {
                if (l == null) {
                    l = slot.ensure(request);
                }
                if (!swapBack || l == null || mc.screen != null || !queuePlan(l, planner.swappedFrom())) {
                    finish(ap);
                }
            }
            case DONE -> finish(ap);
            default -> {
            }
        }
    }

    private boolean queuePlan(ControlLease l, int inventoryIndex) {
        int n = planner.planSwap(inventoryIndex);
        if (n == 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!Services.clicker().queueClick(l, planner.slot(i), planner.button(i), planner.click(i))) {
                return false;
            }
        }
        return true;
    }

    private void finish(IAutopilot ap) {
        if (underAutopilot && ap != null) {
            ap.noteDone(IAutopilot.Reflex.ELYTRA_SWAP);
        }
        underAutopilot = false;
        planner.reset();
        slot.reset();
    }

    private void stop() {
        finish(Services.autopilotOrNull());
    }

    /** Inventory index (0..35) of a usable elytra, or -1. */
    static int findElytra(Inventory inv) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() == Items.ELYTRA && ElytraItem.isFlyEnabled(s)) {
                return i;
            }
        }
        return -1;
    }

    /** Ticks until the ground below (scan of at most {@link #SCAN_DEPTH} blocks); large when none. */
    private int fallTicks(Minecraft mc, LocalPlayer p) {
        int x = p.getBlockX();
        int z = p.getBlockZ();
        int y = p.getBlockY();
        int min = mc.level.getMinBuildHeight();
        for (int d = 0; d <= SCAN_DEPTH && y - d >= min; d++) {
            pos.set(x, y - d, z);
            if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()
                    || !mc.level.getFluidState(pos).isEmpty()) {
                double h = p.getY() - (y - d + 1);
                return mc.level.getFluidState(pos).isEmpty() ? ticksToFall(h, p.getDeltaMovement().y) : 0;
            }
        }
        return 1000;
    }

    /** Ticks to fall {@code height} blocks starting at vertical speed {@code vy} (vanilla gravity and drag). */
    static int ticksToFall(double height, double vy) {
        double fallen = 0.0;
        double v = vy;
        for (int t = 1; t <= 400; t++) {
            v = (v - 0.08) * 0.98;
            fallen -= v;
            if (fallen >= height) {
                return t;
            }
        }
        return 400;
    }

    @Override
    public void onTick() {
        // driven by tick() from the travel bootstrap (it must also run as the autopilot reflex while off)
    }

    @Override
    public void onStopAll() {
        stop();
    }

    @Override
    public void onWorldUnload() {
        stop();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        minFallBlocks = profile.getInt(ID, K_MIN_FALL, 20);
        swapBack = profile.getBool(ID, K_SWAP_BACK, true);
    }

    @Override
    public String displayName() {
        return "Elytra Swap";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_MIN_FALL, "Swap on falls of (blocks)", 20, 8, 200, "",
                        "Falling at least this far with an elytra in the inventory: put it on and open it."),
                SettingSpec.bool(K_SWAP_BACK, "Chestplate back after landing", true, "",
                        "After landing, swap the chestplate back on with the same clicks."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
