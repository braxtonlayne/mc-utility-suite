package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ElytraItem;
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
        boolean busy = Minecraft.getInstance().screen != null || clicker.pending() > 0;
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
        int dst = FlightRestock.pickTarget(hotbarKinds, inv.selected);
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
            elytraLeft[i] = s.getItem() instanceof ElytraItem && ElytraItem.isFlyEnabled(s)
                    ? s.getMaxDamage() - s.getDamageValue() - 1 : -1;
        }
        return FlightRestock.spareElytraSlot(elytraLeft, wornUsesLeft, MIN_ELYTRA_GAIN) >= 0;
    }

    private static boolean safelyHigh(LocalPlayer player) {
        if (player.onGround()) {
            return false;
        }
        int ground = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(player.getX()),
                (int) Math.floor(player.getZ()));
        return player.getY() - ground >= FlightRestock.SAFE_HEIGHT;
    }
}
