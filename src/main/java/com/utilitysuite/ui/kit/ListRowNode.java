package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * Background of one list row (queued jobs, waypoints, allies, rules, files): hover highlight, an accent bar when
 * selected, a hairline separator under it, and optionally a label and a click action of its own (selectable rows).
 * Row controls are separate nodes drawn on top.
 */
public final class ListRowNode extends UiNode {

    private final Consumer<ListRowNode> onPress;
    private String label = "";
    private int labelColor = UiTheme.TEXT_SECONDARY;
    private boolean selected;
    private boolean separator = true;

    public ListRowNode(int w, int h, Consumer<ListRowNode> onPress) {
        super(w, h);
        this.onPress = onPress;
    }

    public ListRowNode label(Font font, String text, int maxW, int color) {
        label = UiRenderUtils.ellipsize(font, text, maxW);
        labelColor = color;
        return this;
    }

    public ListRowNode selected(boolean v) {
        selected = v;
        return this;
    }

    public ListRowNode separator(boolean v) {
        separator = v;
        return this;
    }

    @Override
    public boolean interactive() {
        return onPress != null;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        if (selected) {
            UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, hover ? 0x3038BDF8 : 0x1E38BDF8);
            UiRenderUtils.roundedRect(g, sx, sy + 3, 2, h - 6, 1, UiTheme.ACCENT);
        } else if (hover) {
            UiRenderUtils.roundedRect(g, sx, sy, w, h, UiTheme.RADIUS_SMALL, UiTheme.ROW_HOVER);
        } else if (separator) {
            UiRenderUtils.hLine(g, sx + 6, sx + w - 6, sy + h, 0xFF20242C);
        }
        if (!label.isEmpty()) {
            UiRenderUtils.text(g, font, label, sx + 8, sy + (h - 8) / 2,
                    selected ? UiTheme.ACCENT : hover && onPress != null ? UiTheme.TEXT : labelColor);
        }
    }

    @Override
    public boolean onClick(double mouseX, double mouseY, int button, int sx, int sy) {
        if (onPress == null || button != 0) {
            return false;
        }
        onPress.accept(this);
        return true;
    }
}
