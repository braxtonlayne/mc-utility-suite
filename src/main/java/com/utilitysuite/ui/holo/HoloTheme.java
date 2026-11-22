package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.TextRole;

/**
 * The one palette of the suite: every {@link HoloToken} resolved to ARGB, plus the shared frame metrics. The accent
 * family is derived when the accent changes, never per frame; status hues are fixed. The appearance settings write
 * the public fields below (client thread); drawing code only reads. No Minecraft imports: unit-testable.
 */
public final class HoloTheme {

    public static final int DEFAULT_ACCENT_RGB = 0x3DE8FF;
    /** Chamfer of hero surfaces, in pixels. */
    public static final int CHAMFER = 4;
    /** Width of selection bars, slider fills, progress fills and the active edge of a notice. */
    public static final int BAR = 2;

    private static final int[] ARGB = new int[HoloToken.all().length];

    /** Decoration level (appearance setting). */
    public static EffectLevel effects = EffectLevel.FULL;
    /** Animation level (appearance setting). */
    public static Motion motion = Motion.FULL;
    /** Multiplies every in-game HUD alpha, 0.4 to 1. The menu ignores it. */
    public static float hudOpacity = 1f;
    /** Scale of the in-game HUD, 0.75 to 1.5. The menu keeps its own scale. */
    public static float hudScale = 1f;

    static {
        put(HoloToken.VOID, 0xFF05080B);
        put(HoloToken.GLASS, 0xC70A1218);
        put(HoloToken.GLASS_HUD, 0x9E070D12);
        put(HoloToken.GLASS_RAISED, 0xDB10202A);
        put(HoloToken.FIELD, 0xE6060B0F);
        put(HoloToken.ON_ACCENT, 0xFF021318);
        put(HoloToken.TEXT, 0xFFE6FBFF);
        put(HoloToken.TEXT_SECONDARY, 0xFF9CC7D1);
        put(HoloToken.TEXT_MUTED, 0xFF5C7F8A);
        put(HoloToken.TEXT_FAINT, 0xFF35505A);
        put(HoloToken.SYNC, 0xFF3DFFB4);
        put(HoloToken.WARN, 0xFFFFB547);
        put(HoloToken.DANGER, 0xFFFF4D5E);
        put(HoloToken.ALLY, 0xFF6EA8FF);
        put(HoloToken.MANUAL, 0xFFFFFFFF);
        put(HoloToken.CAT_JOBS, 0xFFFFC857);
        put(HoloToken.CAT_WORLD, 0xFF5CE0A0);
        put(HoloToken.CAT_TRAVEL, 0xFF5BB8FF);
        put(HoloToken.CAT_COMBAT, 0xFFFF6B7A);
        put(HoloToken.CAT_TOOLS, 0xFFB69CFF);
        setAccent(DEFAULT_ACCENT_RGB);
    }

    private HoloTheme() {
    }

    private static void put(HoloToken t, int argb) {
        ARGB[t.ordinal()] = argb;
    }

    /** Sets the accent colour (0xRRGGBB) and derives the line, deep and glow tokens from it. Not for hot paths. */
    public static void setAccent(int rgb) {
        rgb &= 0xFFFFFF;
        put(HoloToken.ACCENT, 0xFF000000 | rgb);
        put(HoloToken.LINE_STRONG, 0xFF000000 | rgb);
        put(HoloToken.LINE, 0x61000000 | rgb);
        put(HoloToken.LINE_FAINT, 0x29000000 | rgb);
        put(HoloToken.ACCENT_GLOW, 0x38000000 | rgb);
        int r = (int) (((rgb >> 16) & 0xFF) * 0.36f);
        int g = (int) (((rgb >> 8) & 0xFF) * 0.40f);
        int b = (int) ((rgb & 0xFF) * 0.43f);
        put(HoloToken.ACCENT_DEEP, 0xFF000000 | (r << 16) | (g << 8) | b);
    }

    public static int argb(HoloToken token) {
        return ARGB[token.ordinal()];
    }

    /** The token with its alpha multiplied by {@code alpha} (0..1). */
    public static int argb(HoloToken token, float alpha) {
        return fade(ARGB[token.ordinal()], alpha);
    }

    /** The colour with its alpha multiplied by {@code f} (0..1). */
    public static int fade(int argb, float f) {
        if (f >= 1f) {
            return argb;
        }
        int a = f <= 0f ? 0 : (int) ((argb >>> 24) * f);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Linear blend of two ARGB colours, {@code t} in 0..1. */
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

    /** Arm length of the corner brackets of a frame of this size: clamp(min(w, h) * 0.18, 4, 10). */
    public static int bracketArm(int w, int h) {
        int a = (int) (Math.min(w, h) * 0.18f);
        return a < 4 ? 4 : Math.min(a, 10);
    }

    /** Line height of a text role in pixels (the suite font, when it lands, keeps these). */
    public static int lineHeight(TextRole role) {
        return role == TextRole.MICRO ? 7 : 9;
    }

    /** Health colour by fraction: accent above half, warning above a quarter, danger below. */
    public static HoloToken healthToken(float fraction01) {
        return fraction01 > 0.5f ? HoloToken.ACCENT : fraction01 > 0.25f ? HoloToken.WARN : HoloToken.DANGER;
    }
}
