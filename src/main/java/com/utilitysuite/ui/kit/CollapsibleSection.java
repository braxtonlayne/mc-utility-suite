package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Header row of a collapsible block inside a card ("Advanced settings · 4"): chevron, title and a count chip, with a
 * hover highlight. Clicking flips the open state through {@code onToggle}; the panel then lays the block out again.
 * When open, the panel draws the block's rows on an inset surface that starts under this header.
 */
public final class CollapsibleSection extends UiNode {

    public static final int H = 20;

    private final String title;
    private final String count;
    private final int titleW;
    private final boolean open;
    private final Runnable onToggle;

    public CollapsibleSection(Font font, String title, int count, boolean open, int w, Runnable onToggle) {
        super(w, H);
        this.title = UiRenderUtils.ellipsize(font, title, w - 60);
        this.titleW = font.width(this.title);
        this.count = count > 0 ? Integer.toString(count) : "";
        this.open = open;
        this.onToggle = onToggle;
    }

    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        if (hover || open) {
            UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, open && !hover ? UiTheme.INSET
                    : UiTheme.ROW_HOVER, open ? UiRenderUtils.TL | UiRenderUtils.TR : UiRenderUtils.ALL);
        }
        int c = hover ? UiTheme.TEXT : UiTheme.TEXT_DIM;
        UiRenderUtils.chevron(g, open ? sx + 7 : sx + 8, sy + (h - 5) / 2, open ? 1 : 0, open ? UiTheme.ACCENT : c);
        UiRenderUtils.text(g, font, title, sx + 18, sy + (h - 8) / 2, c);
        if (!count.isEmpty()) {
            int cw = font.width(count) + 8;
            int cx = sx + 18 + titleW + 6;
            UiRenderUtils.roundedRect(g, cx, sy + (h - 11) / 2, cw, 11, 5, UiTheme.CONTROL);
            UiRenderUtils.text(g, font, count, cx + 4, sy + (h - 11) / 2 + 2, UiTheme.TEXT_DIM);
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (button != 0) {
            return false;
        }
        onToggle.run();
        return true;
    }
}
