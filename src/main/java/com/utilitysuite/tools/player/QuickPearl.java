package com.utilitysuite.tools.player;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
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
 */
public final class QuickPearl implements LeaseListener {

    public static final String KEY_QUICK_PEARL = "key.utilitysuite.quick_pearl";
    static final String OWNER = "quick_pearl";
    static final int STEP_TIMEOUT = 20;

    private enum Step { IDLE, SWAP_IN, SELECTED, THROWN, RESTORING }

    private KeyMapping key;
    private final PearlPlan plan = new PearlPlan();
    private Step step = Step.IDLE;
    private int wait;
    private int stepTicks;
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
        if (step == Step.IDLE) {
            boolean pressed = false;
            while (k.consumeClick()) {
                pressed = true;
            }
            if (pressed && player != null && mc.screen == null) {
                begin(player);
            }
            return;
        }
        if (player == null || lease == null || !lease.isActive()) {
            finish();
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
        stepTicks = 0;
        if (plan.needsSwap()) {
            Services.clicker().queueClick(lease, OffhandManager.menuSlotOf(plan.swapFromInv), plan.useSlot,
                    IContainerClicker.Click.SWAP);
            step = Step.SWAP_IN;
        } else {
            if (plan.needsSelect()) {
                Services.input().selectHotbarSlot(lease, plan.useSlot);
            }
            step = Step.SELECTED;
            wait = Services.humanizer().reactionTicks(2, 1, 3);
        }
    }

    private void advance(LocalPlayer player) {
        if (++stepTicks > STEP_TIMEOUT * 3) {
            finish();
            return;
        }
        Inventory inv = player.getInventory();
        switch (step) {
            case SWAP_IN -> {
                if (Services.clicker().pending() == 0 && inv.items.get(plan.useSlot).is(Items.ENDER_PEARL)) {
                    step = Step.SELECTED;
                    wait = Services.humanizer().reactionTicks(2, 1, 3);
                }
            }
            case SELECTED -> {
                if (--wait > 0) {
                    return;
                }
                if (plan.useSlot == inv.selected &&inv.items.get(plan.useSlot).is(Items.ENDER_PEARL)) {
                    Services.input().scheduleKeyTap(lease, SimKey.USE, 1, 2);
                }
                step = Step.THROWN;
                wait = 4;
            }
            case THROWN -> {
                if (--wait > 0) {
                    return;
                }
                if (plan.needsSwap()) {
                    Services.clicker().queueClick(lease, OffhandManager.menuSlotOf(plan.swapFromInv), plan.useSlot,
                            IContainerClicker.Click.SWAP);
                }
                if (plan.needsSelect()) {
                    Services.input().selectHotbarSlot(lease, plan.restoreSlot);
                }
                step = Step.RESTORING;
            }
            case RESTORING -> {
                if (Services.clicker().pending() == 0) {
                    finish();
                }
            }
            default -> finish();
        }
    }

    private void finish() {
        step = Step.IDLE;
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
            step = Step.IDLE;
        }
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.WARN, text, null);
        }
    }
}
