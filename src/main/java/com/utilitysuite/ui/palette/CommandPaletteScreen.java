package com.utilitysuite.ui.palette;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.ISuiteActions;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.core.api.SuiteAction;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.ui.SettingsAccess;
import com.utilitysuite.ui.kit.StyledTextField;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import com.utilitysuite.ui.tabs.WorldTab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Ctrl+K command palette: a text field with live fuzzy-ranked results over the action registry, module switches and
 * settings, waypoints ("Go to ...") and goal-engine commands, plus compound intents from {@link IntentParser} pinned at
 * the top ("route to nearest stronghold via nether", "where are my diamonds", "pulse sonar"). The selected result's
 * preview card shows what Enter will do before it runs. Up/Down select, Enter runs, Escape closes.
 * <p>
 * Results are rebuilt on each edit (typing time, not a hot path); rendering only reads the cached rows.
 */
public final class CommandPaletteScreen extends Screen {

    private static final int W = 340;
    private static final int ROWS = 8;
    private static final int ROW_H = 14;
    private static final int PAD = 8;

    private final List<PaletteEntry> base = new ArrayList<>();
    private final List<PaletteEntry> results = new ArrayList<>();
    private final List<String> preview = new ArrayList<>();
    private StyledTextField field;
    private int sel;

    public CommandPaletteScreen() {
        super(Component.literal("Command Palette"));
    }

    @Override
    protected void init() {
        String text = field == null ? "" : field.box().getValue();
        field = new StyledTextField(font, null, "Type a command, setting, waypoint or goal...", W - 2 * PAD,
                StyledTextField.H).prompt(true);
        field.box().setValue(text);
        field.box().setResponder(this::refresh);
        field.box().setFocused(true);
        if (base.isEmpty()) {
            buildBase();
        }
        refresh(text);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- sources ----------------------------------------------------------------------------------------------------

    private void buildBase() {
        ISuiteActions actions = Services.actionsOrNull();
        if (actions != null) {
            for (SuiteAction a : actions.all()) {
                if (a.id().equals("command_palette")) {
                    continue;
                }
                base.add(new PaletteEntry("Action", a.label(), "Runs " + a.label() + " (" + a.id() + ").",
                        a.label() + " " + a.keywords() + " " + a.id(), () -> {
                            actions.run(a.id());
                            return null;
                        }));
            }
        }
        ProfileConfig profile = Services.modState().profile();
        for (IModule m : Services.modules().all()) {
            String name = m instanceof IConfigurable c ? c.displayName() : m.id();
            base.add(new PaletteEntry("Module", (m.isEnabled() ? "Turn off " : "Turn on ") + name,
                    name + " is " + (m.isEnabled() ? "on" : "off") + ". Enter switches it.",
                    "toggle " + name + " " + m.id(), () -> {
                        boolean on = !m.isEnabled();
                        CoreBootstrap.modules().setModuleEnabled(m.id(), on, profile);
                        Services.modState().markDirty();
                        return name + (on ? " on" : " off");
                    }));
            if (!(m instanceof IConfigurable c)) {
                continue;
            }
            SettingsAccess access = new SettingsAccess(profile, m.id(), c::onSettingChanged,
                    Services.modState()::markDirty);
            for (SettingSpec s : c.settings()) {
                String label = name + ": " + s.label();
                if (s.type() == SettingSpec.Type.BOOL) {
                    boolean v = access.getBool(s);
                    base.add(new PaletteEntry("Setting", label, "Currently " + (v ? "on" : "off")
                            + ". Enter flips it (Undo reverts).", label + " " + s.key(), () -> {
                                boolean nv = !access.getBool(s);
                                access.setBool(s, nv);
                                return label + ": " + (nv ? "on" : "off");
                            }));
                } else {
                    String v = s.type() == SettingSpec.Type.INT || s.type() == SettingSpec.Type.DOUBLE
                            ? trimNumber(access.getNumber(s)) : access.getString(s);
                    base.add(new PaletteEntry("Setting", label, "Value: " + v
                            + ". Enter opens the suite menu to change it.", label + " " + s.key(), () -> {
                                Services.actionsOrNull().run("open_menu");
                                return null;
                            }));
                }
            }
        }
        IWaypointService wps = Services.waypointsOrNull();
        if (wps != null) {
            for (Waypoint wp : wps.all()) {
                base.add(waypointEntry(wps, wp, "Go to " + wp.name()));
            }
        }
    }

    private static PaletteEntry waypointEntry(IWaypointService wps, Waypoint wp, String label) {
        return new PaletteEntry("Waypoint", label, wp.x() + " " + wp.y() + " " + wp.z() + " in "
                + wp.dimension() + ". Enter paths there.", "go to " + wp.name() + " waypoint " + wp.group(),
                () -> WorldTab.goToWaypoint(wps, wp));
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    // ---- results ----------------------------------------------------------------------------------------------------

    private void refresh(String text) {
        results.clear();
        PaletteEntry intent = intentEntry(text);
        if (intent != null) {
            results.add(intent);
        }
        PaletteEntry goal = goalEntry(text);
        if (goal != null) {
            results.add(goal);
        }
        for (FuzzyScorer.Ranked<PaletteEntry> r : FuzzyScorer.rank(text, base, PaletteEntry::search,
                ROWS - results.size())) {
            results.add(r.item());
        }
        sel = 0;
        updatePreview();
    }

    private PaletteEntry goalEntry(String text) {
        IGoalEngine goals = Services.goalsOrNull();
        String t = text.trim();
        if (goals == null || t.isEmpty()) {
            return null;
        }
        ParseResult r;
        try {
            r = goals.parse(t);
        } catch (RuntimeException e) {
            return null;
        }
        if (!r.isOk()) {
            return null;
        }
        return new PaletteEntry("Goal", "Start goal: " + r.request().label(),
                "Goal engine command \"" + t + "\". Enter queues it as a job.", t, () -> {
                    long id = goals.submit(r.request());
                    return "Queued job #" + id + ": " + r.request().label();
                });
    }

    private PaletteEntry intentEntry(String text) {
        IntentParser.Intent in = IntentParser.parse(text);
        switch (in.kind()) {
            case ROUTE -> {
                return routeEntry(text.trim(), in);
            }
            case GO_TO -> {
                Waypoint wp = bestWaypoint(in.target());
                IWaypointService wps = Services.waypointsOrNull();
                return wp == null || wps == null ? new PaletteEntry("Intent", "Go to " + in.target(),
                        "No waypoint matches \"" + in.target() + "\".", "", () -> null)
                        : waypointEntry(wps, wp, "Go to " + wp.name());
            }
            case FIND_ITEM -> {
                return findEntry(in);
            }
            case ACTION -> {
                ISuiteActions actions = Services.actionsOrNull();
                SuiteAction a = actions == null ? null : actions.byId(in.target()).orElse(null);
                return a == null ? new PaletteEntry("Intent", in.target(), "That action is not available in this "
                        + "build.", "", () -> null)
                        : new PaletteEntry("Action", a.label(), "Runs " + a.label() + ".", "", () -> {
                            actions.run(a.id());
                            return null;
                        });
            }
            case TOGGLE -> {
                return toggleEntry(in);
            }
            default -> {
                return null;
            }
        }
    }

    private PaletteEntry routeEntry(String raw, IntentParser.Intent in) {
        String label = "Route to " + in.target() + (in.extra().isEmpty() ? "" : " via " + in.extra());
        IGoalEngine goals = Services.goalsOrNull();
        if (goals != null) {
            try {
                ParseResult r = goals.parse(raw);
                if (r.isOk()) {
                    return new PaletteEntry("Intent", label, "Goal engine: " + r.request().label()
                            + ". Enter queues it.", "", () -> "Queued job #" + goals.submit(r.request()) + ": "
                            + r.request().label());
                }
                Waypoint wp = bestWaypoint(in.target());
                IWaypointService wps = Services.waypointsOrNull();
                if (wp != null && wps != null) {
                    return new PaletteEntry("Intent", label, "Waypoint " + wp.name() + " (" + wp.x() + " " + wp.z()
                            + "). Enter paths there; far overworld trips use the Nether when cheaper.", "",
                            () -> WorldTab.goToWaypoint(wps, wp));
                }
                return new PaletteEntry("Intent", label, "No route: " + r.error()
                        + (r.suggestions().isEmpty() ? "" : " Try: " + String.join(", ", r.suggestions())), "",
                        () -> null);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return new PaletteEntry("Intent", label, "The goal engine is not loaded.", "", () -> null);
    }

    private PaletteEntry findEntry(IntentParser.Intent in) {
        IStorageIndex storage = Services.storageOrNull();
        Minecraft mc = Minecraft.getInstance();
        String label = "Where are my " + in.extra();
        if (storage == null || mc.player == null || mc.level == null) {
            return new PaletteEntry("Intent", label, "Storage Memory is not available.", "", () -> null);
        }
        String dim = mc.level.dimension().identifier().toString();
        List<StorageEntry> found = storage.containersWith(in.target(), dim, mc.player.blockPosition().asLong());
        int total = storage.count(in.target(), false);
        if (found.isEmpty()) {
            return new PaletteEntry("Intent", label, "No remembered container in this dimension holds "
                    + in.target() + " (" + total + " known in all).", "", () -> null);
        }
        StorageEntry near = found.get(0);
        int x = BlockPos.getX(near.packedPos());
        int y = BlockPos.getY(near.packedPos());
        int z = BlockPos.getZ(near.packedPos());
        return new PaletteEntry("Intent", label, total + " " + in.target() + " known in " + found.size()
                + " container(s); nearest " + near.containerType() + " at " + x + " " + y + " " + z
                + (near.stale() ? " (may be stale)" : "") + ". Enter paths to it.", "", () -> {
                    INavigator nav = Services.navigatorOrNull();
                    if (nav == null || !nav.isAvailable()) {
                        return "Nearest " + in.target() + ": " + x + " " + y + " " + z + " (pathing not available)";
                    }
                    return nav.goToLeased("storage_memory", ControlPriority.TRAVEL, x, y, z, 2).isEmpty()
                            ? "Movement is busy or automation is stopped" : "Going to " + x + " " + y + " " + z;
                });
    }

    private PaletteEntry toggleEntry(IntentParser.Intent in) {
        List<IModule> all = Services.modules().all();
        Function<IModule, String> name = m -> m instanceof IConfigurable c ? c.displayName() + " " + m.id() : m.id();
        List<FuzzyScorer.Ranked<IModule>> r = FuzzyScorer.rank(in.target(), all, name, 1);
        if (r.isEmpty()) {
            return new PaletteEntry("Intent", "Toggle " + in.target(), "No module matches.", "", () -> null);
        }
        IModule m = r.get(0).item();
        boolean on = in.flag() == null ? !m.isEnabled() : in.flag();
        String display = m instanceof IConfigurable c ? c.displayName() : m.id();
        return new PaletteEntry("Intent", (on ? "Turn on " : "Turn off ") + display,
                display + " is " + (m.isEnabled() ? "on" : "off") + ".", "", () -> {
                    CoreBootstrap.modules().setModuleEnabled(m.id(), on, Services.modState().profile());
                    Services.modState().markDirty();
                    return display + (on ? " on" : " off");
                });
    }

    private static Waypoint bestWaypoint(String name) {
        IWaypointService wps = Services.waypointsOrNull();
        if (wps == null) {
            return null;
        }
        List<FuzzyScorer.Ranked<Waypoint>> r = FuzzyScorer.rank(name, wps.all(), Waypoint::name, 1);
        return r.isEmpty() ? null : r.get(0).item();
    }

    private void updatePreview() {
        preview.clear();
        if (sel >= 0 && sel < results.size()) {
            wrap(results.get(sel).detail(), W - 2 * PAD - 12, preview);
        }
    }

    private void wrap(String text, int maxW, List<String> out) {
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (UiRenderUtils.width(font, next) > maxW && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(next);
            }
            if (out.size() >= 3) {
                return;
            }
        }
        if (!line.isEmpty()) {
            out.add(UiRenderUtils.ellipsize(font, line.toString(), maxW));
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        switch (keyCode) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                runSelected();
                return true;
            }
            case InputConstants.KEY_DOWN, InputConstants.KEY_TAB -> {
                move(1);
                return true;
            }
            case InputConstants.KEY_UP -> {
                move(-1);
                return true;
            }
            default -> {
                if (keyCode != InputConstants.KEY_ESCAPE && field.box().keyPressed(event)) {
                    return true;
                }
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return field.box().charTyped(event) || super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int x = left();
        int rowsY = top() + PAD + StyledTextField.H + 6;
        if (mx >= x && mx < x + W && my >= rowsY && my < rowsY + results.size() * ROW_H) {
            sel = (int) ((my - rowsY) / ROW_H);
            runSelected();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void move(int d) {
        if (results.isEmpty()) {
            return;
        }
        sel = Math.floorMod(sel + d, results.size());
        updatePreview();
    }

    private void runSelected() {
        if (sel < 0 || sel >= results.size()) {
            return;
        }
        Supplier<String> run = results.get(sel).run();
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(null); // close first: actions such as Open Menu need no screen open
        String status = run.get();
        if (status != null && mc.player != null && !Services.modState().isCleanScreen()) {
            mc.player.sendOverlayMessage(Component.literal(status));
        }
    }

    // ---- render -----------------------------------------------------------------------------------------------------

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return height / 6;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Font f = font;
        int x = left();
        int y = top();
        int rowsH = Math.max(1, results.size()) * ROW_H;
        int previewH = results.isEmpty() ? 0 : 18 + preview.size() * 10 + 6;
        int h = PAD + StyledTextField.H + 6 + rowsH + (previewH > 0 ? 6 + previewH : 0) + PAD;
        UiRenderUtils.shadow(g, x, y, W, h, 8, 3);
        UiRenderUtils.panel(g, x, y, W, h, 8, UiTheme.WINDOW, UiTheme.BORDER);
        field.render(g, f, x + PAD, y + PAD, mouseX, mouseY);
        int ry = y + PAD + StyledTextField.H + 6;
        if (results.isEmpty()) {
            UiRenderUtils.text(g, f, "No matches", x + PAD + 4, ry + 3, UiTheme.TEXT_MUTED);
        }
        for (int i = 0; i < results.size(); i++) {
            PaletteEntry e = results.get(i);
            int rowY = ry + i * ROW_H;
            if (i == sel) {
                UiRenderUtils.roundedRect(g, x + PAD - 2, rowY, W - 2 * PAD + 4, ROW_H, 4, UiTheme.ACCENT_TINT);
            }
            int kindW = UiRenderUtils.width(f, e.kind());
            UiRenderUtils.text(g, f, UiRenderUtils.ellipsize(f, e.label(), W - 2 * PAD - kindW - 16), x + PAD + 4,
                    rowY + 3, i == sel ? UiTheme.TEXT : UiTheme.TEXT_SECONDARY);
            UiRenderUtils.text(g, f, e.kind(), x + W - PAD - kindW - 2, rowY + 3, UiTheme.TEXT_MUTED);
        }
        if (previewH > 0 && sel < results.size()) {
            int py = ry + rowsH + 6;
            UiRenderUtils.panel(g, x + PAD, py, W - 2 * PAD, previewH, 6, UiTheme.CARD, UiTheme.ACCENT_BORDER);
            PaletteEntry e = results.get(sel);
            UiRenderUtils.text(g, f, UiRenderUtils.ellipsize(f, e.label(), W - 2 * PAD - 12), x + PAD + 6, py + 6,
                    UiTheme.TEXT);
            for (int i = 0; i < preview.size(); i++) {
                UiRenderUtils.text(g, f, preview.get(i), x + PAD + 6, py + 18 + i * 10, UiTheme.TEXT_SECONDARY);
            }
        }
    }
}
