package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Optimal escape path: from where the player stands, the route that leaves the most room between them and the threats
 * around them. A bounded Dijkstra search (cost = ticks at sprint speed) over the standable cells nearby, with two
 * kinds of moves:
 * <ul>
 *   <li>walk: eight directions, one step up, drops of up to {@link #MAX_DROP} (no fall damage);</li>
 *   <li>sprint jump: straight over 1–3 air blocks (one less when landing higher, more room when landing lower) and the
 *       short diagonal, with head room checked along the arc.</li>
 * </ul>
 * Each reached cell is scored by the worst threat: {@code distance(threat, cell) - threatSpeed * myTicks}, i.e. the
 * room left after the threat chased for the same time. A route that crosses a jump counts {@link #JUMP_LEAD} blocks
 * extra against threats that cannot parkour (mobs path around gaps; players follow). The best-scoring cell wins if it
 * beats standing still by {@link #MIN_GAIN}.
 * <p>
 * Engine-free (any {@link MovementPhysics.Voxels}); every array is preallocated and the search allocates nothing.
 * Client thread, a few times a second while threats are near.
 */
public final class EscapePlanner {

    public static final int RADIUS = 12;
    public static final int MAX_NODES = 900;
    public static final int MAX_PATH = 48;
    public static final int MAX_THREATS = 16;
    public static final int MAX_DROP = 3;
    public static final double SPRINT = 0.28;
    public static final double JUMP_LEAD = 6.0;
    public static final double MIN_GAIN = 1.5;

    private static final int[] DX8 = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ8 = {0, 0, 1, -1, 1, -1, 1, -1};

    // threats
    private final double[] tx = new double[MAX_THREATS];
    private final double[] ty = new double[MAX_THREATS];
    private final double[] tz = new double[MAX_THREATS];
    private final double[] tSpeed = new double[MAX_THREATS];
    private final boolean[] tParkour = new boolean[MAX_THREATS];
    private int threats;

    // search
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap(MAX_NODES * 2);
    private final long[] cell = new long[MAX_NODES];
    private final int[] parent = new int[MAX_NODES];
    private final float[] cost = new float[MAX_NODES];
    private final boolean[] viaJump = new boolean[MAX_NODES];
    private final boolean[] jumped = new boolean[MAX_NODES];
    private final boolean[] closed = new boolean[MAX_NODES];
    private int nodes;
    private final int[] heap = new int[MAX_NODES * 8];
    private int heapSize;

    // result
    private final long[] path = new long[MAX_PATH];
    private final boolean[] pathJump = new boolean[MAX_PATH];
    private int pathLength;
    private double bestScore;
    private double startScore;
    private int expanded;

    public EscapePlanner() {
        index.defaultReturnValue(-1);
    }

    public void clearThreats() {
        threats = 0;
    }

    /** Adds a threat at (x, y, z) moving {@code speed} blocks per tick; {@code parkour}: it can follow over gaps. */
    public void addThreat(double x, double y, double z, double speed, boolean parkour) {
        if (threats >= MAX_THREATS) {
            return;
        }
        tx[threats] = x;
        ty[threats] = y;
        tz[threats] = z;
        tSpeed[threats] = speed;
        tParkour[threats] = parkour;
        threats++;
    }

    public int threatCount() {
        return threats;
    }

    /**
     * Plans from the feet position. Returns true when an escape path better than standing still exists; read it with
     * {@link #pathLength()} / {@link #pathCell(int)} (first entry = the start cell).
     */
    public boolean plan(double x, double y, double z, MovementPhysics.Voxels w) {
        pathLength = 0;
        expanded = 0;
        if (threats == 0) {
            return false;
        }
        int sx = floor(x);
        int sy = floor(y + 0.01);
        int sz = floor(z);
        if (!standable(sx, sy, sz, w)) {
            return false;
        }
        index.clear();
        nodes = 0;
        heapSize = 0;
        int start = add(Packed.pack(sx, sy, sz), -1, 0f, false, w);
        startScore = score(sx, sy, sz, 0f, false);
        int best = start;
        bestScore = startScore;
        while (heapSize > 0) {
            int n = pop();
            if (closed[n]) {
                continue;
            }
            closed[n] = true;
            expanded++;
            long c = cell[n];
            int cx = Packed.x(c);
            int cy = Packed.y(c);
            int cz = Packed.z(c);
            double sc = score(cx, cy, cz, cost[n], jumped[n]);
            if (sc > bestScore) {
                bestScore = sc;
                best = n;
            }
            walkMoves(n, cx, cy, cz, sx, sz, w);
            jumpMoves(n, cx, cy, cz, sx, sz, w);
        }
        if (best == start || bestScore < startScore + MIN_GAIN) {
            return false;
        }
        // walk back to the start, then reverse into the path arrays
        int len = 0;
        for (int n = best; n >= 0 && len < MAX_PATH; n = parent[n]) {
            path[len] = cell[n];
            pathJump[len] = viaJump[n];
            len++;
        }
        for (int i = 0, j = len - 1; i < j; i++, j--) {
            long t = path[i];
            path[i] = path[j];
            path[j] = t;
            boolean b = pathJump[i];
            pathJump[i] = pathJump[j];
            pathJump[j] = b;
        }
        pathLength = len;
        return true;
    }

    private void walkMoves(int n, int x, int y, int z, int sx, int sz, MovementPhysics.Voxels w) {
        for (int d = 0; d < 8; d++) {
            int nx = x + DX8[d];
            int nz = z + DZ8[d];
            if (!inRange(nx, nz, sx, sz)) {
                continue;
            }
            boolean diagonal = DX8[d] != 0 && DZ8[d] != 0;
            if (diagonal && (!passable(x + DX8[d], y, z, w) || !passable(x, y, z + DZ8[d], w))) {
                continue; // no corner cutting
            }
            double step = diagonal ? 1.4142 : 1.0;
            if (standable(nx, y, nz, w)) {
                relax(n, nx, y, nz, step / SPRINT, false, w);
            } else if (standable(nx, y + 1, nz, w) && !w.solid(x, y + 2, z)) {
                relax(n, nx, y + 1, nz, step / SPRINT + 3.0, false, w); // a jump up a block costs a little time
            } else if (passable(nx, y, nz, w)) {
                for (int dy = 1; dy <= MAX_DROP; dy++) {
                    if (standable(nx, y - dy, nz, w)) {
                        relax(n, nx, y - dy, nz, step / SPRINT + dy, false, w);
                        break;
                    }
                    if (w.solid(nx, y - dy, nz)) {
                        break;
                    }
                }
            }
        }
    }

    private void jumpMoves(int n, int x, int y, int z, int sx, int sz, MovementPhysics.Voxels w) {
        for (int d = 0; d < 8; d++) {
            int ux = DX8[d];
            int uz = DZ8[d];
            boolean diagonal = ux != 0 && uz != 0;
            // the first cell ahead must be a gap (no floor), otherwise walking covers it
            if (w.solid(x + ux, y - 1, z + uz) || !passable(x + ux, y, z + uz, w)) {
                continue;
            }
            int maxK = diagonal ? 2 : 4;
            for (int k = 2; k <= maxK; k++) {
                int nx = x + ux * k;
                int nz = z + uz * k;
                if (!inRange(nx, nz, sx, sz) || !clearArc(x, y, z, ux, uz, k, w)) {
                    break;
                }
                for (int dy = 1; dy >= -2; dy--) {
                    int gap = k - 1;
                    if (gap > maxGap(dy) || (diagonal && gap > 1)) {
                        continue;
                    }
                    if (standable(nx, y + dy, nz, w) && (dy <= 0 || !w.solid(x, y + 2, z))) {
                        double dist = diagonal ? k * 1.4142 : k;
                        relax(n, nx, y + dy, nz, dist / SPRINT + 2.0, true, w);
                        break;
                    }
                }
            }
        }
    }

    /** Longest sprint-jump gap (air blocks) when landing {@code dy} higher (vanilla limits, see MovementPhysicsTest). */
    static int maxGap(int dy) {
        return dy >= 1 ? 2 : 3;
    }

    /** Head room for the jump arc over every cell between take-off and landing. */
    private static boolean clearArc(int x, int y, int z, int ux, int uz, int k, MovementPhysics.Voxels w) {
        for (int i = 1; i < k; i++) {
            int cx = x + ux * i;
            int cz = z + uz * i;
            if (w.solid(cx, y, cz) || w.solid(cx, y + 1, cz) || w.solid(cx, y + 2, cz)) {
                return false;
            }
        }
        return !w.solid(x, y + 2, z);
    }

    private void relax(int from, int x, int y, int z, double stepTicks, boolean jump, MovementPhysics.Voxels w) {
        long key = Packed.pack(x, y, z);
        float c = (float) (cost[from] + stepTicks);
        int n = index.get(key);
        if (n >= 0) {
            if (closed[n] || c >= cost[n]) {
                return;
            }
            parent[n] = from;
            cost[n] = c;
            viaJump[n] = jump;
            jumped[n] = jump || jumped[from];
            push(n);
            return;
        }
        add(key, from, c, jump, w);
    }

    private int add(long key, int from, float c, boolean jump, MovementPhysics.Voxels w) {
        if (nodes >= MAX_NODES) {
            return -1;
        }
        int n = nodes++;
        cell[n] = key;
        parent[n] = from;
        cost[n] = c;
        viaJump[n] = jump;
        jumped[n] = jump || (from >= 0 && jumped[from]);
        closed[n] = false;
        index.put(key, n);
        push(n);
        return n;
    }

    /** Room left against the worst threat after running {@code ticks} to the cell centre. */
    private double score(int x, int y, int z, double ticks, boolean crossedJump) {
        double cx = x + 0.5;
        double cz = z + 0.5;
        double worst = Double.MAX_VALUE;
        for (int i = 0; i < threats; i++) {
            double dx = cx - tx[i];
            double dy = y - ty[i];
            double dz = cz - tz[i];
            double room = Math.sqrt(dx * dx + dz * dz + dy * dy * 0.25) - tSpeed[i] * ticks;
            if (crossedJump && !tParkour[i]) {
                room += JUMP_LEAD;
            }
            worst = Math.min(worst, room);
        }
        return worst;
    }

    private static boolean inRange(int x, int z, int sx, int sz) {
        return Math.abs(x - sx) <= RADIUS && Math.abs(z - sz) <= RADIUS;
    }

    private static boolean passable(int x, int y, int z, MovementPhysics.Voxels w) {
        return !w.solid(x, y, z) && !w.solid(x, y + 1, z);
    }

    private static boolean standable(int x, int y, int z, MovementPhysics.Voxels w) {
        return w.solid(x, y - 1, z) && passable(x, y, z, w);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---- binary min-heap on cost (lazy deletion: a node may sit in it more than once) ------------------------------

    private void push(int n) {
        if (heapSize >= heap.length) {
            return;
        }
        int i = heapSize++;
        heap[i] = n;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (cost[heap[p]] <= cost[heap[i]]) {
                break;
            }
            int t = heap[p];
            heap[p] = heap[i];
            heap[i] = t;
            i = p;
        }
    }

    private int pop() {
        int top = heap[0];
        heap[0] = heap[--heapSize];
        int i = 0;
        while (true) {
            int l = 2 * i + 1;
            if (l >= heapSize) {
                break;
            }
            int r = l + 1;
            int m = r < heapSize && cost[heap[r]] < cost[heap[l]] ? r : l;
            if (cost[heap[i]] <= cost[heap[m]]) {
                break;
            }
            int t = heap[m];
            heap[m] = heap[i];
            heap[i] = t;
            i = m;
        }
        return top;
    }

    // ---- result -----------------------------------------------------------------------------------------------------

    public int pathLength() {
        return pathLength;
    }

    /** Packed cell (feet block) of path step i; 0 is the start. */
    public long pathCell(int i) {
        return path[i];
    }

    /** True when the move into step i is a sprint jump. */
    public boolean pathJump(int i) {
        return pathJump[i];
    }

    /** Room left at the path's end against the worst threat (blocks). */
    public double bestScore() {
        return bestScore;
    }

    /** The same measure for standing still. */
    public double startScore() {
        return startScore;
    }

    /** Cells expanded by the last search (budget diagnostics). */
    public int expanded() {
        return expanded;
    }
}
