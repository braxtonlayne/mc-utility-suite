package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.autopilot.TravelAutopilot;
import com.utilitysuite.travel.bridge.BridgeAssist;
import com.utilitysuite.travel.terrain.TerrainAssist;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.ui.ModulePanels;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingsRenderer;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.UiTheme;

import java.util.List;
import java.util.Locale;

/**
 * Travel tab: TerrainAssist, TravelAutopilot (destination entry: coordinates or a waypoint name, mode in its settings,
 * Start/Stop with a live status line) and BridgeAssist (Start/Stop, Mark point), each as a settings card. The custom
 * rows are registered as {@link ModulePanels} by {@link #registerPanels()}.
 */
public final class TravelTab {

    private static final String K_DEST = "travel.dest";

    private TravelTab() {
    }

    public static String signature() {
        StringBuilder sb = new StringBuilder();
        Services.modules().find(TravelAutopilot.class).ifPresent(a -> sb.append(a.isRunning()).append(a.runName()));
        Services.modules().find(BridgeAssist.class).ifPresent(b -> sb.append('/').append(b.isRunning()));
        Services.modules().find(TerrainAssist.class).ifPresent(t -> sb.append('/').append(t.isSurfaceActive()));
        return sb.toString();
    }

    /**
     * Direct controls for travel features that otherwise live behind (unbound by default) keys or deep in a card:
     * Water Walk (surface mode on/off + how it works), Step, NoSlowdown and Take Me Back.
     */
    private static void quickActions(Panel p) {
        p.card("Quick actions", "water walk, step, take me back");
        Services.modules().find(TerrainAssist.class).ifPresent(t -> {
            QuickActions.switchRow(p, "Water Walk", "Walk on top of water (and lava if allowed in Terrain Assist). "
                    + "Sneak to sink. Also on the Terrain surface key.", t.isSurfaceActive(), t::toggleSurfaceMode);
            QuickActions.settingRow(p, t, "surface_mode");
            QuickActions.settingRow(p, t, "step");
            QuickActions.settingRow(p, t, "no_slowdown");
        });
        IWaypointService wps = Services.waypointsOrNull();
        if (wps != null) {
            QuickActions.actionRow(p, "Walk back along your trail", "Take Me Back: follows the recorded breadcrumbs",
                    "Go back", PillButton.Style.SECONDARY, () -> WorldTab.takeMeBack(p, wps));
        }
    }

    public static void build(Panel p) {
        quickActions(p);
        List<IModule> mods = Services.modules().byCategory(ModuleCategory.TRAVEL);
        if (mods.isEmpty()) {
            p.card("No travel modules are installed", "");
        }
        for (IModule m : mods) {
            SettingsRenderer.module(p, m);
        }
    }

    /** Custom rows for the autopilot and bridge assist. */
    public static void registerPanels() {
        ModulePanels.register(TravelAutopilot.ID, (p, m) -> {
            if (!(m instanceof TravelAutopilot a)) {
                return;
            }
            int w = p.width();
            boolean running = a.isRunning();
            p.textField(K_DEST, "x y z, x z or waypoint name (stops: a > b)", 0, w - 84);
            p.button(running ? "Stop" : "Start", null, w - 78, 78,
                    running ? PillButton.Style.DANGER : PillButton.Style.PRIMARY, b -> {
                        if (a.isRunning()) {
                            a.stop();
                        } else {
                            String dest = p.state(K_DEST, "").trim();
                            boolean ok = dest.isEmpty() ? a.start() : a.start(dest);
                            if (!ok) {
                                p.toast("Autopilot did not start (see the message above the hotbar)");
                            }
                        }
                        p.rebuild();
                    });
            p.nextRow();
            p.dynamic(() -> a.isRunning() ? "Running: " + a.runName().toLowerCase(Locale.ROOT) : "Idle",
                    a.isRunning() ? UiTheme.RUNNING : Panel.GRAY).bullet(a.isRunning() ? UiTheme.RUNNING
                    : UiTheme.TRACK_OFF);
            p.gap(2);
        });
        ModulePanels.register(BridgeAssist.ID, (p, m) -> {
            if (!(m instanceof BridgeAssist b)) {
                return;
            }
            boolean running = b.isRunning();
            p.button(running ? "Stop bridging" : "Start bridging", null, 0, 108,
                    running ? PillButton.Style.DANGER : PillButton.Style.PRIMARY, btn -> {
                        b.toggle();
                        p.rebuild();
                    });
            p.button("Mark point", "Marks the current block as a line point (MARKED mode)", 114, 84, btn -> b.mark());
            p.nextRow();
        });
    }
}
