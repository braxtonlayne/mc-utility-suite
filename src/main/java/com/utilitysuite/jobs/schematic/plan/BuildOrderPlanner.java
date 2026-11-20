package com.utilitysuite.jobs.schematic.plan;

import com.utilitysuite.jobs.schematic.model.BlockCategory;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.BitSet;

/**
 * Orders the placements of a schematic. Runs off the client thread.
 * <ol>
 *   <li>Bottom-up, one layer at a time.</li>
 *   <li>Within a layer, solid blocks first. They are placed outward from cells that already have support (ground on
 *       the bottom layer, or any face touching a placed or existing block), flood-filling horizontally so overhangs
 *       grow from the wall that holds them. A cell with no support at all gets a temporary scaffold column below it
 *       (up to {@code maxScaffoldHeight}), placed just before it.</li>
 *   <li>Then the gravity blocks of the layer (sand, gravel, concrete powder, anvils), after their supports.</li>
 *   <li>Attachment blocks (torches, redstone, doors, rails, carpets, plants, signs...) after every solid block,
 *       bottom-up.</li>
 *   <li>Scaffold removal last, top-down.</li>
 * </ol>
 * Inside a layer the base order is a serpentine walk (rows along X, alternating direction per Z row), which keeps
 * consecutive placements next to each other.
 */
public final class BuildOrderPlanner {

    /**
     * @param scaffoldItem      item id used for temporary support blocks
     * @param maxScaffoldHeight tallest scaffold column the planner inserts; taller gaps are reported as unsupported
     */
    public record Options(String scaffoldItem, int maxScaffoldHeight) {
        public static final Options DEFAULT = new Options("minecraft:dirt", 12);
    }

    private BuildOrderPlanner() {
    }

    /**
     * @param status per-cell placement status, or {@code null} to plan every block. Cells already placed
     *               ({@link PlacementStatus#isPlaced}) are skipped but count as support.
     */
    public static BuildPlan plan(SchematicVolume v, byte[] status, Options options) {
        int sx = v.sizeX(), sy = v.sizeY(), sz = v.sizeZ();
        int layer = sx * sz;
        int n = v.volume();
        int paletteSize = v.paletteSize();
        BlockCategory[] cat = new BlockCategory[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            cat[i] = v.isBlock(i) ? BlockCategory.of(v.paletteEntry(i)) : null;
        }

        BitSet solid = new BitSet(n);
        boolean[] pending = new boolean[n];
        for (int i = 0; i < n; i++) {
            int p = v.paletteIndex(i);
            if (!v.isBlock(p)) {
                continue;
            }
            byte s = status == null ? PlacementStatus.UNKNOWN : status[i];
            if (PlacementStatus.isPlaced(s)) {
                solid.set(i);
            } else {
                pending[i] = true;
            }
        }

        int[] snake = new int[layer];
        int k = 0;
        for (int z = 0; z < sz; z++) {
            for (int j = 0; j < sx; j++) {
                int x = (z & 1) == 0 ? j : sx - 1 - j;
                snake[k++] = z * sx + x;
            }
        }

        IntArrayList cells = new IntArrayList();
        ByteArrayList kinds = new ByteArrayList();
        IntArrayList scaffolds = new IntArrayList();
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        int unsupported = 0;

        for (int y = 0; y < sy; y++) {
            int base = y * layer;
            // Solid blocks: grow from supported cells, scaffold the rest.
            boolean remaining = true;
            while (remaining) {
                remaining = false;
                boolean progress = false;
                for (int s : snake) {
                    int c = base + s;
                    if (pending[c] && cat[v.paletteIndex(c)] == BlockCategory.SOLID && supported(v, solid, c)) {
                        progress |= flood(v, cat, pending, solid, c, queue, cells, kinds);
                    }
                }
                if (!progress) {
                    for (int s : snake) {
                        int c = base + s;
                        if (pending[c] && cat[v.paletteIndex(c)] == BlockCategory.SOLID) {
                            if (!scaffoldBelow(v, solid, pending, c, options.maxScaffoldHeight(), cells, kinds,
                                    scaffolds)) {
                                unsupported++;
                            }
                            flood(v, cat, pending, solid, c, queue, cells, kinds);
                            remaining = true;
                            break;
                        }
                    }
                } else {
                    remaining = true;
                }
            }
            // Gravity blocks of this layer, after the layer's solids.
            for (int s : snake) {
                int c = base + s;
                if (pending[c] && cat[v.paletteIndex(c)] == BlockCategory.GRAVITY) {
                    emit(c, BuildPlan.PLACE, pending, solid, cells, kinds);
                }
            }
        }
        // Attachments last, bottom-up.
        for (int y = 0; y < sy; y++) {
            int base = y * layer;
            for (int s : snake) {
                int c = base + s;
                if (pending[c] && cat[v.paletteIndex(c)] == BlockCategory.ATTACHMENT) {
                    emit(c, BuildPlan.PLACE, pending, solid, cells, kinds);
                }
            }
        }
        for (int i = scaffolds.size() - 1; i >= 0; i--) {
            cells.add(scaffolds.getInt(i));
            kinds.add(BuildPlan.REMOVE_SCAFFOLD);
        }
        return new BuildPlan(v, cells.toIntArray(), kinds.toByteArray(), options.scaffoldItem(), unsupported);
    }

    /** Places {@code start} and every pending solid cell horizontally connected to it in the same layer (BFS). */
    private static boolean flood(SchematicVolume v, BlockCategory[] cat, boolean[] pending, BitSet solid, int start,
                                 IntArrayFIFOQueue queue, IntArrayList cells, ByteArrayList kinds) {
        if (!pending[start]) {
            return false;
        }
        emit(start, BuildPlan.PLACE, pending, solid, cells, kinds);
        queue.enqueue(start);
        int sx = v.sizeX(), sz = v.sizeZ();
        while (!queue.isEmpty()) {
            int c = queue.dequeueInt();
            int x = v.xOf(c), z = v.zOf(c);
            if (x > 0) {
                visit(c - 1, cat, v, pending, solid, queue, cells, kinds);
            }
            if (x < sx - 1) {
                visit(c + 1, cat, v, pending, solid, queue, cells, kinds);
            }
            if (z > 0) {
                visit(c - sx, cat, v, pending, solid, queue, cells, kinds);
            }
            if (z < sz - 1) {
                visit(c + sx, cat, v, pending, solid, queue, cells, kinds);
            }
        }
        return true;
    }

    private static void visit(int c, BlockCategory[] cat, SchematicVolume v, boolean[] pending, BitSet solid,
                              IntArrayFIFOQueue queue, IntArrayList cells, ByteArrayList kinds) {
        if (pending[c] && cat[v.paletteIndex(c)] == BlockCategory.SOLID) {
            emit(c, BuildPlan.PLACE, pending, solid, cells, kinds);
            queue.enqueue(c);
        }
    }

    private static void emit(int c, byte kind, boolean[] pending, BitSet solid, IntArrayList cells,
                             ByteArrayList kinds) {
        pending[c] = false;
        solid.set(c);
        cells.add(c);
        kinds.add(kind);
    }

    /** Ground on the bottom layer, or any face touching a placed / existing block inside the volume. */
    static boolean supported(SchematicVolume v, BitSet solid, int c) {
        int x = v.xOf(c), y = v.yOf(c), z = v.zOf(c);
        int sx = v.sizeX(), sz = v.sizeZ(), layer = sx * sz;
        if (y == 0) {
            return true;
        }
        return solid.get(c - layer)
                || (y < v.sizeY() - 1 && solid.get(c + layer))
                || (x > 0 && solid.get(c - 1)) || (x < sx - 1 && solid.get(c + 1))
                || (z > 0 && solid.get(c - sx)) || (z < sz - 1 && solid.get(c + sx));
    }

    /**
     * Adds a scaffold column under {@code c} down to the first solid cell (or the ground). Only air and void cells can
     * hold scaffolding.
     *
     * @return false when the column would be too tall or is blocked by a cell that still needs its own block
     */
    private static boolean scaffoldBelow(SchematicVolume v, BitSet solid, boolean[] pending, int c, int maxHeight,
                                         IntArrayList cells, ByteArrayList kinds, IntArrayList scaffolds) {
        int layer = v.sizeX() * v.sizeZ();
        int height = 0;
        int below = c - layer;
        while (below >= 0 && !solid.get(below)) {
            if (pending[below] || v.isBlock(v.paletteIndex(below)) || ++height > maxHeight) {
                return false;
            }
            below -= layer;
        }
        // Emit bottom-up.
        for (int h = height; h >= 1; h--) {
            int cell = c - h * layer;
            solid.set(cell);
            cells.add(cell);
            kinds.add(BuildPlan.SCAFFOLD);
            scaffolds.add(cell);
        }
        return true;
    }
}
