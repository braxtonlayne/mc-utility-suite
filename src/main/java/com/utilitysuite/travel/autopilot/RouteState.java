package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.Angles;

import java.util.List;

/**
 * The active route as primitive arrays plus a cursor, engine-free. Built once per run; queries allocate nothing.
 */
public final class RouteState {

    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final boolean[] hasY;
    private final String[] labels;
    private int index;

    public RouteState(List<RouteParser.Stop> stops) {
        int n = stops.size();
        xs = new int[n];
        ys = new int[n];
        zs = new int[n];
        hasY = new boolean[n];
        labels = new String[n];
        for (int i = 0; i < n; i++) {
            RouteParser.Stop s = stops.get(i);
            xs[i] = s.x();
            ys[i] = s.y();
            zs[i] = s.z();
            hasY[i] = s.hasY();
            labels[i] = s.label();
        }
    }

    public int size() {
        return xs.length;
    }

    public int index() {
        return index;
    }

    public boolean isFinalStop() {
        return index >= xs.length - 1;
    }

    /** Moves to the next stop; returns false when the current one was the last. */
    public boolean advance() {
        if (index >= xs.length - 1) {
            return false;
        }
        index++;
        return true;
    }

    public int x() {
        return xs[index];
    }

    public int y() {
        return ys[index];
    }

    public int z() {
        return zs[index];
    }

    public boolean hasY() {
        return hasY[index];
    }

    public String label() {
        return labels[index];
    }

    /** Horizontal distance from (px, pz) to the current stop's block centre. */
    public double horizontalDistance(double px, double pz) {
        double dx = xs[index] + 0.5 - px;
        double dz = zs[index] + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Horizontal distance from (px, pz) to the final stop along the remaining legs. */
    public double remainingDistance(double px, double pz) {
        double d = horizontalDistance(px, pz);
        for (int i = index; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dz = zs[i + 1] - zs[i];
            d += Math.sqrt(dx * dx + dz * dz);
        }
        return d;
    }

    /** Yaw from (px, pz) toward the current stop. */
    public double bearing(double px, double pz) {
        return Angles.yawTo(xs[index] + 0.5 - px, zs[index] + 0.5 - pz);
    }

    /** Yaw of the leg leaving the current stop, or the incoming bearing at the final stop. */
    public double outgoingYaw(double px, double pz) {
        if (isFinalStop()) {
            return bearing(px, pz);
        }
        return Angles.yawTo(xs[index + 1] - xs[index], zs[index + 1] - zs[index]);
    }

    /** Turn angle (absolute degrees) at the current stop toward the next one, or 0 at the final stop. */
    public double cornerTurn(double px, double pz) {
        if (isFinalStop()) {
            return 0.0;
        }
        double in = bearing(px, pz);
        double out = Angles.yawTo(xs[index + 1] - xs[index], zs[index + 1] - zs[index]);
        return Math.abs(Angles.delta(in, out));
    }
}
