package com.utilitysuite.travel.bridge;

import com.utilitysuite.travel.Packed;

/**
 * Walk-mode bridging, engine-free: which deck cells must be filled, in order, for the player to keep walking where they
 * are going. The path runs from the player's position along the horizontal velocity for {@code lookaheadTicks}, plus
 * {@code edgeMargin} blocks further (so a block appears before the feet reach the edge). It is sampled finely; every
 * replaceable deck cell it crosses is a candidate, in path order. A diagonal step between two cells that share only an
 * edge gets the in-between cell first, so every candidate shares a face with the one before it (or with the deck the
 * player stands on) and can be clicked against once that one exists.
 * <p>
 * Zero allocation: results go to reused arrays; read them with {@link #cell(int)} until the next {@link #plan} call.
 */
public final class WalkBridgePlanner {

    /** World view at the deck level. */
    public interface Cells {
        /** True when the cell can take a block (air, grass, fluid...). */
        boolean replaceable(int x, int y, int z);
    }

    public static final int MAX = 12;
    private static final double SAMPLE_STEP = 0.2;

    private final long[] cells = new long[MAX];
    private int count;

    /**
     * @param x, z           player position (feet centre)
     * @param vx, vz         horizontal velocity per tick
     * @param deckY          the y of the deck cells (the block level under the feet)
     * @param lookaheadTicks how many ticks of travel to cover (0 = only the edge margin)
     * @param edgeMargin     extra distance past the predicted position, in blocks (also used when standing still
     *                       with a direction: pass a unit-length {@code vx, vz} and 0 ticks)
     * @return the number of cells, readable with {@link #cell(int)}
     */
    public int plan(Cells w, double x, double z, double vx, double vz, int deckY, int lookaheadTicks,
                    double edgeMargin) {
        count = 0;
        double speed = Math.sqrt(vx * vx + vz * vz);
        // the cell under the player first (stepping off an edge while standing on its rim)
        int lastX = floor(x);
        int lastZ = floor(z);
        consider(w, lastX, deckY, lastZ);
        if (speed < 1e-4) {
            return count;
        }
        double ux = vx / speed, uz = vz / speed;
        double length = speed * Math.max(0, lookaheadTicks) + Math.max(0.0, edgeMargin);
        int steps = (int) Math.ceil(length / SAMPLE_STEP);
        for (int i = 1; i <= steps && count < MAX; i++) {
            double d = Math.min(length, i * SAMPLE_STEP);
            int cx = floor(x + ux * d);
            int cz = floor(z + uz * d);
            if (cx == lastX && cz == lastZ) {
                continue;
            }
            if (cx != lastX && cz != lastZ) {
                // crossed a corner: fill the cell the path passed nearest to, so the next one has a face to click
                double fx = x + ux * d - cx, fz = z + uz * d - cz; // position inside the new cell
                boolean xFirst = (ux > 0 ? fx : 1 - fx) > (uz > 0 ? fz : 1 - fz);
                if (xFirst) {
                    consider(w, cx, deckY, lastZ);
                } else {
                    consider(w, lastX, deckY, cz);
                }
            }
            consider(w, cx, deckY, cz);
            lastX = cx;
            lastZ = cz;
        }
        return count;
    }

    public long cell(int i) {
        return cells[i];
    }

    public int count() {
        return count;
    }

    private void consider(Cells w, int x, int y, int z) {
        if (count >= MAX || !w.replaceable(x, y, z)) {
            return;
        }
        long p = Packed.pack(x, y, z);
        for (int i = 0; i < count; i++) {
            if (cells[i] == p) {
                return;
            }
        }
        cells[count++] = p;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
