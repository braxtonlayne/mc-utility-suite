package com.utilitysuite.ui;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.UiRenderUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Tab-GUI: the classic arrow-key category list in the top-left corner, off by default. Up/Down move, Right/Enter opens a
 * category or toggles the highlighted module, Left goes back. Keys arrive raw from
 * {@code mixin.ui.KeyboardHandlerTabGuiMixin} through {@link #consumesKey} and are swallowed (never reaching vanilla key
 * mappings) only while the list is visible: module enabled, world loaded, no screen
 * open, not under Clean-Screen, F1 or F3. Module lists are cached per category (refreshed on navigation and once a
 * second), so rendering and key handling allocate nothing. Public only for the mixin's static hook.
 */
public final class TabGui implements IModule, IConfigurable, LayeredDraw.Layer {

    /** The registered instance (client thread only); null until {@link UiBootstrap} creates it. */
    private static TabGui instance;

    static final String ID = "tab_gui";
    private static final int MAX = 48;
    private static final int ROW_H = 12;
    private static final int CAT_W = 64;
    private static final String K_ENTER = "enter_toggles";
    private static final long K_UNFOLD = AnimStore.key("tabgui.unfold", 0);
    private static final float UNFOLD_S = 0.12f;

    private final TabGuiNav nav = new TabGuiNav();
    private final IModule[] modules = new IModule[MAX];
    private final String[] names = new String[MAX];
    private int moduleCount;
    private int modulesW;
    private boolean enabled;
    private boolean enterToggles = true;
    private int ticks;

    TabGui() {
        instance = this;
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

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
            nav.reset();
        }
    }

    @Override
    public void onTick() {
        if (++ticks % 20 == 0 && nav.inModules()) {
            reload(); // modules registered or renamed meanwhile; once a second, not per frame
        }
    }

    @Override
    public void onWorldUnload() {
        nav.reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read();
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Tab-GUI";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.bool(K_ENTER, "Enter also toggles", true, null,
                "Enter works like the Right arrow (open a category, toggle a module)."));
    }

    @Override
    public void onSettingChanged(String key) {
        read();
    }

    private void read() {
        try {
            enterToggles = Services.modState().profile().getBool(ID, K_ENTER, true);
        } catch (RuntimeException e) {
            enterToggles = true;
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    /**
     * Raw-key hook for {@code KeyboardHandlerTabGuiMixin} (HEAD of {@code KeyboardHandler.keyPress}, client thread):
     * runs the navigation for a consumed press/repeat and returns true when the key must not reach vanilla. Returns
     * false at once for any key that is not a navigation key, and for every press while the list is hidden. A release
     * is swallowed exactly when its press was (see {@link TabGuiNav#decide}). Allocation free.
     */
    public static boolean consumesKey(long windowPointer, int key, int action) {
        TabGui gui = instance;
        if (gui == null || TabGuiNav.slotOf(key) < 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (windowPointer != mc.getWindow().getWindow()) {
            return false; // vanilla ignores other windows too
        }
        boolean visible = action != GLFW.GLFW_RELEASE && gui.visible(mc);
        int decision = gui.nav.decide(key, action, visible, gui.enterToggles);
        if (decision == TabGuiNav.NAVIGATE) {
            gui.navigate(key);
        }
        return decision != TabGuiNav.PASS;
    }

    /** True while the list is on screen: the same conditions {@link #render} draws under. */
    private boolean visible(Minecraft mc) {
        if (!enabled || mc.player == null || mc.screen != null || mc.options.hideGui
                || mc.getDebugOverlay().showDebugScreen()) {
            return false;
        }
        try {
            return !Services.modState().isCleanScreen();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void navigate(int key) {
        TabGuiNav.Result r;
        switch (key) {
            case TabGuiNav.KEY_UP -> r = nav.up(moduleCount);
            case TabGuiNav.KEY_DOWN -> r = nav.down(moduleCount);
            case TabGuiNav.KEY_RIGHT, TabGuiNav.KEY_ENTER, TabGuiNav.KEY_KP_ENTER -> r = right();
            case TabGuiNav.KEY_LEFT -> r = nav.left();
            default -> {
                return;
            }
        }
        if (r == TabGuiNav.Result.TOGGLE) {
            toggle(modules[nav.module()]);
        }
    }

    private TabGuiNav.Result right() {
        if (!nav.inModules()) {
            reload();
        }
        return nav.right(moduleCount);
    }

    private static void toggle(IModule m) {
        if (m == null) {
            return;
        }
        try {
            CoreBootstrap.modules().setModuleEnabled(m.id(), !m.isEnabled(), Services.modState().profile());
        } catch (RuntimeException e) {
            UtilitySuiteMod.LOGGER.warn("Tab-GUI could not toggle {}", m.id(), e);
        }
    }

    /** Caches the highlighted category's modules (navigation and once a second; never per frame). */
    private void reload() {
        List<IModule> list;
        try {
            list = Services.modules().byCategory(nav.selectedCategory());
        } catch (RuntimeException e) {
            list = List.of();
        }
        Font font = Minecraft.getInstance().font;
        int n = 0;
        int w = 0;
        for (int i = 0; i < list.size() && n < MAX; i++) {
            IModule m = list.get(i);
            if (m == this) {
                continue;
            }
            modules[n] = m;
            names[n] = m instanceof IConfigurable c ? c.displayName() : m.id();
            if (font != null) {
                w = Math.max(w, font.width(names[n]));
            }
            n++;
        }
        for (int i = n; i < moduleCount; i++) {
            modules[i] = null;
            names[i] = null;
        }
        moduleCount = n;
        modulesW = w + 16;
        nav.clampModule(n);
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        try {
            if (Services.modState().isCleanScreen()) {
                return;
            }
        } catch (RuntimeException e) {
            return;
        }
        Font font = mc.font;
        int x = 4;
        int y = 4;
        boolean inModules = nav.inModules();
        // the module column unfolds over 120 ms; the state also winds back while it is closed
        float unfold = AnimStore.easeOut(AnimStore.SHARED.progress(K_UNFOLD, inModules && moduleCount > 0,
                UNFOLD_S));
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        int lineStrong = HoloTheme.argb(HoloToken.LINE_STRONG);
        int textOn = HoloTheme.argb(HoloToken.TEXT);
        int textOff = HoloTheme.argb(HoloToken.TEXT_MUTED);
        String[] cats = TabGuiNav.CATEGORY_NAMES;
        int catH = cats.length * ROW_H;
        HoloFrames.frame(g, x - 1, y - 1, CAT_W + 2, catH + 2, FrameLevel.FRAMED, false, HoloToken.GLASS_HUD,
                HoloToken.LINE, 1f);
        for (int i = 0; i < cats.length; i++) {
            boolean sel = i == nav.category();
            int ry = y + i * ROW_H;
            if (sel) {
                bracketedRow(g, x, ry, CAT_W, inModules ? HoloTheme.argb(HoloToken.LINE) : lineStrong,
                        inModules ? HoloTheme.argb(HoloToken.LINE_FAINT) : HoloTheme.argb(HoloToken.ACCENT_GLOW),
                        inModules ? HoloTheme.argb(HoloToken.LINE) : accent);
            }
            UiRenderUtils.text(g, font, cats[i], x + 6, ry + 2, sel ? (inModules ? textOn : accent) : textOff);
        }
        if (!inModules || moduleCount == 0 || unfold <= 0f) {
            return;
        }
        int mx = x + CAT_W + 3;
        int my = y + nav.category() * ROW_H;
        int fullH = moduleCount * ROW_H;
        int curH = Math.max(ROW_H, (int) (fullH * unfold));
        HoloFrames.frame(g, mx - 1, my - 1, modulesW + 2, curH + 2, FrameLevel.FRAMED, false, HoloToken.GLASS_HUD,
                HoloToken.LINE, 1f);
        for (int i = 0; i < moduleCount; i++) {
            int ry = my + i * ROW_H;
            if (ry + ROW_H > my + curH) {
                break; // not unfolded this far yet
            }
            IModule m = modules[i];
            boolean sel = i == nav.module();
            boolean on = m != null && m.isEnabled();
            if (sel) {
                bracketedRow(g, mx, ry, modulesW, lineStrong, HoloTheme.argb(HoloToken.ACCENT_GLOW), accent);
            }
            // ON: a small accent bar at the right end of the row; OFF: a dim one
            g.fill(mx + modulesW - 6, ry + 3, mx + modulesW - 4, ry + ROW_H - 3,
                    on ? accent : HoloTheme.argb(HoloToken.TEXT_FAINT));
            UiRenderUtils.text(g, font, names[i], mx + 6, ry + 2, sel || on ? textOn : textOff);
        }
    }

    /** The selected row: faint fill, corner brackets and a 2 px side bar. */
    private static void bracketedRow(GuiGraphics g, int x, int y, int w, int bracket, int fill, int bar) {
        g.fill(x, y, x + w, y + ROW_H, fill);
        HoloFrames.brackets(g, x, y, w, ROW_H, 3, bracket);
        g.fill(x, y + 1, x + HoloTheme.BAR, y + ROW_H - 1, bar);
    }
}
