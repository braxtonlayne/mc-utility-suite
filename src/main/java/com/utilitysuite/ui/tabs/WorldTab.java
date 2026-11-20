package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingWidgetMapper;
import com.utilitysuite.ui.SettingsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * World tab: Observed / Simulated / Hybrid selectors for overlays and for the miner's routing (independent), every
 * WORLD-category module rendered generically (custom panels via {@code ModulePanels}), and the waypoint manager
 * (list, add here, delete, Take Me Back along the breadcrumb trail). Everything degrades gracefully while the world
 * module is not installed.
 */
public final class WorldTab {

    private static final String K_WP_NAME = "world.wpName";
    private static final int MAX_WAYPOINTS = 30;

    private WorldTab() {
    }

    public static String signature() {
        IWaypointService wps = Services.waypointsOrNull();
        IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
        return (wps == null ? -1 : wps.all().size()) + "/" + (wk == null ? "-" : wk.overlayMode() + ":" + wk.routingMode())
                + "/" + Services.modules().byCategory(ModuleCategory.WORLD).size();
    }

    public static void build(Panel p) {
        knowledge(p);
        List<IModule> mods = Services.modules().byCategory(ModuleCategory.WORLD);
        if (mods.isEmpty()) {
            p.heading("World modules");
            p.text("No world modules are installed.", Panel.GRAY);
        }
        for (IModule m : mods) {
            SettingsRenderer.module(p, m);
        }
        waypoints(p);
    }

    private static void knowledge(Panel p) {
        p.heading("Data sources");
        IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
        if (wk == null) {
            p.text("World knowledge is not available (world module not loaded).", Panel.GRAY);
            return;
        }
        int labelW = p.width() * 52 / 100;
        p.rowLabel("Overlays show", 4, labelW - 6, Panel.WHITE);
        p.place(sourceButton(wk.overlayMode(), p.width() - labelW - 4, v -> wk.setOverlayMode(v)), labelW + 4);
        p.nextRow();
        p.rowLabel("Miner / routing uses", 4, labelW - 6, Panel.WHITE);
        p.place(sourceButton(wk.routingMode(), p.width() - labelW - 4, v -> wk.setRoutingMode(v)), labelW + 4);
        p.nextRow();
        p.text("Observed = seen blocks only, Simulated = seed predictions, Hybrid = both.", Panel.GRAY);
    }

    private static CycleButton<DataSourceMode> sourceButton(DataSourceMode current, int w,
                                                           java.util.function.Consumer<DataSourceMode> set) {
        return CycleButton.<DataSourceMode>builder(m -> Component.literal(label(m)))
                .withValues(DataSourceMode.values())
                .withInitialValue(current)
                .displayOnlyValue()
                .create(0, 0, w, Panel.WIDGET_H, Component.literal("Source"), (b, v) -> {
                    set.accept(v);
                    Services.modState().markDirty();
                });
    }

    static String label(DataSourceMode m) {
        return switch (m) {
            case OBSERVED_ONLY -> "Observed";
            case SIMULATED_ONLY -> "Simulated";
            case HYBRID -> "Hybrid";
        };
    }

    private static void waypoints(Panel p) {
        p.heading("Waypoints");
        IWaypointService wps = Services.waypointsOrNull();
        if (wps == null) {
            p.text("Waypoints are not available (world module not loaded).", Panel.GRAY);
            return;
        }
        int w = p.width();
        p.textField(K_WP_NAME, "Waypoint name", 0, w - 184);
        p.button("Add here", w - 180, 86, b -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer pl = mc.player;
            String name = p.state(K_WP_NAME, "").trim();
            if (pl == null || mc.level == null) {
                return;
            }
            if (name.isEmpty()) {
                p.toast("Type a name first");
                return;
            }
            BlockPos pos = pl.blockPosition();
            wps.add(name, "Bases", pos.getX(), pos.getY(), pos.getZ(), mc.level.dimension().location().toString());
            p.setState(K_WP_NAME, "");
            p.toast("Waypoint '" + name + "' added");
            p.rebuild();
        });
        p.button("Take Me Back", "Walk back along the recorded breadcrumb trail", w - 90, 90, b -> takeMeBack(p, wps));
        p.nextRow();
        List<Waypoint> all = wps.all();
        if (all.isEmpty()) {
            p.text("No waypoints yet.", Panel.GRAY);
        }
        int n = Math.min(MAX_WAYPOINTS, all.size());
        for (int i = 0; i < n; i++) {
            Waypoint wp = all.get(i);
            String id = wp.id();
            p.rowLabel(wp.name() + "  " + wp.x() + " " + wp.y() + " " + wp.z() + "  " + shortDim(wp.dimension())
                    + (wp.group() == null || wp.group().isEmpty() ? "" : "  [" + wp.group() + "]"), 4, w - 134,
                    Panel.WHITE);
            p.button("Go", w - 126, 60, b -> goTo(p, wps, wp));
            p.button("Delete", w - 62, 62, b -> {
                wps.remove(id);
                p.rebuild();
            });
            p.nextRow();
        }
        if (all.size() > n) {
            p.text("+ " + (all.size() - n) + " more", Panel.GRAY);
        }
    }

    private static void goTo(Panel p, IWaypointService wps, Waypoint wp) {
        INavigator nav = Services.navigatorOrNull();
        Minecraft mc = Minecraft.getInstance();
        if (nav == null || !nav.isAvailable() || mc.level == null) {
            p.toast("Pathing is not available (Baritone not installed)");
            return;
        }
        long pos = wps.positionIn(wp, mc.level.dimension().location().toString());
        nav.goTo(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), 2);
        p.toast("Going to " + wp.name());
    }

    private static void takeMeBack(Panel p, IWaypointService wps) {
        INavigator nav = Services.navigatorOrNull();
        if (nav == null || !nav.isAvailable()) {
            p.toast("Pathing is not available (Baritone not installed)");
            return;
        }
        long[] crumbs = wps.breadcrumbs();
        if (crumbs.length == 0) {
            p.toast("No breadcrumbs recorded yet");
            return;
        }
        long[] back = new long[crumbs.length];
        for (int i = 0; i < crumbs.length; i++) {
            back[i] = crumbs[crumbs.length - 1 - i];
        }
        nav.followPath(back);
        p.toast("Taking you back along " + back.length + " breadcrumbs");
    }

    static String shortDim(String dim) {
        if (dim == null) {
            return "";
        }
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> SettingWidgetMapper.prettyEnum(dim.substring(dim.indexOf(':') + 1));
        };
    }
}
