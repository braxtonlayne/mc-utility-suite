package com.utilitysuite.ui.kit;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
        this.titleW = UiRenderUtils.width(font, this.title);
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
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        if (open) {
            g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.FIELD));
        }
        if (hv > 0.01f) {
            g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.GLASS_RAISED, hv));
        }
        if (open) {
            g.fill(sx, sy + h - 1, sx + w, sy + h, HoloTheme.argb(HoloToken.LINE_FAINT));
        }
        int c = HoloTheme.mix(UiTheme.TEXT_DIM, UiTheme.TEXT, hv);
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        UiRenderUtils.chevron(g, open ? sx + 7 : sx + 8, sy + (h - 5) / 2, open ? 1 : 0, open ? accent : c);
        UiRenderUtils.text(g, font, title, sx + 18, sy + (h - 8) / 2, c);
        if (!count.isEmpty()) {
            int cw = UiRenderUtils.width(font, count) + 8;
            int cx = sx + 18 + titleW + 6;
            HoloFrames.edge(g, cx, sy + (h - 11) / 2, cw, 11, HoloTheme.argb(HoloToken.LINE_FAINT));
            UiRenderUtils.text(g, font, count, cx + 4, sy + (h - 11) / 2 + 2, UiTheme.TEXT_DIM);
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        onToggle.run();
        return true;
    }
}
