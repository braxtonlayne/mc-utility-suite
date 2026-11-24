package com.utilitysuite.tools.player;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Quick pearl key (unbound by default). Throws an ender pearl where the player already looks (no rotation): selects a
 * hotbar pearl through {@link com.utilitysuite.core.api.IInputSmoother#selectHotbarSlot}, or swaps one from the main
 * inventory into the selected slot through {@link IContainerClicker}, taps use, then restores the previous slot (and
 * swaps the moved stack back). All under one HOTBAR + WORLD_INTERACT lease. Key presses are polled with
 * {@code consumeClick()} only (no allocation while idle).
 *
 * <p>The steps are decided by the engine-free {@link PearlThrow}. The user's own input wins: a scroll, number key or
 * click ends the lease and with it the throw (a notice says so); nothing is reselected against the user. A stack that
 * was swapped into the hotbar by then is swapped back under a new lease once the user's input pause has passed, if the
 * inventory still looks the way this module left it.
 */
public final class QuickPearl implements LeaseListener {

    public static final String KEY_QUICK_PEARL = "key.utilitysuite.quick_pearl";
    static final String OWNER = "quick_pearl";
    static final int STEP_TIMEOUT = 20;

    private KeyMapping key;
    private final PearlPlan plan = new PearlPlan();
    private final PearlThrow seq = new PearlThrow(plan, () -> Services.humanizer().reactionTicks(2, 1, 3));
    private ControlLease lease;
    private LeaseRequest request;

    /** Mod bus. Unbound by default; active in game only. */
    public void onRegisterKeys(RegisterKeyMappingsEvent event) {
        key = new KeyMapping(KEY_QUICK_PEARL, KeyConflictContext.IN_GAME, InputConstants.UNKNOWN,
                com.utilitysuite.core.input.KeybindManager.CATEGORY);
        event.register(key);
    }

    public KeyMapping key() {
        return key;
    }

    /** Game bus. */
    public void onClientTick(ClientTickEvent.Post event) {
        KeyMapping k = key;
        if (k == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (seq.phase() == PearlThrow.Phase.IDLE) {
            boolean pressed = false;
            while (k.consumeClick()) {
                pressed = true;
            }
            if (seq.takeNotice()) {
                say("Pearl not thrown");
            }
            if (seq.owesSwapBack()) {
                settle(mc, player); // the swapped stack goes back before anything new starts
            } else if (pressed && player != null && mc.screen == null) {
                begin(player);
            }
            return;
        }
        if (player == null) {
            seq.reset();
            release();
            return;
        }
        if (lease == null || !lease.isActive()) {
            lease = null;
            seq.interrupted();
            return;
        }
        advance(player);
    }

    private void begin(LocalPlayer player) {
        if (player.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) {
            say("Pearl on cooldown");
            return;
        }
        Inventory inv = player.getInventory();
        if (!plan.plan(inv.selected, i -> inv.items.get(i).is(Items.ENDER_PEARL))) {
            say("No ender pearl");
            return;
        }
        Optional<ControlLease> got = Services.arbiter().request(request());
        if (got.isEmpty()) {
            say("Pearl key: controls are busy");
            return;
        }
        lease = got.get();
        boolean started = plan.needsSwap()
                ? Services.clicker().queueClick(lease, OffhandManager.menuSlotOf(plan.swapFromInv), plan.useSlot,
                        IContainerClicker.Click.SWAP)
                : !plan.needsSelect() || Services.input().selectHotbarSlot(lease, plan.useSlot);
        if (!started) {
            release();
            say("Pearl key: controls are busy");
            return;
        }
        seq.start();
    }

    private void advance(LocalPlayer player) {
        Inventory inv = player.getInventory();
        PearlThrow.Action action = seq.tick(inv.selected, inv.items.get(plan.useSlot).is(Items.ENDER_PEARL),
                Services.clicker().pending());
        if (action == PearlThrow.Action.USE) {
            action = seq.useTapped(Services.input().scheduleKeyTap(lease, SimKey.USE, 1, 2), inv.selected);
        }
        if (action == PearlThrow.Action.RESTORE) {
            if (seq.owesSwapBack()) {
                seq.swapBackQueued(Services.clicker().queueClick(lease, OffhandManager.menuSlotOf(plan.swapFromInv),
                        plan.useSlot, IContainerClicker.Click.SWAP));
            }
            if (seq.reselect()) {
                Services.input().selectHotbarSlot(lease, plan.restoreSlot);
            }
        } else if (action == PearlThrow.Action.FINISH) {
            release();
        }
        if (seq.takeNotice()) {
            say("Pearl not thrown");
        }
    }

    /**
     * Idle with a swap back owed (the lease ended while the pearl stack sat in the hotbar slot). Waits out Stop-All,
     * the user's hotbar pause and open screens, then swaps the stack back under a new lease; gives up when the
     * inventory no longer looks the way this module left it, or after {@link PearlThrow#DEBT_TIMEOUT} ticks.
     */
    private void settle(Minecraft mc, LocalPlayer player) {
        if (player == null) {
            seq.reset();
            return;
        }
        Inventory inv = player.getInventory();
        ItemStack inUse = inv.items.get(plan.useSlot);
        ItemStack inFrom = inv.items.get(plan.swapFromInv);
        if (!seq.debtStillValid(inUse.is(Items.ENDER_PEARL), inFrom.is(Items.ENDER_PEARL), inUse.isEmpty(),
                inFrom.isEmpty())) {
            seq.reset(); // already back, or the user has moved things since: nothing to undo
            return;
        }
        if (!seq.debtTick()) {
            say("Pearl key: the swapped stack was not put back");
            return;
        }
        IActionArbiter arbiter = Services.arbiter();
        if (mc.screen != null || arbiter.isAutomationStopped()
                || arbiter.isManualOverrideActive(ControlChannel.HOTBAR)) {
            return;
        }
        Optional<ControlLease> got = arbiter.request(request());
        if (got.isEmpty()) {
            return;
        }
        lease = got.get();
        seq.resumeRestore(Services.clicker().queueClick(lease, OffhandManager.menuSlotOf(plan.swapFromInv),
                plan.useSlot, IContainerClicker.Click.SWAP));
    }

    private void release() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            Services.arbiter().release(l);
        }
    }

    private LeaseRequest request() {
        if (request == null) {
            request = new LeaseRequest(OWNER, ControlPriority.COMBAT,
                    EnumSet.of(ControlChannel.HOTBAR, ControlChannel.WORLD_INTERACT), STEP_TIMEOUT * 4, this);
        }
        return request;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
            seq.interrupted(); // the notice and any owed swap back follow on the next ticks
        }
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.WARN, text, null);
        }
    }
}
