package com.utilitysuite.ui.kit;

/**
 * The dark-glass dashboard palette (ARGB) and the shared metrics of the menu. Text is always drawn at 1.0 scale
 * without shadow; hierarchy comes from these colors and spacing only.
 */
public final class UiTheme {

    // ---- surfaces ---------------------------------------------------------------------------------------------------
    public static final int BACKDROP = 0x55000000;
    public static final int WINDOW = 0xFF141416;
    public static final int SIDEBAR = 0xFF101012;
    public static final int CARD = 0xFF1A1A1E;
    public static final int CARD_HOVER = 0xFF202025;
    public static final int BORDER = 0xFF2A2A2E;
    /** Inputs, tracks and inset blocks: darker than a card. */
    public static final int FIELD = 0xFF0F1116;
    public static final int FIELD_BORDER_HOVER = 0xFF363B4A;
    public static final int INSET = 0xFF14171D;
    public static final int ROW_HOVER = 0xFF20242D;
    public static final int CONTROL = 0xFF232733;
    public static final int CONTROL_HOVER = 0xFF2B303C;
    public static final int CONTROL_BORDER = 0xFF303543;

    // ---- accents ----------------------------------------------------------------------------------------------------
    public static final int ACCENT = 0xFF38BDF8;
    public static final int ACCENT_HOVER = 0xFF67CEFA;
    public static final int ACCENT_TINT = 0x2438BDF8;
    public static final int ACCENT_BORDER = 0x8038BDF8;
    public static final int ON_ACCENT = 0xFF06131C;

    // ---- text -------------------------------------------------------------------------------------------------------
    public static final int TEXT = 0xFFF3F4F6;
    public static final int TEXT_SECONDARY = 0xFFD1D5DB;
    public static final int TEXT_MUTED = 0xFF6B7280;
    /** Between secondary and muted: field labels, Now/Why/Next captions. */
    public static final int TEXT_DIM = 0xFF9CA3AF;

    // ---- status -----------------------------------------------------------------------------------------------------
    public static final int RUNNING = 0xFF10B981;
    public static final int WARNING = 0xFFF59E0B;
    public static final int DANGER = 0xFFEF4444;
    public static final int DANGER_TEXT = 0xFFFCA5A5;
    public static final int DANGER_TINT = 0x26EF4444;
    public static final int DANGER_BORDER = 0x80EF4444;
    public static final int RUNNING_TINT = 0x2410B981;
    public static final int WARNING_TINT = 0x24F59E0B;

    // ---- toggle -----------------------------------------------------------------------------------------------------
    public static final int TRACK_OFF = 0xFF3A3F4C;
    public static final int KNOB = 0xFFF3F4F6;

    // ---- HUD --------------------------------------------------------------------------------------------------------
    public static final int HUD_PILL = 0xCC111318;

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
    public static final int LIST_BG = 0xFF121214;
    public static final int CONTENT_PAD = 14;
    public static final int CARD_GAP = 8;
    public static final int CARD_PAD = 12;
    public static final int RADIUS = 4;
    public static final int RADIUS_SMALL = 3;
    public static final long OPEN_MS = 120L;
    public static final long TOGGLE_MS = 120L;

    private UiTheme() {
    }

    /** Linear blend of two ARGB colors, {@code t} in 0..1. */
    public static int mix(int a, int b, float t) {
        if (t <= 0f) {
            return a;
        }
        if (t >= 1f) {
            return b;
        }
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** The color with its alpha multiplied by {@code f} (0..1). */
    public static int fade(int color, float f) {
        int a = (int) ((color >>> 24) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (color & 0xFFFFFF);
    }
}
