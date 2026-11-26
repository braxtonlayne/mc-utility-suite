package com.utilitysuite.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A plain square surface at the quiet level: fill and an optional hairline (inset blocks, tracks, separators); purely
 * decorative. The radius and corner mask of the old rounded kit are accepted and ignored.
 */
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
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        UiRenderUtils.roundedRect(g, sx, sy, w, h, radius, fill, corners);
        if (border != 0) {
            UiRenderUtils.roundedOutline(g, sx, sy, w, h, radius, border);
        }
    }
}
