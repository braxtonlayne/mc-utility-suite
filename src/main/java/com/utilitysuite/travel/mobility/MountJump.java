package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import java.util.List;

/**
 * Horse jump charge: tap jump on a jumpable mount (horse, camel, ...) and the jump goes off at a full bar. The
 * automation holds the jump key itself through the core and releases its own hold when the jump-riding scale reaches
 * the target ({@link MountJumpRelease}). A manual charge (holding jump longer than a tap) is left alone, and a jump
 * press during the automated charge preempts the lease (manual input wins), which ends it. Off by default.
 */
public final class MountJump implements IModule, IConfigurable {

    public static final String ID = "mount_jump";
    static final String K_TARGET = "target_percent";

    private final MountJumpRelease release = new MountJumpRelease();
    private final LeaseSlot slot = new LeaseSlot(ID, Services::arbiter);
    private final LeaseRequest request = slot.request(ControlPriority.TRAVEL, 60, ControlChannel.MOVEMENT);
    private boolean enabled;
    private float target = 0.95f;
    private boolean holding;

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
        if (!enabled) {
            stop();
        }
    }

    @Override
    public void onTick() {
        // work happens in the movement-input event (where the jump input is read)
    }

    /** Movement-input event, once per client tick. Allocation free. */
    public void onMovementInput(MovementInputUpdateEvent event) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || event.getEntity() != p) {
            return;
        }
        PlayerRideableJumping mount = p.jumpableVehicle();
        Entity vehicle = p.getVehicle();
        boolean jumpable = mount != null && mount.canJump() && vehicle != null && vehicle.onGround()
                && mc.gui.screen() == null;
        if (holding && slot.active() == null) {
            // the lease ended (the user's own key press, a higher priority, Stop-All): the charge is not ours
            holding = false;
            release.reset();
            slot.reset();
            return;
        }
        // while the automation holds the key the input reports its hold, not the user's
        boolean realDown = !holding && event.getInput().keyPresses.jump();
        switch (release.tick(jumpable, realDown, p.getJumpRidingScale(), target)) {
            case HOLD -> {
                ControlLease l = slot.ensure(request);
                if (l != null && Services.input().setKeyHeld(l, SimKey.JUMP, true)) {
                    holding = true;
                } else {
                    release.reset();
                }
            }
            case RELEASE -> stop();
            default -> {
            }
        }
    }

    private void stop() {
        ControlLease l = slot.active();
        if (l != null && holding) {
            Services.input().setKeyHeld(l, SimKey.JUMP, false);
        }
        holding = false;
        release.reset();
        slot.reset();
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
        target = profile.getInt(ID, K_TARGET, 95) / 100f;
    }

    @Override
    public String displayName() {
        return "Mount Jump Charge";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.integer(K_TARGET, "Release at bar %", 95, 70, 100, "",
                "Tap jump on a horse or camel: the jump is held and released when the bar reaches this. Hold jump"
                        + " yourself to charge by hand."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
