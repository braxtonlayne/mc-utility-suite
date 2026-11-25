package com.utilitysuite.world.sonar;

import com.utilitysuite.world.render.LineSink;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * Engine-free cavity tomography (worker thread). On a {@link VoxelGrid}:
 * <ol>
 *   <li>flood the open cells connected to the sky (cells at or above their column's sky height) and mark them,</li>
 *   <li>label every remaining connected group of open cells (6-connected) as a void; voids smaller than
 *       {@code minCells} are dropped,</li>
 *   <li>extract the contour of each void: the edges of its boundary faces where the surface turns (an edge shared by
 *       two coplanar boundary faces is interior to a flat patch and skipped), de-duplicated and merged into straight
 *       runs, coloured by how deep the void lies below the local surface.</li>
 * </ol>
 * Work is linear in the box volume and bounded by it; the label and queue buffers are reused between scans (one scan
 * at a time). The result ({@link Model}) is immutable and drawn per frame without allocation.
 */
public final class CavityTomography {

    private static final int SKY = -1;
    private static final int SMALL = -2;

    private int[] label = new int[0];
    private int[] queue = new int[0];
    private final Long2IntOpenHashMap edges = new Long2IntOpenHashMap();

    /** Immutable contour set: straight segments in world block-corner coordinates with a colour each. */
    public static final class Model {
        public static final Model EMPTY = new Model(new int[0], new int[0], 0, 0);

        /** x0, y0, z0, x1, y1, z1 per segment. */
        private final int[] seg;
        private final int[] color;
        public final int voids;
        public final int voidCells;

        Model(int[] seg, int[] color, int voids, int voidCells) {
            this.seg = seg;
            this.color = color;
            this.voids = voids;
            this.voidCells = voidCells;
        }

        public int segments() {
            return color.length;
        }

        public int coord(int segment, int k) {
            return seg[segment * 6 + k];
        }

        public int color(int segment) {
            return color[segment];
        }

        /**
         * Per-frame emitter, allocation-free. Segments beyond {@code maxDist} of the camera are skipped and the rest
         * fade with distance (depth cue). Returns the number drawn.
         */
        public int emit(LineSink s, double camX, double camY, double camZ, float maxDist) {
            float max2 = maxDist * maxDist;
            int drawn = 0;
            for (int i = 0, n = color.length; i < n; i++) {
                int o = i * 6;
                float x0 = (float) (seg[o] - camX);
                float y0 = (float) (seg[o + 1] - camY);
                float z0 = (float) (seg[o + 2] - camZ);
                float x1 = (float) (seg[o + 3] - camX);
                float y1 = (float) (seg[o + 4] - camY);
                float z1 = (float) (seg[o + 5] - camZ);
                float mx = (x0 + x1) * 0.5f;
                float my = (y0 + y1) * 0.5f;
                float mz = (z0 + z1) * 0.5f;
                float d2 = mx * mx + my * my + mz * mz;
                if (d2 > max2) {
                    continue;
                }
                float fade = 1f - 0.75f * (d2 / max2);
                int argb = ((int) (((color[i] >>> 24) & 0xFF) * fade) << 24) | (color[i] & 0xFFFFFF);
                s.line(x0, y0, z0, x1, y1, z1, argb);
                drawn++;
            }
            return drawn;
        }
    }

    /**
     * Scans the grid and returns the void contours.
     *
     * @param minCells voids with fewer open cells are ignored
     * @param maxEdges cap on emitted unit edges (before merging into runs)
     */
    public Model scan(VoxelGrid g, int minCells, int maxEdges) {
        int sx = g.sizeX();
        int sy = g.sizeY();
        int sz = g.sizeZ();
        int n = g.volume();
        if (label.length < n) {
            label = new int[n];
            queue = new int[n];
        }
        Arrays.fill(label, 0, n, 0);
        // 1) Sky flood: seed with every open cell at or above its column's sky height.
        int head = 0;
        int tail = 0;
        int gy = g.originY();
        for (int ly = 0; ly < sy; ly++) {
            for (int lz = 0; lz < sz; lz++) {
                for (int lx = 0; lx < sx; lx++) {
                    int i = g.index(lx, ly, lz);
                    if (VoxelGrid.open(g.at(i)) && gy + ly >= g.skyY(lx, lz)) {
                        label[i] = SKY;
                        queue[tail++] = i;
                    }
                }
            }
        }
        flood(g, head, tail, SKY);
        // 2) Label the rest.
        int voids = 0;
        int voidCells = 0;
        int[] sizes = new int[64];
        int[] depthSum = new int[64];
        for (int i = 0; i < n; i++) {
            if (label[i] != 0 || !VoxelGrid.open(g.at(i))) {
                continue;
            }
            int id = voids + 1;
            label[i] = id;
            queue[0] = i;
            int size = flood(g, 0, 1, id);
            if (size < minCells) {
                relabel(size, SMALL);
                continue;
            }
            voids++;
            if (voids >= sizes.length) {
                sizes = Arrays.copyOf(sizes, sizes.length * 2);
                depthSum = Arrays.copyOf(depthSum, depthSum.length * 2);
            }
            sizes[id] = size;
            long depth = 0;
            for (int q = 0; q < size; q++) {
                depth += depthBelow(g, queue[q]);
            }
            depthSum[id] = (int) Math.min(Integer.MAX_VALUE, depth / size);
            voidCells += size;
        }
        if (voids == 0) {
            return Model.EMPTY;
        }
        // 3) Contour edges.
        edges.clear();
        edges.defaultReturnValue(0);
        outer:
        for (int ly = 0; ly < sy; ly++) {
            for (int lz = 0; lz < sz; lz++) {
                for (int lx = 0; lx < sx; lx++) {
                    int id = label[g.index(lx, ly, lz)];
                    if (id <= 0) {
                        continue;
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        for (int sign = -1; sign <= 1; sign += 2) {
                            if (labelAt(g, lx, ly, lz, axis, sign) == id) {
                                continue; // not a boundary face
                            }
                            faceEdges(g, lx, ly, lz, axis, sign, id);
                            if (edges.size() >= maxEdges) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        return buildModel(g, voids, voidCells, depthSum);
    }

    /** BFS from queue[head, tail) over open cells with label 0, assigning {@code id}. Returns the cells visited. */
    private int flood(VoxelGrid g, int head, int tail, int id) {
        int sx = g.sizeX();
        int sz = g.sizeZ();
        int layer = sx * sz;
        int n = g.volume();
        while (head < tail) {
            int i = queue[head++];
            int lx = i % sx;
            int lz = (i / sx) % sz;
            if (lx > 0) {
                tail = visit(g, i - 1, id, tail);
            }
            if (lx < sx - 1) {
                tail = visit(g, i + 1, id, tail);
            }
            if (lz > 0) {
                tail = visit(g, i - sx, id, tail);
            }
            if (lz < sz - 1) {
                tail = visit(g, i + sx, id, tail);
            }
            if (i >= layer) {
                tail = visit(g, i - layer, id, tail);
            }
            if (i + layer < n) {
                tail = visit(g, i + layer, id, tail);
            }
        }
        return tail;
    }

    private int visit(VoxelGrid g, int j, int id, int tail) {
        if (label[j] == 0 && VoxelGrid.open(g.at(j))) {
            label[j] = id;
            queue[tail++] = j;
        }
        return tail;
    }

    private void relabel(int size, int to) {
        for (int q = 0; q < size; q++) {
            label[queue[q]] = to;
        }
    }

    private static int depthBelow(VoxelGrid g, int i) {
        int sx = g.sizeX();
        int sz = g.sizeZ();
        int lx = i % sx;
        int lz = (i / sx) % sz;
        int ly = i / (sx * sz);
        int sky = g.skyY(lx, lz);
        return sky == Integer.MAX_VALUE ? 0 : Math.max(0, sky - (g.originY() + ly));
    }

    /** Label of the neighbour along {@code axis} by {@code sign}; outside the box counts as solid (0). */
    private int labelAt(VoxelGrid g, int lx, int ly, int lz, int axis, int sign) {
        int x = lx + (axis == 0 ? sign : 0);
        int y = ly + (axis == 1 ? sign : 0);
        int z = lz + (axis == 2 ? sign : 0);
        return g.inside(x, y, z) ? label[g.index(x, y, z)] : 0;
    }

    private int labelAt(VoxelGrid g, int x, int y, int z) {
        return g.inside(x, y, z) ? label[g.index(x, y, z)] : 0;
    }

    /**
     * The four edges of the boundary face of cell (lx, ly, lz) on side {@code sign} of {@code axis}. An edge is kept
     * unless the face continues flat across it (the in-plane neighbour is in the void and its neighbour along the
     * face normal is not).
     */
    private void faceEdges(VoxelGrid g, int lx, int ly, int lz, int axis, int sign, int id) {
        int u = (axis + 1) % 3;
        int v = (axis + 2) % 3;
        int[] c = {lx, ly, lz}; // tiny, worker thread only
        int plane = c[axis] + (sign > 0 ? 1 : 0);
        for (int t = 0; t < 2; t++) {
            int across = t == 0 ? u : v; // direction we step to find the continuing face
            int along = t == 0 ? v : u; // direction the edge runs
            for (int side = 0; side <= 1; side++) {
                int step = side == 0 ? -1 : 1;
                int mx = c[0] + (across == 0 ? step : 0);
                int my = c[1] + (across == 1 ? step : 0);
                int mz = c[2] + (across == 2 ? step : 0);
                boolean flat = labelAt(g, mx, my, mz) == id && labelAt(g, mx + (axis == 0 ? sign : 0),
                        my + (axis == 1 ? sign : 0), mz + (axis == 2 ? sign : 0)) != id;
                if (flat) {
                    continue;
                }
                int[] p = {0, 0, 0};
                p[axis] = plane;
                p[across] = c[across] + side;
                p[along] = c[along];
                long key = edgeKey(along, p[0], p[1], p[2]);
                if (!edges.containsKey(key)) {
                    edges.put(key, id);
                }
            }
        }
    }

    /** Sortable key: axis, then the two fixed coordinates, then the coordinate along the axis (lowest bits). */
    static long edgeKey(int axis, int x, int y, int z) {
        int a = axis == 0 ? x : axis == 1 ? y : z;
        int f1 = axis == 0 ? y : x;
        int f2 = axis == 2 ? y : z;
        return ((long) axis << 60) | ((long) (f1 & 0xFFFFF) << 40) | ((long) (f2 & 0xFFFFF) << 20) | (a & 0xFFFFF);
    }

    private Model buildModel(VoxelGrid g, int voids, int voidCells, int[] depthOf) {
        long[] keys = edges.keySet().toLongArray();
        Arrays.sort(keys);
        int[] seg = new int[keys.length * 6];
        int[] col = new int[keys.length];
        int out = 0;
        int ox = g.originX();
        int oy = g.originY();
        int oz = g.originZ();
        int i = 0;
        while (i < keys.length) {
            long k = keys[i];
            int id = edges.get(k);
            int j = i + 1;
            while (j < keys.length && keys[j] == keys[j - 1] + 1 && edges.get(keys[j]) == id
                    && (keys[j] & 0xFFFFF) != 0) {
                j++;
            }
            int axis = (int) (k >>> 60);
            int a = (int) (k & 0xFFFFF);
            int f1 = (int) ((k >>> 40) & 0xFFFFF);
            int f2 = (int) ((k >>> 20) & 0xFFFFF);
            int x = axis == 0 ? a : f1;
            int y = axis == 1 ? a : axis == 0 ? f1 : f2;
            int z = axis == 2 ? a : f2;
            int len = j - i;
            int o = out * 6;
            seg[o] = ox + x;
            seg[o + 1] = oy + y;
            seg[o + 2] = oz + z;
            seg[o + 3] = ox + x + (axis == 0 ? len : 0);
            seg[o + 4] = oy + y + (axis == 1 ? len : 0);
            seg[o + 5] = oz + z + (axis == 2 ? len : 0);
            col[out] = depthColor(depthOf[id]);
            out++;
            i = j;
        }
        return new Model(Arrays.copyOf(seg, out * 6), Arrays.copyOf(col, out), voids, voidCells);
    }

    /** Shallow voids cyan, through blue and violet, to deep red (48+ blocks below the surface). */
    static int depthColor(int depth) {
        float t = Math.min(1f, depth / 48f);
        int r;
        int gr;
        int b;
        if (t < 0.5f) {
            float k = t * 2f;
            r = (int) (40 + 100 * k);
            gr = (int) (230 - 170 * k);
            b = 255;
        } else {
            float k = (t - 0.5f) * 2f;
            r = (int) (140 + 115 * k);
            gr = (int) (60 - 20 * k);
            b = (int) (255 - 195 * k);
        }
        return 0xE0000000 | (r << 16) | (gr << 8) | b;
    }
}
