package com.utilitysuite.travel.navigator;

import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.MovementPhysics;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Bounded voxel A* for the Baritone-free navigator: walks, diagonal walks, one-block step-ups, drops of up to
 * {@value #MAX_DROP} blocks and one-block gap jumps, inside a box of {@value #MAX_RADIUS} blocks around the start.
 * It never breaks or places blocks. Engine-free: it reads {@link MovementPhysics.Voxels} (full-cube collision) and an
 * optional {@link Hazards} cost hook, so it is unit-tested on small voxel grids.
 * <p>
 * Incremental and budgeted: {@link #begin} then {@link #step} with a per-call expansion budget (the navigator calls it
 * once per client tick) until the result is no longer {@link Result#SEARCHING}. All arrays are preallocated; a search
 * allocates nothing (the index map's {@code clear()} keeps its table). Not thread-safe; one search at a time.
 */
public final class LocalPathPlanner {

    public enum Result {
        IDLE,
        SEARCHING,
        /** A route that ends inside the goal range. */
        FOUND,
        /**
         * The goal lies outside the search box, or the node budget ran out: the route ends at the explored cell
         * closest to the goal (it always makes progress; otherwise the result is {@link #FAILED}).
         */
        PARTIAL,
        /** The goal is inside the search box but no walk reaches it, or the start cannot move closer. */
        FAILED
    }

    /** Extra cost of standing at a feet cell; {@link #FORBIDDEN} keeps the search out (lava, fire, cactus). */
    @FunctionalInterface
    public interface Hazards {
        int FORBIDDEN = -1;

        /** @return 0 for a plain cell, a positive extra cost (water, cobweb), or {@link #FORBIDDEN}. Allocation free. */
        int cost(int x, int y, int z);

        Hazards NONE = (x, y, z) -> 0;
    }

    /** Horizontal half-size of the search box around the start (Chebyshev). */
    public static final int MAX_RADIUS = 64;
    /** Vertical half-size of the search box. */
    public static final int MAX_VERTICAL = 32;
    /** Deepest drop taken (more would hurt: fall damage starts after three blocks). */
    public static final int MAX_DROP = 3;
    /** Default cap on expanded nodes per search. */
    public static final int DEFAULT_NODE_BUDGET = 12_000;
    /** Discovered-node capacity (arrays are sized once). */
    public static final int MAX_NODES = 40_000;
    /** Longest route returned (start included); longer routes are cut and the rest is replanned later. */
    public static final int MAX_PATH = 256;

    static final int WALK = 10;
    static final int DIAG = 14;
    static final int STEP_UP = 22;
    static final int DROP_PER_BLOCK = 6;
    static final int GAP_JUMP = 34;

    private static final int[] CARD_X = {1, -1, 0, 0};
    private static final int[] CARD_Z = {0, 0, 1, -1};
    private static final int[] DIAG_X = {1, 1, -1, -1};
    private static final int[] DIAG_Z = {1, -1, 1, -1};

    private final long[] pos = new long[MAX_NODES];
    private final int[] g = new int[MAX_NODES];
    private final int[] f = new int[MAX_NODES];
    private final int[] h = new int[MAX_NODES];
    private final int[] parent = new int[MAX_NODES];
    private final int[] heapAt = new int[MAX_NODES];
    private final boolean[] closed = new boolean[MAX_NODES];
    private final int[] heap = new int[MAX_NODES];
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap(4096);
    private final long[] path = new long[MAX_PATH];

    private MovementPhysics.Voxels v;
    private Hazards hazards = Hazards.NONE;
    private int count;
    private int heapSize;
    private int expanded;
    private int budget;
    private int sx, sy, sz;
    private int gx, gy, gz;
    private int range;
    private int best;
    private int pathLength;
    private Result result = Result.IDLE;

    public LocalPathPlanner() {
        index.defaultReturnValue(-1);
    }

    /**
     * Starts a search from the feet cell {@code start} to within {@code range} blocks (horizontal; vertical within
     * {@code max(1, range)}) of {@code goal}. Both are {@link Packed} cells. Replaces any running search.
     */
    public void begin(MovementPhysics.Voxels voxels, Hazards hz, long start, long goal, int range, int nodeBudget) {
        this.v = voxels;
        this.hazards = hz == null ? Hazards.NONE : hz;
        this.range = Math.max(0, range);
        this.budget = Math.max(1, Math.min(nodeBudget, MAX_NODES));
        sx = Packed.x(start);
        sy = Packed.y(start);
        sz = Packed.z(start);
        gx = Packed.x(goal);
        gy = Packed.y(goal);
        gz = Packed.z(goal);
        index.clear();
        count = 0;
        heapSize = 0;
        expanded = 0;
        pathLength = 0;
        int s = add(start, sx, sy, sz, 0, -1);
        best = s;
        result = Result.SEARCHING;
    }

    /** Re-binds the voxel view (the game side swaps its per-tick cache) without restarting the search. */
    public void rebind(MovementPhysics.Voxels voxels) {
        this.v = voxels;
    }

    /** Expands up to {@code maxExpansions} nodes; returns the result so far. Allocation free. */
    public Result step(int maxExpansions) {
        if (result != Result.SEARCHING) {
            return result;
        }
        for (int n = 0; n < maxExpansions; n++) {
            if (heapSize == 0) {
                return finish(goalInBox() ? Result.FAILED : Result.PARTIAL);
            }
            if (expanded >= budget || count >= MAX_NODES - 16) {
                return finish(Result.PARTIAL);
            }
            int cur = pop();
            closed[cur] = true;
            expanded++;
            long p = pos[cur];
            int x = Packed.x(p);
            int y = Packed.y(p);
            int z = Packed.z(p);
            if (isGoal(x, y, z)) {
                best = cur;
                return finish(Result.FOUND);
            }
            if (h[cur] < h[best] || (h[cur] == h[best] && g[cur] < g[best])) {
                best = cur;
            }
            expand(cur, x, y, z);
        }
        return result;
    }

    /** Runs the whole search at once (tests, small searches). */
    public Result run() {
        while (step(4096) == Result.SEARCHING) {
            // keep going
        }
        return result;
    }

    public Result result() {
        return result;
    }

    /** Nodes expanded by the current/last search. */
    public int expanded() {
        return expanded;
    }

    /** Route length in cells (start first) after FOUND / PARTIAL; 0 otherwise. */
    public int pathLength() {
        return pathLength;
    }

    public long pathCell(int i) {
        return path[i];
    }

    /** Copies the route into {@code out}; returns the number of cells written. */
    public int copyPath(long[] out) {
        int n = Math.min(pathLength, out.length);
        System.arraycopy(path, 0, out, 0, n);
        return n;
    }

    public void reset() {
        result = Result.IDLE;
        pathLength = 0;
        heapSize = 0;
        v = null;
    }

    // ---- search -----------------------------------------------------------------------------------------------------

    private Result finish(Result r) {
        if (r == Result.PARTIAL && (best == 0 || h[best] >= h[0])) {
            r = Result.FAILED; // explored nothing closer than the start
        }
        result = r;
        if (r == Result.FOUND || r == Result.PARTIAL) {
            buildPath(best);
        } else {
            pathLength = 0;
        }
        v = null; // drop the world view
        return r;
    }

    private boolean goalInBox() {
        return Math.abs(gx - sx) <= MAX_RADIUS && Math.abs(gz - sz) <= MAX_RADIUS && Math.abs(gy - sy) <= MAX_VERTICAL;
    }

    private boolean isGoal(int x, int y, int z) {
        int dx = x - gx;
        int dz = z - gz;
        int vr = Math.max(1, range);
        return dx * dx + dz * dz <= range * range && Math.abs(y - gy) <= vr;
    }

    private void expand(int cur, int x, int y, int z) {
        boolean headroom = passable(x, y + 2, z);
        for (int d = 0; d < 4; d++) {
            int dx = CARD_X[d];
            int dz = CARD_Z[d];
            int nx = x + dx;
            int nz = z + dz;
            if (clear2(nx, y, nz)) {
                if (v.solid(nx, y - 1, nz)) {
                    relax(cur, nx, y, nz, WALK);
                } else {
                    // no floor ahead: drop down, or jump the one-block gap
                    for (int k = 1; k <= MAX_DROP; k++) {
                        int fy = y - k;
                        if (!passable(nx, fy, nz)) {
                            break;
                        }
                        if (v.solid(nx, fy - 1, nz)) {
                            relax(cur, nx, fy, nz, WALK + DROP_PER_BLOCK * k);
                            break;
                        }
                    }
                    int jx = x + 2 * dx;
                    int jz = z + 2 * dz;
                    if (headroom && passable(nx, y + 2, nz) && standable(jx, y, jz) && passable(jx, y + 2, jz)) {
                        relax(cur, jx, y, jz, GAP_JUMP);
                    }
                }
            } else if (headroom && v.solid(nx, y, nz) && clear2(nx, y + 1, nz)) {
                relax(cur, nx, y + 1, nz, STEP_UP);
            }
        }
        for (int d = 0; d < 4; d++) {
            int nx = x + DIAG_X[d];
            int nz = z + DIAG_Z[d];
            if (standable(nx, y, nz) && clear2(nx, y, z) && clear2(x, y, nz)) {
                relax(cur, nx, y, nz, DIAG);
            }
        }
    }

    private void relax(int from, int x, int y, int z, int stepCost) {
        if (Math.abs(x - sx) > MAX_RADIUS || Math.abs(z - sz) > MAX_RADIUS || Math.abs(y - sy) > MAX_VERTICAL) {
            return;
        }
        long p = Packed.pack(x, y, z);
        int i = index.get(p);
        if (i >= 0 && closed[i]) {
            return;
        }
        int extra = hazards.cost(x, y, z);
        if (extra < 0) {
            return;
        }
        int ng = g[from] + stepCost + extra;
        if (i < 0) {
            if (count >= MAX_NODES) {
                return;
            }
            add(p, x, y, z, ng, from);
        } else if (ng < g[i]) {
            g[i] = ng;
            f[i] = ng + h[i];
            parent[i] = from;
            siftUp(heapAt[i]);
        }
    }

    private int add(long p, int x, int y, int z, int gCost, int from) {
        int i = count++;
        pos[i] = p;
        g[i] = gCost;
        h[i] = heuristic(x, y, z);
        f[i] = gCost + h[i];
        parent[i] = from;
        closed[i] = false;
        index.put(p, i);
        heap[heapSize] = i;
        heapAt[i] = heapSize;
        heapSize++;
        siftUp(heapSize - 1);
        return i;
    }

    /** Octile horizontal distance minus the goal range (admissible: every horizontal step costs at least WALK). */
    private int heuristic(int x, int y, int z) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int lo = Math.min(dx, dz);
        int hi = Math.max(dx, dz);
        int octile = DIAG * lo + WALK * (hi - lo);
        int dy = Math.abs(y - gy);
        int vert = Math.max(0, dy - Math.max(1, range)) * 2; // each block of climb or drop costs at least this much
        return Math.max(0, octile - WALK * range) + vert;
    }

    private boolean passable(int x, int y, int z) {
        return !v.solid(x, y, z);
    }

    private boolean clear2(int x, int y, int z) {
        return !v.solid(x, y, z) && !v.solid(x, y + 1, z);
    }

    private boolean standable(int x, int y, int z) {
        return clear2(x, y, z) && v.solid(x, y - 1, z);
    }

    private void buildPath(int end) {
        int len = 0;
        for (int i = end; i >= 0; i = parent[i]) {
            len++;
        }
        int skip = Math.max(0, len - MAX_PATH);
        int n = len - skip;
        int i = end;
        for (int k = 0; k < skip; k++) {
            i = parent[i];
        }
        for (int k = n - 1; k >= 0; k--) {
            path[k] = pos[i];
            i = parent[i];
        }
        pathLength = n;
    }

    // ---- binary heap on f -------------------------------------------------------------------------------------------

    private int pop() {
        int top = heap[0];
        heapSize--;
        if (heapSize > 0) {
            heap[0] = heap[heapSize];
            heapAt[heap[0]] = 0;
            siftDown(0);
        }
        heapAt[top] = -1;
        return top;
    }

    private boolean less(int a, int b) {
        return f[a] < f[b] || (f[a] == f[b] && h[a] < h[b]);
    }

    private void siftUp(int k) {
        int node = heap[k];
        while (k > 0) {
            int pk = (k - 1) >>> 1;
            int pn = heap[pk];
            if (!less(node, pn)) {
                break;
            }
            heap[k] = pn;
            heapAt[pn] = k;
            k = pk;
        }
        heap[k] = node;
        heapAt[node] = k;
    }

    private void siftDown(int k) {
        int node = heap[k];
        int half = heapSize >>> 1;
        while (k < half) {
            int c = 2 * k + 1;
            int r = c + 1;
            if (r < heapSize && less(heap[r], heap[c])) {
                c = r;
            }
            if (!less(heap[c], node)) {
                break;
            }
            heap[k] = heap[c];
            heapAt[heap[k]] = k;
            k = c;
        }
        heap[k] = node;
        heapAt[node] = k;
    }
}
