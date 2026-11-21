package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot;
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
    private final HudPill pill = new HudPill();
    private int stoppedW = -1;
    private int cleanW;
    private boolean pollNow;
    private IAutopilot.Reflex lastReflex;
    private int usedTop;
    private final InfoStrip infoStrip = new InfoStrip(this);
    private final ModuleListHud moduleList = new ModuleListHud(this);
    public static final String K_MODULE_LIST = "module_list";

    public static final String K_INFO = "info_strip";
    public static final String K_INFO_POSITION = "info_position";
    public static final String K_INFO_COORDS = "info_coords";
    public static final String K_INFO_CONVERT = "info_conversion";
    public static final String K_INFO_BIOME = "info_biome";
    public static final String K_INFO_TPS = "info_tps";
    public static final String K_INFO_PING = "info_ping";
    public static final String K_INFO_DURABILITY = "info_durability";
    private static final String INFO_GROUP = "Info strip";

    public void subscribe() {
        Services.events().subscribe(SuiteEvent.JobChanged.class, e -> pollNow = true);
        infoStrip.subscribe();
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
        if (!enabled) {
            line.clear();
        }
    }

    @Override
    public void onTick() {
        moduleList.tick();
        // the autopilot's acting reflex is short-lived: check it every tick (two field reads, no allocation)
        IAutopilot ap = Services.autopilotOrNull();
        IAutopilot.Reflex reflex = ap != null && ap.isOn() ? ap.activeReflex() : null;
        if (reflex != lastReflex) {
            lastReflex = reflex;
            pollNow = true;
        }
        if (!pollNow && ++ticks % POLL_TICKS != 0) {
            return;
        }
        pollNow = false;
        if (Services.modState().isCleanScreen()) {
            return;
        }
        if (reflex != null) {
            // what is acting right now outranks the job line; one line only
            line.updateAutopilot(ReflexChips.statusLine(reflex));
            return;
        }
        IGoalEngine goals = Services.goalsOrNull();
        Optional<JobSnapshot> job = goals == null ? Optional.empty() : goals.activeJob();
        if (job.isPresent() && !job.get().status().isTerminal()) {
            line.update(job.get()); // the pill re-measures itself when the line was rebuilt
        } else {
            SchematicToolkit t = SchematicBootstrap.toolkit();
            Optional<SchematicJobCard> card = t == null || !t.isEnabled() ? Optional.empty() : t.jobCard();
            if (card.isPresent()) {
                line.updateSchematic(card.get().statusLine());
            } else {
                line.clear();
            }
        }
    }

    @Override
    public void onWorldUnload() {
        line.clear();
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
        renderOverlay(g, mc.font, g.guiWidth(), Services.modState().isCleanScreen(),
                Services.arbiter().isAutomationStopped());
    }

    /**
     * Draws the pill and badges for the given state (also used by the UI preview with a fake job). Allocation-free in
     * the steady state: the pill re-measures only when the status line was rebuilt.
     */
    void renderOverlay(GuiGraphics g, Font font, int sw, boolean clean, boolean stopped) {
        if (stoppedW < 0) {
            stoppedW = font.width(STOPPED);
            cleanW = font.width(CLEAN);
        }
        usedTop = 0;
        if (clean) {
            if (cleanBadge) {
                HudPill.badge(g, font, CLEAN, cleanW, sw - HudPill.badgeWidth(cleanW) - 6, 6, false);
            }
            return;
        }
        int y = 6;
        if (enabled && !line.isEmpty()) {
            pill.layout(font, line, sw - 16);
            int w = pill.width();
            int x = switch (position) {
                case TOP_LEFT -> 6;
                case TOP_RIGHT -> sw - w - 6;
                default -> (sw - w) / 2;
            };
            pill.render(g, font, x, y);
            y += HudPill.H + 4;
        }
        if (stoppedBadge && stopped) {
            int w = HudPill.badgeWidth(stoppedW);
            int x = position == Position.TOP_RIGHT ? sw - w - 6 : position == Position.TOP_LEFT ? 6 : (sw - w) / 2;
            HudPill.badge(g, font, STOPPED, stoppedW, x, y, true);
            y += 14 + 4;
        }
        usedTop = y - 6;
    }

    /**
     * Vertical space (px) the pill and badges took at the top of the screen on {@code side} in the last frame, so the
     * info strip can sit below them instead of overlapping.
     */
    int topUsed(Position side) {
        return position == side ? usedTop : 0;
    }

    /** The optional classic list of enabled modules (top-right). */
    ModuleListHud moduleList() {
        return moduleList;
    }

    /** The optional info strip (settings live on this module). */
    InfoStrip infoStrip() {
        return infoStrip;
    }

    /** The status line driving the pill (preview harness feeds it a fake snapshot). */
    HudStatusLine line() {
        return line;
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
                        "Small badge while Clean-Screen hides everything else."),
                SettingSpec.bool(K_MODULE_LIST, "Enabled module list", false, "",
                        "Classic client list of the modules that are on, top-right, longest name first, coloured by"
                                + " category. The autopilot heads it while it is on. Hidden with F1, F3 and"
                                + " Clean-Screen."),
                SettingSpec.bool(K_INFO, "Info strip", false, INFO_GROUP,
                        "Compact strip with coordinates, biome, TPS, ping and durability. Hidden with F1, F3, "
                                + "open screens and Clean-Screen."),
                SettingSpec.choice(K_INFO_POSITION, "Strip position", InfoStrip.Position.TOP_LEFT, INFO_GROUP,
                        "Corner the info strip is drawn in."),
                SettingSpec.bool(K_INFO_COORDS, "Coordinates", true, INFO_GROUP, "X Y Z block coordinates."),
                SettingSpec.bool(K_INFO_CONVERT, "Nether/Overworld conversion", true, INFO_GROUP,
                        "Matching coordinates in the other dimension (x/8 or x*8). Not shown in the End."),
                SettingSpec.bool(K_INFO_BIOME, "Biome", true, INFO_GROUP, "Biome at your feet."),
                SettingSpec.bool(K_INFO_TPS, "TPS", true, INFO_GROUP,
                        "Server ticks per second (exact in single-player, estimated on servers)."),
                SettingSpec.bool(K_INFO_PING, "Ping", true, INFO_GROUP, "Your latency to the server."),
                SettingSpec.bool(K_INFO_DURABILITY, "Durability bars", true, INFO_GROUP,
                        "Bars for the main-hand item and worn armor (damageable items only)."));
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
        moduleList.enabled = p.getBool(ID, K_MODULE_LIST, false);
        moduleList.invalidate();
        InfoStrip s = infoStrip;
        s.enabled = p.getBool(ID, K_INFO, false);
        try {
            s.position = InfoStrip.Position.valueOf(p.getString(ID, K_INFO_POSITION, InfoStrip.Position.TOP_LEFT.name()));
        } catch (IllegalArgumentException e) {
            s.position = InfoStrip.Position.TOP_LEFT;
        }
        s.showCoords = p.getBool(ID, K_INFO_COORDS, true);
        s.showConversion = p.getBool(ID, K_INFO_CONVERT, true);
        s.showBiome = p.getBool(ID, K_INFO_BIOME, true);
        s.showTps = p.getBool(ID, K_INFO_TPS, true);
        s.showPing = p.getBool(ID, K_INFO_PING, true);
        s.showDurability = p.getBool(ID, K_INFO_DURABILITY, true);
    }

    /** Current status text (tests, diagnostics). */
    public String text() {
        return line.text();
    }
}
