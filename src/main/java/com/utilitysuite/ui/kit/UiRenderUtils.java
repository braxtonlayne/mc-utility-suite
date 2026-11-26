package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Small drawing kit for the menu and HUD: rounded rectangles built from layered {@code fill} quads (one quad per
 * corner row plus the middle block, so translucent colors never overlap), 1 px hairline outlines that follow the same
 * corner curve, pixel chevrons, scissor helpers and text clipping. Corner insets are precomputed per radius, so
 * drawing allocates nothing.
 */
public final class UiRenderUtils {

    public static final int TL = 1;
    public static final int TR = 2;
    public static final int BL = 4;
    public static final int BR = 8;
    public static final int ALL = TL | TR | BL | BR;

    private static final int MAX_RADIUS = 12;
    private static final int[][] INSETS = new int[MAX_RADIUS + 1][];

    static {
        for (int r = 0; r <= MAX_RADIUS; r++) {
            INSETS[r] = new int[r];
            for (int i = 0; i < r; i++) {
                double dy = r - i - 0.5;
                INSETS[r][i] = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            }
        }
    }

    private UiRenderUtils() {
    }

    /** Horizontal inset of corner row {@code row} (0 = outermost) for a radius; pure, for tests. */
    public static int cornerInset(int radius, int row) {
        int r = Math.max(0, Math.min(MAX_RADIUS, radius));
        return row < 0 || row >= r ? 0 : INSETS[r][row];
    }

    private static int clampRadius(int r, int w, int h) {
        return Math.max(0, Math.min(MAX_RADIUS, Math.min(r, Math.min(w, h) / 2)));
    }

    /** Filled rectangle. The radius is accepted and ignored: every surface is square or chamfered now. */
    public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, color);
    }

    /** Filled rectangle; the corner mask and radius are accepted and ignored (square surfaces). */
    public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color, int corners) {
        roundedRect(g, x, y, w, h, radius, color);
    }

    /** 1 px hairline outline (radius ignored). */
    public static void roundedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 1 || h <= 1 || (color >>> 24) == 0) {
            return;
        }
        HoloFrames.edge(g, x, y, w, h, color);
    }

    /** Filled surface with a hairline border on top. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int fill, int border) {
        roundedRect(g, x, y, w, h, radius, fill);
        roundedOutline(g, x, y, w, h, radius, border);
    }

    /** No drop shadows in the new language: draws nothing. Kept so existing callers compile. */
    public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int layers) {
    }

    /** A bracket frame on glass (see {@link HoloFrames#frame}). */
    public static void bracketFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, FrameLevel level, boolean chamfer,
                                    float alpha) {
        HoloFrames.frame(g, x, y, w, h, level, chamfer, alpha);
    }

    /** A bracket frame with a chosen fill and bracket token. */
    public static void bracketFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, FrameLevel level, boolean chamfer,
                                    HoloToken fill, HoloToken bracket, float alpha) {
        HoloFrames.frame(g, x, y, w, h, level, chamfer, fill, bracket, alpha);
    }

    public static void hLine(GuiGraphicsExtractor g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    public static void vLine(GuiGraphicsExtractor g, int x, int y0, int y1, int color) {
        g.fill(x, y0, x + 1, y1, color);
    }

    /** A filled circle of diameter {@code d}. */
    public static void dot(GuiGraphicsExtractor g, int x, int y, int d, int color) {
        roundedRect(g, x, y, d, d, 0, color);
    }

    /** Pixel chevron, 5 px tall: direction 0 = right, 1 = down, 2 = left, 3 = up. */
    public static void chevron(GuiGraphicsExtractor g, int x, int y, int direction, int color) {
        switch (direction) {
            case 0 -> {
                g.fill(x, y, x + 1, y + 5, color);
                g.fill(x + 1, y + 1, x + 2, y + 4, color);
                g.fill(x + 2, y + 2, x + 3, y + 3, color);
            }
            case 1 -> {
                g.fill(x, y + 1, x + 5, y + 2, color);
                g.fill(x + 1, y + 2, x + 4, y + 3, color);
                g.fill(x + 2, y + 3, x + 3, y + 4, color);
            }
            case 2 -> {
                g.fill(x + 2, y, x + 3, y + 5, color);
                g.fill(x + 1, y + 1, x + 2, y + 4, color);
                g.fill(x, y + 2, x + 1, y + 3, color);
            }
            default -> {
                g.fill(x + 2, y + 1, x + 3, y + 2, color);
                g.fill(x + 1, y + 2, x + 4, y + 3, color);
                g.fill(x, y + 3, x + 5, y + 4, color);
            }
        }
    }

    /** Scissor to a rectangle in GUI coordinates (pair with {@link #endClip}). */
    public static void clip(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        scissor(g, x, y, x + w, y + h);
    }

    /**
     * Scissor to a rectangle given in the caller's layout px. In 26.3 the scissor is transformed by the current pose
     * ({@code GuiGraphicsExtractor.enableScissor}), so inside the menu's effective-scale pass the layout rectangle is
     * passed as is (the 1.21.1 build converted it with {@link MenuScale#scissorMin}/{@link MenuScale#scissorMax}).
     */
    public static void scissor(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.enableScissor(x0, y0, x1, y1);
    }

    public static void endClip(GuiGraphicsExtractor g) {
        g.disableScissor();
    }

    // One reused styled run for menu text (client thread only); the suite UI face when it is active, else vanilla.
    private static final com.utilitysuite.ui.holo.SuiteFonts.Client.Run RUN =
            new com.utilitysuite.ui.holo.SuiteFonts.Client.Run();

    private static net.minecraft.network.chat.Style uiStyle() {
        return com.utilitysuite.ui.holo.SuiteFonts.Client.style(com.utilitysuite.core.api.visual.TextRole.UI);
    }

    /** Text without shadow (the dashboard never uses the vanilla drop shadow), in the suite UI face when active. */
    public static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        net.minecraft.network.chat.Style st = uiStyle();
        if (st == null) {
            g.text(font, s, x, y, color, false);
        } else {
            g.text(font, RUN.set(s, st), x, y, color, false);
        }
    }

    /** Width of {@code s} as {@link #text} draws it. Menu layout must measure with this, not {@code font.width}. */
    public static int width(Font font, String s) {
        net.minecraft.network.chat.Style st = uiStyle();
        return st == null ? font.width(s) : font.width(RUN.set(s, st));
    }

    /** An item icon scaled to {@code size} px (16 = vanilla). */
    public static void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (size == 16) {
            g.item(stack, x, y);
            return;
        }
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        float s = size / 16f;
        pose.scale(s, s);
        g.item(stack, 0, 0);
        pose.popMatrix();
    }

    /** Text clipped to {@code maxWidth} with an ellipsis. Allocates: call on change, not per frame. */
    /**
     * The first of {@code options} (longest phrasing first) that fits {@code maxWidth}; the last one ellipsized when
     * none does. Lets a label say less instead of being cut mid-word.
     */
    public static String firstThatFits(Font font, int maxWidth, String... options) {
        for (String o : options) {
            if (width(font, o) <= maxWidth) {
                return o;
            }
        }
        return ellipsize(font, options[options.length - 1], maxWidth);
    }

    public static String ellipsize(Font font, String s, int maxWidth) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (width(font, s) <= maxWidth) {
            return s;
        }
        int dots = width(font, "…");
        if (maxWidth <= dots) {
            return "";
        }
        net.minecraft.network.chat.Style st = uiStyle();
        String head = st == null ? font.plainSubstrByWidth(s, maxWidth - dots)
                : font.substrByWidth(net.minecraft.network.chat.FormattedText.of(s, st), maxWidth - dots).getString();
        return head.stripTrailing() + "…";
    }

    /** Ease-out cubic, {@code t} in 0..1. */
    public static float easeOut(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        float inv = 1f - c;
        return 1f - inv * inv * inv;
    }
}
