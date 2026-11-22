package com.utilitysuite.ui.kit;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloTheme;

/**
 * The menu palette (ARGB): aliases of the {@link HoloToken} theme (thin lines on translucent dark glass, colour
 * reserved for meaning) plus the shared metrics of the menu. Text is always drawn at 1.0 scale without shadow.
 * <p>
 * The colour fields are plain statics filled from {@link HoloTheme} by {@link #refresh()} (they cannot be compile-time
 * constants because the accent is user-chosen). The kit widgets read the tokens directly; these ints remain for
 * screens that still hold them, and go stale only until the next {@link #refresh()}.
 */
public final class UiTheme {

    // ---- surfaces ---------------------------------------------------------------------------------------------------
    public static int BACKDROP;
    public static int WINDOW;
    public static int SIDEBAR;
    public static int CARD;
    public static int CARD_HOVER;
    public static int BORDER;
    /** Inputs, tracks and inset blocks: darker than a card. */
    public static int FIELD;
    public static int FIELD_BORDER_HOVER;
    public static int INSET;
    public static int ROW_HOVER;
    public static int CONTROL;
    public static int CONTROL_HOVER;
    public static int CONTROL_BORDER;

    // ---- accents ----------------------------------------------------------------------------------------------------
    public static int ACCENT;
    public static int ACCENT_HOVER;
    public static int ACCENT_TINT;
    public static int ACCENT_BORDER;
    public static int ON_ACCENT;

    // ---- text -------------------------------------------------------------------------------------------------------
    public static int TEXT;
    public static int TEXT_SECONDARY;
    public static int TEXT_MUTED;
    /** Between secondary and muted: field labels, Now/Why/Next captions. */
    public static int TEXT_DIM;

    // ---- status -----------------------------------------------------------------------------------------------------
    public static int RUNNING;
    public static int WARNING;
    public static int DANGER;
    public static int DANGER_TEXT;
    public static int DANGER_TINT;
    public static int DANGER_BORDER;
    public static int RUNNING_TINT;
    public static int WARNING_TINT;

    // ---- toggle -----------------------------------------------------------------------------------------------------
    public static int TRACK_OFF;
    public static int KNOB;

    // ---- HUD --------------------------------------------------------------------------------------------------------
    public static int HUD_PILL;

    // ---- metrics ----------------------------------------------------------------------------------------------------
    public static final int WINDOW_W = 880;
    public static final int WINDOW_H = 470;
    public static final int TOP_BAR_H = 38;
    public static final int SIDEBAR_W = 128;
    public static final int SIDEBAR_W_COMPACT = 104;
    /** Column 2 (module list) of the three-column layout; hidden below {@link #THREE_COLUMN_MIN_W}. */
    public static final int LIST_W = 176;
    public static final int LIST_W_COMPACT = 144;
    public static final int THREE_COLUMN_MIN_W = 560;
    /** The list column background, between the sidebar and the window. */
    public static int LIST_BG;
    public static final int CONTENT_PAD = 14;
    public static final int CARD_GAP = 8;
    public static final int CARD_PAD = 12;
    /** Kept for callers; the new surfaces are square or chamfered, radius arguments are ignored. */
    public static final int RADIUS = 4;
    public static final int RADIUS_SMALL = 3;
    public static final long OPEN_MS = 120L;
    public static final long TOGGLE_MS = 140L;

    static {
        refresh();
    }

    private UiTheme() {
    }

    private static int t(HoloToken token) {
        return HoloTheme.argb(token);
    }

    /** Re-reads every colour alias from {@link HoloTheme} (after the accent changes; never per frame). */
    public static void refresh() {
        BACKDROP = HoloTheme.argb(HoloToken.VOID, 0.33f);
        WINDOW = t(HoloToken.GLASS);
        SIDEBAR = t(HoloToken.GLASS_HUD);
        LIST_BG = t(HoloToken.GLASS_HUD);
        CARD = t(HoloToken.GLASS);
        CARD_HOVER = t(HoloToken.GLASS_RAISED);
        BORDER = t(HoloToken.LINE_FAINT);
        FIELD = t(HoloToken.FIELD);
        FIELD_BORDER_HOVER = t(HoloToken.LINE);
        INSET = t(HoloToken.FIELD);
        ROW_HOVER = t(HoloToken.GLASS_RAISED);
        CONTROL = t(HoloToken.GLASS_RAISED);
        CONTROL_HOVER = HoloTheme.mix(t(HoloToken.GLASS_RAISED), t(HoloToken.ACCENT), 0.10f);
        CONTROL_BORDER = t(HoloToken.LINE);
        ACCENT = t(HoloToken.ACCENT);
        ACCENT_HOVER = HoloTheme.mix(t(HoloToken.ACCENT), t(HoloToken.TEXT), 0.35f);
        ACCENT_TINT = t(HoloToken.ACCENT_GLOW);
        ACCENT_BORDER = t(HoloToken.LINE);
        ON_ACCENT = t(HoloToken.ON_ACCENT);
        TEXT = t(HoloToken.TEXT);
        TEXT_SECONDARY = t(HoloToken.TEXT_SECONDARY);
        TEXT_MUTED = t(HoloToken.TEXT_MUTED);
        TEXT_DIM = HoloTheme.mix(t(HoloToken.TEXT_SECONDARY), t(HoloToken.TEXT_MUTED), 0.45f);
        RUNNING = t(HoloToken.SYNC);
        WARNING = t(HoloToken.WARN);
        DANGER = t(HoloToken.DANGER);
        DANGER_TEXT = HoloTheme.mix(t(HoloToken.DANGER), t(HoloToken.TEXT), 0.45f);
        DANGER_TINT = HoloTheme.argb(HoloToken.DANGER, 0.15f);
        DANGER_BORDER = HoloTheme.argb(HoloToken.DANGER, 0.5f);
        RUNNING_TINT = HoloTheme.argb(HoloToken.SYNC, 0.14f);
        WARNING_TINT = HoloTheme.argb(HoloToken.WARN, 0.14f);
        TRACK_OFF = t(HoloToken.TEXT_FAINT);
        KNOB = t(HoloToken.TEXT);
        HUD_PILL = t(HoloToken.GLASS_HUD);
    }

    /** Linear blend of two ARGB colors, {@code t} in 0..1. */
    public static int mix(int a, int b, float t) {
        return HoloTheme.mix(a, b, t);
    }

    /** The color with its alpha multiplied by {@code f} (0..1). */
    public static int fade(int color, float f) {
        return HoloTheme.fade(color, f);
    }
}
