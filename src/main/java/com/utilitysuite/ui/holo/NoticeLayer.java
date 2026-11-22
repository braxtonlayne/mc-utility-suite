package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.Glyph;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.core.api.visual.TextRole;

/**
 * Draws the {@link NoticeBoard} as toasts (design 8.10): a bottom-right stack of at most
 * {@value NoticeBoard#MAX_VISIBLE} framed, chamfered plates (newest at the bottom), each with a
 * {@value HoloTheme#BAR} px kind-coloured left edge, the kind glyph, the title, an optional one-line detail and a
 * 1 px lifetime hairline that drains. Enter: slide 14 px from the right plus fade over 160 ms; leave: the same over the
 * last 220 ms of the notice's life; reflow of the others by {@link AnimStore#settle}. {@link Motion#REDUCED} keeps the
 * fades and drops the slide; {@link Motion#OFF} makes every step instant.
 * <p>
 * Slot {@code FREE}: the layer places the stack itself. Idle (no live notice) it wants nothing, so the host makes no
 * call; Clean-Screen is handled by the host gate (not allowed there) and by the board (posts dropped). Per frame:
 * no allocation (the shown strings were built at post time; the width is measured once per notice), at most
 * {@code 3 * 6} canvas calls and 6 text runs.
 */
public final class NoticeLayer implements HudElement {

    public static final String ID = "notices";
    public static final float ENTER_SECONDS = 0.16f;
    public static final float EXIT_SECONDS = 0.22f;
    public static final float SLIDE = 14f;
    public static final float MARGIN = 6f;
    public static final float GAP = 4f;
    public static final float MIN_W = 70f;
    public static final float MAX_W = 240f;
    public static final float H_TITLE = 18f;
    public static final float H_DETAIL = 28f;
    private static final float TEXT_X = HoloTheme.BAR + 5f + Glyph.SIZE + 5f;
    private static final float PAD_R = 8f;
    private static final String ELLIPSIS = "...";

    private final NoticeBoard board;
    private final AnimStore anim;

    public NoticeLayer(NoticeBoard board) {
        this(board, AnimStore.SHARED);
    }

    NoticeLayer(NoticeBoard board, AnimStore anim) {
        this.board = board;
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
        return board.expire(board.now()) > 0;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        long now = board.now();
        int n = board.count();
        if (n == 0) {
            return;
        }
        float sw = c.screenWidth();
        float sh = c.screenHeight();
        float cursor = 0f;
        int first = Math.max(0, n - NoticeBoard.MAX_VISIBLE);
        for (int i = n - 1; i >= first; i--) { // newest at the bottom, older ones above it
            NoticeBoard.Notice t = board.at(i);
            measure(c, t, sw);
            float h = t.detail != null ? H_DETAIL : H_TITLE;
            float ty = sh - MARGIN - h - cursor;
            cursor += h + GAP;

            long enterKey = AnimStore.key(t.seq, 0x4E01);
            long yKey = AnimStore.key(t.seq, 0x4E02);
            if (!t.entered) {
                t.entered = true;
                anim.set(enterKey, 0f);
                anim.set(yKey, ty);
            }
            float enter = AnimStore.easeOut(anim.progress(enterKey, true, ENTER_SECONDS));
            float exit = 1f;
            if (HoloTheme.motion != Motion.OFF) {
                float left = (t.expiresAt - now) / (EXIT_SECONDS * 1000f);
                exit = left >= 1f ? 1f : left <= 0f ? 0f : left;
            }
            float a = alpha * enter * exit;
            if (a <= 0f) {
                continue;
            }
            float slide = HoloTheme.motion == Motion.FULL ? SLIDE * ((1f - enter) + (1f - exit)) : 0f;
            float py = Math.round(anim.settle(yKey, ty, AnimStore.TAU_NORMAL, ty));
            float px = sw - MARGIN - t.width + slide;
            draw(c, t, px, py, h, a, now);
        }
    }

    private static void draw(IHoloCanvas c, NoticeBoard.Notice t, float x, float y, float h, float a, long now) {
        float w = t.width;
        HoloToken kind = token(t.kind);
        c.frame(x, y, w, h, FrameLevel.FRAMED, true, a);
        c.plate(x, y + 3f, HoloTheme.BAR, h - 6f, kind, a);
        float titleY = y + 5f;
        c.glyph(glyph(t.kind), x + HoloTheme.BAR + 5f, titleY + 1f, kind, a);
        c.text(TextRole.UI, t.shown, x + TEXT_X, titleY, HoloToken.TEXT, a);
        if (t.detail != null) {
            c.text(TextRole.UI, t.detail, x + TEXT_X, titleY + 10f, HoloToken.TEXT_SECONDARY, a);
        }
        float frac = (t.expiresAt - now) / (float) t.lifeMs;
        frac = frac <= 0f ? 0f : Math.min(1f, frac);
        float x0 = x + HoloTheme.BAR + 3f;
        float span = w - HoloTheme.BAR - 6f;
        if (span * frac >= 1f) {
            c.hLine(x0, x0 + span * frac, y + h - 3f, kind, a * 0.8f);
        }
    }

    /** Width of a notice (once per notice or merge): text measured, detail cut with "..." when too wide. */
    private static void measure(IHoloCanvas c, NoticeBoard.Notice t, float screenW) {
        if (t.width >= 0f) {
            return;
        }
        float cap = Math.max(MIN_W, Math.min(MAX_W, screenW - 2f * MARGIN));
        float room = cap - TEXT_X - PAD_R;
        float tw = c.width(TextRole.UI, t.shown);
        float dw = 0f;
        if (t.detail != null) {
            dw = c.width(TextRole.UI, t.detail);
            if (dw > room) {
                t.detail = ellipsize(c, t.detail, room);
                dw = c.width(TextRole.UI, t.detail);
            }
        }
        t.width = Math.max(MIN_W, Math.min(cap, TEXT_X + Math.max(tw, dw) + PAD_R));
    }

    /** {@code s} cut with "..." to fit {@code maxW} (measure time only). */
    static String ellipsize(IHoloCanvas c, String s, float maxW) {
        float ew = c.width(TextRole.UI, ELLIPSIS);
        int end = s.length();
        while (end > 0 && c.width(TextRole.UI, s.substring(0, end)) + ew > maxW) {
            end--;
        }
        return end == 0 ? ELLIPSIS : s.substring(0, end).trim() + ELLIPSIS;
    }

    static HoloToken token(NoticeKind k) {
        return switch (k) {
            case DONE -> HoloToken.SYNC;
            case WARN -> HoloToken.WARN;
            case ERROR -> HoloToken.DANGER;
            default -> HoloToken.ACCENT;
        };
    }

    static Glyph glyph(NoticeKind k) {
        return switch (k) {
            case DONE -> Glyph.DONE;
            case WARN -> Glyph.WARNING;
            case ERROR -> Glyph.FAILED;
            default -> Glyph.DOT;
        };
    }
}
