package com.utilitysuite.world.minimap;

/**
 * Engine-agnostic colour transforms for the minimap terrain texels. Input is a 0xRRGGBB map colour plus a shade level
 * (vanilla-map style height shading); output is opaque 0xAARRGGBB. Pure and allocation free.
 */
public final class MinimapPalette {

    /** Visual style of the minimap. */
    public enum Style { MILITARY, CLEAN, CLASSIC }

    public static final int SHADE_DARK = 0;
    public static final int SHADE_NORMAL = 1;
    public static final int SHADE_BRIGHT = 2;

    /** Colour for columns that are not loaded or not sampled yet. */
    public static final int UNKNOWN = 0xFF101410;

    private MinimapPalette() {
    }

    /** Vanilla-map style multiplier: 180 / 220 / 255 out of 255. */
    static int shadeFactor(int shade) {
        return shade == SHADE_DARK ? 180 : shade == SHADE_BRIGHT ? 255 : 220;
    }

    /**
     * Final texel colour.
     *
     * @param rgb   map colour 0xRRGGBB
     * @param water true for water columns (MILITARY/CLEAN use a dedicated teal)
     * @param grid  true when the texel lies on a chunk boundary (only MILITARY draws the grid)
     */
    public static int apply(Style style, int rgb, int shade, boolean water, boolean grid) {
        int f = shadeFactor(shade);
        int r = ((rgb >> 16) & 0xFF) * f / 255;
        int g = ((rgb >> 8) & 0xFF) * f / 255;
        int b = (rgb & 0xFF) * f / 255;
        switch (style) {
            case MILITARY -> {
                if (water) {
                    // Dark teal, still slightly shaded.
                    r = 18 * f / 255;
                    g = 70 * f / 255;
                    b = 72 * f / 255;
                } else {
                    // Fully desaturate to luminance, then tint toward olive drab (terrain reads by brightness).
                    int lum = (r * 77 + g * 150 + b * 29) >> 8;
                    r = lum * 150 >> 8;
                    g = lum * 190 >> 8;
                    b = lum * 105 >> 8;
                    // Darken overall for a tactical look and lift the floor slightly toward green.
                    r = r * 3 / 4 + 6;
                    g = g * 3 / 4 + 14;
                    b = b * 3 / 4 + 4;
                }
                if (grid) {
                    r = (r * 3 + 110) / 4;
                    g = (g * 3 + 150) / 4;
                    b = (b * 3 + 100) / 4;
                }
            }
            case CLEAN -> {
                if (water) {
                    r = 20 * f / 255;
                    g = 40 * f / 255;
                    b = 64 * f / 255;
                } else {
                    int lum = (r * 77 + g * 150 + b * 29) >> 8;
                    // Cool dark glass: compress luminance into a narrow dark range, blue-grey cast.
                    int v = 24 + lum * 96 / 255;
                    r = v * 220 >> 8;
                    g = v * 236 >> 8;
                    b = Math.min(255, v + 10);
                }
            }
            case CLASSIC -> {
                // True map colours with shading only.
            }
        }
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    /** Shade from the height difference to the northern neighbour (vanilla map rule). */
    public static int shadeFor(int height, int northHeight) {
        if (height > northHeight) {
            return SHADE_BRIGHT;
        }
        return height < northHeight ? SHADE_DARK : SHADE_NORMAL;
    }

    /** 0xAARRGGBB to NativeImage's 0xAABBGGRR. */
    public static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;
    }

    /** Multiplies the alpha channel of {@code argb} by {@code opacity} (0..1). */
    public static int withAlpha(int argb, float opacity) {
        int a = (int) (((argb >>> 24) & 0xFF) * opacity);
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
