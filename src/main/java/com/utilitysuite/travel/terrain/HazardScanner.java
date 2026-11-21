package com.utilitysuite.travel.terrain;

import com.utilitysuite.travel.Packed;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Budgeted hazard scan around the player, engine-free. Each tick it classifies at most {@code budget} columns of the
 * square of radius {@code radius} around the sweep origin; a finished sweep is published by swapping the double
 * buffers, so the renderer always sees a complete, stable result. The sweep restarts when the player moved more than
 * {@value #RESWEEP_DISTANCE} blocks from its origin.
 * <p>
 * Per column, relative to the player's feet level {@code fy}:
 * <ul>
 *   <li><b>Drop</b>: no floor at {@code fy-1}, open at {@code fy}, a walkable floor in a 4-neighbour column, and no
 *       solid block within {@code dropThreshold} blocks below the feet level. Marked at {@code fy-1}.</li>
 *   <li><b>Lava</b>: lava anywhere in {@code fy-2 .. fy+1}. Marked at the first lava block.</li>
 *   <li><b>Low ceiling</b>: floor at {@code fy-1}, open at {@code fy}, solid at {@code fy+1} (one-block headroom).
 *       Marked at {@code fy}.</li>
 * </ul>
 * Results are packed positions in reused {@link LongArrayList}s (no allocation once their capacity settled).
 */
public final class HazardScanner {

    public static final int RESWEEP_DISTANCE = 3;
    public static final int MAX_MARKERS_PER_KIND = 1024;

    public static final int DROP = 0;
    public static final int LAVA = 1;
    public static final int LOW_CEILING = 2;
    public static final int KINDS = 3;

    private final LongArrayList[] building = {new LongArrayList(), new LongArrayList(), new LongArrayList()};
    private final LongArrayList[] published = {new LongArrayList(), new LongArrayList(), new LongArrayList()};

    private int radius = 12;
    private int budget = 96;
    private int dropThreshold = 4;

    private boolean sweeping;
    private int originX;
    private int originY;
    private int originZ;
    private int cursor;

    public void configure(int radius, int budget, int dropThreshold) {
        this.radius = Math.max(1, radius);
        this.budget = Math.max(1, budget);
        this.dropThreshold = Math.max(2, dropThreshold);
        sweeping = false;
    }

    /**
     * Lava at the feet or straight ahead, for the danger model: 0 when lava is in the feet column ({@code fy-1 ..
     * fy}), {@code k} when the first lava is {@code k} blocks along the step direction ({@code dx}, {@code dz} each in
     * -1..1), -1 when none within {@code steps}. A solid block at body height ends the look ahead. Allocation free.
     */
    public static int lavaAhead(HazardWorld w, int fx, int fy, int fz, int dx, int dz, int steps) {
        if (w.lava(fx, fy - 1, fz) || w.lava(fx, fy, fz)) {
            return 0;
        }
        if (dx == 0 && dz == 0) {
            return -1;
        }
        for (int k = 1; k <= steps; k++) {
            int x = fx + dx * k;
            int z = fz + dz * k;
            if (!w.loaded(x, z) || w.solid(x, fy, z)) {
                return -1;
            }
            if (w.lava(x, fy - 1, z) || w.lava(x, fy, z)) {
                return k;
            }
        }
        return -1;
    }

    /** Advances the sweep by at most {@code budget} columns. Returns the number of columns classified. */
    public int tick(HazardWorld world, int playerX, int playerFeetY, int playerZ) {
        if (!sweeping || Math.abs(playerX - originX) > RESWEEP_DISTANCE || Math.abs(playerZ - originZ) > RESWEEP_DISTANCE
                || Math.abs(playerFeetY - originY) > 1) {
            startSweep(playerX, playerFeetY, playerZ);
        }
        int side = radius * 2 + 1;
        int total = side * side;
        int done = 0;
        while (done < budget && cursor < total) {
            int x = originX - radius + cursor % side;
            int z = originZ - radius + cursor / side;
            cursor++;
            if (world.loaded(x, z)) {
                classify(world, x, originY, z);
            }
            done++;
        }
        if (cursor >= total) {
            publish();
            startSweep(playerX, playerFeetY, playerZ);
        }
        return done;
    }

    private void startSweep(int x, int fy, int z) {
        sweeping = true;
        originX = x;
        originY = fy;
        originZ = z;
        cursor = 0;
        for (LongArrayList l : building) {
            l.clear();
        }
    }

    private void publish() {
        for (int k = 0; k < KINDS; k++) {
            LongArrayList old = published[k];
            published[k] = building[k];
            building[k] = old;
        }
    }

    void classify(HazardWorld w, int x, int fy, int z) {
        for (int y = fy - 2; y <= fy + 1; y++) {
            if (w.lava(x, y, z)) {
                add(LAVA, x, y, z);
                break;
            }
        }
        boolean floor = w.solid(x, fy - 1, z);
        boolean open = !w.solid(x, fy, z);
        if (floor && open && w.solid(x, fy + 1, z)) {
            add(LOW_CEILING, x, fy, z);
        }
        if (!floor && open && (walkable(w, x + 1, fy, z) || walkable(w, x - 1, fy, z) || walkable(w, x, fy, z + 1)
                || walkable(w, x, fy, z - 1))) {
            boolean deep = true;
            for (int y = fy - 2; y >= fy - dropThreshold; y--) {
                if (w.solid(x, y, z)) {
                    deep = false;
                    break;
                }
            }
            if (deep) {
                add(DROP, x, fy - 1, z);
            }
        }
    }

    private static boolean walkable(HazardWorld w, int x, int fy, int z) {
        return w.solid(x, fy - 1, z) && !w.solid(x, fy, z);
    }

    private void add(int kind, int x, int y, int z) {
        LongArrayList l = building[kind];
        if (l.size() < MAX_MARKERS_PER_KIND) {
            l.add(Packed.pack(x, y, z));
        }
    }

    /** Last completed sweep's markers of a kind (packed positions). Do not modify. */
    public LongArrayList markers(int kind) {
        return published[kind];
    }

    public boolean hasMarkers() {
        return !published[DROP].isEmpty() || !published[LAVA].isEmpty() || !published[LOW_CEILING].isEmpty();
    }

    /** Drops everything (module disabled, world left). */
    public void clear() {
        sweeping = false;
        for (int k = 0; k < KINDS; k++) {
            building[k].clear();
            published[k].clear();
        }
    }
}
