package com.utilitysuite.world.atlas;

/**
 * Engine-free, fixed-capacity pin list for the atlas layers (waypoints, portal links, seed locator, seed diff). Rebuilt
 * from the layer data APIs on open and about once a second, never per frame; the screen writes each pin's projected
 * screen position into {@link #screenX}/{@link #screenY} every frame (no allocation) and hit-tests against them.
 */
public final class AtlasPins {

    public static final int WAYPOINT = 0;
    public static final int PORTAL_LINK = 1;
    public static final int STRUCTURE = 2;
    public static final int SLIME = 3;
    public static final int ANOMALY = 4;
    public static final int KINDS = 5;

    public final int capacity;
    public final double[] x;
    public final double[] y;
    public final double[] z;
    public final int[] kind;
    public final int[] argb;
    public final String[] label;
    public final double[] screenX;
    public final double[] screenY;
    public final boolean[] visible;
    private int count;

    public AtlasPins(int capacity) {
        this.capacity = capacity;
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        kind = new int[capacity];
        argb = new int[capacity];
        label = new String[capacity];
        screenX = new double[capacity];
        screenY = new double[capacity];
        visible = new boolean[capacity];
    }

    public int count() {
        return count;
    }

    public void clear() {
        for (int i = 0; i < count; i++) {
            label[i] = null;
        }
        count = 0;
    }

    /** Adds a pin; false when full. */
    public boolean add(int k, double px, double py, double pz, int color, String text) {
        if (count >= capacity) {
            return false;
        }
        kind[count] = k;
        x[count] = px;
        y[count] = py;
        z[count] = pz;
        argb[count] = color;
        label[count] = text;
        visible[count] = true;
        count++;
        return true;
    }

    /**
     * Index of the visible pin nearest to (mx, my) within {@code radius} pixels on its projected position, or -1.
     * Layers switched off in {@code kindMask} (bit per kind) are skipped.
     */
    public int hitTest(double mx, double my, double radius, int kindMask) {
        int best = -1;
        double bestD2 = radius * radius;
        for (int i = 0; i < count; i++) {
            if (!visible[i] || (kindMask & (1 << kind[i])) == 0) {
                continue;
            }
            double dx = screenX[i] - mx;
            double dy = screenY[i] - my;
            double d2 = dx * dx + dy * dy;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }
}
