package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Small status chip: text in a hairline frame with a small square marker ("Armed", "Running"; lit = the given colour,
 * normally the accent), or a key cap (hairline frame, no marker) for hotkey names. Layout size is unchanged.
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
            g.fill(sx, sy, sx + w, sy + h, HoloTheme.argb(HoloToken.FIELD));
            HoloFrames.edge(g, sx, sy, w, h, HoloTheme.argb(HoloToken.LINE));
            UiRenderUtils.text(g, font, text, sx + 6, sy + 3, color);
            return;
        }
        // lit chips carry their colour on text, frame and marker; the frame is the faint version of it
        g.fill(sx, sy, sx + w, sy + h, HoloTheme.fade(color, 0.08f));
        HoloFrames.edge(g, sx, sy, w, h, HoloTheme.fade(color, 0.45f));
        g.fill(sx + 5, sy + 5, sx + 9, sy + 9, color);
        UiRenderUtils.text(g, font, text, sx + 15, sy + 3, color);
    }
}
