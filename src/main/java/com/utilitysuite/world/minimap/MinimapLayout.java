package com.utilitysuite.world.minimap;

/**
 * Engine-agnostic placement maths for the minimap. Position is stored as fractions (0..1) of the free space on each
 * axis, so 0 is the left/top margin and 1 the right/bottom margin, and the map stays anchored across window resizes
 * and GUI-scale changes. Size is in GUI pixels, clamped to {@link #MIN_SIZE}..{@link #MAX_SIZE} and to the screen.
 */
public final class MinimapLayout {

    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 320;
    public static final int MARGIN = 4;
    /** Extra room below the map for the bezel ring and the coordinate plate. */
    public static final int BELOW = 16;
    /** Bezel ring thickness outside the map body. */
    public static final int BEZEL = 6;

    private MinimapLayout() {
    }

    /** Largest size that fits the screen. */
    public static int maxSizeFor(int screenW, int screenH) {
        int byW = screenW - 2 * (MARGIN + BEZEL);
        int byH = screenH - 2 * (MARGIN + BEZEL) - BELOW;
        return Math.max(16, Math.min(MAX_SIZE, Math.min(byW, byH)));
    }

    public static int clampSize(int size, int screenW, int screenH) {
        int max = maxSizeFor(screenW, screenH);
        int min = Math.min(MIN_SIZE, max);
        return Math.max(min, Math.min(max, size));
    }

    /** Free span on the x axis for a map of {@code size}. */
    static int spanX(int screenW, int size) {
        return Math.max(0, screenW - size - 2 * (MARGIN + BEZEL));
    }

    static int spanY(int screenH, int size) {
        return Math.max(0, screenH - size - BELOW - 2 * (MARGIN + BEZEL));
    }

    /** Left edge of the map body for a stored x fraction. */
    public static int toX(double frac, int screenW, int size) {
        return MARGIN + BEZEL + (int) Math.round(clamp01(frac) * spanX(screenW, size));
    }

    public static int toY(double frac, int screenH, int size) {
        return MARGIN + BEZEL + (int) Math.round(clamp01(frac) * spanY(screenH, size));
    }

    /** Fraction for a map body left edge (clamped into the screen). */
    public static double toFracX(int x, int screenW, int size) {
        int span = spanX(screenW, size);
        return span == 0 ? 0 : clamp01((x - MARGIN - BEZEL) / (double) span);
    }

    public static double toFracY(int y, int screenH, int size) {
        int span = spanY(screenH, size);
        return span == 0 ? 0 : clamp01((y - MARGIN - BEZEL) / (double) span);
    }

    /** Clamps a dragged left edge so the whole map (with bezel) stays on screen. */
    public static int clampX(int x, int screenW, int size) {
        return MARGIN + BEZEL + Math.max(0, Math.min(spanX(screenW, size), x - MARGIN - BEZEL));
    }

    public static int clampY(int y, int screenH, int size) {
        return MARGIN + BEZEL + Math.max(0, Math.min(spanY(screenH, size), y - MARGIN - BEZEL));
    }

    /**
     * New size while dragging the bottom-right handle: keeps the map square (larger of the two drag extents), clamps
     * to the size range and to the room left between the map's top-left corner and the screen edges.
     */
    public static int resize(int x, int y, int mouseX, int mouseY, int screenW, int screenH) {
        int want = Math.max(mouseX - x, mouseY - y);
        int roomW = screenW - x - MARGIN - BEZEL;
        int roomH = screenH - y - MARGIN - BEZEL - BELOW;
        int max = Math.min(MAX_SIZE, Math.min(roomW, roomH));
        int min = Math.min(MIN_SIZE, Math.max(16, max));
        return Math.max(min, Math.min(max, want));
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
