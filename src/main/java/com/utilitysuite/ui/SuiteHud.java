package com.utilitysuite.ui;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicJobCard;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

import java.util.List;
import java.util.Optional;

/**
 * HUD discipline: a single registered GUI layer that draws nothing while Clean-Screen is on (except the opt-in
 * Clean-Screen badge), nothing when no job runs, and otherwise one compact status pill from the active goal job (or
 * the schematic job card's status line). An optional "automation stopped" badge (default on) and an optional
 * Clean-Screen indicator (default off) are the only other elements.
 * <p>
 * The text is refreshed on {@code JobChanged} (and by a slow safety poll every {@value #POLL_TICKS} ticks, which also
 * covers schematic jobs running outside the goal engine); steady-state ticks only count. {@link HudStatusLine} rebuilds the
 * string only when the snapshot changed, and the render callback only draws the cached string and its cached width.
 */
public final class SuiteHud implements IModule, IConfigurable, LayeredDraw.Layer {

    public static final String ID = "suite_hud";
    public static final String K_STOPPED_BADGE = "stopped_badge";
    public static final String K_CLEAN_BADGE = "clean_screen_badge";
    public static final String K_POSITION = "position";

    public enum Position { TOP_CENTER, TOP_LEFT, TOP_RIGHT }

    /** Safety poll; normally the pill updates on {@code JobChanged}, which the goal engine posts on every visible change. */
    private static final int POLL_TICKS = 40;
    private static final String STOPPED = "Automation stopped";
    private static final String CLEAN = "Clean-Screen";

    private final HudStatusLine line = new HudStatusLine();
    private boolean enabled = true;
    private boolean stoppedBadge = true;
    private boolean cleanBadge;
    private Position position = Position.TOP_CENTER;
    private int ticks;
    private int textWidth = -1;
    private boolean pollNow;

    public void subscribe() {
        Services.events().subscribe(SuiteEvent.JobChanged.class, e -> pollNow = true);
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CORE;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled && line.clear()) {
            textWidth = -1;
        }
    }

    @Override
    public void onTick() {
        if (!pollNow && ++ticks % POLL_TICKS != 0) {
            return;
        }
        pollNow = false;
        if (Services.modState().isCleanScreen()) {
            return;
        }
        boolean changed;
        IGoalEngine goals = Services.goalsOrNull();
        Optional<JobSnapshot> job = goals == null ? Optional.empty() : goals.activeJob();
        if (job.isPresent() && !job.get().status().isTerminal()) {
            changed = line.update(job.get());
        } else {
            SchematicToolkit t = SchematicBootstrap.toolkit();
            Optional<SchematicJobCard> card = t == null || !t.isEnabled() ? Optional.empty() : t.jobCard();
            changed = card.isPresent() ? line.updateSchematic(card.get().statusLine()) : line.clear();
        }
        if (changed) {
            textWidth = -1;
        }
    }

    @Override
    public void onWorldUnload() {
        line.clear();
        textWidth = -1;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read(profile);
    }

    // ---- layer ------------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) {
            return;
        }
        Font font = mc.font;
        int sw = g.guiWidth();
        if (Services.modState().isCleanScreen()) {
            if (cleanBadge) {
                badge(g, font, CLEAN, sw - font.width(CLEAN) - 6, 4, 0xA0303030, 0xFFB0B0B0);
            }
            return;
        }
        int y = 4;
        if (enabled && !line.isEmpty()) {
            if (textWidth < 0) {
                textWidth = font.width(line.text());
            }
            int w = Math.min(textWidth, sw - 16);
            int x = switch (position) {
                case TOP_LEFT -> 6;
                case TOP_RIGHT -> sw - w - 6;
                default -> (sw - w) / 2;
            };
            g.fill(x - 4, y - 2, x + w + 4, y + 10, 0xA0000000);
            g.fill(x - 4, y - 2, x - 3, y + 10, 0xFF55AAFF);
            g.drawString(font, line.text(), x, y, 0xFFFFFFFF, false);
            y += 15;
        }
        if (stoppedBadge && Services.arbiter().isAutomationStopped()) {
            int w = font.width(STOPPED);
            int x = position == Position.TOP_RIGHT ? sw - w - 6 : position == Position.TOP_LEFT ? 6 : (sw - w) / 2;
            badge(g, font, STOPPED, x, y, 0xA0401010, 0xFFFF7070);
        }
    }

    private static void badge(GuiGraphics g, Font font, String text, int x, int y, int bg, int fg) {
        g.fill(x - 3, y - 2, x + font.width(text) + 3, y + 10, bg);
        g.drawString(font, text, x, y, fg, false);
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "HUD status line";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice(K_POSITION, "Position", Position.TOP_CENTER, "",
                        "Where the job status pill is drawn."),
                SettingSpec.bool(K_STOPPED_BADGE, "\"Automation stopped\" badge", true, "",
                        "Small badge while Stop-All is active."),
                SettingSpec.bool(K_CLEAN_BADGE, "Clean-Screen indicator", false, "",
                        "Small badge while Clean-Screen hides everything else."));
    }

    @Override
    public void onSettingChanged(String key) {
        read(Services.modState().profile());
    }

    private void read(ProfileConfig p) {
        stoppedBadge = p.getBool(ID, K_STOPPED_BADGE, true);
        cleanBadge = p.getBool(ID, K_CLEAN_BADGE, false);
        try {
            position = Position.valueOf(p.getString(ID, K_POSITION, Position.TOP_CENTER.name()));
        } catch (IllegalArgumentException e) {
            position = Position.TOP_CENTER;
        }
    }

    /** Current status text (tests, diagnostics). */
    public String text() {
        return line.text();
    }
}
