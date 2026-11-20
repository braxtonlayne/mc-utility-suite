package com.utilitysuite.travel.terrain;

/**
 * SafeWalk's edge clamp: the same back-off algorithm vanilla uses while sneaking ({@code Player.maybeBackOffFromEdge}),
 * with the "can fall" test delegated to a {@link SupportProbe} so the drop that counts as an edge is configurable.
 * <p>
 * Each horizontal component is reduced in {@value #STEP}-block steps until the moved box keeps support; a component
 * that would end at or below one step is zeroed. Results are written to {@link #x()} / {@link #z()} so the hot path
 * allocates nothing.
 */
public final class EdgeClamp {

    public static final double STEP = 0.05;

    private double outX;
    private double outZ;

    /**
     * Clamps the movement (dx, dz). Returns true when it was changed; read the result from {@link #x()} / {@link #z()}.
     */
    public boolean clamp(double dx, double dz, SupportProbe probe) {
        double x = dx;
        double z = dz;
        double stepX = Math.signum(x) * STEP;
        double stepZ = Math.signum(z) * STEP;
        while (x != 0.0 && !probe.hasSupport(x, 0.0)) {
            if (Math.abs(x) <= STEP) {
                x = 0.0;
                break;
            }
            x -= stepX;
        }
        while (z != 0.0 && !probe.hasSupport(0.0, z)) {
            if (Math.abs(z) <= STEP) {
                z = 0.0;
                break;
            }
            z -= stepZ;
        }
        while (x != 0.0 && z != 0.0 && !probe.hasSupport(x, z)) {
            if (Math.abs(x) <= STEP) {
                x = 0.0;
            } else {
                x -= stepX;
            }
            if (Math.abs(z) <= STEP) {
                z = 0.0;
            } else {
                z -= stepZ;
            }
        }
        outX = x;
        outZ = z;
        return x != dx || z != dz;
    }

    public double x() {
        return outX;
    }

    public double z() {
        return outZ;
    }

    /**
     * Edge Jump trigger: true when the player is currently supported but the movement over the next
     * {@code lookaheadTicks} ticks would leave support (the last tick before walking off the edge).
     */
    public static boolean atEdge(SupportProbe probe, double vx, double vz, double lookaheadTicks) {
        if (vx * vx + vz * vz < 1.0e-6) {
            return false;
        }
        return probe.hasSupport(0.0, 0.0) && !probe.hasSupport(vx * lookaheadTicks, vz * lookaheadTicks);
    }
}
