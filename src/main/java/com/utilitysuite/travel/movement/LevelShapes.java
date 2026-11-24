package com.utilitysuite.travel.movement;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.List;

/**
 * {@link MovementPhysics.Voxels} over the client level with exact collision shapes: the per-tick cell cache that the
 * parkour control model plans on (fence and wall tops at 1.5, panes, slabs, surface kinds). Client thread only;
 * {@link #begin} once per tick.
 * <p>
 * Two levels of caching:
 * <ul>
 *   <li><b>Session table</b>, one entry per {@link BlockState} seen: a short shape id (0 = no collision, 1 = full cube,
 *       others index a list of {@code float[6 * n]} block-local boxes read once from {@code getCollisionShape}), the
 *       surface byte and the slipperiness. Built on first sight of a state (that is the only allocation); identical
 *       {@link VoxelShape} instances share one box list.</li>
 *   <li><b>Tick cache</b>, cleared by {@link #begin}: cell ({@code BlockPos.asLong}) to session entry, so the search
 *       reads each cell from the level once per tick. The map keeps its arrays across ticks.</li>
 * </ul>
 * States whose shape depends on position or on the entity (offset blocks, dynamic shapes such as scaffolding, powder
 * snow) are {@link MovementPhysics#SURFACE_UNMODELLED}; their boxes are the ones read at first sight, which the
 * fail-safe rule never commits to.
 */
public final class LevelShapes implements MovementPhysics.Voxels {

    public static final short SHAPE_NONE = 0;
    public static final short SHAPE_CUBE = 1;
    private static final float[] CUBE = {0f, 0f, 0f, 1f, 1f, 1f};

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** cell -> session entry + 1 (0 = not read this tick) */
    private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap(1024);
    private final Reference2IntOpenHashMap<BlockState> entryOf = new Reference2IntOpenHashMap<>(256);
    private final Reference2IntOpenHashMap<VoxelShape> shapeOf = new Reference2IntOpenHashMap<>(128);
    // session table, indexed by entry
    private short[] entryShape = new short[256];
    private byte[] entrySurface = new byte[256];
    private boolean[] entryRepl = new boolean[256];
    private float[] entrySlip = new float[256];
    private int entries;
    // shape table, indexed by shape id
    private float[][] shapes = new float[64][];
    private int shapeCount;
    private Level level;

    public LevelShapes() {
        shapes[SHAPE_NONE] = new float[0];
        shapes[SHAPE_CUBE] = CUBE;
        shapeCount = 2;
        shapeOf.defaultReturnValue(-1);
        entryOf.defaultReturnValue(-1);
    }

    public LevelShapes begin(Level level) {
        this.level = level;
        this.source = null;
        cache.clear();
        return this;
    }

    /** Test seam: where a cell's session entry comes from when there is no level. */
    @FunctionalInterface
    interface CellSource {
        int entryAt(int x, int y, int z);
    }

    private CellSource source;

    /** Starts a tick that reads cells from {@code source} instead of a level (engine-free tests). */
    LevelShapes begin(CellSource source) {
        this.level = null;
        this.source = source;
        cache.clear();
        return this;
    }

    /**
     * Adds a session entry without a block state (tests): {@code boxes} null or empty = no collision, a single unit box
     * = the full-cube id, anything else a new box list. Returns the entry index for a {@link CellSource}.
     */
    int defineEntry(float[] boxes, byte surface, float slipperiness) {
        int e = entries++;
        if (e >= entryShape.length) {
            int n = entryShape.length * 2;
            entryShape = Arrays.copyOf(entryShape, n);
            entrySurface = Arrays.copyOf(entrySurface, n);
            entryRepl = Arrays.copyOf(entryRepl, n);
            entrySlip = Arrays.copyOf(entrySlip, n);
        }
        short id;
        if (boxes == null || boxes.length == 0) {
            id = SHAPE_NONE;
        } else if (Arrays.equals(boxes, CUBE)) {
            id = SHAPE_CUBE;
        } else {
            if (shapeCount >= shapes.length) {
                shapes = Arrays.copyOf(shapes, shapes.length * 2);
            }
            id = (short) shapeCount++;
            shapes[id] = boxes.clone();
        }
        entryShape[e] = id;
        entrySurface[e] = surface;
        entrySlip[e] = slipperiness;
        return e;
    }

    @Override
    public boolean shaped() {
        return true;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return entryShape[entry(x, y, z)] != SHAPE_NONE;
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        return entryShape[entry(x, y, z)] == SHAPE_CUBE;
    }

    @Override
    public boolean replaceable(int x, int y, int z) {
        return entryRepl[entry(x, y, z)];
    }

    @Override
    public int boxCount(int x, int y, int z) {
        return shapes[entryShape[entry(x, y, z)]].length / 6;
    }

    @Override
    public void box(int x, int y, int z, int i, float[] out, int off) {
        float[] b = shapes[entryShape[entry(x, y, z)]];
        System.arraycopy(b, i * 6, out, off, 6);
    }

    @Override
    public byte surface(int x, int y, int z) {
        return entrySurface[entry(x, y, z)];
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        return entrySlip[entry(x, y, z)];
    }

    /** Shape id of the cell (0 none, 1 full cube, others a session box list). */
    public short shapeId(int x, int y, int z) {
        return entryShape[entry(x, y, z)];
    }

    /** Distinct block states seen this session (for diagnostics and tests). */
    public int statesSeen() {
        return entries;
    }

    private int entry(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int v = cache.get(key);
        if (v == 0) {
            if (source != null) {
                v = source.entryAt(x, y, z) + 1;
            } else {
                pos.set(x, y, z);
                v = entryFor(level.getBlockState(pos)) + 1;
            }
            cache.put(key, v);
        }
        return v - 1;
    }

    /** Session entry of a state; built (allocating) only the first time the state is seen. */
    private int entryFor(BlockState st) {
        int e = entryOf.getInt(st);
        if (e >= 0) {
            return e;
        }
        e = entries++;
        if (e >= entryShape.length) {
            int n = entryShape.length * 2;
            entryShape = Arrays.copyOf(entryShape, n);
            entrySurface = Arrays.copyOf(entrySurface, n);
            entryRepl = Arrays.copyOf(entryRepl, n);
            entrySlip = Arrays.copyOf(entrySlip, n);
        }
        VoxelShape shape = st.isAir() ? Shapes.empty() : st.getCollisionShape(level, pos);
        entryShape[e] = shapeIdFor(shape);
        entrySurface[e] = surfaceOf(st, entryShape[e] != SHAPE_NONE);
        entryRepl[e] = LevelVoxels.isReplaceable(st);
        entrySlip[e] = st.getBlock().getFriction();
        entryOf.put(st, e);
        return e;
    }

    private short shapeIdFor(VoxelShape shape) {
        if (shape.isEmpty()) {
            return SHAPE_NONE;
        }
        if (Block.isShapeFullBlock(shape)) {
            return SHAPE_CUBE;
        }
        int id = shapeOf.getInt(shape);
        if (id >= 0) {
            return (short) id;
        }
        List<AABB> list = shape.toAabbs();
        float[] b = new float[list.size() * 6];
        for (int i = 0; i < list.size(); i++) {
            AABB a = list.get(i);
            b[i * 6] = (float) a.minX;
            b[i * 6 + 1] = (float) a.minY;
            b[i * 6 + 2] = (float) a.minZ;
            b[i * 6 + 3] = (float) a.maxX;
            b[i * 6 + 4] = (float) a.maxY;
            b[i * 6 + 5] = (float) a.maxZ;
        }
        if (shapeCount >= shapes.length) {
            shapes = Arrays.copyOf(shapes, shapes.length * 2);
        }
        if (shapeCount > Short.MAX_VALUE) {
            return SHAPE_CUBE; // never in practice; a cube is the conservative stand-in
        }
        id = shapeCount++;
        shapes[id] = b;
        shapeOf.put(shape, id);
        return (short) id;
    }

    /** The surface byte of a state (see {@code MovementPhysics.SURFACE_*}); first sight only. */
    static byte surfaceOf(BlockState st, boolean collides) {
        if (st.isAir()) {
            return MovementPhysics.SURFACE_NONE;
        }
        Block b = st.getBlock();
        if (st.hasOffsetFunction() || b.hasDynamicShape() || b == Blocks.SCAFFOLDING || b == Blocks.POWDER_SNOW) {
            return MovementPhysics.SURFACE_UNMODELLED;
        }
        FluidState fluid = st.getFluidState();
        if (fluid.is(FluidTags.LAVA) || st.is(BlockTags.FIRE) || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.MAGMA_BLOCK || b == Blocks.WITHER_ROSE || st.is(BlockTags.CAMPFIRES)) {
            return MovementPhysics.SURFACE_HARM;
        }
        if (b == Blocks.COBWEB) {
            return MovementPhysics.SURFACE_STUCK;
        }
        if (st.is(BlockTags.CLIMBABLE)) {
            return MovementPhysics.SURFACE_CLIMBABLE;
        }
        if (fluid.is(FluidTags.WATER)) {
            return MovementPhysics.SURFACE_WATER;
        }
        if (b == Blocks.SLIME_BLOCK) {
            return MovementPhysics.SURFACE_SLIME;
        }
        if (b == Blocks.HONEY_BLOCK) {
            return MovementPhysics.SURFACE_HONEY;
        }
        if (b.getSpeedFactor() < 1.0f || b.getJumpFactor() < 1.0f) {
            return MovementPhysics.SURFACE_SOUL_SAND;
        }
        if (b.getFriction() != (float) MovementPhysics.DEFAULT_SLIPPERINESS) {
            return MovementPhysics.SURFACE_ICE;
        }
        return collides ? MovementPhysics.SURFACE_NORMAL : MovementPhysics.SURFACE_NONE;
    }
}
