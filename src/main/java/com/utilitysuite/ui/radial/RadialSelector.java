package com.utilitysuite.ui.radial;

/**
 * Flick-gesture slice selection for the radial wheel, engine free. The mouse delta (screen convention: +x right,
 * +y down) is accumulated into a virtual stick; once it is longer than the threshold the slice is the one its direction
 * points at. Slice 0 is straight up, then clockwise in steps of 360/slices degrees, each slice centred on its
 * direction. The stick is clamped to twice the threshold so a flick the other way re-selects quickly instead of first
 * unwinding a long earlier motion. Primitive state only: allocation free.
 */
public final class RadialSelector {

    private final int slices;
    private double threshold;
    private double x;
    private double y;

    public RadialSelector(int slices, double threshold) {
        if (slices < 2) {
            throw new IllegalArgumentException("slices < 2");
        }
        this.slices = slices;
        this.threshold = Math.max(1.0, threshold);
    }

    public int slices() {
        return slices;
    }

    public void setThreshold(double threshold) {
        this.threshold = Math.max(1.0, threshold);
    }

    public double threshold() {
        return threshold;
    }

    /** Centres the stick (wheel opened). */
    public void reset() {
        x = 0;
        y = 0;
    }

    /** Adds a mouse delta and returns the selected slice, or -1 while the stick is inside the threshold. */
    public int add(double dx, double dy) {
        x += dx;
        y += dy;
        double len = Math.sqrt(x * x + y * y);
        double cap = threshold * 2.0;
        if (len > cap) {
            x = x / len * cap;
            y = y / len * cap;
        }
        return selected();
    }

    /** The slice the stick points at, or -1 inside the threshold. */
    public int selected() {
        return sliceFor(x, y, threshold, slices);
    }

    /** Pure function: the slice for a stick vector (screen convention), or -1 when shorter than {@code threshold}. */
    public static int sliceFor(double x, double y, double threshold, int slices) {
        if (x * x + y * y < threshold * threshold) {
            return -1;
        }
        // angle clockwise from straight up, in [0, 360)
        double deg = Math.toDegrees(Math.atan2(x, -y));
        if (deg < 0) {
            deg += 360.0;
        }
        double width = 360.0 / slices;
        int s = (int) Math.floor((deg + width / 2.0) / width);
        return s % slices;
    }

    /** Centre angle of a slice in degrees clockwise from straight up (for drawing). */
    public static double centreDeg(int slice, int slices) {
        return slice * (360.0 / slices);
    }
}
