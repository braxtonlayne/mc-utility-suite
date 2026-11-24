package com.utilitysuite.tools.player;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Auto-Fish. Bite detection is event-first: the bobber splash sound ({@link PlaySoundEvent}) within
 * {@link #BITE_RADIUS} blocks of the player's own hook; as a backup, the hook's sharp dip that the client applies when
 * the synced "biting" flag turns on (vertical speed below {@link #DIP_SPEED} after the hook has settled in water). The
 * reel and recast are use-key taps through {@link com.utilitysuite.core.api.IInputSmoother} under a WORLD_INTERACT
 * lease, after humanizer delays. It never turns the camera. When the rod in hand reaches the minimum durability (hook
 * reeled in) it selects another hotbar rod above the minimum through a short HOTBAR lease and carries on. Stops when
 * no such rod is left, the inventory is full, the rod leaves the hand, or the user takes over (manual override ends
 * the lease).
 */
public final class AutoFish implements IModule, IConfigurable, LeaseListener, FishingCycle.Delays {

    public static final String ID = "auto_fish";
    public static final String K_MIN_DURABILITY = "min_durability";
    public static final String K_STOP_FULL = "stop_when_full";
    static final double BITE_RADIUS_SQ = 2.0 * 2.0;
    static final double DIP_SPEED = -0.2;
    static final int SETTLE_TICKS = 20;
    static final int SWAP_RETRIES = 40;

    private final FishingCycle cycle = new FishingCycle(this);
    private boolean enabled;
    private int minDurability = 3;
    private boolean stopWhenFull = true;
    private ControlLease lease;
    private LeaseRequest request;
    private LeaseRequest swapRequest;
    private final int[] rodUses = new int[9];
    private int swapTries;
    private int inWaterTicks;
    private boolean announcedStop;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            cycle.start();
            announcedStop = false;
        } else {
            releaseLease();
        }
    }

    public FishingCycle cycle() {
        return cycle;
    }

    @Override
    public void onTick() {
        if (cycle.phase() == FishingCycle.Phase.STOPPED) {
            if (!announcedStop) {
                announcedStop = true;
                releaseLease();
                say("Auto-Fish stopped: " + cycle.stopReason().name().toLowerCase().replace('_', ' ')
                        + " (" + cycle.catches() + " catches)");
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || player.isDeadOrDying()) {
            return;
        }
        if (Services.arbiter().isAutomationStopped()) {
            return;
        }
        if (lease == null || !lease.isActive()) {
            if (Services.arbiter().isManualOverrideActive(ControlChannel.WORLD_INTERACT)) {
                return;
            }
            Optional<ControlLease> got = Services.arbiter().request(request());
            if (got.isEmpty()) {
                return;
            }
            lease = got.get();
        }
        ItemStack rod = player.getMainHandItem();
        boolean holding = rod.is(Items.FISHING_ROD);
        FishingHook hook = player.fishing;
        boolean inWater = hook != null && !hook.isRemoved() && hook.isInWater();
        inWaterTicks = inWater ? inWaterTicks + 1 : 0;
        if (inWater && inWaterTicks > SETTLE_TICKS && hook.getDeltaMovement().y < DIP_SPEED) {
            cycle.onBite();
        }
        int usesLeft = holding && rod.isDamageableItem() ? rod.getMaxDamage() - rod.getDamageValue() : Integer.MAX_VALUE;
        if (holding && usesLeft <= minDurability && cycle.phase() == FishingCycle.Phase.IDLE && swapRod(player)) {
            return;
        }
        boolean full = stopWhenFull && player.getInventory().getFreeSlot() < 0;
        FishingCycle.Action a = cycle.tick(holding, inWater, usesLeft, minDurability, full);
        if (a == FishingCycle.Action.USE) {
            Services.input().scheduleKeyTap(lease, SimKey.USE, 1, 3);
        }
    }

    /**
     * The rod in hand is at its minimum (the hook is reeled in: the cycle is IDLE). Selects another hotbar rod with more
     * than the minimum left, through a short HOTBAR lease. True when the swap was made or is still being tried (the
     * cycle must wait); false when no such rod exists, so the cycle stops as before.
     */
    private boolean swapRod(LocalPlayer player) {
        int current = player.getInventory().selected;
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().items.get(i);
            rodUses[i] = st.is(Items.FISHING_ROD)
                    ? (st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : Integer.MAX_VALUE)
                    : RodSwap.NO_ROD;
        }
        int slot = RodSwap.pick(rodUses, current, minDurability);
        if (slot < 0 || swapTries > SWAP_RETRIES) {
            swapTries = 0;
            return false;
        }
        Optional<ControlLease> got = Services.arbiter().request(swapRequest());
        if (got.isEmpty()) {
            swapTries++;
            return true;
        }
        ControlLease l = got.get();
        boolean ok = Services.input().selectHotbarSlot(l, slot);
        l.close();
        if (!ok) {
            swapTries++;
        } else {
            swapTries = 0;
            say("Auto-Fish: switched to the rod in slot " + (slot + 1));
        }
        return true;
    }

    private LeaseRequest swapRequest() {
        if (swapRequest == null) {
            swapRequest = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.HOTBAR), 5, this);
        }
        return swapRequest;
    }

    /** Game bus: the splash sound near the player's own hook is the bite. */
    public void onPlaySound(PlaySoundEvent event) {
        if (!enabled || cycle.phase() != FishingCycle.Phase.WAITING) {
            return;
        }
        SoundInstance s = event.getSound();
        if (s == null || !SoundEvents.FISHING_BOBBER_SPLASH.getLocation().equals(s.getLocation())) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        FishingHook hook = player == null ? null : player.fishing;
        if (hook == null) {
            return;
        }
        double dx = s.getX() - hook.getX();
        double dy = s.getY() - hook.getY();
        double dz = s.getZ() - hook.getZ();
        if (dx * dx + dy * dy + dz * dz <= BITE_RADIUS_SQ) {
            cycle.onBite();
        }
    }

    // ---- delays (humanized) ---------------------------------------------------------------------------------------

    @Override
    public int reelTicks() {
        return Services.humanizer().reactionTicks(5, 3, 12);
    }

    @Override
    public int recastTicks() {
        return Services.humanizer().reactionTicks(14, 9, 30);
    }

    // ---- lease ----------------------------------------------------------------------------------------------------

    private LeaseRequest request() {
        if (request == null) {
            request = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.WORLD_INTERACT), 0, this);
        }
        return request;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l != lease) {
            return;
        }
        lease = null;
        if (reason == LeaseEndReason.MANUAL_OVERRIDE || reason == LeaseEndReason.STOP_ALL) {
            cycle.stop(FishingCycle.StopReason.MANUAL);
        }
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            Services.input().releaseFor(l);
            Services.arbiter().release(l);
        }
    }

    @Override
    public void onStopAll() {
        lease = null;
        cycle.stop(FishingCycle.StopReason.MANUAL);
    }

    @Override
    public void onWorldUnload() {
        lease = null;
        cycle.stop(FishingCycle.StopReason.NONE);
        announcedStop = true;
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.WARN, text, null);
        }
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        minDurability = p.getInt(ID, K_MIN_DURABILITY, 3);
        stopWhenFull = p.getBool(ID, K_STOP_FULL, true);
    }

    @Override
    public String displayName() {
        return "Auto-Fish";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_MIN_DURABILITY, "Stop at rod durability", 3, 0, 64, "",
                        "Stops before the rod breaks (uses left). 0 = fish until it breaks."),
                SettingSpec.bool(K_STOP_FULL, "Stop when inventory is full", true, "",
                        "Stops when no free slot is left for the catch."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
