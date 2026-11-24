package com.utilitysuite.tools.player;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;
import java.util.Optional;

/**
 * Auto-Reconnect for multiplayer servers. When the disconnect screen opens after an unexpected drop, it shows a
 * countdown and a cancel button, then reconnects to the same server with {@link ConnectScreen#startConnecting}; attempts
 * are capped with backoff by {@link ReconnectPolicy}. Never after a manual disconnect (leaving from the pause menu) or a
 * kick/ban/whitelist message. The active goal's label is remembered at logout and, once back in the world and settled,
 * re-parsed and submitted to the goal engine unless a job is already running. Event-driven; the tick listener does
 * nothing unless a countdown or resume is pending.
 */
public final class AutoReconnect implements IModule, IConfigurable {

    public static final String ID = "auto_reconnect";
    public static final String K_DELAY = "delay_seconds";
    public static final String K_ATTEMPTS = "max_attempts";
    public static final String K_RESUME = "resume_job";
    static final int SETTLE_TICKS = 100;
    static final int HOLD_RECHECK_TICKS = 20;
    static final int MAX_HOLD_CHECKS = 60;

    private final ReconnectPolicy policy = new ReconnectPolicy();
    private boolean enabled;
    private boolean resumeJob = true;

    private ServerData lastServer;
    private boolean manualDisconnect;
    private int attempts;
    private Screen countingOn;
    private int countdown = -1;
    private String resumeLabel;
    private int settle = -1;
    private int holdChecks;

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
        if (!enabled) {
            cancel();
            resumeLabel = null;
            settle = -1;
        }
    }

    @Override
    public void onTick() {
        // see onClientTick: it must also run while no world is loaded
    }

    // ---- events ---------------------------------------------------------------------------------------------------

    /** Game bus. */
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        ServerData s = mc.getCurrentServer();
        lastServer = s != null && !mc.isLocalServer() ? s : null;
        manualDisconnect = false;
        attempts = 0;
        countdown = -1;
        countingOn = null;
        holdChecks = 0;
        settle = enabled && resumeJob && resumeLabel != null ? SETTLE_TICKS : -1;
    }

    /** Game bus. Leaving from the pause menu counts as a manual disconnect. */
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft mc = Minecraft.getInstance();
        manualDisconnect = mc.screen instanceof PauseScreen;
        settle = -1;
        if (!enabled || manualDisconnect) {
            resumeLabel = null;
            return;
        }
        IGoalEngine goals = Services.goalsOrNull();
        Optional<JobSnapshot> job = goals == null ? Optional.empty() : goals.activeJob();
        resumeLabel = job.map(JobSnapshot::goalLabel).orElse(null);
    }

    /** Game bus: the disconnect screen finished building its widgets (also fires again on resize). */
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (!enabled || !(event.getScreen() instanceof DisconnectedScreen screen) || lastServer == null) {
            return;
        }
        if (countingOn != screen) {
            String reason = reasonText(event.getListenersList(), screen);
            if (!policy.shouldReconnect(reason, manualDisconnect, attempts)) {
                countingOn = null;
                countdown = -1;
                return;
            }
            countingOn = screen;
            countdown = policy.delayTicks(attempts);
        }
        event.addListener(Button.builder(Component.literal("Cancel auto-reconnect"), b -> {
            cancel();
            b.active = false;
        }).bounds(screen.width / 2 - 100, screen.height - 28, 200, 20).build());
    }

    /** Game bus: the visible countdown. */
    public void onScreenRender(ScreenEvent.Render.Post event) {
        if (countdown < 0 || event.getScreen() != countingOn) {
            return;
        }
        Screen s = event.getScreen();
        int secs = (countdown + 19) / 20;
        event.getGuiGraphics().drawCenteredString(Minecraft.getInstance().font,
                "Reconnecting in " + secs + " s (attempt " + (attempts + 1) + "/" + policy.maxAttempts() + ")",
                s.width / 2, 12, 0xFFFFFF55);
    }

    /** Game bus. Early-out unless a countdown or resume is pending. */
    public void onClientTick(ClientTickEvent.Post event) {
        if (countdown < 0 && settle < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (countdown >= 0) {
            if (mc.screen != countingOn) {
                cancel(); // the user left the screen
            } else if (--countdown <= 0) {
                connect(mc);
            }
        }
        if (settle >= 0 && mc.player != null && mc.level != null && --settle < 0) {
            resume();
        }
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    private void connect(Minecraft mc) {
        countdown = -1;
        countingOn = null;
        ServerData s = lastServer;
        if (s == null) {
            return;
        }
        attempts++;
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(s.ip),
                s, false, null);
    }

    private void resume() {
        String label = resumeLabel;
        IGoalEngine goals = Services.goalsOrNull();
        if (label == null || goals == null || goals.activeJob().isPresent()) {
            resumeLabel = null;
            return;
        }
        String unsafe = unsafeNow();
        if (unsafe != null) {
            // Keep the job paused and look again every second for up to a minute; then drop it.
            if (++holdChecks > MAX_HOLD_CHECKS) {
                resumeLabel = null;
                holdChecks = 0;
                say("Job '" + label + "' not resumed: still " + unsafe, true);
                return;
            }
            if (holdChecks == 1) {
                say("Job '" + label + "' paused: " + unsafe + ". Resumes when it is safe.", true);
            }
            settle = HOLD_RECHECK_TICKS;
            return;
        }
        holdChecks = 0;
        resumeLabel = null;
        ParseResult r = goals.parse(label);
        if (r.isOk()) {
            long id = goals.submit(r.request());
            say("Resumed job #" + id + ": " + r.request().label(), false);
        } else {
            say("Could not resume '" + label + "': " + r.error(), true);
        }
    }

    /** Reason the player is not safe to resume the job (see {@link ReconnectSafety}), or null. */
    private static String unsafeNow() {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return "no player";
        }
        boolean airborne = !p.onGround() && !p.isInWater() && !p.onClimbable() && !p.getAbilities().flying
                && !p.isFallFlying();
        boolean falling = airborne && p.getDeltaMovement().y < -0.1;
        int sinceHurt = p.getLastHurtByMob() != null || p.hurtTime > 0
                ? p.tickCount - p.getLastHurtByMobTimestamp() : Integer.MAX_VALUE;
        return ReconnectSafety.unsafeReason(p.isInLava(), falling, sinceHurt, Services.danger().any());
    }

    public void cancel() {
        countdown = -1;
        countingOn = null;
    }

    static String reasonText(List<GuiEventListener> widgets, Screen screen) {
        StringBuilder b = new StringBuilder(screen.getTitle().getString());
        for (GuiEventListener l : widgets) {
            if (l instanceof AbstractWidget w && !(l instanceof Button)) {
                b.append(' ').append(w.getMessage().getString());
            }
        }
        return b.toString();
    }

    private static void say(String text, boolean error) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Services.visuals().notices().post(error ? com.utilitysuite.core.api.visual.NoticeKind.ERROR
                    : com.utilitysuite.core.api.visual.NoticeKind.DONE, text, null);
        }
    }

    @Override
    public void onWorldUnload() {
        // keeps lastServer / resumeLabel: they are exactly what a reconnect needs
    }

    @Override
    public void onStopAll() {
        cancel();
        resumeLabel = null;
        settle = -1;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        policy.configure(p.getInt(ID, K_ATTEMPTS, 5), p.getInt(ID, K_DELAY, 5));
        resumeJob = p.getBool(ID, K_RESUME, true);
    }

    @Override
    public String displayName() {
        return "Auto-Reconnect";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_DELAY, "First delay (seconds)", 5, 1, 120, "",
                        "Wait before the first attempt; each further attempt waits twice as long (max 5 minutes)."),
                SettingSpec.integer(K_ATTEMPTS, "Max attempts", 5, 1, 20, "",
                        "Gives up after this many failed attempts."),
                SettingSpec.bool(K_RESUME, "Resume the running job", true, "",
                        "Re-submits the goal that was running when the connection dropped."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
