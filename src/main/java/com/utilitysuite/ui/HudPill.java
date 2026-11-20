package com.utilitysuite.ui;

import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.ui.kit.GoalHeroCard;
import com.utilitysuite.ui.kit.ItemIcons;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The floating HUD job pill (about 240x24): status dot, target item icon, {@code X/Y} progress and the current step,
 * ellipsized to fit. Layout (strings, widths, icon) is recomputed only when the {@link HudStatusLine} was rebuilt or
 * the screen width changed; {@link #render} only draws cached values. Also draws the small danger and Clean-Screen
 * badges.
 */
public final class HudPill {

    public static final int H = 24;
    public static final int MAX_W = 240;
    public static final int MIN_W = 120;
    private static final int PAD_L = 8;
    private static final int PAD_R = 10;

    private int seenRebuilds = -1;
    private int seenMaxW = -1;
    private int width;
    private String progress = "";
    private int progressW;
    private String step = "";
    private ItemStack icon = ItemStack.EMPTY;
    private int dotColor = UiTheme.ACCENT;

    /** Current pill width (after {@link #layout}). */
    public int width() {
        return width;
    }

    /** Recomputes the cached layout when the line or the available width changed. */
    public void layout(Font font, HudStatusLine line, int maxW) {
        if (line.rebuilds() == seenRebuilds && maxW == seenMaxW) {
            return;
        }
        seenRebuilds = line.rebuilds();
        seenMaxW = maxW;
        progress = line.progressText();
        progressW = progress.isEmpty() ? 0 : font.width(progress);
        if (line.isSchematic()) {
            icon = ItemIcons.stack(Items.SCAFFOLDING);
            dotColor = UiTheme.RUNNING;
        } else {
            icon = ItemIcons.forId(line.itemId(), GoalHeroCard.fallbackIcon(line.goalLabel()));
            dotColor = statusColor(line.status());
        }
        int fixed = PAD_L + 6 + 6 + 16 + 6 + PAD_R + (progressW > 0 ? progressW + 8 : 0);
        String full = line.detailText();
        int limit = Math.max(MIN_W, Math.min(MAX_W, maxW));
        width = Math.max(MIN_W, Math.min(limit, fixed + font.width(full)));
        step = UiRenderUtils.ellipsize(font, full, width - fixed);
    }

    static int statusColor(JobStatus s) {
        if (s == null) {
            return UiTheme.ACCENT;
        }
        return switch (s) {
            case RUNNING, COMPLETED -> UiTheme.RUNNING;
            case FAILED, CANCELLED -> UiTheme.DANGER;
            default -> UiTheme.WARNING;
        };
    }

    /** Draws the pill at {@code (x, y)}; call {@link #layout} first. */
    public void render(GuiGraphics g, Font font, int x, int y) {
        UiRenderUtils.roundedRect(g, x, y, width, H, H / 2, UiTheme.HUD_PILL);
        UiRenderUtils.roundedOutline(g, x, y, width, H, H / 2, 0xCC2A2F3B);
        int cx = x + PAD_L;
        UiRenderUtils.dot(g, cx, y + (H - 6) / 2, 6, dotColor);
        cx += 6 + 6;
        UiRenderUtils.item(g, icon, cx, y + (H - 16) / 2, 16);
        cx += 16 + 6;
        int ty = y + (H - 8) / 2;
        if (progressW > 0) {
            UiRenderUtils.text(g, font, progress, cx, ty, UiTheme.TEXT);
            cx += progressW + 8;
        }
        UiRenderUtils.text(g, font, step, cx, ty, UiTheme.TEXT_SECONDARY);
    }

    /** Tiny pill badge ("Automation stopped" in danger colors, Clean-Screen muted); returns its height. */
    public static int badge(GuiGraphics g, Font font, String text, int textW, int x, int y, boolean danger) {
        int w = textW + 22;
        int h = 14;
        UiRenderUtils.roundedRect(g, x, y, w, h, h / 2, danger ? 0xCC2A1013 : UiTheme.HUD_PILL);
        UiRenderUtils.roundedOutline(g, x, y, w, h, h / 2, danger ? UiTheme.DANGER_BORDER : 0xCC2A2F3B);
        UiRenderUtils.dot(g, x + 6, y + 4, 6, danger ? UiTheme.DANGER : UiTheme.TEXT_MUTED);
        UiRenderUtils.text(g, font, text, x + 16, y + 3, danger ? UiTheme.DANGER_TEXT : UiTheme.TEXT_DIM);
        return h;
    }

    public static int badgeWidth(int textW) {
        return textW + 22;
    }
}
