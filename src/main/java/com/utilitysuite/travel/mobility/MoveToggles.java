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
import com.utilitysuite.core.input.MoveInputs;
import com.utilitysuite.travel.LeaseSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import java.util.List;

/**
 * Toggle Sprint / Toggle Sneak. A press of the toggle key latches the key; the toggle key again, Stop-All or a screen
 * releases it. Legit: the game sends its normal packets.
 * <ul>
 *   <li>Sprint is held through the core ({@code IInputSmoother.setKeyHeld}) under an IDLE-priority MOVEMENT lease, so
 *       any automation outranks it. A movement key press pauses it (manual input wins) and it resumes when the
 *       arbiter frees the channel.</li>
 *   <li>Sneak is applied in the movement-input event (the input the game builds from the keys), not as a held key: a
 *       manual-override pause must never drop a sneak at a ledge. A fresh real sneak press (the input reports sneak
 *       while the latch is off-tick) releases it.</li>
 * </ul>
 */
public final class MoveToggles implements IModule, IConfigurable {

    public static final String ID = "move_toggles";
    static final String K_SPRINT = "sprint";
    static final String K_SNEAK = "sneak";

    private final ToggleLatch sprint = new ToggleLatch();
    private final ToggleLatch sneak = new ToggleLatch();
    private final LeaseSlot slot = new LeaseSlot(ID, Services::arbiter);
    private final LeaseRequest request = slot.request(ControlPriority.IDLE, 0, ControlChannel.MOVEMENT);
    private boolean enabled;
    private boolean sprintAllowed = true;
    private boolean sneakAllowed = true;
    private boolean sprintHeld;

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
            releaseAll();
        }
    }

    /** Toggle-sprint key pressed (dispatched by TravelKeys). */
    public void toggleSprint() {
        if (!enabled || !sprintAllowed) {
            return;
        }
        MobilityContext.say("Sprint", sprint.pressToggle() ? "latched" : "released");
    }

    /** Toggle-sneak key pressed. */
    public void toggleSneak() {
        if (!enabled || !sneakAllowed) {
            return;
        }
        MobilityContext.say("Sneak", sneak.pressToggle() ? "latched" : "released");
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!enabled || p == null) {
            return;
        }
        if (slot.terminated()) {
            sprint.release();
            sneak.release();
            slot.reset();
        }
        boolean want = sprint.tick(false, mc.gui.screen() != null);
        if (want) {
            ControlLease l = slot.ensure(request);
            if (l != null && !sprintHeld) {
                sprintHeld = Services.input().setKeyHeld(l, SimKey.SPRINT, true);
            } else if (l == null) {
                sprintHeld = false;
            }
        } else if (sprintHeld || slot.active() != null) {
            ControlLease l = slot.active();
            if (l != null) {
                Services.input().setKeyHeld(l, SimKey.SPRINT, false);
            }
            sprintHeld = false;
            slot.reset();
        }
    }

    /** Movement-input event: applies the sneak latch. A fresh real sneak while latched releases it. */
    public void onMovementInput(MovementInputUpdateEvent event) {
        if (!enabled || !sneak.latched()) {
            sneak.tick(false, false);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player) {
            return;
        }
        boolean realSneak = event.getInput().keyPresses.shift();
        if (!sneak.tick(realSneak, mc.gui.screen() != null)) {
            return;
        }
        // 26.3: the key state is an immutable record; MoveInputs hands out the cached instance (no allocation)
        Input k = event.getInput().keyPresses;
        event.getInput().keyPresses = MoveInputs.input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), true,
                k.sprint());
    }

    @Override
    public void onStopAll() {
        releaseAll();
    }

    @Override
    public void onWorldUnload() {
        releaseAll();
    }

    private void releaseAll() {
        sprint.release();
        sneak.release();
        sprintHeld = false;
        slot.reset();
    }

    public boolean sprintLatched() {
        return sprint.latched();
    }

    public boolean sneakLatched() {
        return sneak.latched();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read(profile);
    }

    private void read(ProfileConfig p) {
        sprintAllowed = p.getBool(ID, K_SPRINT, true);
        sneakAllowed = p.getBool(ID, K_SNEAK, true);
        if (!sprintAllowed) {
            sprint.release();
        }
        if (!sneakAllowed) {
            sneak.release();
        }
    }

    @Override
    public String displayName() {
        return "Sprint & Sneak Toggles";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_SPRINT, "Toggle sprint", true, "",
                        "The Toggle Sprint key latches sprint; press it again to release. Any automation goes first,"
                                + " and pressing a movement key pauses it a moment."),
                SettingSpec.bool(K_SNEAK, "Toggle sneak", true, "",
                        "The Toggle Sneak key latches sneak; press it again, or press your sneak key, to release."));
    }

    @Override
    public void onSettingChanged(String key) {
        read(Services.modState().profile());
    }
}
