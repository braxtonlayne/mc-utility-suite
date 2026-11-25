package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.Glyph;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.TextRole;

/**
 * Draws the one holo-card of the {@link HoloCardBook} beside its target, anchored through the
 * {@link ScreenProjector}'s auxiliary projection: a framed card to the right of the target's screen box (to the left
 * when it would leave the screen), title, subtitle, up to {@link HoloCardBook#ROWS} icon rows (cost, arrow, result
 * for offers) and, for furnaces and brewing stands, a progress ring. Fades in and out over {@link #FADE_SECONDS};
 * keeps the last content while fading out. Slot {@code FREE}; render thread; allocation free.
 */
public final class HoloCardLayer implements HudElement {

    public static final String ID = "holo_card";
    public static final float FADE_SECONDS = 0.15f;
    static final float PAD = 4f;
    static final float ROW_H = 18f;
    static final float RING_R = 9f;

    private static final long FADE_KEY = AnimStore.key("holo_card.fade", 0);

    private final HoloCardBook book;
    private final ScreenProjector projector;
    private final AnimStore anim;
    private float visibility;

    public HoloCardLayer(HoloCardBook book, ScreenProjector projector) {
        this(book, projector, AnimStore.SHARED);
    }

    HoloCardLayer(HoloCardBook book, ScreenProjector projector, AnimStore anim) {
        this.book = book;
        this.projector = projector;
        this.anim = anim;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return HudSlot.FREE;
    }

    @Override
    public boolean wantsDraw() {
        if (book.card.kind == HoloCardBook.Kind.NONE) {
            return false;
        }
        visibility = anim.progress(FADE_KEY, book.active, FADE_SECONDS);
        if (visibility <= 0f && !book.active) {
            projector.clearAnchor();
            return false;
        }
        return projector.auxState() == ScreenProjector.ON_SCREEN;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        float a = alpha * AnimStore.easeOut(visibility);
        if (a <= 0f) {
            return;
        }
        HoloCardBook.Card card = book.card;
        float sw = c.screenWidth();
        float sh = c.screenHeight();
        float bx0 = x + projector.auxValue(ScreenProjector.MIN_X) * sw;
        float by0 = y + projector.auxValue(ScreenProjector.MIN_Y) * sh;
        float bx1 = x + projector.auxValue(ScreenProjector.MAX_X) * sw;

        float lh = c.lineHeight(TextRole.UI);
        float mh = c.lineHeight(TextRole.MICRO);
        float w = Math.max(c.width(TextRole.UI, card.title), c.width(TextRole.MICRO, card.subtitle));
        boolean offer = card.kind == HoloCardBook.Kind.VILLAGER;
        float rowW = offer ? 16f + 22f + Glyph.SIZE + 4f + 16f + 22f : 16f + 30f;
        if (card.rows > 0) {
            w = Math.max(w, rowW);
        }
        boolean ring = card.progress >= 0f;
        if (ring) {
            w += RING_R * 2f + PAD;
        }
        float cw = w + PAD * 2f;
        float ch = PAD * 2f + lh + mh + 2f + card.rows * ROW_H;
        if (ring) {
            ch = Math.max(ch, PAD * 2f + RING_R * 2f + 2f);
        }
        float cx = bx1 + 10f;
        boolean left = cx + cw > x + sw - 2f;
        if (left) {
            cx = bx0 - 10f - cw;
        }
        float cy = clamp(by0, y + 2f, y + sh - ch - 2f);
        float slide = HoloTheme.motion == Motion.FULL ? (1f - AnimStore.easeOut(visibility)) * 6f : 0f;
        cx += left ? -slide : slide;

        c.ribbon(left ? bx0 - 1f : bx1 + 1f, by0 + 2f, left ? cx + cw : cx, cy + lh * 0.5f + PAD, 1f,
                HoloToken.LINE_STRONG, a);
        c.frame(cx, cy, cw, ch, FrameLevel.FRAMED, true, a);
        float px = cx + PAD;
        float py = cy + PAD;
        c.text(TextRole.UI, card.title, px, py, HoloToken.TEXT, a);
        py += lh + 1f;
        if (!card.subtitle.isEmpty()) {
            c.text(TextRole.MICRO, card.subtitle, px, py, card.estimate ? HoloToken.WARN : HoloToken.TEXT_SECONDARY,
                    a);
        }
        py += mh + 1f;
        for (int i = 0; i < card.rows; i++) {
            float rx = px;
            if (card.iconA[i] != null) {
                c.item(card.iconA[i], rx, py, a);
                c.number(TextRole.MONO, card.countA[i], rx + 18f, py + 4f, HoloToken.TEXT, a);
                rx += 16f + 22f;
            }
            if (card.iconB[i] != null) {
                c.glyph(Glyph.CHEVRON_RIGHT, rx, py + 8f - Glyph.SIZE * 0.5f, HoloToken.ACCENT, a);
                rx += Glyph.SIZE + 4f;
                c.item(card.iconB[i], rx, py, a);
                c.number(TextRole.MONO, card.countB[i], rx + 18f, py + 4f, HoloToken.TEXT, a);
            }
            py += ROW_H;
        }
        if (ring) {
            float p = card.progressAt(FrameClock.nowMs());
            float rcx = cx + cw - PAD - RING_R;
            float rcy = cy + PAD + RING_R + 1f;
            c.arc(rcx, rcy, RING_R, 0f, 360f, 1f, HoloToken.LINE_FAINT, a);
            if (p > 0f) {
                c.arc(rcx, rcy, RING_R, 0f, 360f * p, 2f, p >= 1f ? HoloToken.SYNC : HoloToken.ACCENT, a);
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
