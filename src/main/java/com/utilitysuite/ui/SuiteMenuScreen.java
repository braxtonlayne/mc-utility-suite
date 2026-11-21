package com.utilitysuite.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.kit.CollapsibleSection;
import com.utilitysuite.ui.kit.GoalHeroCard;
import com.utilitysuite.ui.kit.ItemIcons;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.StyledTextField;
import com.utilitysuite.ui.kit.ToggleSwitch;
import com.utilitysuite.ui.kit.UiNode;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import com.utilitysuite.ui.tabs.CombatTab;
import com.utilitysuite.ui.tabs.JobsTab;
import com.utilitysuite.ui.tabs.ToolsTab;
import com.utilitysuite.ui.tabs.TravelTab;
import com.utilitysuite.ui.tabs.WorldTab;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The suite menu (Right Shift): a floating dark-glass window over a lightly dimmed game. Three zones: a top bar
 * (title, active profile, the Goal Console command field, Clean-Screen switch, Stop-All / Resume pill), a sidebar with
 * the five tabs (Jobs, World, Travel, Combat, Tools) and a status footer, and a scrolling card canvas built by the
 * tab through {@link Panel}. Everything is custom drawn (no vanilla buttons); text input uses vanilla edit boxes with
 * their background disabled inside drawn frames.
 * <p>
 * Layout is computed when the tab is (re)built: on structure changes detected by the tab's {@code signature()} (a job
 * starts or ends, a preview arrives, a list grows), every few ticks dynamic text refreshes in place. Rendering only
 * offsets nodes by the eased scroll and draws cached strings. Typed text, scroll positions and open sections survive
 * rebuilds and reopening the menu during the session.
 */
public final class SuiteMenuScreen extends Screen {

    public enum Tab {
        JOBS("Jobs"), WORLD("World"), TRAVEL("Travel"), COMBAT("Combat"), TOOLS("Tools");

        final String label;

        Tab(String label) {
            this.label = label;
        }

        ModuleCategory category() {
            return ModuleCategory.valueOf(name());
        }

        /** Short tag chip shown in front of a module row (column 2). */
        String chip() {
            return switch (this) {
                case JOBS -> "AUTO";
                case WORLD -> "VISUAL";
                case TRAVEL -> "MOVE";
                case COMBAT -> "COMBAT";
                case TOOLS -> "TOOL";
            };
        }

        int chipColor() {
            return switch (this) {
                case JOBS -> 0xFF38BDF8;
                case WORLD -> 0xFFA78BFA;
                case TRAVEL -> 0xFF34D399;
                case COMBAT -> 0xFFF87171;
                case TOOLS -> 0xFFFBBF24;
            };
        }

        Item icon() {
            return switch (this) {
                case JOBS -> Items.IRON_PICKAXE;
                case WORLD -> Items.GRASS_BLOCK;
                case TRAVEL -> Items.ELYTRA;
                case COMBAT -> Items.IRON_SWORD;
                case TOOLS -> Items.COMPARATOR;
            };
        }
    }

    private static final int REFRESH_TICKS = 10;
    private static final int TAB_H = 24;
    private static final int TAB_PITCH = 28;
    private static final int GOAL_H = 20;
    private static final int START_W = 44;
    private static final long TOOLTIP_DELAY_MS = 350L;

    private static Tab lastTab = Tab.JOBS;
    private static final int[] SCROLL = new int[Tab.values().length];
    private static final Map<String, String> STATE = new HashMap<>();
    /** Column 2 selection per category: "" = the category's overview page, else a module id. */
    private static final Map<Tab, String> SELECTED = new EnumMap<>(Tab.class);
    static final String K_SEARCH = "menu.search";
    private static final int ROW_H = 18;
    private static final int ROW_H_SUB = 27;
    private static final int SEARCH_H = 18;

    private final Screen parent;
    private final long openedAt = Util.getMillis();
    /** A press of the open key closes the menu only after the press that opened it was released. */
    private boolean openKeyReleased = !openKeyHeld();
    private String closeReason;
    private static long lastClosedAt;
    private static final long OPEN_KEY_GRACE_MS = 250L;
    private final Map<EditBox, String> textFieldKeys = new HashMap<>();
    private Tab tab = lastTab;

    // geometry (GUI px), computed in init()
    private int wx;
    private int wy;
    private int ww;
    private int wh;
    private int sidebarW;
    private int listX;
    private int listW;
    private int listTop;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;

    // chrome
    private final List<UiNode> chrome = new ArrayList<>();
    private StyledTextField goalField;
    private PillButton startPill;
    private String profileText = "";
    private int profilePillW;
    private int titleW;
    private String cleanLabel = "";
    private int cleanLabelX;

    private String versionText = "";

    // column 2: module list
    private StyledTextField searchField;
    private List<UiNode> listNodes = List.of();
    private int listContentH;
    private int listScroll;

    // content
    private Panel panel;
    private List<UiNode> nodes = List.of();
    private int contentHeight;
    private float scrollCur;
    private int scrollTarget;
    private boolean scrollDragging;
    private long lastFrame;
    private Tab builtTab;

    // interaction
    private UiNode focusNode;
    private UiNode dragNode;
    private boolean dragInContent;
    private UiNode hoverNode;
    private long hoverSince;
    private String tipText = "";
    private List<FormattedCharSequence> tipLines = List.of();
    private int tipW;

    private boolean rebuildRequested;
    private String signature = "";
    private int ticks;
    private String status = "";
    private int statusTicks;
    private String footerStatus = "";
    private String footerJob = "";
    private boolean footerStopped;
    private int footerH = 42;
    private int tabPitch = TAB_PITCH;

    public SuiteMenuScreen(Screen parent) {
        super(Component.literal("Utility Suite"));
        this.parent = parent;
        logOpen();
    }

    /** Diagnostic, once per menu instance (never per tick): who opened the menu and the open key's state. */
    private void logOpen() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < Math.min(st.length, 8); i++) { // skip logOpen and the constructor
            sb.append("\n    at ").append(st[i]);
        }
        var key = KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU);
        UtilitySuiteMod.LOGGER.info("Suite menu opened (open key held={}, mapping down={}, release seen={}){}",
                !openKeyReleased, key != null && key.isDown(), openKeyReleased, sb);
    }

    // ---- state used by Panel and the goal console -------------------------------------------------------------------

    String state(String key, String def) {
        return STATE.getOrDefault(key, def);
    }

    void setState(String key, String value) {
        if (value == null || value.isEmpty()) {
            STATE.remove(key);
        } else {
            STATE.put(key, value);
        }
    }

    void requestRebuild() {
        rebuildRequested = true;
    }

    void setStatus(String message) {
        status = message == null ? "" : message;
        statusTicks = status.isEmpty() ? 0 : 100;
    }

    void clearGoalField() {
        if (goalField != null) {
            goalField.box().setValue("");
        }
    }

    public Tab tab() {
        return tab;
    }

    /** Read-only view of the current tab's content nodes (for the dev playtest harness and UI preview). */
    public List<UiNode> contentNodes() {
        return java.util.Collections.unmodifiableList(nodes);
    }

    /** Switches to a tab (also used by the UI preview / playtest harness). */
    public void selectTab(Tab t) {
        if (t == null || t == tab) {
            return;
        }
        SCROLL[tab.ordinal()] = scrollTarget;
        tab = t;
        lastTab = t;
        focusNode = null;
        rebuildKeepingFocus();
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        textFieldKeys.clear();
        chrome.clear();
        focusNode = null;
        dragNode = null;
        hoverNode = null;

        ww = Math.min(UiTheme.WINDOW_W, (int) (width * 0.9f));
        wh = Math.min(UiTheme.WINDOW_H, (int) (height * 0.88f));
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        sidebarW = ww < 640 ? UiTheme.SIDEBAR_W_COMPACT : UiTheme.SIDEBAR_W;
        listX = wx + sidebarW + 1;
        // column 2 only when the content column keeps a usable width; otherwise the two-column layout (overview pages)
        listW = ww < UiTheme.THREE_COLUMN_MIN_W ? 0 : ww < 720 ? UiTheme.LIST_W_COMPACT : UiTheme.LIST_W;
        viewX = listX + (listW > 0 ? listW + 1 : 0);
        viewY = wy + UiTheme.TOP_BAR_H + 1;
        viewW = wx + ww - viewX;
        viewH = wy + wh - viewY - 1;
        if (versionText.isEmpty()) {
            versionText = modVersion();
        }

        buildChrome();
        buildList();
        buildContent();
        signature = signature();
    }

    private void buildChrome() {
        Font f = font;
        // left: title + profile pill
        titleW = f.width(TITLE) + (versionText.isEmpty() ? 0 : 8 + f.width(versionText) + 8);
        String profile = safeProfile();
        int leftEnd = wx + 24 + titleW; // the title is drawn at wx + 24 (after the accent dot)
        boolean showProfile = ww >= 470;
        if (showProfile) {
            profileText = UiRenderUtils.ellipsize(f, profile, 96);
            profilePillW = f.width(profileText) + 18;
            leftEnd += 8 + profilePillW;
        } else {
            profileText = "";
            profilePillW = 0;
        }

        // right: Stop-All / Resume pill, Clean-Screen switch
        boolean stopped = automationStopped();
        boolean narrow = ww < 560;
        String stopLabel = stopped ? "Resume" : narrow ? "Stop" : "Stop all";
        int stopW = f.width(stopLabel) + 20;
        PillButton stop = new PillButton(f, stopLabel, stopW, stopped ? PillButton.Style.SUCCESS
                : PillButton.Style.DANGER, b -> toggleStopAll()).pill(true);
        stop.tooltip(stopped ? "Automation is stopped. Resume lets modules take control again."
                : "Stop-All: revokes every automation lease at once.");
        stop.x = wx + ww - 12 - stopW;
        stop.y = wy + (UiTheme.TOP_BAR_H - PillButton.H) / 2;
        chrome.add(stop);

        ToggleSwitch clean = new ToggleSwitch(cleanScreen(), v -> {
            try {
                Services.modState().setCleanScreen(v);
            } catch (RuntimeException e) {
                UtilitySuiteMod.LOGGER.warn("Clean-Screen toggle failed", e);
            }
        }).animKey("chrome.clean");
        clean.tooltip("Clean-Screen hides every overlay and the HUD pill.");
        clean.x = stop.x - 12 - ToggleSwitch.W;
        clean.y = wy + (UiTheme.TOP_BAR_H - ToggleSwitch.H) / 2;
        chrome.add(clean);
        cleanLabel = narrow ? "" : "Clean screen"; // narrow: the switch's tooltip names it
        cleanLabelX = narrow ? clean.x : clean.x - 6 - f.width(cleanLabel);

        // center: goal bar, centered over the content canvas, clamped between the two groups
        int regionL = leftEnd + 14;
        int regionR = cleanLabelX - 10;
        int gw = Math.max(40, Math.min(320, regionR - regionL));
        int center = viewX + viewW / 2;
        int gx = Math.max(regionL, Math.min(regionR - gw, center - gw / 2));
        goalField = new StyledTextField(f, GoalConsole.K_GOAL, narrow ? "Goal, e.g. gather 32 diamonds"
                : "Goal: gather 32 diamonds, craft 16 hoppers...", gw,
                GOAL_H).prompt(true).reserveRight(START_W - 2);
        goalField.x = gx;
        goalField.y = wy + (UiTheme.TOP_BAR_H - GOAL_H) / 2;
        EditBox box = goalField.box();
        box.setMaxLength(256);
        box.setValue(state(GoalConsole.K_GOAL, ""));
        box.setResponder(v -> setState(GoalConsole.K_GOAL, v));
        registerField(goalField);
        chrome.add(goalField);
        startPill = new PillButton(f, "Start", START_W - 6, PillButton.Style.PRIMARY, b -> GoalConsole.submit(this))
                .pill(true);
        startPill.tooltip("Queue this goal (or press Enter again)");
        startPill.h = 14;
        startPill.x = gx + gw - START_W + 3;
        startPill.y = goalField.y + (GOAL_H - 14) / 2;
        chrome.add(startPill);

        // sidebar tabs; short windows (GUI scale 3-4) get tighter tabs and a one-line footer
        Tab[] tabs = Tab.values();
        int sideH = wy + wh - viewY;
        footerH = 42;
        tabPitch = Math.min(TAB_PITCH, (sideH - 8 - footerH - 4) / tabs.length);
        if (tabPitch < 22) {
            footerH = 26;
            tabPitch = Math.max(16, Math.min(TAB_PITCH, (sideH - 8 - footerH - 4) / tabs.length));
        }
        int tabH = Math.min(TAB_H, tabPitch - 2);
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            SidebarTab st = new SidebarTab(f, t, t == tab, sidebarW - 16, tabH, enabledCount(t));
            st.x = wx + 8;
            st.y = viewY + 8 + i * tabPitch;
            chrome.add(st);
        }
        refreshFooter();
    }

    // ---- column 2: search + module list -----------------------------------------------------------------------------

    private static final String TITLE = "UTILITY SUITE";

    private static String modVersion() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(UtilitySuiteMod.MOD_ID)
                    .map(c -> "v" + c.getModInfo().getVersion()).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int enabledCount(Tab t) {
        try {
            int n = 0;
            for (IModule m : Services.modules().byCategory(t.category())) {
                if (m.isEnabled()) {
                    n++;
                }
            }
            return n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The module selected in column 2 for the current tab, or null for the overview page. */
    private IModule selectedModule() {
        if (listW == 0) {
            return null;
        }
        String id = SELECTED.getOrDefault(tab, "");
        if (id.isEmpty()) {
            return null;
        }
        try {
            Optional<IModule> m = Services.modules().byId(id);
            return m.isPresent() && m.get().category() == tab.category() ? m.get() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Selects a column-2 entry ("" = overview) in a category, switching to that category if needed. */
    void selectModule(Tab t, String moduleId) {
        SELECTED.put(t, moduleId == null ? "" : moduleId);
        if (t != tab) {
            SCROLL[tab.ordinal()] = scrollTarget;
            tab = t;
            lastTab = t;
        }
        SCROLL[tab.ordinal()] = 0;
        builtTab = null; // reset the content scroll for the new page
        focusNode = null;
        rebuildKeepingFocus();
    }

    private void buildList() {
        if (listW == 0) {
            searchField = null;
            listNodes = List.of();
            return;
        }
        Font f = font;
        int pad = 8;
        searchField = new StyledTextField(f, K_SEARCH, "Search modules, settings…", listW - 2 * pad, SEARCH_H);
        searchField.x = listX + pad;
        searchField.y = viewY + pad;
        EditBox box = searchField.box();
        box.setMaxLength(64);
        box.setValue(state(K_SEARCH, ""));
        box.setResponder(v -> {
            if (!v.equals(state(K_SEARCH, ""))) {
                setState(K_SEARCH, v);
                listScroll = 0;
                requestRebuild();
            }
        });
        registerField(searchField);
        chrome.add(searchField);
        listTop = searchField.y + SEARCH_H + 6;

        List<UiNode> out = new ArrayList<>();
        int y = 0;
        int rowW = listW - 2 * 4;
        String q = state(K_SEARCH, "").trim().toLowerCase(Locale.ROOT);
        String sel = SELECTED.getOrDefault(tab, "");
        try {
            if (q.isEmpty()) {
                y = addCaption(out, tab.label.toUpperCase(Locale.ROOT), y, rowW);
                y = addRow(out, new ModuleRow(f, tab, "", "Overview", "", false, true, sel.isEmpty(), rowW), y);
                List<IModule> mods = Services.modules().byCategory(tab.category());
                if (!mods.isEmpty()) {
                    y = addCaption(out, "MODULES", y + 4, rowW);
                }
                for (IModule m : mods) {
                    y = addRow(out, new ModuleRow(f, tab, m.id(), SettingsRenderer.describe(m)[0], "", m.isEnabled(),
                            false, m.id().equals(sel), rowW), y);
                }
            } else {
                int found = 0;
                for (Tab t : Tab.values()) {
                    boolean captioned = false;
                    for (IModule m : Services.modules().byCategory(t.category())) {
                        String match = searchMatch(m, q);
                        if (match == null) {
                            continue;
                        }
                        if (!captioned) {
                            y = addCaption(out, t.label.toUpperCase(Locale.ROOT), y + (found > 0 ? 4 : 0), rowW);
                            captioned = true;
                        }
                        found++;
                        y = addRow(out, new ModuleRow(f, t, m.id(), SettingsRenderer.describe(m)[0], match,
                                m.isEnabled(), false, t == tab && m.id().equals(sel), rowW), y);
                    }
                }
                if (found == 0) {
                    y = addCaption(out, "NO MATCHES", y, rowW);
                }
            }
        } catch (RuntimeException e) {
            UtilitySuiteMod.LOGGER.warn("Module list failed to build", e);
        }
        listNodes = out;
        listContentH = y;
        listScroll = Math.max(0, Math.min(listScroll, maxListScroll()));
    }

    /** "" when the module's name/description/id matches, the matching setting's label, or null. */
    private static String searchMatch(IModule m, String q) {
        String[] d = SettingsRenderer.describe(m);
        if (d[0].toLowerCase(Locale.ROOT).contains(q) || d[1].toLowerCase(Locale.ROOT).contains(q)
                || m.id().contains(q)) {
            return "";
        }
        if (m instanceof IConfigurable c) {
            for (SettingSpec s : c.settings()) {
                if (s.label().toLowerCase(Locale.ROOT).contains(q) || s.key().toLowerCase(Locale.ROOT).contains(q)) {
                    return s.label();
                }
            }
            // second pass: groups and descriptions ("water" finds Terrain Assist's surface mode)
            for (SettingSpec s : c.settings()) {
                if (s.group().toLowerCase(Locale.ROOT).contains(q) || s.tooltip().toLowerCase(Locale.ROOT).contains(q)) {
                    return s.label();
                }
            }
        }
        return null;
    }

    private static int addCaption(List<UiNode> out, String text, int y, int w) {
        CaptionRow c = new CaptionRow(text, w);
        c.x = 4;
        c.y = y;
        out.add(c);
        return y + c.h;
    }

    private static int addRow(List<UiNode> out, UiNode row, int y) {
        row.x = 4;
        row.y = y;
        out.add(row);
        return y + row.h + 1;
    }

    private int listViewH() {
        return wy + wh - 1 - listTop;
    }

    private int maxListScroll() {
        return Math.max(0, listContentH + 6 - listViewH());
    }

    private boolean inList(double mx, double my) {
        return listW > 0 && mx >= listX && mx < listX + listW && my >= listTop && my < wy + wh - 1;
    }

    private int listSx(UiNode n) {
        return listX + n.x;
    }

    private int listSy(UiNode n) {
        return listTop + n.y - listScroll;
    }

    private void buildContent() {
        int canvasW = viewW - 2 * UiTheme.CONTENT_PAD;
        panel = new Panel(this, font, canvasW);
        IModule selected = selectedModule();
        try {
            if (selected != null) {
                SettingsRenderer.module(panel, selected);
            } else switch (tab) {
                case JOBS -> JobsTab.build(panel);
                case WORLD -> WorldTab.build(panel);
                case TRAVEL -> TravelTab.build(panel);
                case COMBAT -> CombatTab.build(panel);
                case TOOLS -> ToolsTab.build(panel);
            }
        } catch (RuntimeException e) {
            panel.card("This tab failed to build", "");
            panel.text(String.valueOf(e), Panel.RED);
            UtilitySuiteMod.LOGGER.error("Menu tab {} failed", tab, e);
        }
        contentHeight = panel.finish();
        nodes = panel.nodes();
        for (StyledTextField sf : panel.fields()) {
            registerField(sf);
        }
        if (builtTab != tab) {
            scrollTarget = SCROLL[tab.ordinal()];
            clampScroll();
            scrollCur = scrollTarget;
            builtTab = tab;
        } else {
            clampScroll();
            scrollCur = Math.min(scrollCur, maxScroll());
        }
        lastFrame = Util.getMillis();
    }

    private void registerField(StyledTextField sf) {
        EditBox box = sf.box();
        addWidget(box);
        if (sf.stateKey() != null) {
            textFieldKeys.put(box, sf.stateKey());
        }
    }

    private int maxScroll() {
        return Math.max(0, contentHeight + 2 * UiTheme.CONTENT_PAD - viewH);
    }

    private void clampScroll() {
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
    }

    private String signature() {
        Supplier<String> s = switch (tab) {
            case JOBS -> JobsTab::signature;
            case WORLD -> WorldTab::signature;
            case TRAVEL -> TravelTab::signature;
            case COMBAT -> CombatTab::signature;
            case TOOLS -> ToolsTab::signature;
        };
        try {
            return s.get() + "|" + automationStopped() + "|" + cleanScreen() + "|" + safeProfile();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (statusTicks > 0 && --statusTicks == 0) {
            status = "";
        }
        if (rebuildRequested) {
            rebuildRequested = false;
            rebuildKeepingFocus();
            return;
        }
        if (ticks % REFRESH_TICKS == 0) {
            for (int i = 0; i < nodes.size(); i++) {
                nodes.get(i).refresh(font);
            }
            refreshFooter();
            String sig = signature();
            if (!sig.equals(signature)) {
                rebuildKeepingFocus();
            }
        }
    }

    private void rebuildKeepingFocus() {
        String focusedKey = null;
        GuiEventListener f = getFocused();
        if (f instanceof EditBox box) {
            focusedKey = textFieldKeys.get(box);
        }
        SCROLL[tab.ordinal()] = scrollTarget;
        rebuildWidgets();
        if (focusedKey != null) {
            for (Map.Entry<EditBox, String> e : textFieldKeys.entrySet()) {
                if (focusedKey.equals(e.getValue())) {
                    setFocused(e.getKey());
                    break;
                }
            }
        }
    }

    private void refreshFooter() {
        footerStopped = automationStopped();
        footerStatus = footerStopped ? "Automation stopped" : "Automation on";
        String job = "Idle";
        IGoalEngine goals = Services.goalsOrNull();
        if (goals != null) {
            try {
                Optional<JobSnapshot> a = UiPreview.activeJob(goals);
                if (a.isPresent()) {
                    JobSnapshot j = a.get();
                    job = GoalHeroCard.chipText(j.status())
                            + (j.progressTarget() > 0 ? " · " + Math.max(0, j.progressCurrent()) + "/"
                            + j.progressTarget() : "");
                }
            } catch (RuntimeException ignored) {
                // footer is best effort
            }
        }
        footerJob = UiRenderUtils.ellipsize(font, job, sidebarW - 30);
        footerStatus = UiRenderUtils.ellipsize(font, footerStatus, sidebarW - 30);
    }

    private static boolean automationStopped() {
        try {
            return Services.arbiter().isAutomationStopped();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean cleanScreen() {
        try {
            return Services.modState().isCleanScreen();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String safeProfile() {
        try {
            return Services.modState().profile().profileId();
        } catch (RuntimeException e) {
            return "global";
        }
    }

    private void toggleStopAll() {
        try {
            if (Services.arbiter().isAutomationStopped()) {
                Services.arbiter().resumeAutomation();
                setStatus("Automation resumed");
            } else {
                Services.arbiter().stopAll("menu");
                setStatus("Stopped all automation");
            }
        } catch (RuntimeException e) {
            setStatus("Stop-All is not available");
        }
        requestRebuild();
    }

    // ---- preview / harness helpers ----------------------------------------------------------------------------------

    /** Opens (or closes) the first collapsible section of the current tab and scrolls to it; false when there is none. */
    boolean expandFirstSection(boolean open) {
        if (panel == null || panel.sections().isEmpty()) {
            return false;
        }
        CollapsibleSection s = panel.sections().get(0);
        if (s.isOpen() != open) {
            s.onClick(0, 0, 0, 0, 0);
        }
        rebuildKeepingFocus();
        if (!panel.sections().isEmpty()) {
            scrollTarget = Math.max(0, panel.sections().get(0).y - 90);
            clampScroll();
            scrollCur = scrollTarget;
        }
        return true;
    }

    /** Jumps the content scroll (preview harness). */
    void scrollForPreview(int y) {
        scrollTarget = y;
        clampScroll();
        scrollCur = scrollTarget;
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    private boolean inView(double mx, double my) {
        return mx >= viewX && mx < viewX + viewW && my >= viewY && my < viewY + viewH;
    }

    private int contentSx(UiNode n) {
        return viewX + UiTheme.CONTENT_PAD + n.x;
    }

    private int contentSy(UiNode n) {
        return viewY + UiTheme.CONTENT_PAD + n.y - Math.round(scrollCur);
    }

    private boolean chromeVisible(UiNode n) {
        return n != startPill || GoalConsole.canStart(this);
    }

    private void focus(UiNode n) {
        if (focusNode != null) {
            focusNode.focused = false;
        }
        focusNode = n;
        if (n != null) {
            n.focused = true;
        }
    }

    private boolean click(UiNode n, double mx, double my, int button, int sx, int sy, boolean content) {
        if (n instanceof StyledTextField sf) {
            focus(null);
            setFocused(sf.box());
            sf.onClick(mx, my, button, sx, sy);
            return true;
        }
        if (!n.interactive()) {
            return false;
        }
        if (n.onClick(mx, my, button, sx, sy)) {
            setFocused(null);
            focus(n.focusable() ? n : null);
            dragNode = n;
            dragInContent = content;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = chrome.size() - 1; i >= 0; i--) {
            UiNode n = chrome.get(i);
            if (chromeVisible(n) && n.contains(mx, my, n.x, n.y) && click(n, mx, my, button, n.x, n.y, false)) {
                return true;
            }
        }
        if (inList(mx, my)) {
            for (int i = listNodes.size() - 1; i >= 0; i--) {
                UiNode n = listNodes.get(i);
                int sx = listSx(n);
                int sy = listSy(n);
                if (n.contains(mx, my, sx, sy) && click(n, mx, my, button, sx, sy, false)) {
                    dragNode = null; // rows act on press; nothing to drag
                    return true;
                }
            }
            setFocused(null);
            focus(null);
            return true;
        }
        int max = maxScroll();
        if (max > 0 && button == 0 && mx >= viewX + viewW - 9 && mx < viewX + viewW && inView(mx, my)) {
            scrollDragging = true;
            dragScrollbar(my);
            return true;
        }
        if (inView(mx, my)) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                UiNode n = nodes.get(i);
                int sx = contentSx(n);
                int sy = contentSy(n);
                if (n.contains(mx, my, sx, sy) && click(n, mx, my, button, sx, sy, true)) {
                    return true;
                }
            }
        }
        setFocused(null);
        focus(null);
        return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
    }

    private void dragScrollbar(double my) {
        int max = maxScroll();
        int trackH = viewH - 12;
        int thumbH = thumbHeight(trackH, max);
        double p = (my - (viewY + 6) - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        scrollTarget = (int) Math.round(Math.max(0, Math.min(1, p)) * max);
        scrollCur = scrollTarget;
    }

    private int thumbHeight(int trackH, int max) {
        return Math.max(18, trackH * viewH / (viewH + max));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (scrollDragging) {
            dragScrollbar(my);
            return true;
        }
        if (dragNode != null) {
            int sx = dragInContent ? contentSx(dragNode) : dragNode.x;
            int sy = dragInContent ? contentSy(dragNode) : dragNode.y;
            dragNode.onDrag(mx, my, sx, sy);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        scrollDragging = false;
        if (dragNode != null) {
            dragNode.onRelease();
            dragNode = null;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (inList(mx, my)) {
            listScroll = Math.max(0, Math.min(maxListScroll(), listScroll - (int) Math.round(scrollY * 20)));
            return true;
        }
        if (inView(mx, my)) {
            scrollTarget -= (int) Math.round(scrollY * 32);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        GuiEventListener f = getFocused();
        if (goalField != null && f == goalField.box() && (keyCode == 257 || keyCode == 335)) {
            if (tab != Tab.JOBS) {
                selectTab(Tab.JOBS);
            }
            GoalConsole.enter(this);
            return true;
        }
        if (f != null) {
            if (f.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        } else if (focusNode != null && focusNode.onKey(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == 258) {
            focus(null);
            return super.keyPressed(keyCode, scanCode, modifiers); // Tab: move between text fields
        }
        if (!(f instanceof EditBox)) {
            int page = viewH - 40;
            switch (keyCode) {
                case 266 -> scrollTarget -= page;      // Page Up
                case 267 -> scrollTarget += page;      // Page Down
                case 268 -> scrollTarget = 0;          // Home
                case 269 -> scrollTarget = maxScroll(); // End
                default -> {
                    // Right Shift closes the menu again unless a text box is being typed in.
                    var open = KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU);
                    if (open != null && open.matches(keyCode, scanCode)) {
                        // Only a fresh press closes: not the key-repeat of the press that opened the menu, and not
                        // within the first moments after opening.
                        if (openKeyReleased && Util.getMillis() - openedAt >= OPEN_KEY_GRACE_MS) {
                            closeReason = "open key";
                            onClose();
                        }
                        return true;
                    }
                    return false;
                }
            }
            clampScroll();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        var open = KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU);
        if (open != null && open.matches(keyCode, scanCode)) {
            openKeyReleased = true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        SCROLL[tab.ordinal()] = scrollTarget;
        if (closeReason == null) {
            closeReason = "escape/onClose";
        }
        UtilitySuiteMod.LOGGER.info("Suite menu closed ({})", closeReason);
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
        lastClosedAt = Util.getMillis();
        drainOpenKeyClicks();
    }

    /** Millis timestamp of the last time a menu instance was removed (0 = never); used to debounce reopening. */
    public static long lastClosedAt() {
        return lastClosedAt;
    }

    /** Drops queued Open-Menu presses so a stale click count can't reopen (or re-close) the menu later. */
    public static void drainOpenKeyClicks() {
        var open = KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU);
        if (open != null) {
            int guard = 0;
            while (open.consumeClick() && ++guard < 64) {
                // drain
            }
        }
    }

    /** True when the Open-Menu key is physically held right now (GLFW state, independent of key contexts). */
    public static boolean openKeyHeld() {
        var open = KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (open == null || mc == null || mc.getWindow() == null || open.isUnbound()) {
            return false;
        }
        var key = open.getKey();
        return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                && com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow().getWindow(), key.getValue());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    /** Light dim only: no vanilla blur, menu texture or panorama. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, UiTheme.BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float open = UiRenderUtils.easeOut((now - openedAt) / (float) UiTheme.OPEN_MS);
        boolean animating = open < 1f;
        if (animating) {
            RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0.05f, open));
        }
        renderBackground(g, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        if (animating) {
            float s = 0.96f + 0.04f * open;
            g.pose().translate(width / 2f, height / 2f, 0);
            g.pose().scale(s, s, 1f);
            g.pose().translate(-width / 2f, -height / 2f, 0);
        }
        stepScroll(now);
        drawWindow(g);
        drawTopBar(g, mouseX, mouseY);
        drawSidebar(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);
        drawContent(g, mouseX, mouseY);
        drawToast(g);
        updateHover(mouseX, mouseY, now);
        drawTooltip(g, mouseX, mouseY, now);
        g.pose().popPose();
        if (animating) {
            g.flush();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    private void stepScroll(long now) {
        long dt = Math.min(100L, Math.max(0L, now - lastFrame));
        lastFrame = now;
        float diff = scrollTarget - scrollCur;
        if (Math.abs(diff) < 0.5f) {
            scrollCur = scrollTarget;
        } else {
            scrollCur += diff * (1f - (float) Math.exp(-dt / 55.0));
        }
    }

    private void drawWindow(GuiGraphics g) {
        UiRenderUtils.shadow(g, wx, wy, ww, wh, UiTheme.RADIUS, 6);
        // Opaque base: with the 0xE8 glass alpha the chat and other HUD text drawn before the screen showed through
        // the window as faint ghost text.
        UiRenderUtils.roundedRect(g, wx, wy, ww, wh, UiTheme.RADIUS, UiTheme.WINDOW | 0xFF000000);
        UiRenderUtils.roundedRect(g, wx, viewY, sidebarW, wy + wh - viewY, UiTheme.RADIUS, UiTheme.SIDEBAR,
                UiRenderUtils.BL);
        if (listW > 0) {
            g.fill(listX, viewY, listX + listW, wy + wh - 1, UiTheme.LIST_BG);
            UiRenderUtils.vLine(g, listX + listW, viewY, wy + wh - 1, UiTheme.BORDER);
        }
        UiRenderUtils.hLine(g, wx + 1, wx + ww - 1, wy + UiTheme.TOP_BAR_H, UiTheme.BORDER);
        UiRenderUtils.vLine(g, wx + sidebarW, viewY, wy + wh - 1, UiTheme.BORDER);
        UiRenderUtils.roundedOutline(g, wx, wy, ww, wh, UiTheme.RADIUS, UiTheme.BORDER);
    }

    private void drawList(GuiGraphics g, int mouseX, int mouseY) {
        if (listW == 0) {
            return;
        }
        boolean mouseIn = inList(mouseX, mouseY);
        int mx = mouseIn ? mouseX : -10000;
        int my = mouseIn ? mouseY : -10000;
        int top = listTop;
        int bottom = wy + wh - 1;
        UiRenderUtils.clip(g, listX, top, listW, bottom - top);
        for (int i = 0; i < listNodes.size(); i++) {
            UiNode n = listNodes.get(i);
            int sy = listSy(n);
            if (sy + n.h < top || sy > bottom) {
                continue;
            }
            n.render(g, font, listSx(n), sy, mx, my);
        }
        UiRenderUtils.endClip(g);
        int max = maxListScroll();
        if (max > 0) {
            int trackH = bottom - top - 8;
            int thumbH = Math.max(14, trackH * (bottom - top) / (bottom - top + max));
            int thumbY = top + 4 + Math.round((trackH - thumbH) * (listScroll / (float) max));
            UiRenderUtils.roundedRect(g, listX + listW - 4, thumbY, 2, thumbH, 1, 0x706B7280);
        }
    }

    /** Small caps text at 0.75 scale (chips, captions, version). */
    static void smallText(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    static int smallWidth(Font font, String s) {
        return (int) Math.ceil(font.width(s) * 0.75f);
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        int ty = wy + (UiTheme.TOP_BAR_H - 8) / 2;
        UiRenderUtils.roundedRect(g, wx + 12, ty, 3, 8, 1, UiTheme.ACCENT);
        UiRenderUtils.text(g, font, TITLE, wx + 20, ty, UiTheme.TEXT);
        if (!versionText.isEmpty()) {
            int vx = wx + 20 + font.width(TITLE) + 6;
            int vw = smallWidth(font, versionText) + 8;
            UiRenderUtils.panel(g, vx, ty - 1, vw, 10, 2, UiTheme.CONTROL, UiTheme.CONTROL_BORDER);
            smallText(g, font, versionText, vx + 4, ty + 2, UiTheme.TEXT_DIM);
        }
        if (profilePillW > 0) {
            int px = wx + 20 + titleW + 8;
            int py = wy + (UiTheme.TOP_BAR_H - 14) / 2;
            UiRenderUtils.panel(g, px, py, profilePillW, 14, 7, UiTheme.CONTROL, UiTheme.CONTROL_BORDER);
            UiRenderUtils.dot(g, px + 5, py + 5, 4, UiTheme.TEXT_MUTED);
            UiRenderUtils.text(g, font, profileText, px + 12, py + 3, UiTheme.TEXT_DIM);
        }
        UiRenderUtils.text(g, font, cleanLabel, cleanLabelX, ty, UiTheme.TEXT_DIM);
        for (int i = 0; i < chrome.size(); i++) {
            UiNode n = chrome.get(i);
            if (n instanceof SidebarTab || !chromeVisible(n)) {
                continue;
            }
            n.render(g, font, n.x, n.y, mx, my);
        }
        if (goalField != null && !GoalConsole.canStart(this) && goalField.box().isFocused()
                && !goalField.box().getValue().isEmpty()) {
            int hx = goalField.x + goalField.w - START_W + 4;
            UiRenderUtils.panel(g, hx, goalField.y + 4, START_W - 8, 12, 3, UiTheme.CONTROL, UiTheme.CONTROL_BORDER);
            UiRenderUtils.text(g, font, "Enter", hx + (START_W - 8 - font.width("Enter")) / 2, goalField.y + 6,
                    UiTheme.TEXT_MUTED);
        }
    }

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < chrome.size(); i++) {
            UiNode n = chrome.get(i);
            if (n instanceof SidebarTab) {
                n.render(g, font, n.x, n.y, mx, my);
            }
        }
        int fy = wy + wh - footerH;
        UiRenderUtils.hLine(g, wx + 10, wx + sidebarW - 10, fy, UiTheme.BORDER);
        int dotColor = footerStopped ? UiTheme.DANGER : UiTheme.RUNNING;
        boolean twoLines = footerH >= 42;
        // one-line footer: the job state replaces the automation text unless automation is stopped
        String first = twoLines || footerStopped ? footerStatus : footerJob;
        UiRenderUtils.dot(g, wx + 14, fy + 11, 6, dotColor);
        UiRenderUtils.text(g, font, first, wx + 26, fy + 10, footerStopped ? UiTheme.DANGER_TEXT
                : UiTheme.TEXT_SECONDARY);
        if (twoLines) {
            UiRenderUtils.text(g, font, footerJob, wx + 26, fy + 23, UiTheme.TEXT_MUTED);
        }
    }

    private void drawContent(GuiGraphics g, int mouseX, int mouseY) {
        boolean mouseIn = inView(mouseX, mouseY);
        int mx = mouseIn ? mouseX : -10000;
        int my = mouseIn ? mouseY : -10000;
        UiRenderUtils.clip(g, viewX, viewY, viewW, viewH);
        int top = viewY;
        int bottom = viewY + viewH;
        for (int i = 0; i < nodes.size(); i++) {
            UiNode n = nodes.get(i);
            int sy = contentSy(n);
            if (sy + n.h < top || sy > bottom) {
                if (n instanceof StyledTextField sf) {
                    sf.position(contentSx(n), sy);
                }
                continue;
            }
            n.render(g, font, contentSx(n), sy, mx, my);
        }
        // fade edges so cards slide under the bars
        g.fillGradient(viewX, viewY, viewX + viewW, viewY + 6, 0x40000000, 0x00000000);
        UiRenderUtils.endClip(g);

        int max = maxScroll();
        if (max > 0) {
            int trackX = viewX + viewW - 6;
            int trackY = viewY + 6;
            int trackH = viewH - 12;
            int thumbH = thumbHeight(trackH, max);
            int thumbY = trackY + Math.round((trackH - thumbH) * (scrollCur / max));
            boolean hot = scrollDragging || (mouseX >= viewX + viewW - 9 && mouseIn);
            int bw = hot ? 4 : 3;
            UiRenderUtils.roundedRect(g, trackX - (bw - 3), trackY, bw, trackH, 2, 0x18FFFFFF);
            UiRenderUtils.roundedRect(g, trackX - (bw - 3), thumbY, bw, thumbH, 2, hot ? 0xC0D1D5DB : 0x706B7280);
        }
    }

    private void drawToast(GuiGraphics g) {
        if (status.isEmpty()) {
            return;
        }
        String s = status;
        int tw = Math.min(font.width(s), viewW - 60);
        int w = tw + 24;
        int x = viewX + (viewW - w) / 2;
        int y = viewY + viewH - 28;
        float alpha = statusTicks < 10 ? statusTicks / 10f : 1f;
        UiRenderUtils.panel(g, x, y, w, 18, 9, UiTheme.fade(0xF5181B22, alpha), UiTheme.fade(0xFF30384A, alpha));
        UiRenderUtils.dot(g, x + 8, y + 6, 6, UiTheme.fade(UiTheme.ACCENT, alpha));
        if (alpha > 0.1f) {
            g.enableScissor(x + 18, y, x + 18 + tw, y + 18);
            UiRenderUtils.text(g, font, s, x + 18, y + 5, UiTheme.fade(UiTheme.TEXT_SECONDARY, alpha));
            g.disableScissor();
        }
    }

    private void updateHover(int mouseX, int mouseY, long now) {
        UiNode found = null;
        for (int i = chrome.size() - 1; i >= 0 && found == null; i--) {
            UiNode n = chrome.get(i);
            if (chromeVisible(n) && !n.tooltipText().isEmpty() && n.contains(mouseX, mouseY, n.x, n.y)) {
                found = n;
            }
        }
        if (found == null && inList(mouseX, mouseY)) {
            for (int i = listNodes.size() - 1; i >= 0; i--) {
                UiNode n = listNodes.get(i);
                if (!n.tooltipText().isEmpty() && n.contains(mouseX, mouseY, listSx(n), listSy(n))) {
                    found = n;
                    break;
                }
            }
        }
        if (found == null && inView(mouseX, mouseY)) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                UiNode n = nodes.get(i);
                if (!n.tooltipText().isEmpty() && n.contains(mouseX, mouseY, contentSx(n), contentSy(n))) {
                    found = n;
                    break;
                }
            }
        }
        if (found != hoverNode) {
            hoverNode = found;
            hoverSince = now;
        }
    }

    private void drawTooltip(GuiGraphics g, int mouseX, int mouseY, long now) {
        if (hoverNode == null || dragNode != null || now - hoverSince < TOOLTIP_DELAY_MS) {
            return;
        }
        String t = hoverNode.tooltipText();
        if (!t.equals(tipText)) { // re-wrap only when the tooltip changes
            tipText = t;
            tipLines = font.split(Component.literal(t), 200);
            tipW = 0;
            for (FormattedCharSequence l : tipLines) {
                tipW = Math.max(tipW, font.width(l));
            }
        }
        int w = tipW + 16;
        int h = tipLines.size() * 10 + 10;
        int x = mouseX + 10;
        int y = mouseY + 12;
        if (x + w > width - 4) {
            x = mouseX - w - 6;
        }
        if (y + h > height - 4) {
            y = mouseY - h - 6;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        UiRenderUtils.panel(g, x, y, w, h, UiTheme.RADIUS_SMALL, 0xF50D0F13, 0xFF30384A);
        for (int i = 0; i < tipLines.size(); i++) {
            g.drawString(font, tipLines.get(i), x + 8, y + 6 + i * 10, UiTheme.TEXT_SECONDARY, false);
        }
        g.pose().popPose();
    }

    // ---- sidebar tab button -----------------------------------------------------------------------------------------

    private final class SidebarTab extends UiNode {
        private final Tab t;
        private final boolean active;
        private final ItemStack icon;
        private final String label;
        private final String badge;

        SidebarTab(Font f, Tab t, boolean active, int w, int h, int enabled) {
            super(w, h);
            this.t = t;
            this.active = active;
            this.icon = ItemIcons.stack(t.icon());
            this.badge = enabled > 0 ? Integer.toString(enabled) : "";
            this.label = UiRenderUtils.ellipsize(f, t.label, w - 40 - (badge.isEmpty() ? 0 : 14));
            if (!badge.isEmpty()) {
                tooltip(enabled + (enabled == 1 ? " module" : " modules") + " on in " + t.label);
            }
        }

        @Override
        public boolean interactive() {
            return !active;
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
            if (active) {
                UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, UiTheme.CARD);
                UiRenderUtils.roundedRect(g, sx, sy + 5, 2, h - 10, 1, UiTheme.ACCENT);
            } else if (hover) {
                UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, 0x0FFFFFFF);
            }
            UiRenderUtils.item(g, icon, sx + 10, sy + (h - 12) / 2, 12);
            UiRenderUtils.text(g, font, label, sx + 30, sy + (h - 8) / 2,
                    active ? UiTheme.TEXT : hover ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_DIM);
            if (!badge.isEmpty()) {
                int bw = Math.max(10, smallWidth(font, badge) + 6);
                int bx = sx + w - 6 - bw;
                int by = sy + (h - 10) / 2;
                UiRenderUtils.roundedRect(g, bx, by, bw, 10, 2, active ? UiTheme.CONTROL_HOVER : UiTheme.CONTROL);
                smallText(g, font, badge, bx + (bw - smallWidth(font, badge)) / 2, by + 3,
                        active ? UiTheme.TEXT : UiTheme.TEXT_DIM);
            }
        }

        @Override
        public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
            if (button != 0 || active) {
                return false;
            }
            selectTab(t);
            return true;
        }
    }

    // ---- column 2 rows ----------------------------------------------------------------------------------------------

    /** Dim small-caps group caption ("MODULES", a category name in search results). */
    private static final class CaptionRow extends UiNode {
        private final String text;

        CaptionRow(String text, int w) {
            super(w, 14);
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            smallText(g, font, text, sx + 6, sy + 5, UiTheme.TEXT_MUTED);
        }
    }

    /**
     * One entry of the module list: tag chip, name (white when on, grey when off), an optional matched-setting line in
     * search results and an on/off dot. Left click opens it in column 3; right click turns the module on or off.
     */
    private final class ModuleRow extends UiNode {
        private final Tab target;
        private final String moduleId;
        private final String name;
        private final String sub;
        private final boolean enabled;
        private final boolean overview;
        private final boolean selected;
        private final String chip;

        ModuleRow(Font f, Tab target, String moduleId, String name, String sub, boolean enabled, boolean overview,
                  boolean selected, int w) {
            super(w, sub.isEmpty() ? ROW_H : ROW_H_SUB);
            this.target = target;
            this.moduleId = moduleId;
            this.overview = overview;
            this.selected = selected;
            this.enabled = enabled;
            this.chip = overview ? "PAGE" : target.chip();
            int chipW = smallWidth(f, chip) + 8;
            this.name = UiRenderUtils.ellipsize(f, name, w - 10 - chipW - 6 - 12);
            this.sub = sub.isEmpty() ? "" : UiRenderUtils.ellipsize(f, "setting: " + sub, (int) ((w - 16) / 0.75f));
            tooltip(overview ? "Quick actions and status for " + target.label
                    : "Click to open · right-click to turn " + (enabled ? "off" : "on"));
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
            if (selected) {
                UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, UiTheme.CARD_HOVER);
                UiRenderUtils.roundedRect(g, sx, sy + 4, 2, h - 8, 1, UiTheme.ACCENT);
            } else if (hover) {
                UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, 0x0FFFFFFF);
            }
            int chipColor = overview ? UiTheme.TEXT_DIM : target.chipColor();
            int cw = smallWidth(font, chip) + 8;
            int cy = sy + (ROW_H - 10) / 2;
            UiRenderUtils.roundedRect(g, sx + 6, cy, cw, 10, 2, UiTheme.fade(chipColor, 0.16f));
            smallText(g, font, chip, sx + 10, cy + 3, chipColor);
            boolean on = overview || enabled;
            UiRenderUtils.text(g, font, name, sx + 6 + cw + 6, sy + (ROW_H - 8) / 2,
                    selected ? UiTheme.TEXT : on ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED);
            if (!overview) {
                UiRenderUtils.dot(g, sx + w - 9, sy + (ROW_H - 4) / 2, 4, enabled ? UiTheme.RUNNING : UiTheme.TRACK_OFF);
            }
            if (!sub.isEmpty()) {
                smallText(g, font, sub, sx + 8, sy + ROW_H + 1, UiTheme.TEXT_DIM);
            }
        }

        @Override
        public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
            if (button == 1 && !overview) {
                try {
                    Optional<IModule> m = Services.modules().byId(moduleId);
                    if (m.isPresent()) {
                        boolean v = !m.get().isEnabled();
                        CoreBootstrap.modules().setModuleEnabled(moduleId, v, Services.modState().profile());
                        Services.modState().markDirty();
                        setStatus(name + (v ? " on" : " off"));
                        requestRebuild();
                    }
                } catch (RuntimeException e) {
                    setStatus("Could not change " + name);
                }
                return true;
            }
            if (button != 0) {
                return false;
            }
            selectModule(target, moduleId);
            return true;
        }
    }
}
