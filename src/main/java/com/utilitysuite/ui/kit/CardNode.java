package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A content card: rounded surface with a hairline border that lightens on hover, an optional header row (title in
 * primary text, a short muted description after it) and a divider between header and body. Header controls such as
 * the enable switch are separate nodes placed on top by the panel. Its height is set when the panel closes the card.
 */
public final class CardNode extends UiNode {

    public static final int HEADER_H = 30;

    public enum Tone { NORMAL, HERO, DANGER }

    private final String title;
    private final String subtitle;
    private final int titleW;
    private final Tone tone;
    private String shownSubtitle = "";
    private int dividerY = -1;

    public CardNode(Font font, String title, String subtitle, int w, int reserveRight, Tone tone) {
        super(w, HEADER_H);
        this.tone = tone;
        int room = w - 2 * UiTheme.CARD_PAD - reserveRight;
        this.title = UiRenderUtils.ellipsize(font, title == null ? "" : title, room);
        this.titleW = UiRenderUtils.width(font, this.title);
        if (subtitle != null && !subtitle.isEmpty()) {
            int left = room - titleW - 8;
            // a subtitle is shown whole or not at all: a cut-off description reads worse than none
            this.subtitle = UiRenderUtils.width(font, subtitle) <= left ? subtitle : "";
        } else {
            this.subtitle = "";
        }
        shownSubtitle = this.subtitle;
    }

    public boolean hasHeader() {
        return !title.isEmpty();
    }

    /** Draws the header divider at this offset from the card top. */
    public void divider(int offset) {
        dividerY = offset;
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int sx, int sy, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, sx, sy, w, h);
        float hv = hoverAmount(hover);
        HoloFrames.frame(g, sx, sy, w, h, FrameLevel.FRAMED, false, HoloToken.GLASS,
                tone == Tone.DANGER ? HoloToken.DANGER : HoloToken.LINE, 1f);
        if (hv > 0.01f) {
            // hover: the faint edge rises to LINE and the fill gains about 6 %
            HoloFrames.edge(g, sx, sy, w, h, HoloTheme.fade(HoloTheme.argb(HoloToken.LINE), hv));
            g.fill(sx + 1, sy + 1, sx + w - 1, sy + h - 1, HoloTheme.fade(HoloTheme.argb(HoloToken.ACCENT), 0.06f * hv));
        }
        if (tone == Tone.HERO) {
            HoloFrames.brackets(g, sx, sy, w, h, HoloTheme.bracketArm(w, h), HoloTheme.argb(HoloToken.LINE_STRONG));
        } else if (tone == Tone.DANGER) {
            HoloFrames.edge(g, sx, sy, w, h, HoloTheme.argb(HoloToken.DANGER, 0.5f));
            HoloFrames.brackets(g, sx, sy, w, h, HoloTheme.bracketArm(w, h), HoloTheme.argb(HoloToken.DANGER));
        }
        if (!title.isEmpty()) {
            int ty = sy + (HEADER_H - 8) / 2;
            UiRenderUtils.text(g, font, title, sx + UiTheme.CARD_PAD, ty, UiTheme.TEXT);
            if (!shownSubtitle.isEmpty()) {
                UiRenderUtils.text(g, font, shownSubtitle, sx + UiTheme.CARD_PAD + titleW + 8, ty, UiTheme.TEXT_MUTED);
            }
        }
        if (dividerY > 0) {
            UiRenderUtils.hLine(g, sx + 1, sx + w - 1, sy + dividerY, HoloTheme.argb(HoloToken.LINE_FAINT));
        }
    }
}
