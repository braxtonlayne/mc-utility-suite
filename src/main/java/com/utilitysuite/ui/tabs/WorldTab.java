package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.SettingsAccess;
import com.utilitysuite.ui.SettingWidgetMapper;
import com.utilitysuite.world.overlays.VisualPresets;
import com.utilitysuite.ui.SettingsRenderer;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.world.camera.ScoutCameraModule;
import com.utilitysuite.world.minimap.MinimapModule;
import com.utilitysuite.ui.ModulePanels;
import com.utilitysuite.world.overlays.WorldOverlaysModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;
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
    /** Shared wording for the walk-back-along-the-trail control (Travel and World tabs). */
    static final String GO_BACK_TITLE = "Walk back along your trail";
    static final String GO_BACK_DESC = "Follows the recorded breadcrumbs";
    static final String GO_BACK_LABEL = "Go back";
    static final int GO_BACK_PILL_WIDTH = 68;

    /** Lease owner for waypoint Go / Take Me Back runs (the waypoints module id). */
    static final String NAV_OWNER = "waypoints";
    private static final int WP_ROW = 30;

    private WorldTab() {
    }

    public static String signature() {
        IWaypointService wps = Services.waypointsOrNull();
        IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
        return (wps == null ? -1 : wps.all().size()) + "/" + (wk == null ? "-" : wk.overlayMode() + ":" + wk.routingMode())
                + "/" + Services.modules().byCategory(ModuleCategory.WORLD).size()
                + "/" + Services.modules().find(ScoutCameraModule.class).map(c -> c.isFreecamActive()).orElse(false)
                + "/" + Services.modules().find(WorldOverlaysModule.class).map(IModule::isEnabled).orElse(false)
                + "/" + Services.modules().find(MinimapModule.class).map(IModule::isEnabled).orElse(false);
    }

    /** Custom rows inside World module cards (called once from the UI bootstrap). */
    public static void registerPanels() {
        ModulePanels.register(MinimapModule.ID, (p, m) -> {
            if (m instanceof MinimapModule mm) {
                QuickActions.actionRow(p, "Position and size", "Drag the map to move it, the corner to resize.",
                        "Edit HUD layout", PillButton.Style.SECONDARY, mm::openLayoutEditor);
            }
        });
    }

    /** Direct controls for the World features that also have (unbound by default) keys. */
    private static void quickActions(Panel p) {
        p.card("Quick actions", "freecam, overlays, minimap, waypoints");
        if (Services.modState().isCleanScreen()) {
            // Clean-Screen silently hides overlays, xray and notices; say so where people switch them on
            net.minecraft.client.KeyMapping key = com.utilitysuite.core.input.KeybindManager.mapping(
                    com.utilitysuite.core.input.KeybindManager.KeyId.CLEAN_SCREEN);
            String keyName = key == null ? "its key" : key.getTranslatedKeyMessage().getString();
            p.text("Clean-Screen is on: overlays, xray, the minimap and notices stay hidden until it is off.",
                    com.utilitysuite.ui.holo.HoloTheme.argb(com.utilitysuite.core.api.visual.HoloToken.WARN));
            QuickActions.actionRow(p, "Clean-Screen (" + keyName + ")",
                    "Same as the CLEAN switch in the top bar.", "Turn off", PillButton.Style.PRIMARY, () -> {
                        Services.modState().setCleanScreen(false);
                        p.toast("Clean-Screen off");
                        p.rebuild();
                    });
        }
        Services.modules().find(ScoutCameraModule.class).ifPresent(cam -> QuickActions.switchRow(p, "Freecam",
                "Detaches the camera so you can look around; your player stays put. Also on the Toggle Freecam key (Right Alt by default).",
                cam.isFreecamActive(), cam::toggleFreecam));
        Services.modules().find(WorldOverlaysModule.class).ifPresent(o -> QuickActions.moduleSwitch(p, o,
                "Show world overlays", "Ore / block outlines and markers (same as the World Overlays card switch)."));
        Services.modules().find(MinimapModule.class).ifPresent(mm -> {
            QuickActions.moduleSwitch(p, mm, "Show minimap",
                    "Top-down map in the corner (same as the Minimap card switch).");
            QuickActions.actionRow(p, "Minimap position and size", "Drag the map to move it, the corner to resize.",
                    "Edit HUD layout", PillButton.Style.SECONDARY, mm::openLayoutEditor);
        });
        IWaypointService wps = Services.waypointsOrNull();
        if (wps != null) {
            QuickActions.actionRow(p, "Waypoint at your position", "Uses the name typed in the Waypoints card, or "
                    + "Waypoint N", "Add here", PillButton.Style.SECONDARY, () -> addHere(p, wps));
        }
    }

    /** One-click visual presets (definitions in {@link VisualPresets}); two pills per row on narrow panels. */
    private static void presets(Panel p) {
        p.card("Presets", "one-click overlay setups");
        int w = p.width();
        List<VisualPresets.Preset> all = VisualPresets.ALL;
        int perRow = w >= 4 * 100 + 18 ? 4 : 2;
        int gap = 6;
        int bw = (w - gap * (perRow - 1)) / perRow;
        for (int i = 0; i < all.size(); i++) {
            VisualPresets.Preset preset = all.get(i);
            int col = i % perRow;
            p.button(preset.name(), preset.tooltip(), col * (bw + gap), bw,
                    isApplied(p, preset) ? PillButton.Style.PRIMARY : PillButton.Style.OUTLINE, // fill = applied
                    b -> applyPreset(p, preset));
            if (col == perRow - 1 || i == all.size() - 1) {
                p.nextRow();
            }
        }
    }

    /** Writes the preset through each module's settings API (onSettingChanged + markDirty) and sets enable flags. */
    static void applyPreset(Panel p, VisualPresets.Preset preset) {
        ProfileConfig profile = Services.modState().profile();
        int missing = 0;
        for (VisualPresets.Setting s : preset.settings()) {
            if (!writeSetting(profile, s)) {
                missing++;
            }
        }
        for (VisualPresets.Toggle t : preset.toggles()) {
            if (Services.modules().byId(t.moduleId()).isPresent()) {
                CoreBootstrap.modules().setModuleEnabled(t.moduleId(), t.enabled(), profile);
            } else {
                missing++;
            }
        }
        Services.modState().markDirty();
        p.setState(K_APPLIED_PRESET, preset.name());
        p.toast("Preset applied: " + preset.name() + (missing > 0 ? " (some modules not installed)" : ""));
        p.rebuild();
    }

    private static final String K_APPLIED_PRESET = "world.applied_preset";

    /** The preset last applied from this menu, while its module switches still match (changing one un-fills it). */
    static boolean isApplied(Panel p, VisualPresets.Preset preset) {
        if (!preset.name().equals(p.state(K_APPLIED_PRESET, ""))) {
            return false;
        }
        for (VisualPresets.Toggle t : preset.toggles()) {
            Optional<IModule> m = Services.modules().byId(t.moduleId());
            if (m.isPresent() && m.get().isEnabled() != t.enabled()) {
                return false;
            }
        }
        return true;
    }

    private static boolean writeSetting(ProfileConfig profile, VisualPresets.Setting s) {
        IModule m = Services.modules().byId(s.moduleId()).orElse(null);
        if (!(m instanceof IConfigurable c)) {
            return false;
        }
        for (SettingSpec spec : c.settings()) {
            if (!spec.key().equals(s.key())) {
                continue;
            }
            SettingsAccess access = new SettingsAccess(profile, m.id(), c::onSettingChanged,
                    Services.modState()::markDirty);
            Object v = s.value();
            if (v instanceof Boolean b) {
                access.setBool(spec, b);
            } else if (v instanceof Number n) {
                access.setNumber(spec, n.doubleValue());
            } else {
                access.setString(spec, String.valueOf(v));
            }
            return true;
        }
        return false;
    }

    public static void build(Panel p) {
        presets(p);
        quickActions(p);
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
            p.paragraph("World knowledge is not available (world module not loaded).", Panel.GRAY);
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
            p.paragraph("Waypoints are not available (world module not loaded).", Panel.GRAY);
            return;
        }
        List<Waypoint> all = wps.all();
        p.card("Waypoints", all.isEmpty() ? "none yet" : all.size() + " saved", 100,
                com.utilitysuite.ui.kit.CardNode.Tone.NORMAL);
        p.headerNode(new PillButton(p.font(), GO_BACK_LABEL, GO_BACK_PILL_WIDTH, PillButton.Style.SECONDARY,
                b -> takeMeBack(p, wps)).tooltip("Walk back along the recorded breadcrumb trail"), 0);
        int w = p.width();
        p.textField(K_WP_NAME, "New waypoint name", 0, w - 92);
        p.button("Add here", "Saves your current position (unnamed: Waypoint N)", w - 86, 86,
                PillButton.Style.PRIMARY, b -> addHere(p, wps));
        p.nextRow();
        if (all.isEmpty()) {
            p.paragraph("No waypoints yet. Press Add here (a name is optional).", Panel.GRAY);
            return;
        }
        p.gap(4);
        int n = Math.min(MAX_WAYPOINTS, all.size());
        // Two-line rows: name on top, compact "x y z · Dimension" under it, buttons right-aligned and centered on the
        // row, so narrow windows no longer cut both into "Playte..." / "800...".
        int delW = 50;
        int goW = 34;
        int btnGap = 6;
        int textW = w - delW - goW - btnGap - 12;
        for (int i = 0; i < n; i++) {
            Waypoint wp = all.get(i);
            String id = wp.id();
            String coords = wp.x() + " " + wp.y() + " " + wp.z() + "  ·  " + shortDim(wp.dimension());
            String group = wp.group() == null || wp.group().isEmpty() ? "" : "  ·  " + wp.group();
            var row = p.listRow(null);
            row.h = WP_ROW - 2;
            row.tooltip(wp.name() + "\n" + wp.x() + ", " + wp.y() + ", " + wp.z() + " · " + shortDim(wp.dimension())
                    + group);
            var name = p.rowLabel(wp.name(), 4, textW, Panel.WHITE);
            name.y -= 3;
            var pos = p.rowLabel(coords + group, 4, textW, Panel.GRAY);
            pos.y += 9;
            int shift = (WP_ROW - Panel.ROW) / 2;
            p.button("Go", "Path to " + wp.name(), w - delW - btnGap - goW, goW, PillButton.Style.SECONDARY,
                    b -> goTo(p, wps, wp)).y += shift;
            p.button("Delete", null, w - delW, delW, PillButton.Style.DANGER_SOFT, b -> {
                wps.remove(id);
                p.rebuild();
            }).y += shift;
            p.nextRow();
            p.gap(WP_ROW - Panel.ROW);
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
        if (nav.goToLeased(NAV_OWNER, ControlPriority.TRAVEL, BlockPos.getX(pos), BlockPos.getY(pos),
                BlockPos.getZ(pos), 2).isEmpty()) {
            p.toast("Movement is busy or automation is stopped");
            return;
        }
        p.toast("Going to " + wp.name());
    }

    /** Saves the player's position under the typed name, or "Waypoint N" when the name field is empty. */
    static void addHere(Panel p, IWaypointService wps) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (pl == null || mc.level == null) {
            p.toast("Join a world to add waypoints");
            return;
        }
        String name = p.state(K_WP_NAME, "").trim();
        if (name.isEmpty()) {
            name = "Waypoint " + (wps.all().size() + 1);
        }
        BlockPos pos = pl.blockPosition();
        wps.add(name, "Bases", pos.getX(), pos.getY(), pos.getZ(), mc.level.dimension().location().toString());
        p.setState(K_WP_NAME, "");
        p.toast("Waypoint '" + name + "' added");
        p.rebuild();
    }

    static void takeMeBack(Panel p, IWaypointService wps) {
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
        back = com.utilitysuite.world.waypoints.TrailPruner.prune(back); // loops walked once, not retraced
        if (nav.followPathLeased(NAV_OWNER, ControlPriority.TRAVEL, back).isEmpty()) {
            p.toast("Movement is busy or automation is stopped");
            return;
        }
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
