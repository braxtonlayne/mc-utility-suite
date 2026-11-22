package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Corridor geometry for {@link RouteFollower}: can the body walk in a straight line from where it stands to a route
 * cell? The line is sampled every {@link #STEP} blocks; at each sample the column under the body centre must be
 * standable at the current level (one step up or one step down is allowed, a deeper drop only where the route itself
 * drops), and every column the 0.6-wide body box overlaps must be body-clear with floor at most two blocks below
 * (a wall or a hole beside the line fails it, so corners next to walls and holes are not cut).
 * <p>
 * Engine-free, allocation free; samples are counted against a per-update budget.
 */
final class RouteCorridor {

    /** Sample spacing along the line (blocks). */
    static final double STEP = 0.25;
    /** Body half width, a hair inside 0.3 so a box exactly on a block boundary does not count the next column. */
    static final double HALF = 0.299;

    private int samples;
    private int budget = Integer.MAX_VALUE;

    void begin(int sampleBudget) {
        samples = 0;
        budget = sampleBudget;
    }

    int samples() {
        return samples;
    }

    boolean exhausted() {
        return samples >= budget;
    }

    /**
     * Straight walk from (sx, sz) with feet at level {@code sy} to the centre of route cell {@code ti}; drops deeper
     * than one block are allowed only onto route cells with index in [{@code from}, {@code ti}].
     */
    boolean straight(double sx, double sz, int sy, long[] route, int from, int ti, MovementPhysics.Voxels w) {
        long target = route[ti];
        double tx = Packed.x(target) + 0.5;
        double tz = Packed.z(target) + 0.5;
        int ty = Packed.y(target);
        double dx = tx - sx;
        double dz = tz - sz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int n = Math.max(1, (int) Math.ceil(dist / STEP));
        int ly = sy;
        for (int s = 1; s <= n; s++) {
            if (samples >= budget) {
                return false;
            }
            samples++;
            double t = (double) s / n;
            double px = sx + dx * t;
            double pz = sz + dz * t;
            int cx = MovementPhysics.floor(px);
            int cz = MovementPhysics.floor(pz);
            int prev = ly;
            if (standable(cx, ly, cz, w)) {
                // level
            } else if (standable(cx, ly + 1, cz, w) && !w.solid(cx, ly + 2, cz)) {
                ly++; // a one-block step up (a jump on the run)
            } else if (standable(cx, ly - 1, cz, w)) {
                ly--;
            } else {
                int d = 2;
                for (; d <= 3; d++) {
                    if (standable(cx, ly - d, cz, w)) {
                        break;
                    }
                    if (w.solid(cx, ly - d, cz)) {
                        d = 4;
                        break;
                    }
                }
                if (d > 3 || indexOf(route, from, ti, cx, ly - d, cz) < 0) {
                    return false; // a drop the route does not take, a hole or a wall
                }
                ly -= d;
            }
            int top = Math.max(prev, ly) + 1;
            if (!sideClear(MovementPhysics.floor(px - HALF), MovementPhysics.floor(pz - HALF), cx, cz, ly, top, w)
                    || !sideClear(MovementPhysics.floor(px + HALF), MovementPhysics.floor(pz - HALF), cx, cz, ly, top, w)
                    || !sideClear(MovementPhysics.floor(px - HALF), MovementPhysics.floor(pz + HALF), cx, cz, ly, top, w)
                    || !sideClear(MovementPhysics.floor(px + HALF), MovementPhysics.floor(pz + HALF), cx, cz, ly, top, w)) {
                return false;
            }
        }
        return ly == ty;
    }

    /** A column the body box overlaps beside the centre one: clear with floor close below, or a step it stands on. */
    private static boolean sideClear(int bx, int bz, int cx, int cz, int ly, int top, MovementPhysics.Voxels w) {
        if (bx == cx && bz == cz) {
            return true;
        }
        if (passable(bx, ly, bz, w) && (w.solid(bx, ly - 1, bz) || w.solid(bx, ly - 2, bz))) {
            return true;
        }
        for (int y = ly + 1; y <= top; y++) {
            if (standable(bx, y, bz, w)) {
                return true;
            }
        }
        return false;
    }

    /** Index of the route cell (x, y, z) within [from, to], or -1. */
    static int indexOf(long[] route, int from, int to, int x, int y, int z) {
        long key = Packed.pack(x, y, z);
        for (int i = Math.max(0, from); i <= to; i++) {
            if (route[i] == key) {
                return i;
            }
        }
        return -1;
    }

    static boolean passable(int x, int y, int z, MovementPhysics.Voxels w) {
        return !w.solid(x, y, z) && !w.solid(x, y + 1, z);
    }

    static boolean standable(int x, int y, int z, MovementPhysics.Voxels w) {
        return w.solid(x, y - 1, z) && passable(x, y, z, w);
    }
}
