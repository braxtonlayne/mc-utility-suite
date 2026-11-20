package com.utilitysuite.world.overlays;

/**
 * Overlay readability: the focus layer and opacity multipliers shared by the X-Ray Filters renderer and the OreSim
 * prediction ghosts. Pure Java; everything here is a float multiply on a packed ARGB int (no allocation per frame).
 */
public final class OverlayStyle {

    /** Which overlay layer stays at full strength; the others draw at {@link #DIM}. */
    public enum Focus { ALL, ORES, CAVES, FLUIDS, PREDICTIONS }

    /** Alpha multiplier for layers that are not the focus layer. */
    public static final float DIM = 0.25f;

    /** Alpha multiplier for OreSim prediction ghosts, published by the X-Ray Filters module (1 = unchanged). */
    private static volatile float predictionScale = 1f;

    private OverlayStyle() {
    }

    /** Alpha multiplier for {@code layer}: the focus dim times the given opacity (0..1). */
    public static float layerScale(Focus focus, Focus layer, float opacity) {
        return (focus == Focus.ALL || focus == layer ? 1f : DIM) * opacity;
    }

    /** {@code argb} with its alpha multiplied by {@code scale} (clamped 0..1); unchanged when {@code scale >= 1}. */
    public static int scaleAlpha(int argb, float scale) {
        if (scale >= 1f) {
            return argb;
        }
        float s = scale <= 0f ? 0f : scale;
        int a = (int) (((argb >>> 24) & 0xFF) * s + 0.5f);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static float predictionScale() {
        return predictionScale;
    }

    static void setPredictionScale(float scale) {
        predictionScale = scale;
    }
}
