package com.utilitysuite.ui;

import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.TextRole;
import com.utilitysuite.ui.holo.HoloTheme;

/**
 * The optional info strip (off by default; settings live on the {@code suite_hud} module) as a {@link HudElement}:
 * a quiet glass plate with micro captions ({@code POS}, {@code LINK}, {@code BIOME}, {@code SERVER}, {@code PING})
 * over mono values (coordinates, Nether / Overworld conversion, biome, TPS, ping), hairline separators and
 * segmented durability bars for the main hand and armour.
 * <p>
 * Values are sampled once per client tick by {@code SuiteHud} into {@link InfoStripText}, which rebuilds a string
 * only when its integers changed. The caption row and the value row are each joined into one string when that
 * version moves (two text runs per frame however many segments are on), padded with spaces so every caption sits
 * over its value; {@link #draw} only draws cached strings. Hidden in Clean-Screen, with F1, with F3 and while a
 * screen is open ({@link #blocked}, written by the HUD module each frame). Pure Java.
 */
public final class InfoStrip implements HudElement {

    public enum Position { BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }

    public static final String ID = "info_strip";
    static final int H = 24;
    private static final int PAD = 7;
    private static final float SEG_GAP = 14f;
    private static final int BAR_W = 14;
    private static final int BAR_H = 2;
    private static final int BAR_GAP = 3;
    static final int SLOTS = 5;
    private static final int MAX_SEGMENTS = 5;

    private final InfoStripText text = new InfoStripText();

    // settings (written by SuiteHud.read on the client thread)
    boolean enabled;
    Position position = Position.TOP_LEFT;
    boolean showCoords = true;
    boolean showConversion = true;
    boolean showBiome = true;
    boolean showTps = true;
    boolean showPing = true;
    boolean showDurability = true;
    /** F3 open, a screen open: written each frame by the HUD module. */
    boolean blocked;

    // durability per slot (0 = main hand, 1..4 = armour head..feet); fraction < 0 = no bar
    final float[] barFrac = new float[SLOTS];
    int bars;

    // cached layout
    private int seenVersion = -1;
    private int seenSettings = -1;
    private String captionLine = "";
    private String valueLine = "";
    private final float[] sepX = new float[MAX_SEGMENTS];
    private int seps;
    private float textW;
    private float width;

    InfoStrip() {
        for (int i = 0; i < SLOTS; i++) {
            barFrac[i] = -1f;
        }
    }

    /** The text cache (sampling, tests and preview). */
    InfoStripText text() {
        return text;
    }

    /** Sets the durability of a slot from damage values ({@code max <= 0} = no bar). */
    void setBar(int slot, int damage, int max) {
        if (max <= 0) {
            barFrac[slot] = -1f;
            return;
        }
        barFrac[slot] = Math.max(0f, Math.min(1f, (max - damage) / (float) max));
    }

    void countBars() {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (barFrac[i] >= 0f) {
                n++;
            }
        }
        bars = n;
    }

    // ---- element ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HudSlot slot() {
        return switch (position) {
            case BOTTOM_LEFT -> HudSlot.FREE;
            case TOP_RIGHT -> HudSlot.TOP_RIGHT;
            default -> HudSlot.TOP_LEFT;
        };
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public boolean wantsDraw() {
        return enabled && !blocked && (!valueLine.isEmpty() || text.version() != seenVersion
                || settingsMask() != seenSettings || showDurability && bars > 0);
    }

    private int settingsMask() {
        return (showCoords ? 1 : 0) | (showConversion ? 2 : 0) | (showBiome ? 4 : 0) | (showTps ? 8 : 0)
                | (showPing ? 16 : 0);
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

    /** Rebuilds the joined caption and value rows when a value or a setting changed (not per frame). */
    void layout(IHoloCanvas c) {
        int mask = settingsMask();
        if (text.version() == seenVersion && mask == seenSettings) {
            width = computeWidth();
            return;
        }
        seenVersion = text.version();
        seenSettings = mask;
        StringBuilder cap = new StringBuilder(48);
        StringBuilder val = new StringBuilder(96);
        seps = 0;
        boolean first = true;
        String conv = text.conversion();
        for (int s = 0; s < MAX_SEGMENTS; s++) {
            String v;
            String k;
            switch (s) {
                case 0 -> {
                    v = showCoords ? text.coords() : "";
                    k = "POS";
                }
                case 1 -> {
                    v = showConversion ? conv : "";
                    k = "LINK";
                }
                case 2 -> {
                    v = showBiome ? text.biome() : "";
                    k = "BIOME";
                }
                case 3 -> {
                    v = showTps ? text.tps() : "";
                    k = "SERVER";
                }
                default -> {
                    v = showPing ? text.ping() : "";
                    k = "PING";
                }
            }
            if (v.isEmpty()) {
                continue;
            }
            if (!first) {
                float end = Math.max(c.width(TextRole.MICRO, cap.toString()), c.width(TextRole.MONO, val.toString()));
                sepX[seps++] = end + SEG_GAP * 0.5f;
                float start = end + SEG_GAP;
                pad(c, cap, TextRole.MICRO, start);
                pad(c, val, TextRole.MONO, start);
                // the two rows may land a few pixels apart (one space is 4 px): even them out
                float cw = c.width(TextRole.MICRO, cap.toString());
                float vw = c.width(TextRole.MONO, val.toString());
                if (cw < vw) {
                    pad(c, cap, TextRole.MICRO, vw);
                } else if (vw < cw) {
                    pad(c, val, TextRole.MONO, cw);
                }
            }
            first = false;
            cap.append(k);
            val.append(v);
        }
        captionLine = cap.toString();
        valueLine = val.toString();
        textW = valueLine.isEmpty() ? 0f
                : Math.max(c.width(TextRole.MICRO, captionLine), c.width(TextRole.MONO, valueLine));
        width = computeWidth();
    }

    private static void pad(IHoloCanvas c, StringBuilder sb, TextRole role, float target) {
        for (int guard = 0; guard < 64 && c.width(role, sb.toString()) < target; guard++) {
            sb.append(' ');
        }
    }

    private float computeWidth() {
        float w = textW;
        float barsW = barsWidth();
        if (barsW > 0f) {
            w += (w > 0f ? SEG_GAP : 0f) + barsW;
        }
        return w <= 0f ? 0f : w + 2 * PAD;
    }

    private float barsWidth() {
        return showDurability && bars > 0 ? bars * (BAR_W + BAR_GAP) - BAR_GAP : 0f;
    }

    @Override
    public void draw(IHoloCanvas c, float x, float y, float alpha) {
        layout(c);
        if (width <= 0f) {
            return;
        }
        if (position == Position.BOTTOM_LEFT) {
            x = 6f;
            y = c.screenHeight() - H - 6f;
        }
        c.frame(x, y, width, H, FrameLevel.QUIET, false, alpha);
        float tx = x + PAD;
        float capY = y + 3;
        float valY = capY + c.lineHeight(TextRole.MICRO) + 2;
        if (!valueLine.isEmpty()) {
            c.text(TextRole.MICRO, captionLine, tx, capY, HoloToken.TEXT_MUTED, alpha);
            c.text(TextRole.MONO, valueLine, tx, valY, HoloToken.TEXT, alpha);
            for (int i = 0; i < seps; i++) {
                c.vLine(tx + sepX[i], y + 4, y + H - 4, HoloToken.LINE_FAINT, alpha);
            }
        }
        float barsW = barsWidth();
        if (barsW > 0f) {
            float bx = tx + textW + (textW > 0f ? SEG_GAP : 0f);
            if (textW > 0f) {
                c.vLine(bx - SEG_GAP * 0.5f, y + 4, y + H - 4, HoloToken.LINE_FAINT, alpha);
            }
            float by = valY + 3;
            for (int i = 0; i < SLOTS; i++) {
                float f = barFrac[i];
                if (f < 0f) {
                    continue;
                }
                c.bar(bx, by, BAR_W, BAR_H, f, 1, HoloTheme.healthToken(f), HoloToken.LINE_FAINT, alpha);
                bx += BAR_W + BAR_GAP;
            }
        }
    }
}
