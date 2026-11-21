package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.Services;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

import java.util.List;

/**
 * The classic enabled-modules list: names of the modules that are on, right-aligned in the top-right corner, longest
 * first, each with its category's colour bar; the autopilot always heads the list when it is on. Opt-in (HUD
 * discipline: nothing draws by default). The list is rebuilt only when the set of enabled modules changes (checked
 * once a second); rendering iterates cached arrays and allocates nothing.
 */
final class ModuleListHud implements LayeredDraw.Layer {

    private static final int MAX = 32;
    static final int AUTOPILOT_COLOR = 0xFF10B981;

    boolean enabled;
    private final SuiteHud hud;
    private final String[] names = new String[MAX];
    private final int[] widths = new int[MAX];
    private final int[] colors = new int[MAX];
    private int count;
    private long lastMask = -1L;
    private int ticks;

    ModuleListHud(SuiteHud hud) {
        this.hud = hud;
    }

    /** Called from the HUD module's tick; cheap early-out when off. */
    void tick() {
        if (!enabled || ++ticks % 20 != 0) {
            return;
        }
        List<IModule> all = Services.modules().all();
        long mask = 0L;
        for (int i = 0; i < all.size() && i < 63; i++) {
            if (all.get(i).isEnabled()) {
                mask |= 1L << i;
            }
        }
        if (mask != lastMask) {
            lastMask = mask;
            rebuild(all, Minecraft.getInstance().font);
        }
    }

    void invalidate() {
        lastMask = -1L;
        ticks = 19; // rebuild on the next tick
    }

    private void rebuild(List<IModule> all, Font font) {
        int n = 0;
        IAutopilot ap = Services.autopilotOrNull();
        if (ap != null && ap.isOn()) {
            names[n] = "Autopilot";
            widths[n] = font.width(names[n]);
            colors[n] = AUTOPILOT_COLOR;
            n++;
        }
        int firstSorted = n;
        for (int i = 0; i < all.size() && n < MAX; i++) {
            IModule m = all.get(i);
            if (!m.isEnabled() || m.category() == ModuleCategory.CORE) {
                continue;
            }
            String name = m instanceof IConfigurable c ? c.displayName() : m.id();
            names[n] = name;
            widths[n] = font.width(name);
            colors[n] = colorOf(m.category());
            n++;
        }
        // longest first (insertion sort over a few dozen entries, only on change)
        for (int i = firstSorted + 1; i < n; i++) {
            String sn = names[i];
            int sw = widths[i];
            int sc = colors[i];
            int j = i - 1;
            while (j >= firstSorted && widths[j] < sw) {
                names[j + 1] = names[j];
                widths[j + 1] = widths[j];
                colors[j + 1] = colors[j];
                j--;
            }
            names[j + 1] = sn;
            widths[j + 1] = sw;
            colors[j + 1] = sc;
        }
        count = n;
    }

    static int colorOf(ModuleCategory c) {
        return switch (c) {
            case JOBS -> SuiteMenuScreen.Tab.JOBS.chipColor();
            case WORLD -> SuiteMenuScreen.Tab.WORLD.chipColor();
            case TRAVEL -> SuiteMenuScreen.Tab.TRAVEL.chipColor();
            case COMBAT -> SuiteMenuScreen.Tab.COMBAT.chipColor();
            case TOOLS -> SuiteMenuScreen.Tab.TOOLS.chipColor();
            case CORE -> 0xFFD1D5DB;
        };
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!enabled || count == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.getDebugOverlay().showDebugScreen()
                || Services.modState().isCleanScreen()) {
            return;
        }
        draw(g, mc.font, g.guiWidth(), 2 + hud.topUsed(SuiteHud.Position.TOP_RIGHT));
    }

    /** Draws the cached list at the top-right corner starting at {@code top} (also used by the UI preview). */
    void draw(GuiGraphics g, Font font, int sw, int top) {
        int y = top;
        for (int i = 0; i < count; i++) {
            int w = widths[i];
            int x = sw - w - 4;
            g.fill(x - 2, y, sw - 1, y + 10, 0x90000000);
            g.fill(sw - 1, y, sw, y + 10, colors[i]);
            g.drawString(font, names[i], x, y + 1, colors[i], true);
            y += 10;
        }
    }
}
