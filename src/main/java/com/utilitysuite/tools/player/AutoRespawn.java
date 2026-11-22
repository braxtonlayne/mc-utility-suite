package com.utilitysuite.tools.player;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;
import java.util.Optional;

/**
 * Auto-Respawn: when the death screen opens, prints one local line with the death coordinates and presses respawn after
 * a short human delay (never on hardcore, where the button means spectate). The death waypoint is recorded by the
 * Waypoints module (and the Survival Autopilot) from {@link SuiteEvent.PlayerDied}; this module posts that event only
 * when neither of those reporters is enabled, so a death is never recorded twice.
 */
public final class AutoRespawn implements IModule, IConfigurable {

    public static final String ID = "auto_respawn";
    public static final String K_DELAY = "delay_ticks";
    static final String WAYPOINTS_ID = "waypoints";
    static final String SURVIVAL_ID = "survival_autopilot";
    /** Vanilla enables the death screen buttons after 20 ticks. */
    static final int MIN_DELAY = 22;

    private boolean enabled;
    private int delaySetting = 30;
    private int countdown = -1;

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
        countdown = -1;
    }

    /** Game bus. */
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (!enabled || !(event.getNewScreen() instanceof DeathScreen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        BlockPos p = player.blockPosition();
        String dim = mc.level.dimension().location().toString();
        String where = p.getX() + " " + p.getY() + " " + p.getZ() + " (" + dim + ")";
        Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.ERROR, "Died", where);
        // the coordinates stay in chat as well: a toast is gone in 4 s and the user copies these from the chat line
        player.displayClientMessage(Component.literal("[Suite] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal("Died at " + where).withStyle(ChatFormatting.GRAY)), false);
        if (!otherReporterActive()) {
            ISuiteEventBus events = Services.eventsOrNull();
            if (events != null) {
                events.post(new SuiteEvent.PlayerDied(System.currentTimeMillis(), p.asLong(), dim));
            }
        }
        if (mc.level.getLevelData().isHardcore()) {
            countdown = -1;
            return;
        }
        int median = Math.max(MIN_DELAY, delaySetting);
        countdown = Services.humanizer().reactionTicks(median, Math.max(MIN_DELAY, median - 10), median + 20);
    }

    private static boolean otherReporterActive() {
        IModuleRegistry reg = Services.modules();
        return isOn(reg, WAYPOINTS_ID) || isOn(reg, SURVIVAL_ID);
    }

    private static boolean isOn(IModuleRegistry reg, String id) {
        Optional<com.utilitysuite.core.api.IModule> m = reg.byId(id);
        return m.isPresent() && m.get().isEnabled();
    }

    @Override
    public void onTick() {
        if (countdown < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof DeathScreen) || mc.player == null) {
            countdown = -1; // respawned by hand, or left the world
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = -1;
        // same call as the death screen's own Respawn button
        mc.player.respawn();
    }

    @Override
    public void onStopAll() {
        countdown = -1;
    }

    @Override
    public void onWorldUnload() {
        countdown = -1;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        delaySetting = p.getInt(ID, K_DELAY, 30);
    }

    @Override
    public String displayName() {
        return "Auto-Respawn";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.integer(K_DELAY, "Respawn delay (ticks)", 30, MIN_DELAY, 200, "",
                "About how long to wait on the death screen (varied like a person). Never on hardcore."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
