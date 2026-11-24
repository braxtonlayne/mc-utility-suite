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
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.BootSequence;
import com.utilitysuite.ui.holo.FrameClock;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.holo.Motion;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.kit.CollapsibleSection;
import com.utilitysuite.ui.kit.GoalHeroCard;
import com.utilitysuite.ui.kit.ItemIcons;
import com.utilitysuite.ui.kit.MenuScale;
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

        /** The category hue token (a 2 px bar and micro label tint only, never a filled chip). */
        HoloToken token() {
            return switch (this) {
                case JOBS -> HoloToken.CAT_JOBS;
                case WORLD -> HoloToken.CAT_WORLD;
                case TRAVEL -> HoloToken.CAT_TRAVEL;
                case COMBAT -> HoloToken.CAT_COMBAT;
                case TOOLS -> HoloToken.CAT_TOOLS;
            };
        }

        int chipColor() {
            return HoloTheme.argb(token());
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
    /** The autopilot's settings page is open in the content canvas (its bar's "Settings" end). */
    private static boolean autopilotPage;
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
    private AutopilotBar autopilotBar;

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
    private boolean cleanOn;
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
    /** Bumped by every {@link #init()}: a click handler that rebuilt the screen left its node behind. */
    private int buildGeneration;
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

    // STEM chrome: motion and cached state (nothing here is built per frame)
    private static final long SIDEBAR_BAR_KEY = AnimStore.key("menu.sidebar.bar", 0);
    private static final long PRESET_BAR_KEY = AnimStore.key("menu.preset.bar", 0);
    /** Open: frame unfold 0-110 ms, content fade 60-220 ms. */
    private static final float OPEN_FRAME_MS = 110f;
    private static final float OPEN_FADE_FROM_MS = 60f;
    private static final float OPEN_FADE_MS = 160f;
    /** Category switch: content fades in and slides 6 px over 140 ms. */
    private static final float SWITCH_MS = 140f;
    private static final int SWITCH_SLIDE = 6;
    private static final float TOOLTIP_FADE_MS = 90f;
    private static final long SWEEP_PERIOD_MS = 8000L;
    private static final float SWEEP_MS = 900f;
    private static final int SWEEP_H = 24;
    private long contentSwitchAt;
    /** The player's menu blur is on (accessibility setting); without it the glass gets a darker base. */
    private boolean blurOn = true;
    /** Module ids holding a lease per control channel, sampled at the refresh cadence (never per frame). */
    private final String[] leaseHolders = new String[ControlChannel.all().length];
    /** The next list build follows a search edit: rows reflow from where they were instead of snapping. */
    private boolean searchReflow;
    private int titleTextW;
    private int versionW;
    private int profileTextW;
    private int cleanLabelW;
    private int enterW;
    private int hoverSx;
    private int hoverSy;
    /** Cached pixel width of the toast text (-1 = measure on the next frame it shows). */
    private int statusW = -1;

    // boot sequence (first open per session, motion FULL only)
    private final BootSequence boot = new BootSequence();
    private boolean bootChecked;
    private static final String[] PERCENT = new String[101];
    private static final int[] PERCENT_W = new int[101];
    private static boolean percentMeasured;
    private static final String READOUT_HEAD = "SYNC ";
    private String readoutTail = "";
    private int readoutHeadW;
    private int readoutTailW;

    static {
        for (int i = 0; i <= 100; i++) {
            PERCENT[i] = Integer.toString(i);
        }
    }

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
        statusW = -1;
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
        if (t == null || (t == tab && !autopilotPage)) {
            return;
        }
        autopilotPage = false;
        SCROLL[tab.ordinal()] = scrollTarget;
        tab = t;
        lastTab = t;
        focusNode = null;
        contentSwitchAt = Util.getMillis();
        rebuildKeepingFocus();
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        buildGeneration++;
        textFieldKeys.clear();
        chrome.clear();
        focusNode = null;
        dragNode = null;
        hoverNode = null;

        // GUI scale 3-4: lay out against the effective-scale-2 virtual size (render scales the pose to match)
        if (minecraft != null && minecraft.getWindow() != null) {
            double gs = minecraft.getWindow().getGuiScale();
            if (MenuScale.applies(gs)) {
                width = MenuScale.virtualSize(minecraft.getWindow().getWidth(), gs);
                height = MenuScale.virtualSize(minecraft.getWindow().getHeight(), gs);
            }
        }
        ww = Math.min(UiTheme.WINDOW_W, (int) (width * 0.9f));
        wh = Math.min(UiTheme.WINDOW_H, (int) (height * 0.88f));
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        sidebarW = ww < 640 ? UiTheme.SIDEBAR_W_COMPACT : UiTheme.SIDEBAR_W;
        listX = wx + sidebarW + 1;
        // column 2 only when the content column keeps a usable width; otherwise the two-column layout (overview pages)
        listW = ww < UiTheme.THREE_COLUMN_MIN_W ? 0 : ww < 720 ? UiTheme.LIST_W_COMPACT : UiTheme.LIST_W;
        viewX = listX + (listW > 0 ? listW + 1 : 0);
        viewY = autopilotBarY() + AutopilotBar.H + 1;
        viewW = wx + ww - viewX;
        viewH = wy + wh - viewY - 1;
        if (versionText.isEmpty()) {
            versionText = modVersion();
        }
        try {
            blurOn = minecraft == null || minecraft.options.getMenuBackgroundBlurriness() >= 1;
        } catch (RuntimeException e) {
            blurOn = true;
        }
        refreshLeases();
        if (!bootChecked) {
            bootChecked = true;
            if (boot.begin(openedAt, HoloTheme.motion)) {
                prepareReadout();
            }
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
        String stopLabel = stopped ? "Resume" : "Stop all";
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
        clean.tooltip(cleanScreen() ? "Clean-Screen is ON: overlays, xray, the minimap and notices are hidden."
                : "Clean-Screen hides every overlay and the HUD pill.");
        clean.x = stop.x - 12 - ToggleSwitch.W;
        clean.y = wy + (UiTheme.TOP_BAR_H - ToggleSwitch.H) / 2;
        chrome.add(clean);
        cleanOn = cleanScreen();
        cleanLabel = cleanOn ? "CLEAN ON" : "CLEAN"; // never unlabeled; louder while it hides things
        cleanLabelW = f.width(cleanLabel);
        cleanLabelX = clean.x - 6 - cleanLabelW;
        titleTextW = f.width(TITLE);
        versionW = versionText.isEmpty() ? 0 : smallWidth(f, versionText);
        profileTextW = profileText.isEmpty() ? 0 : f.width(profileText);
        enterW = f.width("Enter");

        // center: goal bar, centered over the content canvas, clamped between the two groups
        int regionL = leftEnd + 14;
        int regionR = cleanLabelX - 10;
        int gw = Math.max(40, Math.min(320, regionR - regionL));
        int center = viewX + viewW / 2;
        int gx = Math.max(regionL, Math.min(regionR - gw, center - gw / 2));
        goalField = new StyledTextField(f, GoalConsole.K_GOAL, UiRenderUtils.firstThatFits(f, gw - START_W - 24,
                "Goal: gather 32 diamonds, craft 16 hoppers...", "Goal, e.g. gather 32 diamonds",
                "Goal, e.g. 32 diamonds", "Type a goal"), gw,
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

        // the autopilot's own toggle: pinned across the window under the title bar, above every category panel
        autopilotBar = new AutopilotBar(f, ww - 2, autopilotPage, this::toggleAutopilotPage);
        autopilotBar.x = wx + 1;
        autopilotBar.y = autopilotBarY();
        chrome.add(autopilotBar);

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
            SidebarTab st = new SidebarTab(f, t, t == tab && !autopilotPage, sidebarW - 16, tabH, enabledCount(t));
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
        autopilotPage = false;
        SELECTED.put(t, moduleId == null ? "" : moduleId);
        if (t != tab) {
            SCROLL[tab.ordinal()] = scrollTarget;
            tab = t;
            lastTab = t;
        }
        SCROLL[tab.ordinal()] = 0;
        builtTab = null; // reset the content scroll for the new page
        focusNode = null;
        contentSwitchAt = Util.getMillis();
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
                searchReflow = true;
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
            y = addCaption(out, "LEGIT LEVEL", y, rowW);
            y = addRow(out, new PresetBar(rowW), y) + 4;
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
        // list reflow: after a search edit, rows that stay glide to their new place and new rows enter 10 px low;
        // any other rebuild snaps every row to its place (build time only, never per frame)
        boolean reflow = searchReflow && HoloTheme.motion == Motion.FULL;
        searchReflow = false;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof ListRow r) {
                if (!reflow) {
                    AnimStore.SHARED.set(r.animKey, r.y);
                } else if (Float.isNaN(AnimStore.SHARED.peek(r.animKey, Float.NaN))) {
                    AnimStore.SHARED.set(r.animKey, r.y + 10);
                }
            }
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

    private int autopilotBarY() {
        return wy + UiTheme.TOP_BAR_H + 1;
    }

    /** Opens or closes the autopilot's settings page in the content canvas. */
    void toggleAutopilotPage() {
        autopilotPage = !autopilotPage;
        builtTab = null; // reset the content scroll for the page
        focusNode = null;
        contentSwitchAt = Util.getMillis();
        rebuildKeepingFocus();
    }

    /** The autopilot bar's {x, y, w, h} in GUI pixels (playtest harness clicks it like a user would). */
    public int[] autopilotBarRect() {
        if (autopilotBar == null) {
            return new int[4];
        }
        double gs = guiScale();
        return new int[]{(int) Math.round(MenuScale.toGui(autopilotBar.x, gs)),
                (int) Math.round(MenuScale.toGui(autopilotBar.y, gs)),
                (int) Math.round(MenuScale.toGui(autopilotBar.w, gs)),
                (int) Math.round(MenuScale.toGui(autopilotBar.h, gs))};
    }

    /** The window's GUI scale (1 before the screen is attached). */
    private double guiScale() {
        return minecraft == null || minecraft.getWindow() == null ? 1.0 : minecraft.getWindow().getGuiScale();
    }

    /** GUI-px mouse coordinate to the menu's virtual layout px (identity below GUI scale 3). */
    private double toVirtual(double guiCoord) {
        return MenuScale.toVirtual(guiCoord, guiScale());
    }

    /** True while the autopilot's settings page fills the content canvas (preview / playtest harness). */
    public boolean autopilotPageOpen() {
        return autopilotPage;
    }

    private void buildContent() {
        int canvasW = viewW - 2 * UiTheme.CONTENT_PAD;
        panel = new Panel(this, font, canvasW);
        IModule selected = selectedModule();
        try {
            if (autopilotPage && CoreBootstrap.autopilot() != null) {
                // back control; the bar above already is the on/off switch, so the card has none of its own
                panel.button("< Back", "Close the autopilot page and return to " + tab.name().charAt(0)
                        + tab.name().substring(1).toLowerCase(Locale.ROOT), 0, com.utilitysuite.ui.kit.UiRenderUtils.width(font, "< Back") + 20,
                        b -> toggleAutopilotPage());
                panel.nextRow();
                SettingsRenderer.module(panel, CoreBootstrap.autopilot(), false);
                panel.paragraph("The autopilot is a layer over the modules: it runs the reflexes you allow here even when"
                        + " their own module is off. Every module stays in the categories on the left, including the"
                        + " ones the autopilot never uses.", Panel.DIM);
            } else if (selected != null) {
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
            return s.get() + "|" + autopilotOn() + "|" + automationStopped() + "|" + cleanScreen() + "|" + safeProfile();
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
            refreshLeases();
            for (int i = 0; i < listNodes.size(); i++) {
                listNodes.get(i).refresh(font);
            }
            if (autopilotBar != null) {
                autopilotBar.refresh(font);
            }
            refreshFooter();
            String sig = signature();
            // Never rebuild under a held control (a slider mid-drag): the rebuild would drop it. The changed
            // signature is picked up on the first refresh after the release.
            if (!sig.equals(signature) && dragNode == null && !scrollDragging) {
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

    /** Samples which module holds each control channel (refresh cadence only: a few lookups, no per-frame polling). */
    private void refreshLeases() {
        ControlChannel[] all = ControlChannel.all();
        try {
            var arbiter = Services.arbiter();
            for (int i = 0; i < leaseHolders.length && i < all.length; i++) {
                Optional<ControlLease> l = arbiter.holder(all[i]);
                leaseHolders[i] = l.isPresent() ? l.get().moduleId() : null;
            }
        } catch (RuntimeException e) {
            java.util.Arrays.fill(leaseHolders, null);
        }
    }

    /** True when the module held a lease at the last {@link #refreshLeases} sample. */
    private boolean holdsLease(String moduleId) {
        if (moduleId == null || moduleId.isEmpty()) {
            return false;
        }
        for (String h : leaseHolders) {
            if (moduleId.equals(h)) {
                return true;
            }
        }
        return false;
    }

    private static boolean autopilotOn() {
        return CoreBootstrap.autopilot() != null && CoreBootstrap.autopilot().isOn();
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

    /**
     * Playtest harness: scrolls a content node into view and returns the GUI-px point to click so the click goes
     * through {@link #mouseClicked} like a real one, or null when the node is not on this tab or cannot be shown.
     *
     * @param fraction 0..1 across the node's width (0.5 = centre; near 0 = a slider's low end)
     */
    public double[] revealForHarness(UiNode n, double fraction) {
        if (!nodes.contains(n)) {
            return null;
        }
        scrollTarget = n.y + n.h / 2 - viewH / 2;
        clampScroll();
        scrollCur = scrollTarget;
        double vx = contentSx(n) + Math.max(1, Math.min(n.w - 1, n.w * fraction));
        double vy = contentSy(n) + n.h / 2.0;
        if (!inView(vx, vy)) {
            return null;
        }
        return new double[] {MenuScale.toGui(vx, guiScale()), MenuScale.toGui(vy, guiScale())};
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
        int gen = buildGeneration;
        if (n.onClick(mx, my, button, sx, sy)) {
            setFocused(null);
            // A click that rebuilt the screen (a tab, a module row, the autopilot bar) replaced n: never keep the
            // discarded node as the drag or focus target.
            if (gen == buildGeneration) {
                focus(n.focusable() ? n : null);
                dragNode = n;
                dragInContent = content;
            }
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boot.skip(); // any input ends the boot sequence; the input itself is handled as usual
        mx = toVirtual(mx);
        my = toVirtual(my);
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
                int sy = listDrawY(n); // hit-test where the row is drawn (it may still be settling after a reflow)
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
        mx = toVirtual(mx);
        my = toVirtual(my);
        dx = toVirtual(dx);
        dy = toVirtual(dy);
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
        mx = toVirtual(mx);
        my = toVirtual(my);
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
        boot.skip();
        mx = toVirtual(mx);
        my = toVirtual(my);
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
        var openKey = KeybindManager.mapping(KeybindManager.KeyId.OPEN_MENU);
        if (openKeyReleased || openKey == null || !openKey.matches(keyCode, scanCode)) {
            boot.skip(); // any input skips the boot, except the key-repeat of the press that opened the menu
        }
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

    /** Fraction of the backdrop tint drawn this frame (it fades in with the frame unfold). */
    private float backdropAlpha = 1f;

    /** Backdrop: VOID at 55 % over the vanilla menu blur (the blur itself runs first, in {@link #render}). */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, HoloTheme.argb(HoloToken.VOID, 0.55f * backdropAlpha));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        FrameClock.sample();
        // Screen#renderBlurredBackground (1.21.1, NeoForge-patched to disable depth first): runs the vanilla blur post
        // effect, which reads the player's Menu Background Blur accessibility option and does nothing at 0. Flush
        // first so whatever the HUD batched before the screen is part of the blurred image.
        g.flush();
        renderBlurredBackground(partialTick);
        double gs = guiScale();
        float f = (float) MenuScale.factor(gs);
        if (f == 1f) {
            renderScaled(g, mouseX, mouseY, partialTick);
            return;
        }
        // effective scale 2 at GUI scale 3-4: virtual layout px -> GUI px by 2 / guiScale
        g.pose().pushPose();
        g.pose().scale(f, f, 1f);
        MenuScale.setActive(f);
        try {
            renderScaled(g, (int) MenuScale.toVirtual(mouseX, gs), (int) MenuScale.toVirtual(mouseY, gs),
                    partialTick);
        } finally {
            MenuScale.reset();
            g.pose().popPose();
        }
    }

    /** The boot readout's strings and widths, from real numbers (registry module count), built once at open. */
    private void prepareReadout() {
        int modules = 0;
        try {
            for (Tab t : Tab.values()) {
                modules += Services.modules().byCategory(t.category()).size();
            }
        } catch (RuntimeException ignored) {
            // readout is decoration; zero is still honest
        }
        readoutTail = " %  ·  " + modules + " MODULES  ·  LINK OK";
        readoutHeadW = smallWidth(font, READOUT_HEAD);
        readoutTailW = smallWidth(font, readoutTail);
        if (!percentMeasured) {
            percentMeasured = true;
            for (int i = 0; i <= 100; i++) {
                PERCENT_W[i] = smallWidth(font, PERCENT[i]);
            }
        }
    }

    /**
     * The 900 ms boot (design 8.2): centre line, frame unfold with a bracket snap and flicker, the columns revealed
     * left to right behind a bright caret, then a counting readout. Input stays live (any input skips to the end).
     */
    private void renderBoot(GuiGraphics g, int mouseX, int mouseY, float partialTick, long t, long now) {
        backdropAlpha = clamp01(t / (float) BootSequence.LINE_END_MS);
        renderBackground(g, mouseX, mouseY, partialTick);
        stepScroll(now);
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        int cx = wx + ww / 2;
        if (t < BootSequence.LINE_END_MS) {
            int half = Math.max(1, Math.round(ww / 2f * BootSequence.line(t)));
            g.fill(cx - half, wy + wh / 2, cx + half, wy + wh / 2 + 1, accent);
            return;
        }
        if (t < BootSequence.UNFOLD_END_MS) {
            int fh = Math.max(2, Math.round(wh * BootSequence.unfold(t)));
            int fy = wy + (wh - fh) / 2;
            float fa = BootSequence.flicker(t) ? 0.55f : 1f;
            if (!blurOn) {
                HoloFrames.frame(g, wx, fy, ww, fh, FrameLevel.QUIET, true, HoloToken.VOID, HoloToken.LINE, 0.62f * fa);
            }
            HoloFrames.frame(g, wx, fy, ww, fh, FrameLevel.HERO, true, fa);
            HoloFrames.scanlines(g, wx, fy, ww, fh, fa);
            int arm = Math.max(1, Math.round(14f * BootSequence.brackets(t)));
            HoloFrames.brackets(g, wx - 2, fy - 2, ww + 4, fh + 4, Math.min(arm, Math.max(1, fh / 2)), accent);
            return;
        }
        drawWindow(g, 1f, now);
        float c0 = BootSequence.column(0, t);
        float c1 = BootSequence.column(1, t);
        float c2 = BootSequence.column(2, t);
        int barsH = UiTheme.TOP_BAR_H + 1 + AutopilotBar.H;
        if (revealClip(g, wx, wy, ww, barsH, c0)) {
            drawTopBar(g, mouseX, mouseY);
            UiRenderUtils.endClip(g);
        }
        int colH = wy + wh - viewY;
        if (revealClip(g, wx, viewY, sidebarW, colH, c0)) {
            drawSidebar(g, mouseX, mouseY);
            UiRenderUtils.endClip(g);
        }
        if (listW > 0 && revealClip(g, listX, viewY, listW, colH, c1)) {
            drawList(g, mouseX, mouseY);
            UiRenderUtils.endClip(g);
        }
        if (revealClip(g, viewX, viewY, viewW, viewH, c2)) {
            drawContent(g, mouseX, mouseY, 0);
            UiRenderUtils.endClip(g);
        }
        revealCaret(g, wx, wy, ww, barsH, c0, accent);
        revealCaret(g, wx, viewY, sidebarW, colH, c0, accent);
        if (listW > 0) {
            revealCaret(g, listX, viewY, listW, colH, c1, accent);
        }
        revealCaret(g, viewX, viewY, viewW, viewH, c2, accent);
        if (t >= BootSequence.COLUMNS_END_MS && !readoutTail.isEmpty()) {
            int pct = Math.max(0, Math.min(100, BootSequence.readoutPercent(t)));
            int color = HoloTheme.mix(HoloTheme.argb(HoloToken.TEXT_FAINT), accent, BootSequence.readoutBright(t));
            int slot = PERCENT_W[100];
            int x = viewX + viewW - 12 - readoutHeadW - slot - readoutTailW;
            int y = viewY + 4;
            smallText(g, font, READOUT_HEAD, x, y, color);
            smallText(g, font, PERCENT[pct], x + readoutHeadW + slot - PERCENT_W[pct], y, color);
            smallText(g, font, readoutTail, x + readoutHeadW + slot, y, color);
        }
        updateHover(mouseX, mouseY, now);
    }

    /** Scissors a column to its revealed part; false (nothing pushed) while it is still hidden. */
    private static boolean revealClip(GuiGraphics g, int x, int y, int w, int h, float reveal) {
        int rw = Math.round(w * reveal);
        if (rw <= 0 || h <= 0) {
            return false;
        }
        UiRenderUtils.clip(g, x, y, rw, h);
        return true;
    }

    /** The bright 1 px caret at a column's reveal edge while it is moving. */
    private static void revealCaret(GuiGraphics g, int x, int y, int w, int h, float reveal, int argb) {
        if (reveal > 0f && reveal < 1f) {
            int ex = x + Math.round(w * reveal);
            g.fill(ex, y, ex + 1, y + h, argb);
        }
    }

    private static float clamp01(float v) {
        return v <= 0f ? 0f : Math.min(v, 1f);
    }

    /**
     * Open: the frame unfolds from its centre line over 110 ms, the content fades in from 60 to 220 ms; a category
     * switch fades the content column in and slides it 6 px. Input is live from the first frame (nothing here gates
     * a click), Stop all is a chrome node and never waits. Motion OFF: all instant; REDUCED: fades only.
     */
    private void renderScaled(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        long bootT = boot.elapsed(now);
        if (bootT >= 0L) {
            renderBoot(g, mouseX, mouseY, partialTick, bootT, now);
            return;
        }
        Motion motion = HoloTheme.motion;
        float age = now - openedAt;
        float frameP = motion == Motion.OFF ? 1f : clamp01(age / OPEN_FRAME_MS);
        float contentA = motion == Motion.OFF ? 1f : clamp01((age - OPEN_FADE_FROM_MS) / OPEN_FADE_MS);
        float switchP = motion == Motion.OFF || contentSwitchAt == 0L ? 1f
                : clamp01((now - contentSwitchAt) / SWITCH_MS);
        backdropAlpha = frameP;
        renderBackground(g, mouseX, mouseY, partialTick);
        stepScroll(now);
        drawWindow(g, frameP, now);
        boolean fading = contentA < 1f;
        if (fading) {
            g.flush();
            RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0.02f, contentA));
        }
        drawTopBar(g, mouseX, mouseY);
        drawSidebar(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);
        boolean switching = switchP < 1f;
        float sw = AnimStore.easeOut(switchP);
        if (switching) {
            g.flush();
            RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0.02f, contentA * sw));
        }
        int slide = switching && motion == Motion.FULL ? Math.round(SWITCH_SLIDE * (1f - sw)) : 0;
        drawContent(g, mouseX, mouseY, slide);
        if (switching) {
            g.flush();
            RenderSystem.setShaderColor(1f, 1f, 1f, fading ? Math.max(0.02f, contentA) : 1f);
        }
        drawToast(g);
        updateHover(mouseX, mouseY, now);
        drawTooltip(g, mouseX, mouseY, now);
        if (fading) {
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

    /**
     * The window: one hero bracket frame on glass with the chamfer and scanlines; the three columns are one glass
     * surface split by faint hairlines. Without the player's blur the glass gets a darker base so HUD text behind
     * the window cannot ghost through.
     */
    private void drawWindow(GuiGraphics g, float frameP, long now) {
        int fy = wy;
        int fh = wh;
        float fa = 1f;
        if (frameP < 1f) {
            if (HoloTheme.motion == Motion.FULL) {
                float e = AnimStore.easeOut(frameP);
                fh = Math.max(2, Math.round(wh * e));
                fy = wy + (wh - fh) / 2;
                fa = Math.min(1f, 0.35f + e);
            } else {
                fa = frameP;
            }
        }
        if (!blurOn) {
            HoloFrames.frame(g, wx, fy, ww, fh, FrameLevel.QUIET, true, HoloToken.VOID, HoloToken.LINE, 0.62f * fa);
        }
        HoloFrames.frame(g, wx, fy, ww, fh, FrameLevel.HERO, true, fa);
        HoloFrames.scanlines(g, wx, fy, ww, fh, fa);
        if (fh < wh) {
            return;
        }
        drawSweep(g, now);
        int line = HoloTheme.argb(HoloToken.LINE_FAINT, fa);
        HoloFrames.hLine(g, wx + 1, wx + ww - 1, wy + UiTheme.TOP_BAR_H, line);
        HoloFrames.hLine(g, wx + 1, wx + ww - 1, autopilotBarY() + AutopilotBar.H, line);
        HoloFrames.vLine(g, wx + sidebarW, viewY, wy + wh - 1, line);
        if (listW > 0) {
            HoloFrames.vLine(g, listX + listW, viewY, wy + wh - 1, line);
        }
    }

    /** A 24 px band at 5 % accent travelling down the window on open and then every 8 s (effects FULL, motion FULL). */
    private void drawSweep(GuiGraphics g, long now) {
        if (HoloTheme.effects != EffectLevel.FULL || HoloTheme.motion != Motion.FULL) {
            return;
        }
        long phase = (now - openedAt) % SWEEP_PERIOD_MS;
        if (phase < 0L || phase >= (long) SWEEP_MS) {
            return;
        }
        int y0 = wy - SWEEP_H + Math.round((wh + SWEEP_H) * (phase / SWEEP_MS));
        int mid = y0 + SWEEP_H / 2;
        int y1 = y0 + SWEEP_H;
        int top = wy + 1;
        int bot = wy + wh - 1;
        int c = HoloTheme.argb(HoloToken.ACCENT, 0.05f);
        int clear = c & 0x00FFFFFF;
        int a0 = Math.max(y0, top);
        int a1 = Math.min(mid, bot);
        if (a1 > a0) {
            g.fillGradient(wx + 1, a0, wx + ww - 1, a1, clear, c);
        }
        int b0 = Math.max(mid, top);
        int b1 = Math.min(y1, bot);
        if (b1 > b0) {
            g.fillGradient(wx + 1, b0, wx + ww - 1, b1, c, clear);
        }
    }

    /** The y a list row is drawn at: its laid-out place, or the reflow position while it settles there. */
    private int listDrawY(UiNode n) {
        if (n instanceof ListRow r && HoloTheme.motion == Motion.FULL) {
            return listTop + Math.round(AnimStore.SHARED.settle(r.animKey, n.y, AnimStore.TAU_NORMAL)) - listScroll;
        }
        return listSy(n);
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
            int sy = listDrawY(n); // every row settles, visible or not, so none jumps when scrolled into view
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
            int rx = listX + listW - 4;
            boolean hot = mouseIn && mouseX >= rx - 6;
            g.fill(rx, top + 4, rx + 2, top + 4 + trackH, HoloTheme.argb(HoloToken.LINE_FAINT));
            g.fill(rx, thumbY, rx + 2, thumbY + thumbH, HoloTheme.argb(hot ? HoloToken.LINE_STRONG : HoloToken.LINE));
        }
    }

    private static final com.utilitysuite.ui.holo.SuiteFonts.Client.Run SMALL_RUN =
            new com.utilitysuite.ui.holo.SuiteFonts.Client.Run();

    private static net.minecraft.network.chat.Style microStyle() {
        return com.utilitysuite.ui.holo.SuiteFonts.Client.style(com.utilitysuite.core.api.visual.TextRole.MICRO);
    }

    /**
     * Small caps text (chips, captions, version). In the suite MICRO face it is drawn natively at its own size: a TTF
     * pose-scaled to 0.75 loses its thin strokes. The vanilla fallback keeps the 0.75 pose scaling.
     */
    static void smallText(GuiGraphics g, Font font, String s, int x, int y, int color) {
        net.minecraft.network.chat.Style st = microStyle();
        if (st != null) {
            g.drawString(font, SMALL_RUN.set(s, st), x, y, color, false);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    static int smallWidth(Font font, String s) {
        net.minecraft.network.chat.Style st = microStyle();
        return st != null ? font.width(SMALL_RUN.set(s, st)) : (int) Math.ceil(font.width(s) * 0.75f);
    }

    /** Square brackets around a readout, drawn as hairlines (no fill). */
    private static void readoutBrackets(GuiGraphics g, int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + 1, y + h, argb);
        g.fill(x + 1, y, x + 3, y + 1, argb);
        g.fill(x + 1, y + h - 1, x + 3, y + h, argb);
        g.fill(x + w - 1, y, x + w, y + h, argb);
        g.fill(x + w - 3, y, x + w - 1, y + 1, argb);
        g.fill(x + w - 3, y + h - 1, x + w - 1, y + h, argb);
    }

    /** The "acting" glyph: a 5 px diamond centred on (cx, cy). */
    static void diamond(GuiGraphics g, int cx, int cy, int argb) {
        g.fill(cx, cy - 2, cx + 1, cy - 1, argb);
        g.fill(cx - 1, cy - 1, cx + 2, cy, argb);
        g.fill(cx - 2, cy, cx + 3, cy + 1, argb);
        g.fill(cx - 1, cy + 1, cx + 2, cy + 2, argb);
        g.fill(cx, cy + 2, cx + 1, cy + 3, argb);
    }

    /** The "armed" glyph: a 3 px dot with a faint 5 px halo, centred on (cx, cy). */
    static void armedDot(GuiGraphics g, int cx, int cy, HoloToken token) {
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, HoloTheme.argb(token, 0.22f));
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, HoloTheme.argb(token));
    }

    /** The "stopped" glyph: a 5 px cross with its top-left at (x, y). */
    static void cross(GuiGraphics g, int x, int y, int argb) {
        for (int i = 0; i < 5; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, argb);
            g.fill(x + 4 - i, y + i, x + 5 - i, y + i + 1, argb);
        }
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        int ty = wy + (UiTheme.TOP_BAR_H - 8) / 2;
        HoloFrames.sideBar(g, wx + 12, ty - 1, 10, HoloToken.ACCENT, 1f);
        UiRenderUtils.text(g, font, TITLE, wx + 20, ty, HoloTheme.argb(HoloToken.TEXT));
        if (versionW > 0) {
            int vx = wx + 20 + titleTextW + 6;
            int vw = versionW + 8;
            readoutBrackets(g, vx, ty - 1, vw, 10, HoloTheme.argb(HoloToken.LINE));
            smallText(g, font, versionText, vx + 4, ty + 2, HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
        if (profilePillW > 0) {
            int px = wx + 20 + titleW + 8;
            int py = wy + (UiTheme.TOP_BAR_H - 14) / 2;
            readoutBrackets(g, px, py, profilePillW, 14, HoloTheme.argb(HoloToken.LINE));
            g.fill(px + 5, py + 6, px + 8, py + 9, HoloTheme.argb(HoloToken.ACCENT, 0.8f));
            UiRenderUtils.text(g, font, profileText, px + 12, py + 3, HoloTheme.argb(HoloToken.TEXT_SECONDARY));
        }
        UiRenderUtils.text(g, font, cleanLabel, cleanLabelX, ty,
                HoloTheme.argb(cleanOn ? HoloToken.WARN : HoloToken.TEXT_MUTED));
        if (goalField != null) { // FIELD inset under the goal console (the kit field draws its own edge on top)
            g.fill(goalField.x, goalField.y, goalField.x + goalField.w, goalField.y + goalField.h,
                    HoloTheme.argb(HoloToken.FIELD));
        }
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
            g.fill(hx, goalField.y + 4, hx + START_W - 8, goalField.y + 16, HoloTheme.argb(HoloToken.FIELD));
            HoloFrames.edge(g, hx, goalField.y + 4, START_W - 8, 12, HoloTheme.argb(HoloToken.LINE_FAINT));
            UiRenderUtils.text(g, font, "Enter", hx + (START_W - 8 - enterW) / 2, goalField.y + 6,
                    HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
    }

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        SidebarTab active = null;
        for (int i = 0; i < chrome.size(); i++) {
            UiNode n = chrome.get(i);
            if (n instanceof SidebarTab st) {
                if (st.active) {
                    active = st;
                }
                n.render(g, font, n.x, n.y, mx, my);
            }
        }
        if (active != null) { // the 2 px selection bar travels to the newly selected row
            int y0 = Math.round(AnimStore.SHARED.settle(SIDEBAR_BAR_KEY, active.y + 4, AnimStore.TAU_NORMAL));
            g.fill(active.x, y0, active.x + HoloTheme.BAR, y0 + Math.max(4, active.h - 8),
                    HoloTheme.argb(HoloToken.ACCENT));
        }
        int fy = wy + wh - footerH;
        HoloFrames.hLine(g, wx + 10, wx + sidebarW - 10, fy, HoloTheme.argb(HoloToken.LINE_FAINT));
        boolean twoLines = footerH >= 42;
        // one-line footer: the job state replaces the automation text unless automation is stopped
        String first = twoLines || footerStopped ? footerStatus : footerJob;
        if (footerStopped) {
            cross(g, wx + 12, fy + 9, HoloTheme.argb(HoloToken.DANGER));
        } else {
            armedDot(g, wx + 14, fy + 11, HoloToken.ACCENT);
        }
        UiRenderUtils.text(g, font, first, wx + 26, fy + 10, HoloTheme.argb(footerStopped ? HoloToken.DANGER
                : HoloToken.TEXT_SECONDARY));
        if (twoLines) {
            UiRenderUtils.text(g, font, footerJob, wx + 26, fy + 23, HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
    }

    private void drawContent(GuiGraphics g, int mouseX, int mouseY, int slide) {
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
            n.render(g, font, contentSx(n), sy + slide, mx, my);
        }
        // fade edge so cards slide under the bars
        g.fillGradient(viewX, viewY, viewX + viewW, viewY + 6, HoloTheme.argb(HoloToken.VOID, 0.4f),
                HoloTheme.argb(HoloToken.VOID) & 0x00FFFFFF);
        UiRenderUtils.endClip(g);

        int max = maxScroll();
        if (max > 0) {
            int trackX = viewX + viewW - 5;
            int trackY = viewY + 6;
            int trackH = viewH - 12;
            int thumbH = thumbHeight(trackH, max);
            int thumbY = trackY + Math.round((trackH - thumbH) * (scrollCur / max));
            boolean hot = scrollDragging || (mouseX >= viewX + viewW - 9 && mouseIn);
            g.fill(trackX, trackY, trackX + 2, trackY + trackH, HoloTheme.argb(HoloToken.LINE_FAINT));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH,
                    HoloTheme.argb(hot ? HoloToken.LINE_STRONG : HoloToken.LINE));
        }
    }

    private void drawToast(GuiGraphics g) {
        if (status.isEmpty()) {
            return;
        }
        if (statusW < 0) {
            statusW = com.utilitysuite.ui.kit.UiRenderUtils.width(font, status);
        }
        int tw = Math.min(statusW, viewW - 60);
        int w = tw + 20;
        int x = viewX + (viewW - w) / 2;
        int y = viewY + viewH - 28;
        float alpha = statusTicks < 10 ? statusTicks / 10f : 1f;
        HoloFrames.frame(g, x, y, w, 18, FrameLevel.FRAMED, false, HoloToken.GLASS_RAISED, HoloToken.LINE, alpha);
        HoloFrames.sideBar(g, x, y, 18, HoloToken.ACCENT, alpha);
        if (alpha > 0.1f) {
            UiRenderUtils.scissor(g, x + 10, y, x + 10 + tw, y + 18);
            UiRenderUtils.text(g, font, status, x + 10, y + 5, HoloTheme.argb(HoloToken.TEXT_SECONDARY, alpha));
            g.disableScissor();
        }
    }

    private void updateHover(int mouseX, int mouseY, long now) {
        UiNode found = null;
        int fx = 0;
        int fy = 0;
        for (int i = chrome.size() - 1; i >= 0 && found == null; i--) {
            UiNode n = chrome.get(i);
            if (chromeVisible(n) && !n.tooltipText().isEmpty() && n.contains(mouseX, mouseY, n.x, n.y)) {
                found = n;
                fx = n.x;
                fy = n.y;
            }
        }
        if (found == null && inList(mouseX, mouseY)) {
            for (int i = listNodes.size() - 1; i >= 0; i--) {
                UiNode n = listNodes.get(i);
                if (!n.tooltipText().isEmpty() && n.contains(mouseX, mouseY, listSx(n), listSy(n))) {
                    found = n;
                    fx = listSx(n);
                    fy = listSy(n);
                    break;
                }
            }
        }
        if (found == null && inView(mouseX, mouseY)) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                UiNode n = nodes.get(i);
                if (!n.tooltipText().isEmpty() && n.contains(mouseX, mouseY, contentSx(n), contentSy(n))) {
                    found = n;
                    fx = contentSx(n);
                    fy = contentSy(n);
                    break;
                }
            }
        }
        if (found != hoverNode) {
            hoverNode = found;
            hoverSince = now;
        }
        hoverSx = fx;
        hoverSy = fy;
    }

    /**
     * The tooltip: a framed GLASS_RAISED plate after 350 ms with a 90 ms fade, placed below-right of the pointer and
     * flipped above or beside the control when clipped, so it never covers the control it explains.
     */
    private void drawTooltip(GuiGraphics g, int mouseX, int mouseY, long now) {
        long since = now - hoverSince - TOOLTIP_DELAY_MS;
        if (hoverNode == null || dragNode != null || since < 0L) {
            return;
        }
        String t = hoverNode.tooltipText();
        if (!t.equals(tipText)) { // re-wrap only when the tooltip changes
            tipText = t;
            tipLines = font.split(Component.literal(t), 200);
            tipW = 0;
            for (FormattedCharSequence l : tipLines) {
                tipW = Math.max(tipW, font.width(l)); // tooltips: vanilla-wrapped sequences, vanilla face
            }
        }
        float a = HoloTheme.motion == Motion.OFF ? 1f : Math.min(1f, since / TOOLTIP_FADE_MS);
        if (a < 0.05f) { // vanilla draws near-zero-alpha text fully opaque: skip the first sliver of the fade
            return;
        }
        int w = tipW + 16;
        int h = tipLines.size() * 10 + 10;
        long pos = TooltipPlacement.place(mouseX, hoverSx, hoverSy, hoverNode.w, hoverNode.h, w, h, width, height,
                4);
        int x = TooltipPlacement.x(pos);
        int y = TooltipPlacement.y(pos);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        HoloFrames.frame(g, x, y, w, h, FrameLevel.FRAMED, false, HoloToken.GLASS_RAISED, HoloToken.LINE, a);
        int text = HoloTheme.argb(HoloToken.TEXT_SECONDARY, a);
        for (int i = 0; i < tipLines.size(); i++) {
            g.drawString(font, tipLines.get(i), x + 8, y + 6 + i * 10, text, false);
        }
        g.pose().popPose();
    }

    /**
     * Where a tooltip goes, as a pure function: below the anchor with x just right of the pointer; above when it does
     * not fit below; beside (right, then left) when neither fits; always clamped on screen. Below and above never
     * overlap the anchor rectangle. Allocation free: the result is packed into a long.
     */
    static final class TooltipPlacement {
        static final int GAP = 4;

        private TooltipPlacement() {
        }

        static long place(int pointerX, int ax, int ay, int aw, int ah, int tw, int th, int sw, int sh, int margin) {
            int x = clamp(pointerX + 8, margin, sw - margin - tw);
            int below = ay + ah + GAP;
            if (below + th <= sh - margin) {
                return pack(x, below);
            }
            int above = ay - GAP - th;
            if (above >= margin) {
                return pack(x, above);
            }
            int y = clamp(ay, margin, sh - margin - th);
            int right = ax + aw + GAP;
            if (right + tw <= sw - margin) {
                return pack(right, y);
            }
            int left = ax - GAP - tw;
            if (left >= margin) {
                return pack(left, y);
            }
            return pack(x, clamp(below, margin, sh - margin - th)); // no free side: stay on screen
        }

        static int clamp(int v, int lo, int hi) {
            if (hi < lo) {
                return lo;
            }
            return v < lo ? lo : Math.min(v, hi);
        }

        static long pack(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        static int x(long packed) {
            return (int) (packed >> 32);
        }

        static int y(long packed) {
            return (int) packed;
        }
    }

    // ---- sidebar tab button -----------------------------------------------------------------------------------------

    private final class SidebarTab extends UiNode {
        private final Tab t;
        private final boolean active;
        private final ItemStack icon;
        private final String label;
        private final String badge;
        private final int badgeW;

        SidebarTab(Font f, Tab t, boolean active, int w, int h, int enabled) {
            super(w, h);
            this.t = t;
            this.active = active;
            this.icon = ItemIcons.stack(t.icon());
            this.badge = enabled > 0 ? Integer.toString(enabled) : "";
            this.badgeW = badge.isEmpty() ? 0 : smallWidth(f, badge);
            this.label = UiRenderUtils.ellipsize(f, t.label, w - 40 - (badge.isEmpty() ? 0 : 14));
            if (!badge.isEmpty()) {
                tooltip(enabled + (enabled == 1 ? " module" : " modules") + " on in " + t.label);
            }
        }

        @Override
        public boolean interactive() {
            return !active;
        }

        /** Quiet rows; the selected one is raised glass with brackets (its accent bar is drawn by the screen). */
        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
            if (active) {
                g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED));
                HoloFrames.brackets(g, sx, sy, w, h, HoloTheme.bracketArm(w, h),
                        HoloTheme.argb(HoloToken.LINE_STRONG));
            } else if (hover) {
                g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED, 0.5f));
                HoloFrames.edge(g, sx, sy, w, h, HoloTheme.argb(HoloToken.LINE_FAINT));
            }
            UiRenderUtils.item(g, icon, sx + 10, sy + (h - 12) / 2, 12);
            UiRenderUtils.text(g, font, label, sx + 30, sy + (h - 8) / 2,
                    HoloTheme.argb(active || hover ? HoloToken.TEXT : HoloToken.TEXT_SECONDARY));
            if (!badge.isEmpty()) {
                int bx = sx + w - 6 - Math.max(10, badgeW + 6);
                int by = sy + (h - 10) / 2;
                smallText(g, font, badge, bx + (Math.max(10, badgeW + 6) - badgeW) / 2, by + 3,
                        HoloTheme.argb(active ? HoloToken.ACCENT : HoloToken.TEXT_MUTED));
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

    /** A column-2 row with a stable animation key, so search can reflow it from its old place. */
    private abstract static class ListRow extends UiNode {
        final long animKey;

        ListRow(int w, int h, long animKey) {
            super(w, h);
            this.animKey = animKey;
        }
    }

    /** Small-caps group caption ("MODULES", a category name in search results) in accent, with a hairline. */
    private static final class CaptionRow extends ListRow {
        private final String text;
        private int textW = -1;

        CaptionRow(String text, int w) {
            super(w, 14, AnimStore.key(text, 101));
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            if (textW < 0) {
                textW = smallWidth(font, text);
            }
            smallText(g, font, text, sx + 6, sy + 5, HoloTheme.argb(HoloToken.ACCENT, 0.85f));
            int lx = sx + 6 + textW + 4;
            if (lx < sx + w - 6) {
                HoloFrames.hLine(g, lx, sx + w - 6, sy + 8, HoloTheme.argb(HoloToken.LINE_FAINT));
            }
        }
    }

    /**
     * One entry of the module list: a 2 px category-hue bar, the name (bright when on, muted when off), an optional
     * matched-setting line in search results and a state mark at the right edge (accent dot on, dim dot off, SYNC
     * diamond while the module holds a lease). Left click opens it in column 3; right click turns the module on or off.
     */
    private final class ModuleRow extends ListRow {
        private final Tab target;
        private final String moduleId;
        private final String name;
        private final String sub;
        private final boolean enabled;
        private final boolean overview;
        private final boolean isSelected;
        private boolean leased;

        ModuleRow(Font f, Tab target, String moduleId, String name, String sub, boolean enabled, boolean overview,
                  boolean selected, int w) {
            super(w, sub.isEmpty() ? ROW_H : ROW_H_SUB, overview ? AnimStore.key("menu.row.overview", target.ordinal())
                    : AnimStore.key(moduleId, 0));
            this.target = target;
            this.moduleId = moduleId;
            this.overview = overview;
            this.isSelected = selected;
            this.enabled = enabled;
            this.name = UiRenderUtils.ellipsize(f, name, w - 10 - 14);
            this.sub = sub.isEmpty() ? "" : UiRenderUtils.ellipsize(f, "setting: " + sub, (int) ((w - 16) / 0.75f));
            this.leased = !overview && holdsLease(moduleId);
            tooltip(overview ? "Quick actions and status for " + target.label
                    : "Click to open · right-click to turn " + (enabled ? "off" : "on"));
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public void refresh(Font font) {
            leased = !overview && holdsLease(moduleId);
        }

        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
            if (isSelected) {
                g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED));
                HoloFrames.edge(g, sx, sy, w, h, HoloTheme.argb(HoloToken.LINE_FAINT));
            } else if (hover) {
                g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED, 0.5f));
            }
            if (overview) {
                HoloFrames.sideBar(g, sx, sy + 3, ROW_H - 6, isSelected ? HoloToken.ACCENT : HoloToken.LINE, 1f);
            } else {
                HoloFrames.sideBar(g, sx, sy + 3, ROW_H - 6, target.token(), enabled || isSelected ? 1f : 0.4f);
            }
            boolean on = overview || enabled;
            UiRenderUtils.text(g, font, name, sx + 8, sy + (ROW_H - 8) / 2, HoloTheme.argb(isSelected
                    ? HoloToken.TEXT : on ? HoloToken.TEXT_SECONDARY : HoloToken.TEXT_MUTED));
            if (!overview) {
                int cx = sx + w - 8;
                int cy = sy + ROW_H / 2 - 1;
                if (leased) {
                    diamond(g, cx, cy, HoloTheme.argb(HoloToken.SYNC));
                } else if (enabled) {
                    armedDot(g, cx, cy, HoloToken.ACCENT);
                } else {
                    g.fill(cx - 1, cy - 1, cx + 2, cy + 2, HoloTheme.argb(HoloToken.TEXT_FAINT));
                }
            }
            if (!sub.isEmpty()) {
                smallText(g, font, sub, sx + 8, sy + ROW_H + 1, HoloTheme.argb(HoloToken.TEXT_MUTED));
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

    // ---- legit level ------------------------------------------------------------------------------------------------

    /** Applies a legit level: writes its knobs, notifies the modules whose settings changed, saves. */
    void applyLegitLevel(com.utilitysuite.core.LegitPresets.Level level) {
        try {
            var ms = Services.modState();
            List<com.utilitysuite.core.LegitPresets.Knob> changed =
                    com.utilitysuite.core.LegitPresets.apply(level, ms.profile(), ms.global());
            boolean core = false;
            for (com.utilitysuite.core.LegitPresets.Knob k : changed) {
                if (k.global()) {
                    core = true;
                    continue;
                }
                Services.modules().byId(k.moduleId()).ifPresent(m -> {
                    if (m instanceof IConfigurable c) {
                        c.onSettingChanged(k.key());
                    }
                });
            }
            if (core) {
                CoreBootstrap.applyCoreSettings();
            }
            ms.markDirty();
            setStatus("Legit level: " + presetLabel(level) + (changed.isEmpty() ? " (already set)"
                    : " (" + changed.size() + " settings)"));
        } catch (RuntimeException e) {
            UtilitySuiteMod.LOGGER.warn("Applying legit level {} failed", level, e);
            setStatus("Could not apply the legit level");
        }
        requestRebuild();
    }

    private static com.utilitysuite.core.LegitPresets.Level currentLegitLevel() {
        try {
            return com.utilitysuite.core.LegitPresets.current(Services.modState().profile(),
                    Services.modState().global());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String presetLabel(com.utilitysuite.core.LegitPresets.Level l) {
        return switch (l) {
            case LEGIT -> "Legit";
            case BALANCED -> "Balanced";
            case RELAXED -> "Relaxed";
        };
    }

    private static String presetTip(com.utilitysuite.core.LegitPresets.Level l) {
        return switch (l) {
            case LEGIT -> "Slower turns, human spacing, line of sight required, visible rotations, walk bridge "
                    + "disguised as a backward bridger";
            case BALANCED -> "The defaults";
            case RELAXED -> "Fast turns and placements, silent rotations, no line-of-sight requirement, instant "
                    + "walk bridging without a disguise";
        };
    }

    /**
     * Segmented Legit / Balanced / Relaxed control (column 2). No segment is lit when the knobs are mixed ("custom");
     * re-reads the profile in {@link #refresh} (every few ticks), so hand-tuning a setting shows up.
     */
    private final class PresetBar extends ListRow {
        private static final com.utilitysuite.core.LegitPresets.Level[] LEVELS =
                com.utilitysuite.core.LegitPresets.Level.values();
        private static final String[] LABELS_UC = new String[LEVELS.length];

        static {
            for (int i = 0; i < LEVELS.length; i++) {
                LABELS_UC[i] = presetLabel(LEVELS[i]).toUpperCase(Locale.ROOT);
            }
        }

        private com.utilitysuite.core.LegitPresets.Level current = currentLegitLevel();
        private final int[] labelW = new int[LEVELS.length];
        private boolean widthsReady;
        private com.utilitysuite.core.LegitPresets.Level statusFor;
        private boolean statusValid;
        private String statusText = "";

        PresetBar(int w) {
            super(w, 26, AnimStore.key("menu.row.preset", 0));
            tooltip("Tunes every system at once (speed, rotations, line of sight, bridge disguise). It never turns "
                    + "features on or off. Lit segment: the level all settings match; none lit: custom.");
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public void refresh(Font font) {
            current = currentLegitLevel();
        }

        /**
         * One FIELD inset split by hairlines; the lit segment is raised glass with a 2 px accent underline that
         * travels between segments. Labels, widths and the status line are cached (rebuilt only when the level
         * changes), so nothing is built per frame.
         */
        @Override
        public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
            if (!widthsReady) {
                widthsReady = true;
                for (int i = 0; i < LEVELS.length; i++) {
                    labelW[i] = smallWidth(font, LABELS_UC[i]);
                }
            }
            if (!statusValid || statusFor != current) {
                statusFor = current;
                statusValid = true;
                String s = current == null ? "custom: settings are hand-tuned" : presetTip(current);
                statusText = UiRenderUtils.ellipsize(font, s, (int) ((w - 12) / 0.75f));
            }
            int bx = sx + 4, bw = w - 8, bh = 14;
            g.fill(bx, sy, bx + bw, sy + bh, HoloTheme.argb(HoloToken.FIELD));
            HoloFrames.edge(g, bx, sy, bw, bh, HoloTheme.argb(HoloToken.LINE_FAINT));
            int segW = bw / LEVELS.length;
            int litX = -1;
            int litW = 0;
            for (int i = 0; i < LEVELS.length; i++) {
                int x0 = bx + i * segW;
                int segWi = i == LEVELS.length - 1 ? bw - i * segW : segW;
                boolean on = LEVELS[i] == current;
                boolean hover = inside(mouseX, mouseY, x0, sy, segWi, bh);
                if (i > 0) {
                    HoloFrames.vLine(g, x0, sy + 3, sy + bh - 3, HoloTheme.argb(HoloToken.LINE_FAINT));
                }
                if (on) {
                    g.fill(x0 + 1, sy + 1, x0 + segWi - 1, sy + bh - 1, HoloTheme.argb(HoloToken.GLASS_RAISED));
                    litX = x0;
                    litW = segWi;
                } else if (hover) {
                    g.fill(x0 + 1, sy + 1, x0 + segWi - 1, sy + bh - 1, HoloTheme.argb(HoloToken.GLASS_RAISED, 0.5f));
                }
                smallText(g, font, LABELS_UC[i], x0 + (segWi - labelW[i]) / 2, sy + 5, HoloTheme.argb(on
                        ? HoloToken.ACCENT : hover ? HoloToken.TEXT : HoloToken.TEXT_SECONDARY));
            }
            if (litX >= 0) { // the underline travels to the newly lit segment (offset from the bar, so a reflow
                // of the list does not drag it)
                int ux = bx + Math.round(AnimStore.SHARED.settle(PRESET_BAR_KEY, litX - bx, AnimStore.TAU_NORMAL));
                g.fill(ux + 1, sy + bh - 1 - HoloTheme.BAR, ux + litW - 1, sy + bh - 1,
                        HoloTheme.argb(HoloToken.ACCENT));
            }
            smallText(g, font, statusText, sx + 6, sy + bh + 4,
                    HoloTheme.argb(current == null ? HoloToken.WARN : HoloToken.TEXT_MUTED));
        }

        @Override
        public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
            if (button != 0 || mouseY > sy + 14) {
                return false;
            }
            int bw = w - 8;
            int i = (int) ((mouseX - sx - 4) / Math.max(1, bw / LEVELS.length));
            if (i < 0 || i >= LEVELS.length) {
                return false;
            }
            applyLegitLevel(LEVELS[i]);
            return true;
        }
    }
}
