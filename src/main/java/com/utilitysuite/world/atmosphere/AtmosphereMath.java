package com.utilitysuite.world.atmosphere;

/**
 * Engine-free colour maths for the Atmosphere studio: packed {@code 0xRRGGBB} colours, linear blends, a time-of-day
 * colour ramp, the per-biome tint table and the warm-lift strength. No Minecraft types, no allocation in the per-frame
 * helpers (the ramp object is built on settings changes only).
 */
public final class AtmosphereMath {

    /** "No tint" marker for {@link #biomeTint(String)}. */
    public static final int NO_TINT = -1;
    /** Warm amber used by the Warm Twilight preset. */
    public static final int WARM = 0xF0A060;

    private AtmosphereMath() {
    }

    public static int r(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int g(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int b(int rgb) {
        return rgb & 0xFF;
    }

    public static int rgb(int r, int g, int b) {
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static float clamp01(float t) {
        return t < 0f ? 0f : Math.min(1f, t);
    }

    /** Linear blend of two packed colours, {@code t} clamped to 0..1 (0 = a). */
    public static int lerpRgb(int a, int b, float t) {
        float k = clamp01(t);
        return rgb(Math.round(r(a) + (r(b) - r(a)) * k), Math.round(g(a) + (g(b) - g(a)) * k),
                Math.round(b(a) + (b(b) - b(a)) * k));
    }

    /** Blends one 0..1 channel toward a 0..255 target channel (fog colour events work in floats). */
    public static float blendChannel(float vanilla, float target255, float t) {
        return vanilla + (target255 / 255f - vanilla) * clamp01(t);
    }

    /** Parses {@code #RRGGBB}, {@code RRGGBB} or {@code 0xRRGGBB}; anything else gives {@code fallback}. */
    public static int parseHex(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        if (t.isEmpty() || t.length() > 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Fraction of vanilla's night-vision brightening the Warm Twilight preset applies (0..1): a partial lift, never
     * the flat full-strength fullbright.
     */
    public static float lightLift(float strength) {
        return 0.6f * clamp01(strength);
    }

    /**
     * Per-biome tint by registry path keyword (e.g. {@code "snowy_taiga"}), or {@link #NO_TINT} for biomes left as
     * vanilla. Called only when the biome under the player changes.
     */
    public static int biomeTint(String p) {
        if (p == null) {
            return NO_TINT;
        }
        if (p.contains("deep_dark")) return 0x203048;
        if (p.contains("crimson")) return 0xA03030;
        if (p.contains("warped")) return 0x307070;
        if (p.contains("soul_sand")) return 0x6090A0;
        if (p.contains("basalt")) return 0x605860;
        if (p.contains("nether")) return 0xA04020;
        if (p.startsWith("end_") || p.equals("the_end") || p.contains("small_end")) return 0x806090;
        if (p.contains("cherry")) return 0xF0B0D0;
        if (p.contains("mushroom")) return 0xC090C0;
        if (p.contains("dark_forest")) return 0x506048;
        if (p.contains("swamp") || p.contains("mangrove")) return 0x7A9A5A;
        if (p.contains("jungle") || p.contains("bamboo")) return 0x6FC070;
        if (p.contains("desert") || p.contains("badlands") || p.contains("savanna")) return 0xE8B070;
        if (p.contains("snow") || p.contains("frozen") || p.contains("ice") || p.contains("grove")
                || p.contains("peaks")) return 0xBFD8F0;
        if (p.contains("ocean") || p.contains("river") || p.contains("beach")) return 0x80B0E0;
        return NO_TINT;
    }

    /**
     * Vanilla's sky brightness over the day for a {@code Level.getTimeOfDay} value (0..1, 0 = noon): 1 by day, 0 at
     * night, a short ramp at dawn and dusk. Same curve as {@code ClientLevel.getSkyColor} uses.
     */
    public static float daylight(float timeOfDay) {
        return clamp01((float) Math.cos(timeOfDay * (float) (Math.PI * 2)) * 2.0f + 0.5f);
    }

    /** Each channel of {@code rgb} times {@code k} (clamped to 0..255). */
    public static int scaleRgb(int rgb, float k) {
        return rgb(Math.round(r(rgb) * k), Math.round(g(rgb) * k), Math.round(b(rgb) * k));
    }

    /**
     * Cloud colour that goes with a sky target: the sky colour washed most of the way to white (clouds stay clouds),
     * dimmed by daylight the way vanilla dims white clouds (0.1 floor at night).
     */
    public static int cloudFor(int skyRgb, float daylight) {
        int washed = lerpRgb(skyRgb, 0xFFFFFF, 0.6f);
        return scaleRgb(washed, clamp01(daylight) * 0.9f + 0.1f);
    }

    /** Sky tint weight under weather: rain and thunder pull it back toward vanilla's grey (keeps storms stormy). */
    public static float weatherDamp(float weight, float rain, float thunder) {
        return clamp01(weight) * (1f - 0.6f * clamp01(rain)) * (1f - 0.5f * clamp01(thunder));
    }

    /** Exponential approach of {@code cur} toward {@code target} by {@code rate} (0..1) per step. */
    public static float approach(float cur, float target, float rate) {
        return cur + (target - cur) * rate;
    }

    /**
     * A colour ramp over the day (ticks 0..23999), sampled by linear interpolation with wrap-around between the last
     * and first key. Immutable after construction; {@link #sample} does not allocate.
     */
    public static final class ColorRamp {
        private final int[] times;
        private final int[] colors;

        /** Keys strictly increasing by time, all in 0..23999, at least one. */
        public ColorRamp(int[] times, int[] colors) {
            if (times.length == 0 || times.length != colors.length) {
                throw new IllegalArgumentException("ramp needs matching, non-empty keys");
            }
            for (int i = 1; i < times.length; i++) {
                if (times[i] <= times[i - 1]) {
                    throw new IllegalArgumentException("ramp keys must be strictly increasing");
                }
            }
            this.times = times.clone();
            this.colors = colors.clone();
        }

        /** Dawn (0), noon (6000), dusk (12000), midnight (18000). */
        public static ColorRamp fourKey(int dawn, int day, int dusk, int night) {
            return new ColorRamp(new int[] {0, 6000, 12000, 18000}, new int[] {dawn, day, dusk, night});
        }

        public int sample(long dayTime) {
            int n = times.length;
            if (n == 1) {
                return colors[0];
            }
            int t = (int) Math.floorMod(dayTime, 24000L);
            for (int i = 0; i < n; i++) {
                int next = i + 1 == n ? 0 : i + 1;
                int t0 = times[i];
                int t1 = next == 0 ? times[0] + 24000 : times[next];
                int tt = t < t0 ? t + 24000 : t;
                if (tt >= t0 && tt < t1) {
                    return lerpRgb(colors[i], colors[next], (tt - t0) / (float) (t1 - t0));
                }
            }
            return colors[0];
        }
    }

    /** A tint that eases toward a target colour and weight once per tick (biome-aware blending). */
    public static final class BlendedTint {
        public float r;
        public float g;
        public float b;
        /** 0 = no tint applied. */
        public float weight;

        /** One step toward {@code target} ({@link #NO_TINT} eases the weight to 0, keeping the colour). */
        public void step(int target, float rate) {
            if (target == NO_TINT) {
                weight = approach(weight, 0f, rate);
                return;
            }
            if (weight < 0.001f) {
                r = AtmosphereMath.r(target);
                g = AtmosphereMath.g(target);
                b = AtmosphereMath.b(target);
            } else {
                r = approach(r, AtmosphereMath.r(target), rate);
                g = approach(g, AtmosphereMath.g(target), rate);
                b = approach(b, AtmosphereMath.b(target), rate);
            }
            weight = approach(weight, 1f, rate);
        }

        public void reset() {
            r = 0f;
            g = 0f;
            b = 0f;
            weight = 0f;
        }
    }
}
