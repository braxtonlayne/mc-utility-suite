package com.utilitysuite.world.redstone;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import static com.utilitysuite.world.redstone.RedstoneCell.*;

/**
 * Engine-free redstone network tracer. From a starting component it follows where the signal goes, downstream, using
 * only block descriptions ({@link RedstoneCell}): a bounded shortest-delay search (relaxing a node again when a faster
 * route reaches it) that records for every reached component its cumulative game-tick delay from the start, the
 * parent it was reached from, its hop depth (for the pulse animation) and whether it was reached through
 * quasi-connectivity. Plain blocks conduct: strongly powered blocks (by a repeater, comparator, observer, the torch
 * below, an attached lever / button / plate) power every adjacent component; weakly powered ones (by dust) power
 * everything but dust. Torches on a powered block are reached (they invert). Observers watching a reached component
 * fire. Quasi-connectivity zones (the block above each piston / dropper / dispenser) are listed separately.
 * <p>
 * Approximations: dust is treated as pointing into every horizontal neighbour (no cross / line shape analysis);
 * comparator side inputs and subtract mode are ignored; piston extension is reported as starting at the piston's
 * delay ({@link #PISTON_EXTEND_GT} more to finish). All buffers are preallocated; a trace allocates nothing once the
 * hash structures have grown, and stops at {@code maxNodes} nodes or {@code maxLookups} cell reads (budget).
 */
public final class RedstoneTracer {

    /** A cell reader at block coordinates; the game adapter classifies the block state, tests build grids. */
    public interface View {
        int cell(int x, int y, int z);
    }

    public static final int PISTON_EXTEND_GT = 2;
    public static final int MAX_NODES = 1024;
    public static final int MAX_QC = 256;

    private final long[] pos = new long[MAX_NODES];
    private final int[] cells = new int[MAX_NODES];
    private final int[] delay = new int[MAX_NODES];
    private final int[] parent = new int[MAX_NODES];
    private final int[] depth = new int[MAX_NODES];
    private final boolean[] viaQc = new boolean[MAX_NODES];
    private final int[] queue = new int[MAX_NODES * 4];
    private final boolean[] queued = new boolean[MAX_NODES];
    private final long[] qcZones = new long[MAX_QC];
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap(256);
    private final LongOpenHashSet qcSeen = new LongOpenHashSet(64);
    private int count;
    private int qcCount;
    private int lookups;
    private int maxLookups;
    private int maxNodes;
    private int maxDepth;
    private int maxDelay;
    private boolean truncated;
    private View view;
    // the node being expanded (fields instead of parameters keep the helpers short)
    private int from;

    public RedstoneTracer() {
        index.defaultReturnValue(-1);
    }

    /**
     * Traces from the component at (x, y, z). Returns the node count (0 when the start is not a component).
     *
     * @param maxNodes   node cap (at most {@link #MAX_NODES})
     * @param maxLookups cell-read budget for this trace
     */
    public int trace(View v, int x, int y, int z, int maxNodes, int maxLookups) {
        clear();
        this.view = v;
        this.maxNodes = Math.max(1, Math.min(MAX_NODES, maxNodes));
        this.maxLookups = maxLookups;
        int c = read(x, y, z);
        if (!isComponent(kind(c))) {
            view = null;
            return 0;
        }
        int start = addNode(BlockPosPacking.pack(x, y, z), c);
        delay[start] = 0;
        depth[start] = 0;
        parent[start] = -1;
        noteQcZone(start);
        head = 0;
        tail = 0;
        enqueue(start);
        while (head != tail) {
            int n = queue[head];
            head = (head + 1) % queue.length;
            queued[n] = false;
            from = n;
            expand(n);
        }
        for (int i = 0; i < count; i++) {
            maxDepth = Math.max(maxDepth, depth[i]);
            maxDelay = Math.max(maxDelay, delay[i]);
        }
        view = null;
        return count;
    }

    private int head;
    private int tail;

    private void enqueue(int i) {
        if (queued[i]) {
            return;
        }
        int next = (tail + 1) % queue.length;
        if (next == head) {
            truncated = true;
            return;
        }
        queue[tail] = i;
        tail = next;
        queued[i] = true;
    }

    public void clear() {
        index.clear();
        qcSeen.clear();
        java.util.Arrays.fill(queued, 0, count, false);
        count = 0;
        qcCount = 0;
        lookups = 0;
        maxDepth = 0;
        maxDelay = 0;
        truncated = false;
    }

    // ---- expansion ----------------------------------------------------------------------------------------------

    private void expand(int n) {
        long p = pos[n];
        int x = BlockPosPacking.x(p);
        int y = BlockPosPacking.y(p);
        int z = BlockPosPacking.z(p);
        int c = cells[n];
        int d = dir(c);
        switch (kind(c)) {
            case LEVER, BUTTON, PRESSURE_PLATE -> {
                for (int i = 0; i < 6; i++) {
                    deliver(x + DX[i], y + DY[i], z + DZ[i], i, i == d ? STRONG : DIRECT);
                }
            }
            case REDSTONE_BLOCK, TARGET, DAYLIGHT -> {
                for (int i = 0; i < 6; i++) {
                    deliver(x + DX[i], y + DY[i], z + DZ[i], i, DIRECT);
                }
            }
            case TORCH -> {
                for (int i = 0; i < 6; i++) {
                    if (i != d) {
                        deliver(x + DX[i], y + DY[i], z + DZ[i], i, i == UP ? STRONG : DIRECT);
                    }
                }
            }
            case REPEATER, COMPARATOR -> deliver(x + DX[d], y + DY[d], z + DZ[d], d, STRONG);
            case OBSERVER -> {
                int o = opposite(d);
                deliver(x + DX[o], y + DY[o], z + DZ[o], o, STRONG);
            }
            case DUST -> expandDust(x, y, z);
            default -> {
                // consumers have no outputs
            }
        }
        observersOf(x, y, z);
    }

    private static final int DIRECT = 0;
    private static final int STRONG = 1;
    private static final int WEAK = 2;

    private void expandDust(int x, int y, int z) {
        deliver(x, y - 1, z, DOWN, WEAK);
        boolean roofSolid = kind(read(x, y + 1, z)) == SOLID;
        for (int i = 2; i < 6; i++) {
            int nx = x + DX[i];
            int nz = z + DZ[i];
            int side = read(nx, y, nz);
            deliver(nx, y, nz, i, WEAK);
            if (!roofSolid && kind(side) == SOLID && kind(read(nx, y + 1, nz)) == DUST) {
                deliver(nx, y + 1, nz, i, DIRECT); // step up
            }
            if (kind(side) != SOLID && kind(read(nx, y - 1, nz)) == DUST) {
                deliver(nx, y - 1, nz, i, DIRECT); // step down
            }
        }
    }

    /**
     * Power arrives at a cell, travelling in direction {@code via}. {@code mode}: DIRECT reaches components only,
     * STRONG also makes a solid block power all its neighbours, WEAK makes it power all but dust.
     */
    private void deliver(int x, int y, int z, int via, int mode) {
        if (count >= maxNodes) {
            truncated = true;
            return;
        }
        int c = read(x, y, z);
        int k = kind(c);
        if (k == SOLID) {
            if (mode != DIRECT) {
                conduct(x, y, z, via, mode == STRONG);
            }
        } else if (accepts(c, via)) {
            reach(x, y, z, c, false);
        }
        quasi(x, y - 1, z);
    }

    private boolean accepts(int c, int via) {
        int k = kind(c);
        return switch (k) {
            case DUST, PISTON, DROPPER, CRAFTER, LAMP, OTHER_SINK -> true;
            case REPEATER, COMPARATOR -> dir(c) == via; // input only from behind
            default -> false;
        };
    }

    /** A powered solid block at (x, y, z), entered travelling in {@code via}. */
    private void conduct(int x, int y, int z, int via, boolean strong) {
        int back = opposite(via);
        for (int i = 0; i < 6; i++) {
            if (i == back) {
                continue;
            }
            int nx = x + DX[i];
            int ny = y + DY[i];
            int nz = z + DZ[i];
            int c = read(nx, ny, nz);
            int k = kind(c);
            if (k == TORCH && opposite(dir(c)) == i) {
                reach(nx, ny, nz, c, false); // torch attached to this block: inverts
            } else if (k == DUST ? strong : accepts(c, i)) {
                reach(nx, ny, nz, c, false);
            }
            if (k != SOLID) {
                quasi(nx, ny - 1, nz);
            }
        }
    }

    /** Power at a cell also activates a piston / dropper right below it (quasi-connectivity). */
    private void quasi(int x, int y, int z) {
        int c = read(x, y, z);
        if (quasiConnects(kind(c))) {
            reach(x, y, z, c, true);
        }
    }

    private void observersOf(int x, int y, int z) {
        for (int i = 0; i < 6; i++) {
            int nx = x + DX[i];
            int ny = y + DY[i];
            int nz = z + DZ[i];
            int c = read(nx, ny, nz);
            if (kind(c) == OBSERVER && dir(c) == opposite(i)) {
                reach(nx, ny, nz, c, false);
            }
        }
    }

    /** Records (or improves) the node at a position reached from {@link #from}. */
    private void reach(int x, int y, int z, int c, boolean qc) {
        long p = BlockPosPacking.pack(x, y, z);
        int nd = delay[from] + delayGt(c);
        int i = index.get(p);
        if (i < 0) {
            if (count >= maxNodes) {
                truncated = true;
                return;
            }
            i = addNode(p, c);
            viaQc[i] = qc;
            noteQcZone(i);
        } else if (i == from || delay[i] <= nd) {
            return;
        } else {
            viaQc[i] = qc;
        }
        delay[i] = nd;
        parent[i] = from;
        depth[i] = depth[from] + 1;
        enqueue(i);
    }

    private int addNode(long p, int c) {
        int i = count++;
        pos[i] = p;
        cells[i] = c;
        viaQc[i] = false;
        queued[i] = false;
        index.put(p, i);
        return i;
    }

    private void noteQcZone(int i) {
        if (!quasiConnects(kind(cells[i])) || qcCount >= MAX_QC) {
            return;
        }
        long p = pos[i];
        long above = BlockPosPacking.pack(BlockPosPacking.x(p), BlockPosPacking.y(p) + 1, BlockPosPacking.z(p));
        if (qcSeen.add(above)) {
            qcZones[qcCount++] = above;
        }
    }

    private int read(int x, int y, int z) {
        if (lookups >= maxLookups) {
            truncated = true;
            return NONE;
        }
        lookups++;
        return view.cell(x, y, z);
    }

    // ---- results ------------------------------------------------------------------------------------------------

    public int count() {
        return count;
    }

    public long pos(int i) {
        return pos[i];
    }

    public int cell(int i) {
        return cells[i];
    }

    public int nodeKind(int i) {
        return RedstoneCell.kind(cells[i]);
    }

    /** Cumulative game ticks from the start until this component changes. */
    public int delay(int i) {
        return delay[i];
    }

    public int parent(int i) {
        return parent[i];
    }

    public int depth(int i) {
        return depth[i];
    }

    public boolean viaQuasiConnectivity(int i) {
        return viaQc[i];
    }

    /** Signal strength from the block state, -1 when unknown. */
    public int nodeStrength(int i) {
        return RedstoneCell.strength(cells[i]);
    }

    public int qcCount() {
        return qcCount;
    }

    public long qcZone(int i) {
        return qcZones[i];
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxDelay() {
        return maxDelay;
    }

    /** The node cap or lookup budget cut the trace short. */
    public boolean truncated() {
        return truncated;
    }

    public int lookups() {
        return lookups;
    }

    /** Node index at a position, -1 when not reached. */
    public int indexOf(int x, int y, int z) {
        return index.get(BlockPosPacking.pack(x, y, z));
    }
}
