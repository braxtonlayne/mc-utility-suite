package com.utilitysuite.ui;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Tab-GUI: the classic arrow-key category list in the top-left corner, off by default. Up/Down move, Right/Enter opens a
 * category or toggles the highlighted module, Left goes back. Keys arrive raw from
 * {@code mixin.ui.KeyboardHandlerTabGuiMixin} through {@link #consumesKey} and are swallowed (never reaching vanilla key
 * mappings or {@link InputEvent.Key} listeners) only while the list is visible: module enabled, world loaded, no screen
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

    /**
     * Former {@link InputEvent.Key} listener, still registered by {@link UiBootstrap}. Intentionally empty: navigation
     * now runs from {@link #consumesKey} so a key is never handled twice. The registration can be dropped.
     */
    void onKey(InputEvent.Key event) {
        // handled by consumesKey (raw-key mixin); see class javadoc
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
        String[] cats = TabGuiNav.CATEGORY_NAMES;
        g.fill(x, y, x + CAT_W, y + cats.length * ROW_H, UiTheme.fade(UiTheme.WINDOW, 0.75f));
        for (int i = 0; i < cats.length; i++) {
            boolean sel = i == nav.category();
            int ry = y + i * ROW_H;
            if (sel) {
                g.fill(x, ry, x + CAT_W, ry + ROW_H, nav.inModules() ? UiTheme.CONTROL : UiTheme.ACCENT);
            }
            UiRenderUtils.text(g, font, cats[i], x + 4, ry + 2, sel && !nav.inModules() ? UiTheme.ON_ACCENT
                    : UiTheme.TEXT);
        }
        if (!nav.inModules() || moduleCount == 0) {
            return;
        }
        int mx = x + CAT_W + 2;
        int my = y + nav.category() * ROW_H;
        g.fill(mx, my, mx + modulesW, my + moduleCount * ROW_H, UiTheme.fade(UiTheme.WINDOW, 0.75f));
        for (int i = 0; i < moduleCount; i++) {
            IModule m = modules[i];
            int ry = my + i * ROW_H;
            boolean sel = i == nav.module();
            if (sel) {
                g.fill(mx, ry, mx + modulesW, ry + ROW_H, UiTheme.ACCENT);
            }
            boolean on = m != null && m.isEnabled();
            g.fill(mx + 3, ry + 3, mx + 5, ry + ROW_H - 3, on ? UiTheme.RUNNING : UiTheme.TRACK_OFF);
            UiRenderUtils.text(g, font, names[i], mx + 8, ry + 2, sel ? UiTheme.ON_ACCENT
                    : on ? UiTheme.TEXT : UiTheme.TEXT_DIM);
        }
    }
}
