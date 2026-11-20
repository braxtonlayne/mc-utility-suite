package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A plain rounded surface (inset blocks, progress tracks, chips' backgrounds); purely decorative. */
public final class BoxNode extends UiNode {

    private final int fill;
    private final int border;
    private final int radius;
    private final int corners;

    public BoxNode(int w, int h, int radius, int fill, int border, int corners) {
        super(w, h);
        this.fill = fill;
        this.border = border;
        this.radius = radius;
        this.corners = corners;
    }

    @Override
    public void render(GuiGraphics g, Font font, int sx, int sy, int mouseX, int mouseY) {
        UiRenderUtils.roundedRect(g, sx, sy, w, h, radius, fill, corners);
        if (border != 0) {
            UiRenderUtils.roundedOutline(g, sx, sy, w, h, radius, border);
        }
    }
}
