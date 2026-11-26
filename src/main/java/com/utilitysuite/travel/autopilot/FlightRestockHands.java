package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.Services;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.Minecraft;
import com.utilitysuite.travel.mobility.ElytraSwapPlanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Engine side of {@link FlightRestock}: reads the inventory into reusable arrays and queues the swap click through
 * {@link IContainerClicker} under the flight's lease (which already holds HOTBAR). Same swap convention as the other
 * jobs: the menu slot of main-inventory index i is i, and the click button is the hotbar index. Never clicks while a
 * screen is open. Allocation-free; only runs while the hotbar rockets are at the reserve.
 */
final class FlightRestockHands {

    private static final int MIN_ELYTRA_GAIN = 100;

    private final FlightRestock plan = new FlightRestock();
    private final int[] rocketCounts = new int[36];
    private final int[] hotbarKinds = new int[9];
    private final int[] elytraLeft = new int[36];
    private final int[] elytraMax = new int[36];
    private final boolean[] elytraMending = new boolean[36];
    private final ElytraSwapPlanner swapPlan = new ElytraSwapPlanner();
    private Holder<Enchantment> mending;
    private Level mendingLevel;

    void reset() {
        plan.reset();
    }

    /** Drops queued swaps when the flight lets go of its lease. */
    void cancel(ControlLease lease) {
        if (lease != null) {
            try {
                Services.clicker().cancel(lease);
            } catch (IllegalStateException ignored) {
                // no clicker registered (tests): nothing queued
            }
        }
    }

    /**
     * Maps the reserve verdict for a cruise tick: a low-rocket abort becomes CONTINUE while a restock from the main
     * inventory is possible or in progress, and stays ABORT_LOW_ROCKETS once none is left or height never allows it.
     */
    FlightReserve.Decision adjust(LocalPlayer player, ControlLease lease, FlightReserve.Decision d) {
        plan.tick();
        if (d == FlightReserve.Decision.CONTINUE) {
            plan.clearDefer();
            return d;
        }
        if (d != FlightReserve.Decision.ABORT_LOW_ROCKETS) {
            return d;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            rocketCounts[i] = s.is(Items.FIREWORK_ROCKET) ? s.getCount() : 0;
            if (i < 9) {
                hotbarKinds[i] = s.isEmpty() ? FlightRestock.EMPTY
                        : s.is(Items.FIREWORK_ROCKET) ? FlightRestock.ROCKET : FlightRestock.OTHER;
            }
        }
        IContainerClicker clicker = Services.clicker();
        boolean busy = Minecraft.getInstance().gui.screen() != null || clicker.pending() > 0;
        FlightRestock.Action a = plan.decide(FlightRestock.sumMain(rocketCounts), safelyHigh(player), busy);
        switch (a) {
            case ABORT:
                return d;
            case WAIT:
                return FlightReserve.Decision.CONTINUE;
            default:
                break;
        }
        int src = FlightRestock.pickSource(rocketCounts);
        int dst = FlightRestock.pickTarget(hotbarKinds, inv.getSelectedSlot());
        if (src < 0 || dst < 0 || !clicker.queueClick(lease, src, dst, IContainerClicker.Click.SWAP)) {
            return d; // no usable hotbar slot or the lease lost HOTBAR: land
        }
        return FlightReserve.Decision.CONTINUE;
    }

    /** True when the inventory holds a flyable elytra clearly healthier than the worn one. */
    boolean hasSpareElytra(LocalPlayer player, int wornUsesLeft) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            elytraLeft[i] = s.has(DataComponents.GLIDER) && !s.nextDamageWillBreak()
                    ? s.getMaxDamage() - s.getDamageValue() - 1 : -1;
        }
        return FlightRestock.spareElytraSlot(elytraLeft, wornUsesLeft, MIN_ELYTRA_GAIN) >= 0;
    }

    /**
     * Inventory index of the backup elytra to swap in ({@link BackupElytraSwap#pickSpare}: Mending first, more than a
     * quarter of its durability left), or -1. Only called once the worn elytra is near the reserve.
     */
    int pickBackupElytra(LocalPlayer player, int wornUsesLeft) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            boolean usable = s.has(DataComponents.GLIDER) && !s.nextDamageWillBreak();
            elytraLeft[i] = usable ? s.getMaxDamage() - s.getDamageValue() - 1 : -1;
            elytraMax[i] = usable ? s.getMaxDamage() : 0;
            elytraMending[i] = usable && hasMending(player, s);
        }
        return BackupElytraSwap.pickSpare(elytraLeft, elytraMax, elytraMending, wornUsesLeft);
    }

    /** Queues the chest-slot swap with inventory index {@code inventoryIndex} (the clicks a player makes by hand). */
    boolean queueElytraSwap(ControlLease lease, int inventoryIndex) {
        if (Minecraft.getInstance().gui.screen() != null) {
            return false;
        }
        int n = swapPlan.planSwap(inventoryIndex);
        if (n == 0) {
            return false;
        }
        IContainerClicker clicker = Services.clicker();
        for (int i = 0; i < n; i++) {
            if (!clicker.queueClick(lease, swapPlan.slot(i), swapPlan.button(i), swapPlan.click(i))) {
                clicker.cancel(lease);
                return false;
            }
        }
        return true;
    }

    /** A screen is open or clicks are still queued. */
    boolean busy() {
        return Minecraft.getInstance().gui.screen() != null || Services.clicker().pending() > 0;
    }

    /** The item carries Mending (the registry holder is looked up once per level). */
    boolean hasMending(LocalPlayer player, ItemStack s) {
        Level level = player.level();
        if (mendingLevel != level) {
            mendingLevel = level;
            mending = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.MENDING);
        }
        return EnchantmentHelper.getItemEnchantmentLevel(mending, s) > 0;
    }

    static boolean safelyHigh(LocalPlayer player) {
        if (player.onGround()) {
            return false;
        }
        int ground = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(player.getX()),
                (int) Math.floor(player.getZ()));
        return player.getY() - ground >= FlightRestock.SAFE_HEIGHT;
    }
}
