package com.utilitysuite.travel.movement;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A progressive parkour course (the movement benchmark): a run-up, then segments of rising difficulty along +Z, each
 * a gap to a landing pad that doubles as the run-up for the next one. Course-local block coordinates with the start
 * floor at y = 0; the in-game playtest builds the same boxes at an offset, the unit benchmark uses this class directly
 * as {@link MovementPhysics.Voxels}. Pure data, no game access.
 * <p>
 * A pad can be a shaped landing ({@link Landing}): fences and walls (top 1.5 above their cell), panes, bottom slabs.
 * A course with any shaped pad answers the shape path of {@link MovementPhysics.Voxels} with the vanilla 1.21.1 boxes
 * (fences connect to fences, walls to walls, panes to panes along the pad); an all-FULL course stays a cube grid, so
 * the {@link #STANDARD} benchmark runs exactly as before.
 */
public final class ParkourCourse implements MovementPhysics.Voxels {

    /** What a pad is built from. Heights are of the top surface above the pad's cell. */
    public enum Landing {
        /** Full blocks, top at +1. */
        FULL(1.0),
        /** Fence row: post and arms 0.25 wide, top at +1.5. */
        FENCE(1.5),
        /** Wall row: post 0.5 wide, sides 0.375, top at +1.5. */
        WALL(1.5),
        /** Glass pane / iron bars row: 0.125 wide, top at +1. */
        PANE(1.0),
        /** Bottom slabs, top at +0.5. */
        SLAB_BOTTOM(0.5);

        /** Top of the landing above the pad's cell. */
        public final double top;

        Landing(double top) {
            this.top = top;
        }
    }

    /**
     * One jump: {@code gap} air blocks, landing {@code dy} higher (negative = lower) and shifted {@code side} blocks in
     * X, onto a pad {@code width} blocks wide (1 = a strip) and {@code length} long. {@code ceiling} > 0 puts a
     * block row that many blocks above the takeoff floor over the gap and the last two takeoff blocks (head hitter).
     * {@code dy} moves the pad's cell; the feet land at the cell plus {@link Landing#top}, so a fence pad with dy 0 is
     * half a block above the takeoff.
     */
    public record Segment(String name, int gap, int dy, int side, int width, int length, int ceiling,
                          Landing landing) {
        public Segment(String name, int gap, int dy, int side, int width, int length) {
            this(name, gap, dy, side, width, length, 0, Landing.FULL);
        }

        public Segment(String name, int gap, int dy, int side, int width, int length, int ceiling) {
            this(name, gap, dy, side, width, length, ceiling, Landing.FULL);
        }
    }

    /** The standard benchmark, easiest first. Every segment is clearable by a vanilla sprint jump. */
    public static final List<Segment> STANDARD = List.of(
            new Segment("1 gap", 1, 0, 0, 3, 5),
            new Segment("2 gap", 2, 0, 0, 3, 5),
            new Segment("3 gap", 3, 0, 0, 3, 5),
            new Segment("up 1, 1 gap", 1, 1, 0, 3, 5),
            new Segment("up 1, 2 gap", 2, 1, 0, 3, 5),
            new Segment("down 1, 3 gap", 3, -1, 0, 3, 5),
            new Segment("2 gap, 1 sideways", 2, 0, 1, 3, 5),
            new Segment("2 gap onto a strip", 2, 0, 0, 1, 5),
            new Segment("3 gap, 1 sideways", 3, 0, -1, 3, 5),
            new Segment("up 1, 2 gap onto a strip", 2, 1, 0, 1, 5),
            new Segment("down 2, 3 gap, 2 sideways", 3, -2, 2, 3, 5),
            new Segment("3 gap onto a strip, 1 sideways", 3, 0, -1, 1, 3),
            // expert tier: momentum, ceilings, long drops, strip to strip
            new Segment("3 gap from a 3-block run-up", 3, 0, 1, 1, 3),
            new Segment("low ceiling, 2 gap", 2, 0, 0, 3, 5, 4),
            new Segment("down 3, 4 gap", 4, -3, 0, 3, 5),
            new Segment("low ceiling, 3 gap", 3, 0, 0, 3, 4, 4),
            new Segment("strip to strip, 3 gap, 1 sideways", 3, 0, -1, 1, 4),
            new Segment("up 1, 2 gap, 1 sideways onto a strip", 2, 1, 1, 1, 4),
            new Segment("down 1, 3 gap, 2 sideways onto a strip", 3, -1, -2, 1, 6));

    /**
     * Shaped-landing and corner cases (design 2026-11-22, section 11). Each entry is run as its own one-segment course
     * from the standard run-up ({@link #edgeCase}), so a narrow landing is never the take-off of the next jump. A
     * corner case lands diagonally past the run-up's corner (one air column sideways, one air row ahead).
     */
    public static final List<Segment> EDGE_CASES = List.of(
            new Segment("2 gap onto a fence post", 2, 0, 0, 1, 1, 0, Landing.FENCE),
            new Segment("2 gap onto a fence row", 2, 0, 0, 1, 3, 0, Landing.FENCE),
            new Segment("3 gap onto a wall", 3, 0, 0, 1, 1, 0, Landing.WALL),
            new Segment("corner onto a block", 1, 0, 3, 1, 1),
            new Segment("corner onto a fence", 1, 0, 3, 1, 1, 0, Landing.FENCE),
            new Segment("1 gap onto a pane", 1, 0, 0, 1, 1, 0, Landing.PANE),
            new Segment("slab up", 1, 1, 0, 3, 3, 0, Landing.SLAB_BOTTOM));

    public static final int RUNUP = 8;

    private final List<Segment> segments;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Landing> boxLanding = new ArrayList<>();
    private final LongOpenHashSet solid = new LongOpenHashSet();
    /** Shaped cells only: cell -> Landing ordinal. */
    private final Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
    private final boolean shaped;
    // per pad (index 0 = start runway): centre x, floor y, first and last z
    private final int[] padX;
    private final int[] padY;
    private final int[] padZ0;
    private final int[] padZ1;
    private final int[] padHalf;
    private final Landing[] padLanding;

    public ParkourCourse(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        int n = segments.size() + 1;
        padX = new int[n];
        padY = new int[n];
        padZ0 = new int[n];
        padZ1 = new int[n];
        padHalf = new int[n];
        padLanding = new Landing[n];
        padLanding[0] = Landing.FULL;
        padHalf[0] = 1;
        padZ1[0] = RUNUP - 1;
        boolean anyShape = false;
        box(-1, 0, 0, 1, 0, RUNUP - 1, Landing.FULL);
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            int p = i + 1;
            padX[p] = padX[i] + s.side();
            padY[p] = padY[i] + s.dy();
            padZ0[p] = padZ1[i] + s.gap() + 1;
            padZ1[p] = padZ0[p] + s.length() - 1;
            padHalf[p] = s.width() / 2;
            padLanding[p] = s.landing();
            anyShape |= s.landing() != Landing.FULL;
            box(padX[p] - padHalf[p], padY[p], padZ0[p], padX[p] + padHalf[p], padY[p], padZ1[p], s.landing());
            if (s.ceiling() > 0) {
                int cy = padY[i] + s.ceiling();
                int x0 = Math.min(padX[i] - padHalf[i], padX[p] - padHalf[p]) - 1;
                int x1 = Math.max(padX[i] + padHalf[i], padX[p] + padHalf[p]) + 1;
                box(x0, cy, padZ1[i] - 1, x1, cy, padZ0[p] - 1, Landing.FULL);
            }
        }
        shaped = anyShape;
    }

    public static ParkourCourse standard() {
        return new ParkourCourse(STANDARD);
    }

    /** Edge case {@code i} of {@link #EDGE_CASES} as a one-segment course. */
    public static ParkourCourse edgeCase(int i) {
        return new ParkourCourse(List.of(EDGE_CASES.get(i)));
    }

    private void box(int x0, int y0, int z0, int x1, int y1, int z1, Landing landing) {
        boxes.add(new int[]{x0, y0, z0, x1, y1, z1});
        boxLanding.add(landing);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    long key = BlockPos.asLong(x, y, z);
                    solid.add(key);
                    if (landing != Landing.FULL) {
                        kinds.put(key, (byte) landing.ordinal());
                    } else {
                        kinds.remove(key);
                    }
                }
            }
        }
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return solid.contains(BlockPos.asLong(x, y, z));
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        return solid.contains(key) && !kinds.containsKey(key);
    }

    @Override
    public boolean shaped() {
        return shaped;
    }

    /** Landing kind of a solid cell (FULL for cubes), or null for air. */
    public Landing landingAt(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (!solid.contains(key)) {
            return null;
        }
        return kinds.containsKey(key) ? Landing.values()[kinds.get(key)] : Landing.FULL;
    }

    @Override
    public int boxCount(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (!solid.contains(key)) {
            return 0;
        }
        if (!kinds.containsKey(key)) {
            return 1;
        }
        int kind = kinds.get(key);
        if (kind == Landing.SLAB_BOTTOM.ordinal()) {
            return 1;
        }
        int arms = Integer.bitCount(connections(x, y, z, kind));
        return (hasPost(x, y, z, kind) ? 1 : 0) + arms;
    }

    @Override
    public void box(int x, int y, int z, int i, float[] out, int off) {
        long key = BlockPos.asLong(x, y, z);
        int kind = kinds.containsKey(key) ? kinds.get(key) : Landing.FULL.ordinal();
        if (kind == Landing.FULL.ordinal()) {
            put(out, off, 0f, 0f, 0f, 1f, 1f, 1f);
            return;
        }
        if (kind == Landing.SLAB_BOTTOM.ordinal()) {
            put(out, off, 0f, 0f, 0f, 1f, 0.5f, 1f);
            return;
        }
        // vanilla CrossCollisionBlock / WallBlock collision boxes, in sixteenths
        float h;
        float post0;
        float arm0;
        if (kind == Landing.FENCE.ordinal()) {
            h = 1.5f;
            post0 = 6f / 16f;
            arm0 = 6f / 16f;
        } else if (kind == Landing.WALL.ordinal()) {
            h = 1.5f;
            post0 = 4f / 16f;
            arm0 = 5f / 16f;
        } else { // PANE
            h = 1.0f;
            post0 = 7f / 16f;
            arm0 = 7f / 16f;
        }
        float post1 = 1f - post0;
        float arm1 = 1f - arm0;
        if (hasPost(x, y, z, kind)) {
            if (i == 0) {
                put(out, off, post0, 0f, post0, post1, h, post1);
                return;
            }
            i--;
        }
        int conn = connections(x, y, z, kind);
        int dir = 0;
        for (int bit = 0; bit < 4; bit++) {
            if ((conn & (1 << bit)) != 0) {
                if (dir == i) {
                    switch (bit) {
                        case 0 -> put(out, off, arm0, 0f, 0f, arm1, h, arm1);      // north (-Z)
                        case 1 -> put(out, off, arm0, 0f, arm0, arm1, h, 1f);      // south (+Z)
                        case 2 -> put(out, off, 0f, 0f, arm0, arm1, h, arm1);      // west (-X)
                        default -> put(out, off, arm0, 0f, arm0, 1f, h, arm1);     // east (+X)
                    }
                    return;
                }
                dir++;
            }
        }
        put(out, off, post0, 0f, post0, post1, h, post1);
    }

    /** Bits 0..3 = north, south, west, east neighbour of the same kind. */
    private int connections(int x, int y, int z, int kind) {
        int c = 0;
        if (sameKind(x, y, z - 1, kind)) {
            c |= 1;
        }
        if (sameKind(x, y, z + 1, kind)) {
            c |= 2;
        }
        if (sameKind(x - 1, y, z, kind)) {
            c |= 4;
        }
        if (sameKind(x + 1, y, z, kind)) {
            c |= 8;
        }
        return c;
    }

    /** Fences and panes always have their centre box; a wall drops it when it runs straight through (no post). */
    private boolean hasPost(int x, int y, int z, int kind) {
        if (kind != Landing.WALL.ordinal()) {
            return true;
        }
        int c = connections(x, y, z, kind);
        return c != 0b0011 && c != 0b1100;
    }

    private boolean sameKind(int x, int y, int z, int kind) {
        long key = BlockPos.asLong(x, y, z);
        return kinds.containsKey(key) && kinds.get(key) == kind;
    }

    private static void put(float[] out, int off, float a, float b, float c, float d, float e, float f) {
        out[off] = a;
        out[off + 1] = b;
        out[off + 2] = c;
        out[off + 3] = d;
        out[off + 4] = e;
        out[off + 5] = f;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** Solid boxes {x0, y0, z0, x1, y1, z1} in course coordinates (for /fill). */
    public List<int[]> boxes() {
        return Collections.unmodifiableList(boxes);
    }

    /** What box {@code i} of {@link #boxes()} is built from (the playtest picks the block from it). */
    public Landing boxLanding(int i) {
        return boxLanding.get(i);
    }

    /** Bounds {minX, minY, minZ, maxX, maxY, maxZ} of everything solid. */
    public int[] bounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] x : boxes) {
            for (int k = 0; k < 3; k++) {
                b[k] = Math.min(b[k], x[k]);
                b[k + 3] = Math.max(b[k + 3], x[k + 3]);
            }
        }
        return b;
    }

    /** Number of pads, start runway included (= segments + 1). */
    public int pads() {
        return padX.length;
    }

    public int padY(int pad) {
        return padY[pad];
    }

    public Landing padLanding(int pad) {
        return padLanding[pad];
    }

    /** Feet height on the pad's top surface. */
    public double padTop(int pad) {
        return padY[pad] + padLanding[pad].top;
    }

    /** Pad the given feet position stands on (course coordinates), or -1. */
    public int padAt(double x, double y, double z) {
        int fy = (int) Math.floor(y - 0.01) ;
        for (int p = 0; p < padX.length; p++) {
            if (padLanding[p] == Landing.FULL) {
                if (fy == padY[p] && z + 0.3 > padZ0[p] && z - 0.3 < padZ1[p] + 1
                        && x + 0.3 > padX[p] - padHalf[p] && x - 0.3 < padX[p] + padHalf[p] + 1) {
                    return p;
                }
                continue;
            }
            double inset = switch (padLanding[p]) {
                case FENCE -> 6.0 / 16.0;
                case WALL -> 4.0 / 16.0;
                case PANE -> 7.0 / 16.0;
                default -> 0.0;
            };
            if (Math.abs(y - padTop(p)) < 0.02 && z + 0.3 > padZ0[p] + inset && z - 0.3 < padZ1[p] + 1 - inset
                    && x + 0.3 > padX[p] - padHalf[p] + inset && x - 0.3 < padX[p] + padHalf[p] + 1 - inset) {
                return p;
            }
        }
        return -1;
    }

    /**
     * Where a runner on {@code pad} should head: the middle of the next pad's first two blocks (a player's natural
     * aim point). The last pad aims at its own far end.
     */
    public double aimX(int pad) {
        int p = Math.min(pad + 1, padX.length - 1);
        return padX[p] + 0.5;
    }

    public double aimZ(int pad) {
        if (pad + 1 >= padX.length) {
            return padZ1[pad] + 0.5;
        }
        return padZ0[pad + 1] + 1.0;
    }

    /** Start position (course coordinates): middle of the runway, feet on its floor. */
    public double startX() {
        return 0.5;
    }

    public double startZ() {
        return 0.5;
    }
}
