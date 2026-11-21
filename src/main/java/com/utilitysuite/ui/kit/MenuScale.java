package com.utilitysuite.ui.kit;

/**
 * Effective-scale math for the suite menu (engine-free, unit tested).
 *
 * <p>At window GUI scale 3 or 4 the menu window gets only ~400 GUI px, below the three-column layout. The menu
 * therefore renders at an effective integer scale of {@value #TARGET} when the window GUI scale is
 * {@value #MIN_GUI_SCALE} or more: it lays out against a virtual size (window px / 2), scales the pose stack by
 * {@code 2 / guiScale} and converts every incoming mouse coordinate with {@link #toVirtual}. Scissor rectangles
 * are given in virtual px and converted back to GUI px with {@link #scissorMin}/{@link #scissorMax}.
 *
 * <p>{@link #active()} is the factor in effect for the current frame (1 outside the menu); the menu sets it for the
 * duration of its render pass so the shared clip helpers can convert without knowing who called them.
 */
public final class MenuScale {

    /** The effective integer scale the menu uses at high GUI scales. */
    public static final int TARGET = 2;
    /** The window GUI scale from which the effective scale applies. */
    public static final int MIN_GUI_SCALE = 3;

    private static double active = 1.0;

    private MenuScale() {
    }

    /** True when the menu should render at the effective scale for this window GUI scale. */
    public static boolean applies(double guiScale) {
        return guiScale >= MIN_GUI_SCALE;
    }

    /** Pose scale factor (virtual px to GUI px): {@code 2 / guiScale}, or 1 when the effective scale is off. */
    public static double factor(double guiScale) {
        return applies(guiScale) ? TARGET / guiScale : 1.0;
    }

    /**
     * The virtual layout size for one window axis: {@code ceil(windowPx / 2)} when the effective scale applies,
     * else the vanilla GUI size {@code ceil(windowPx / guiScale)}.
     */
    public static int virtualSize(int windowPx, double guiScale) {
        double s = applies(guiScale) ? TARGET : Math.max(1.0, guiScale);
        int v = (int) (windowPx / s);
        return windowPx / s > v ? v + 1 : v;
    }

    /** Converts a GUI-px mouse coordinate into the menu's virtual px. */
    public static double toVirtual(double guiCoord, double guiScale) {
        return applies(guiScale) ? guiCoord * guiScale / TARGET : guiCoord;
    }

    /** Converts a virtual-px coordinate back into GUI px (e.g. for a harness that clicks like a user). */
    public static double toGui(double virtualCoord, double guiScale) {
        return applies(guiScale) ? virtualCoord * TARGET / guiScale : virtualCoord;
    }

    /** Scissor start edge: virtual px to GUI px, rounded outward (down). */
    public static int scissorMin(int virtual, double factor) {
        return (int) Math.floor(virtual * factor);
    }

    /** Scissor end edge: virtual px to GUI px, rounded outward (up). */
    public static int scissorMax(int virtual, double factor) {
        return (int) Math.ceil(virtual * factor - 1e-9);
    }

    /** The factor in effect for the current render pass (1 outside a scaled menu). */
    public static double active() {
        return active;
    }

    /** Sets the factor for the current render pass; call {@link #reset()} when the pass ends. */
    public static void setActive(double factor) {
        active = factor > 0 ? factor : 1.0;
    }

    public static void reset() {
        active = 1.0;
    }
}
