package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Small status chip: a tinted pill with a colored dot and label ("Armed", "Running"), or a key cap (bordered, no dot)
 * for hotkey names.
 */
public final class ChipNode extends UiNode {

    public static final int H = 14;

    private final String text;
    private final int color;
    private final boolean keycap;

    public ChipNode(Font font, String text, int color) {
        this(font, text, color, false);
    }

    private ChipNode(Font font, String text, int color, boolean keycap) {
        super(font.width(text) + (keycap ? 12 : 22), H);
        this.text = text;
        this.color = color;
        this.keycap = keycap;
    }

    public static ChipNode keycap(Font font, String text) {
        return new ChipNode(font, text, UiTheme.TEXT_SECONDARY, true);
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        if (keycap) {
            UiRenderUtils.panel(g, sx, sy, w, h, 3, UiTheme.FIELD, UiTheme.CONTROL_BORDER);
            UiRenderUtils.hLine(g, sx + 2, sx + w - 2, sy + h - 2, 0xFF1A1D24);
            UiRenderUtils.text(g, font, text, sx + 6, sy + 3, color);
            return;
        }
        UiRenderUtils.roundedRect(g, sx, sy, w, h, h / 2, UiTheme.fade(color, 0.16f));
        UiRenderUtils.dot(g, sx + 5, sy + 4, 6, color);
        UiRenderUtils.text(g, font, text, sx + 15, sy + 3, color);
    }
}
