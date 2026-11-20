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
import com.utilitysuite.ui.kit.PillButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.Consumer;

/**
 * World tab: Observed / Simulated / Hybrid selectors for overlays and for the miner's routing (independent), every
 * WORLD-category module as a settings card (custom panels via {@code ModulePanels}), and the waypoint manager (list,
 * add here, go, delete, Take Me Back along the breadcrumb trail). Everything degrades gracefully while the world
 * module is not installed or no world is loaded.
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
        // Overlay vs routing data sources are edited in the World Knowledge module card below (its settings), so the
        // separate selector card is shown only when that module is missing.
        if (Services.modules().byId("world_knowledge").isEmpty()) {
            knowledge(p);
        }
        waypoints(p);
        List<IModule> mods = Services.modules().byCategory(ModuleCategory.WORLD);
        p.section("World modules");
        if (mods.isEmpty()) {
            p.card("No world modules are installed", "");
        }
        for (IModule m : mods) {
            SettingsRenderer.module(p, m);
        }
    }

    private static void knowledge(Panel p) {
        p.card("Data sources", "where overlays and the miner get block data");
        IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
        if (wk == null) {
            p.text("World knowledge is not available (world module not loaded).", Panel.GRAY);
            return;
        }
        int w = p.width();
        int cw = Math.min(240, w * 58 / 100);
        p.rowLabel("Overlays show", 0, w - cw - 10, Panel.WHITE);
        source(p, wk.overlayMode(), w - cw, cw, wk::setOverlayMode);
        p.nextRow();
        p.rowLabel("Miner / routing uses", 0, w - cw - 10, Panel.WHITE);
        source(p, wk.routingMode(), w - cw, cw, wk::setRoutingMode);
        p.nextRow();
        p.paragraph("Observed = blocks you have seen. Simulated = seed predictions. Hybrid = both.", Panel.GRAY);
    }

    private static void source(Panel p, DataSourceMode current, int dx, int w, Consumer<DataSourceMode> set) {
        p.cycle(List.of(DataSourceMode.values()), current, WorldTab::label, dx, w, v -> {
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
        IWaypointService wps = Services.waypointsOrNull();
        if (wps == null) {
            p.card("Waypoints", "not available");
            p.text("Waypoints are not available (world module not loaded).", Panel.GRAY);
            return;
        }
        List<Waypoint> all = wps.all();
        p.card("Waypoints", all.isEmpty() ? "none yet" : all.size() + " saved", 100,
                com.utilitysuite.ui.kit.CardNode.Tone.NORMAL);
        p.headerNode(new PillButton(p.font(), "Take Me Back", 92, PillButton.Style.SECONDARY,
                b -> takeMeBack(p, wps)).tooltip("Walk back along the recorded breadcrumb trail"), 0);
        int w = p.width();
        p.textField(K_WP_NAME, "New waypoint name", 0, w - 92);
        p.button("Add here", "Saves your current position", w - 86, 86, PillButton.Style.PRIMARY, b -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer pl = mc.player;
            String name = p.state(K_WP_NAME, "").trim();
            if (pl == null || mc.level == null) {
                p.toast("Join a world to add waypoints");
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
        p.nextRow();
        if (all.isEmpty()) {
            p.text("No waypoints yet. Type a name and press Add here.", Panel.GRAY);
            return;
        }
        p.gap(4);
        int n = Math.min(MAX_WAYPOINTS, all.size());
        for (int i = 0; i < n; i++) {
            Waypoint wp = all.get(i);
            String id = wp.id();
            p.listRow(null);
            int textW = w - 120;
            int nameW = Math.min(textW / 2, p.font().width(wp.name()) + 4);
            p.rowLabel(wp.name(), 4, nameW, Panel.WHITE);
            p.rowLabel(wp.x() + " " + wp.y() + " " + wp.z() + "  ·  " + shortDim(wp.dimension())
                            + (wp.group() == null || wp.group().isEmpty() ? "" : "  ·  " + wp.group()),
                    nameW + 12, textW - nameW - 12, Panel.GRAY);
            p.button("Go", null, w - 110, 48, PillButton.Style.SECONDARY, b -> goTo(p, wps, wp));
            p.button("Delete", null, w - 58, 56, PillButton.Style.DANGER_SOFT, b -> {
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
