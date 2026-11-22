package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Engine-free "Ghost Line" samples: where a player-sized ghost stands about a second ahead of the player, and the
 * polyline (route trail or jump arc) leading to it. Computed once per client tick into preallocated arrays; the
 * renderer interpolates the ghost between the previous and the current tick with the partial tick.
 * <ul>
 *   <li>{@link #updateRoute}: the ghost sits {@code max(speed x leadTicks, MIN_LEAD)} blocks (horizontal arc length)
 *       along the route polyline that starts at the player and runs through the remaining cells (cell centres), clamped
 *       to the route's end.</li>
 *   <li>{@link #updateJump}: the ghost sits at the predicted landing; the arc is a parabola from the player to it whose
 *       apex is {@link #ARC_RISE} above the chord's midpoint.</li>
 * </ul>
 * Both go inactive when the ghost would be closer than {@link #MIN_SHOW} to the player. Nothing is allocated.
 */
public final class GhostLine {

    public static final int NONE = 0;
    public static final int ROUTE = 1;
    public static final int JUMP = 2;
    public static final int MAX_TRAIL = 64;
    public static final double MIN_LEAD = 3.0;
    public static final double MIN_SHOW = 1.5;
    public static final int ARC_SEGMENTS = 16;
    public static final double ARC_RISE = 1.0;

    private final float[] trail = new float[MAX_TRAIL * 3];
    private int trailCount;
    private int mode = NONE;
    private float gx, gy, gz;
    private float pgx, pgy, pgz;

    public int mode() {
        return mode;
    }

    public boolean active() {
        return mode != NONE;
    }

    /** Number of points in {@link #trailAt}. */
    public int trailCount() {
        return trailCount;
    }

    public float trailX(int i) {
        return trail[i * 3];
    }

    public float trailY(int i) {
        return trail[i * 3 + 1];
    }

    public float trailZ(int i) {
        return trail[i * 3 + 2];
    }

    public float ghostX() {
        return gx;
    }

    public float ghostY() {
        return gy;
    }

    public float ghostZ() {
        return gz;
    }

    public void clear() {
        mode = NONE;
        trailCount = 0;
    }

    /** Ghost position between the previous and the current tick (out must hold 3). */
    public void interpolate(float t, float[] out) {
        out[0] = pgx + (gx - pgx) * t;
        out[1] = pgy + (gy - pgy) * t;
        out[2] = pgz + (gz - pgz) * t;
    }

    /**
     * Places the ghost along a route. {@code cells} are packed feet cells ({@link Packed}), {@code index} the first
     * cell still ahead; {@code speed} the player's horizontal speed in blocks per tick.
     */
    public void updateRoute(long[] cells, int count, int index, double px, double py, double pz, double speed,
                            int leadTicks) {
        double target = Math.max(MIN_LEAD, speed * leadTicks);
        trailCount = 0;
        addTrail((float) px, (float) py, (float) pz);
        double cx = px;
        double cy = py;
        double cz = pz;
        double walked = 0.0;
        double ox = px;
        double oy = py;
        double oz = pz;
        boolean placed = false;
        for (int i = Math.max(0, index); i < count && trailCount < MAX_TRAIL - 1; i++) {
            long c = cells[i];
            double nx = Packed.x(c) + 0.5;
            double ny = Packed.y(c);
            double nz = Packed.z(c) + 0.5;
            double dx = nx - cx;
            double dz = nz - cz;
            double seg = Math.sqrt(dx * dx + dz * dz);
            if (seg < 0.3) {
                continue; // the cell the body is standing on
            }
            if (walked + seg >= target) {
                double t = (target - walked) / seg;
                ox = cx + dx * t;
                oy = cy + (ny - cy) * t;
                oz = cz + dz * t;
                addTrail((float) ox, (float) oy, (float) oz);
                placed = true;
                break;
            }
            walked += seg;
            cx = nx;
            cy = ny;
            cz = nz;
            addTrail((float) cx, (float) cy, (float) cz);
            ox = cx;
            oy = cy;
            oz = cz;
        }
        publish(ROUTE, (float) ox, (float) oy, (float) oz, px, py, pz);
    }

    /** Places the ghost at the predicted landing with a parabolic arc from the player. */
    public void updateJump(double px, double py, double pz, double lx, double ly, double lz) {
        trailCount = 0;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double t = (double) i / ARC_SEGMENTS;
            double y = py + (ly - py) * t + 4.0 * ARC_RISE * t * (1.0 - t);
            addTrail((float) (px + (lx - px) * t), (float) y, (float) (pz + (lz - pz) * t));
        }
        publish(JUMP, (float) lx, (float) ly, (float) lz, px, py, pz);
    }

    private void addTrail(float x, float y, float z) {
        int o = trailCount * 3;
        trail[o] = x;
        trail[o + 1] = y;
        trail[o + 2] = z;
        trailCount++;
    }

    private void publish(int newMode, float x, float y, float z, double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        if (dx * dx + dy * dy + dz * dz < MIN_SHOW * MIN_SHOW) {
            clear();
            return;
        }
        if (mode == NONE) {
            pgx = x;
            pgy = y;
            pgz = z;
        } else {
            pgx = gx;
            pgy = gy;
            pgz = gz;
        }
        gx = x;
        gy = y;
        gz = z;
        mode = newMode;
    }
}
