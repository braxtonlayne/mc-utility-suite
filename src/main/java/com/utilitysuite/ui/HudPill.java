package com.utilitysuite.ui;

import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.Glyph;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.TextRole;
import com.utilitysuite.ui.holo.AnimStore;

/**
 * The HUD status line as a {@link HudElement}: a framed glass plate with the status glyph, a bracketed item cell,
 * the {@code X/Y} progress in the mono role, the current step (ellipsized to fit) and a 1 px progress hairline along
 * the bottom edge. The layout (strings and widths) is recomputed only when the {@link HudStatusLine} was rebuilt (on
 * {@code JobChanged}) or the screen width changed; {@link #draw} only draws cached values and allocates nothing.
 * A text change cross-fades in over {@value #TEXT_FADE_S} s. Also holds the small {@link Badge} elements. Pure Java
 * (drawn only through {@link IHoloCanvas} and tokens).
 */
public final class HudPill implements HudElement {

    public static final String ID = "status_line";
    public static final int H = 20;
    public static final int MAX_W = 260;
    public static final int MIN_W = 120;
    static final float TEXT_FADE_S = 0.12f;
    private static final int PAD_L = 6;
    private static final int PAD_R = 8;
    private static final int CELL = 18;
    private static final String ELLIPSIS = "...";

    private final HudStatusLine line;
    private final long textKey = AnimStore.key(ID, 0x7E);

    /** Written by {@code SuiteHud} from its settings (client thread). */
    boolean enabled = true;
    HudSlot slot = HudSlot.TOP_CENTER;

    private int seenRebuilds = -1;
    private float seenScreenW = -1f;
    private float width = MIN_W;
    private String progress = "";
    private float progressW;
    private String step = "";
    private String itemId = "";
    private Glyph glyph = Glyph.RUNNING;
    private HoloToken glyphToken = HoloToken.ACCENT;
    private float fraction = -1f;

    public HudPill(HudStatusLine line) {
        this.line = line;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return slot;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean wantsDraw() {
        return enabled && !line.isEmpty();
    }

    @Override
    public float width(IHoloCanvas c) {
        layout(c);
        return width;
    }

    @Override
    public float height(IHoloCanvas c) {
        return H;
    }

    /** Recomputes the cached layout when the line or the screen width changed (not per frame). */
    void layout(IHoloCanvas c) {
        float sw = c.screenWidth();
        if (line.rebuilds() == seenRebuilds && sw == seenScreenW) {
            return;
        }
        boolean textChanged = seenRebuilds >= 0 && line.rebuilds() != seenRebuilds;
        seenRebuilds = line.rebuilds();
        seenScreenW = sw;
        progress = line.progressText();
        progressW = progress.isEmpty() ? 0f : c.width(TextRole.MONO, progress);
        fraction = line.fraction();
        if (line.isAutopilot()) {
            itemId = "minecraft:feather";
            glyph = Glyph.ACTING;
            glyphToken = HoloToken.SYNC;
        } else if (line.isSchematic()) {
            itemId = "minecraft:scaffolding";
            glyph = Glyph.RUNNING;
            glyphToken = HoloToken.ACCENT;
        } else {
            String id = line.itemId();
            itemId = id.isEmpty() ? fallbackItem(line.goalLabel()) : id;
            glyph = glyphOf(line.status());
            glyphToken = tokenOf(line.status());
        }
        float fixed = PAD_L + 7 + 5 + CELL + 6 + PAD_R + (progressW > 0f ? progressW + 6f : 0f);
        String full = line.detailText();
        float limit = Math.max(MIN_W, Math.min(MAX_W, sw - 16f));
        width = Math.max(MIN_W, Math.min(limit, fixed + c.width(TextRole.UI, full)));
        step = ellipsize(c, full, width - fixed);
        if (textChanged) {
            AnimStore.SHARED.set(textKey, 0f);
        }
    }

    /** {@code s} cut with "..." to fit {@code maxW} (rebuild-time only). */
    static String ellipsize(IHoloCanvas c, String s, float maxW) {
        if (s == null || s.isEmpty() || c.width(TextRole.UI, s) <= maxW) {
            return s == null ? "" : s;
        }
        float ew = c.width(TextRole.UI, ELLIPSIS);
        int end = s.length();
        while (end > 0 && c.width(TextRole.UI, s.substring(0, end)) + ew > maxW) {
            end--;
        }
        return end == 0 ? "" : s.substring(0, end).trim() + ELLIPSIS;
    }

    static String fallbackItem(String label) {
        String l = label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("travel")) {
            return "minecraft:compass";
        }
        if (l.startsWith("build")) {
            return "minecraft:bricks";
        }
        if (l.startsWith("craft")) {
            return "minecraft:crafting_table";
        }
        return "minecraft:iron_pickaxe";
    }

    static Glyph glyphOf(JobStatus s) {
        if (s == null) {
            return Glyph.RUNNING;
        }
        return switch (s) {
            case RUNNING -> Glyph.RUNNING;
            case COMPLETED -> Glyph.DONE;
            case FAILED, CANCELLED -> Glyph.FAILED;
            default -> Glyph.WAITING;
        };
    }

    static HoloToken tokenOf(JobStatus s) {
        if (s == null) {
            return HoloToken.ACCENT;
        }
        return switch (s) {
            case RUNNING -> HoloToken.ACCENT;
            case COMPLETED -> HoloToken.SYNC;
            case FAILED, CANCELLED -> HoloToken.DANGER;
            default -> HoloToken.WARN;
        };
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        c.frame(x, y, width, H, FrameLevel.FRAMED, false, alpha);
        float gx = x + PAD_L;
        c.glyph(glyph, gx, y + (H - Glyph.SIZE) / 2f, glyphToken, alpha);
        float cx = gx + Glyph.SIZE + 5;
        float cy = y + 1;
        c.brackets(cx, cy, CELL, CELL, 3, HoloToken.LINE, alpha);
        c.item(itemId, cx + 1, cy + 1, alpha);
        float tx = cx + CELL + 6;
        float ty = y + (H - c.lineHeight(TextRole.UI)) / 2f + 1f;
        float ta = alpha * AnimStore.SHARED.progress(textKey, true, TEXT_FADE_S);
        if (progressW > 0f) {
            c.text(TextRole.MONO, progress, tx, ty, HoloToken.TEXT, ta);
            tx += progressW + 6f;
        }
        c.text(TextRole.UI, step, tx, ty, HoloToken.TEXT_SECONDARY, ta);
        if (fraction >= 0f) {
            float fx = x + 1 + (width - 2) * fraction;
            if (fx > x + 1) {
                c.hLine(x + 1, fx, y + H - 1, HoloToken.ACCENT, alpha);
            }
        }
    }

    /** Cached width after the last layout (tests). */
    float cachedWidth() {
        return width;
    }

    /**
     * A small badge: "Automation stopped" (danger: snaps in with ease-out-back brackets and never waits on a fade) or
     * the opt-in Clean-Screen indicator (quiet plate). Driven by {@link #active}, which the HUD module writes.
     */
    public static final class Badge implements HudElement {

        public static final int H = 14;
        static final float SNAP_S = 0.18f;

        private final String id;
        private final String text;
        private final boolean danger;
        private final int priority;
        private final long snapKey;
        HudSlot slot = HudSlot.TOP_CENTER;
        /** Shown while true (client thread). */
        public boolean active;
        private boolean wasActive;
        private float textW = -1f;

        public Badge(String id, String text, boolean danger, int priority) {
            this.id = id;
            this.text = text;
            this.danger = danger;
            this.priority = priority;
            this.snapKey = AnimStore.key(id, 0x5A);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public HudSlot slot() {
            return slot;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean wantsDraw() {
            if (active && !wasActive) {
                AnimStore.SHARED.set(snapKey, 0f); // the snap restarts every time the badge comes back
            }
            wasActive = active;
            return active;
        }

        @Override
        public float width(IHoloCanvas c) {
            if (textW < 0f) {
                textW = c.width(TextRole.UI, text);
            }
            return textW + 22f;
        }

        @Override
        public float height(IHoloCanvas c) {
            return H;
        }

        @Override
        public void draw(IHoloCanvas c, float x, float y, float alpha) {
            float w = width(c);
            float ty = y + (H - c.lineHeight(TextRole.UI)) / 2f + 1f;
            if (danger) {
                float e = AnimStore.easeOutBack(AnimStore.SHARED.progress(snapKey, true, SNAP_S));
                float off = (1f - e) * 6f;
                c.plate(x, y, w, H, HoloToken.GLASS_HUD, alpha);
                c.hLine(x, x + w, y, HoloToken.DANGER, alpha * 0.35f);
                c.hLine(x, x + w, y + H - 1, HoloToken.DANGER, alpha * 0.35f);
                c.brackets(x - off, y - off, w + 2f * off, H + 2f * off, 4, HoloToken.DANGER, alpha);
                c.glyph(Glyph.FAILED, x + 5, y + (H - Glyph.SIZE) / 2f, HoloToken.DANGER, alpha);
                c.text(TextRole.UI, text, x + 16, ty, HoloToken.TEXT, alpha);
            } else {
                c.frame(x, y, w, H, FrameLevel.QUIET, false, alpha);
                c.glyph(Glyph.DOT, x + 5, y + (H - Glyph.SIZE) / 2f, HoloToken.TEXT_MUTED, alpha);
                c.text(TextRole.UI, text, x + 16, ty, HoloToken.TEXT_SECONDARY, alpha);
            }
        }
    }
}
