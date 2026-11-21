package com.utilitysuite.ui.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

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

    public static void roundedRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        roundedRect(g, x, y, w, h, radius, color, ALL);
    }

    /** Filled rounded rectangle; {@code corners} is a mask of {@link #TL}, {@link #TR}, {@link #BL}, {@link #BR}. */
    public static void roundedRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int corners) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = clampRadius(radius, w, h);
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        int[] ins = INSETS[r];
        int top = corners & (TL | TR);
        int bottom = corners & (BL | BR);
        int midTop = top != 0 ? y + r : y;
        int midBottom = bottom != 0 ? y + h - r : y + h;
        if (midBottom > midTop) {
            g.fill(x, midTop, x + w, midBottom, color);
        }
        if (top != 0) {
            for (int i = 0; i < r; i++) {
                int l = (corners & TL) != 0 ? ins[i] : 0;
                int rr = (corners & TR) != 0 ? ins[i] : 0;
                g.fill(x + l, y + i, x + w - rr, y + i + 1, color);
            }
        }
        if (bottom != 0) {
            for (int i = 0; i < r; i++) {
                int l = (corners & BL) != 0 ? ins[i] : 0;
                int rr = (corners & BR) != 0 ? ins[i] : 0;
                g.fill(x + l, y + h - 1 - i, x + w - rr, y + h - i, color);
            }
        }
    }

    /** 1 px outline following the rounded corners (all four rounded). */
    public static void roundedOutline(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 1 || h <= 1 || (color >>> 24) == 0) {
            return;
        }
        int r = clampRadius(radius, w, h);
        int[] ins = INSETS[r];
        int i0 = r > 0 ? ins[0] : 0;
        g.fill(x + i0, y, x + w - i0, y + 1, color);
        g.fill(x + i0, y + h - 1, x + w - i0, y + h, color);
        int vTop = Math.max(1, r);
        int vBottom = h - Math.max(1, r);
        if (vBottom > vTop) {
            g.fill(x, y + vTop, x + 1, y + vBottom, color);
            g.fill(x + w - 1, y + vTop, x + w, y + vBottom, color);
        }
        for (int i = 1; i < r; i++) {
            int a = ins[i];
            int b = Math.max(a + 1, ins[i - 1]);
            g.fill(x + a, y + i, x + b, y + i + 1, color);
            g.fill(x + w - b, y + i, x + w - a, y + i + 1, color);
            g.fill(x + a, y + h - 1 - i, x + b, y + h - i, color);
            g.fill(x + w - b, y + h - 1 - i, x + w - a, y + h - i, color);
        }
    }

    /** Filled rounded rectangle with a hairline border on top. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int radius, int fill, int border) {
        roundedRect(g, x, y, w, h, radius, fill);
        roundedOutline(g, x, y, w, h, radius, border);
    }

    /** Soft drop shadow: a few expanding, very transparent rounded layers. */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h, int radius, int layers) {
        for (int i = layers; i >= 1; i--) {
            roundedRect(g, x - i, y - i + 2, w + 2 * i, h + 2 * i, radius + i, 0x12000000);
        }
    }

    public static void hLine(GuiGraphics g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    public static void vLine(GuiGraphics g, int x, int y0, int y1, int color) {
        g.fill(x, y0, x + 1, y1, color);
    }

    /** A filled circle of diameter {@code d}. */
    public static void dot(GuiGraphics g, int x, int y, int d, int color) {
        roundedRect(g, x, y, d, d, d / 2, color);
    }

    /** Pixel chevron, 5 px tall: direction 0 = right, 1 = down, 2 = left, 3 = up. */
    public static void chevron(GuiGraphics g, int x, int y, int direction, int color) {
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
    public static void clip(GuiGraphics g, int x, int y, int w, int h) {
        g.enableScissor(x, y, x + w, y + h);
    }

    public static void endClip(GuiGraphics g) {
        g.disableScissor();
    }

    /** Text without shadow (the dashboard never uses the vanilla drop shadow). */
    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, false);
    }

    /** An item icon scaled to {@code size} px (16 = vanilla). */
    public static void item(GuiGraphics g, ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (size == 16) {
            g.renderItem(stack, x, y);
            return;
        }
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        float s = size / 16f;
        pose.scale(s, s, 1f);
        g.renderItem(stack, 0, 0);
        pose.popPose();
    }

    /** Text clipped to {@code maxWidth} with an ellipsis. Allocates: call on change, not per frame. */
    /**
     * The first of {@code options} (longest phrasing first) that fits {@code maxWidth}; the last one ellipsized when
     * none does. Lets a label say less instead of being cut mid-word.
     */
    public static String firstThatFits(Font font, int maxWidth, String... options) {
        for (String o : options) {
            if (font.width(o) <= maxWidth) {
                return o;
            }
        }
        return ellipsize(font, options[options.length - 1], maxWidth);
    }

    public static String ellipsize(Font font, String s, int maxWidth) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int dots = font.width("…");
        if (maxWidth <= dots) {
            return "";
        }
        return font.plainSubstrByWidth(s, maxWidth - dots).stripTrailing() + "…";
    }

    /** Ease-out cubic, {@code t} in 0..1. */
    public static float easeOut(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        float inv = 1f - c;
        return 1f - inv * inv * inv;
    }
}
