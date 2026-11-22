package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The bracket frame, the single surface shape of the suite, in plain axis-aligned fills (batched by the game into the
 * GUI buffer): translucent glass, a hairline edge, L-shaped corner brackets, an optional chamfer on the top-left and
 * bottom-right corners and a three-layer outer glow for the one focused surface. Menu screens, widgets and HUD plates
 * all draw their surfaces through this class so they stay one style. Integer pixels, no pose changes, allocation
 * free. {@code alpha} is 0..1 and multiplies every colour.
 */
public final class HoloFrames {

    private HoloFrames() {
    }

    public static void plate(GuiGraphics g, int x, int y, int w, int h, HoloToken fill, float alpha) {
        g.fill(x, y, x + w, y + h, HoloTheme.argb(fill, alpha));
    }

    public static void hLine(GuiGraphics g, int x0, int x1, int y, int argb) {
        g.fill(x0, y, x1, y + 1, argb);
    }

    public static void vLine(GuiGraphics g, int x, int y0, int y1, int argb) {
        g.fill(x, y0, x + 1, y1, argb);
    }

    /** A 1 px rectangular outline. */
    public static void edge(GuiGraphics g, int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + 1, argb);
        g.fill(x, y + h - 1, x + w, y + h, argb);
        g.fill(x, y + 1, x + 1, y + h - 1, argb);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    /** Four L-shaped corner brackets sitting on the edge of the rectangle. */
    public static void brackets(GuiGraphics g, int x, int y, int w, int h, int arm, int argb) {
        int x1 = x + w;
        int y1 = y + h;
        g.fill(x, y, x + arm, y + 1, argb);
        g.fill(x, y + 1, x + 1, y + arm, argb);
        g.fill(x1 - arm, y, x1, y + 1, argb);
        g.fill(x1 - 1, y + 1, x1, y + arm, argb);
        g.fill(x, y1 - 1, x + arm, y1, argb);
        g.fill(x, y1 - arm, x + 1, y1 - 1, argb);
        g.fill(x1 - arm, y1 - 1, x1, y1, argb);
        g.fill(x1 - 1, y1 - arm, x1, y1 - 1, argb);
    }

    /** A bracket frame on {@link HoloToken#GLASS}. */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, FrameLevel level, boolean chamfer,
                             float alpha) {
        frame(g, x, y, w, h, level, chamfer, HoloToken.GLASS, HoloToken.LINE_STRONG, alpha);
    }

    /**
     * A bracket frame with a chosen fill and bracket colour (a danger notice uses the danger token for its brackets).
     * QUIET draws fill and faint edge; FRAMED adds dim brackets; HERO a stronger edge, bright brackets and the glow.
     */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, FrameLevel level, boolean chamfer,
                             HoloToken fill, HoloToken bracket, float alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0f) {
            return;
        }
        boolean hero = level == FrameLevel.HERO;
        int fillArgb = HoloTheme.argb(fill, alpha);
        int edgeArgb = HoloTheme.argb(hero ? HoloToken.LINE : HoloToken.LINE_FAINT, alpha);
        int c = chamfer && w > 4 * HoloTheme.CHAMFER && h > 4 * HoloTheme.CHAMFER ? HoloTheme.CHAMFER : 0;
        if (hero && HoloTheme.effects != EffectLevel.OFF) {
            int rgb = HoloTheme.argb(HoloToken.ACCENT) & 0xFFFFFF;
            edge(g, x - 1, y - 1, w + 2, h + 2, ((int) (26 * alpha) << 24) | rgb);
            edge(g, x - 2, y - 2, w + 4, h + 4, ((int) (15 * alpha) << 24) | rgb);
            edge(g, x - 3, y - 3, w + 6, h + 6, ((int) (8 * alpha) << 24) | rgb);
        }
        if (c == 0) {
            g.fill(x, y, x + w, y + h, fillArgb);
            edge(g, x, y, w, h, edgeArgb);
        } else {
            // stair-step chamfer: row r of the top is inset c - r on the left, row r of the bottom on the right
            for (int r = 0; r < c; r++) {
                g.fill(x + c - r, y + r, x + w, y + r + 1, fillArgb);
                g.fill(x, y + h - c + r, x + w - r - 1, y + h - c + r + 1, fillArgb);
            }
            g.fill(x, y + c, x + w, y + h - c, fillArgb);
            g.fill(x + c, y, x + w, y + 1, edgeArgb);
            g.fill(x, y + h - 1, x + w - c, y + h, edgeArgb);
            g.fill(x, y + c, x + 1, y + h - 1, edgeArgb);
            g.fill(x + w - 1, y + 1, x + w, y + h - c, edgeArgb);
        }
        if (level == FrameLevel.QUIET) {
            return;
        }
        int b = HoloTheme.argb(hero ? bracket : HoloToken.LINE, alpha);
        int arm = HoloTheme.bracketArm(w, h);
        if (c == 0) {
            brackets(g, x, y, w, h, arm, b);
            return;
        }
        int x1 = x + w;
        int y1 = y + h;
        // square corners keep their brackets; the two cut corners get the diagonal in the bracket colour
        g.fill(x1 - arm, y, x1, y + 1, b);
        g.fill(x1 - 1, y + 1, x1, y + arm, b);
        g.fill(x, y1 - 1, x + arm, y1, b);
        g.fill(x, y1 - arm, x + 1, y1 - 1, b);
        for (int r = 0; r < c; r++) {
            g.fill(x + c - r - 1, y + r, x + c - r, y + r + 1, b);
            g.fill(x1 - r - 1, y1 - c + r, x1 - r, y1 - c + r + 1, b);
        }
        g.fill(x + c, y, x + c + arm, y + 1, b);
        g.fill(x, y + c, x + 1, y + c + arm, b);
        g.fill(x1 - c - arm, y1 - 1, x1 - c, y1, b);
        g.fill(x1 - 1, y1 - c - arm, x1, y1 - c, b);
    }

    /** Scanlines inside a hero surface: 1 px every 3 px at 4 % accent. Only at {@link EffectLevel#FULL}; menu only. */
    public static void scanlines(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        if (HoloTheme.effects != EffectLevel.FULL) {
            return;
        }
        int argb = ((int) (10 * alpha) << 24) | (HoloTheme.argb(HoloToken.ACCENT) & 0xFFFFFF);
        for (int yy = y + 1; yy < y + h - 1; yy += 3) {
            g.fill(x + 1, yy, x + w - 1, yy + 1, argb);
        }
    }

    /** A selection or category bar {@link HoloTheme#BAR} px wide on the left edge of a row. */
    public static void sideBar(GuiGraphics g, int x, int y, int h, HoloToken token, float alpha) {
        g.fill(x, y, x + HoloTheme.BAR, y + h, HoloTheme.argb(token, alpha));
    }

    /** A horizontal progress bar: track and fill, {@code fraction} 0..1. */
    public static void progress(GuiGraphics g, int x, int y, int w, int h, float fraction, HoloToken fill,
                                float alpha) {
        g.fill(x, y, x + w, y + h, HoloTheme.argb(HoloToken.LINE_FAINT, alpha));
        int fw = fraction <= 0f ? 0 : fraction >= 1f ? w : (int) (w * fraction);
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, HoloTheme.argb(fill, alpha));
        }
    }
}
