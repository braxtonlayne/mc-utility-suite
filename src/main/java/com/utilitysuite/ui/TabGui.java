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
 * category or toggles the highlighted module, Left goes back. Keys are read from {@link InputEvent.Key} only while the
 * module is enabled, a world is loaded and no screen is open; nothing is drawn otherwise, nor under Clean-Screen or F1.
 * Module lists are cached per category (refreshed on navigation and once a second), so rendering allocates nothing.
 */
final class TabGui implements IModule, IConfigurable, LayeredDraw.Layer {

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

    /** NeoForge {@link InputEvent.Key} listener (registered from {@link UiBootstrap}); cheap early-out when off. */
    void onKey(InputEvent.Key event) {
        if (!enabled || event.getAction() == GLFW.GLFW_RELEASE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        int key = event.getKey();
        TabGuiNav.Result r;
        switch (key) {
            case GLFW.GLFW_KEY_UP -> r = nav.up(moduleCount);
            case GLFW.GLFW_KEY_DOWN -> r = nav.down(moduleCount);
            case GLFW.GLFW_KEY_RIGHT -> r = right();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!enterToggles || event.getAction() != GLFW.GLFW_PRESS) {
                    return;
                }
                r = right();
            }
            case GLFW.GLFW_KEY_LEFT -> r = nav.left();
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
