package com.utilitysuite.travel.movement;

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
 */
public final class ParkourCourse implements MovementPhysics.Voxels {

    /**
     * One jump: {@code gap} air blocks, landing {@code dy} higher (negative = lower) and shifted {@code side} blocks in
     * X, onto a pad {@code width} blocks wide (1 = a strip) and {@code length} long. {@code ceiling} > 0 puts a
     * block row that many blocks above the takeoff floor over the gap and the last two takeoff blocks (head hitter).
     */
    public record Segment(String name, int gap, int dy, int side, int width, int length, int ceiling) {
        public Segment(String name, int gap, int dy, int side, int width, int length) {
            this(name, gap, dy, side, width, length, 0);
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

    public static final int RUNUP = 8;

    private final List<Segment> segments;
    private final List<int[]> boxes = new ArrayList<>();
    private final LongOpenHashSet solid = new LongOpenHashSet();
    // per pad (index 0 = start runway): centre x, floor y, first and last z
    private final int[] padX;
    private final int[] padY;
    private final int[] padZ0;
    private final int[] padZ1;
    private final int[] padHalf;

    public ParkourCourse(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        int n = segments.size() + 1;
        padX = new int[n];
        padY = new int[n];
        padZ0 = new int[n];
        padZ1 = new int[n];
        padHalf = new int[n];
        padHalf[0] = 1;
        padZ1[0] = RUNUP - 1;
        box(-1, 0, 0, 1, 0, RUNUP - 1);
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            int p = i + 1;
            padX[p] = padX[i] + s.side();
            padY[p] = padY[i] + s.dy();
            padZ0[p] = padZ1[i] + s.gap() + 1;
            padZ1[p] = padZ0[p] + s.length() - 1;
            padHalf[p] = s.width() / 2;
            box(padX[p] - padHalf[p], padY[p], padZ0[p], padX[p] + padHalf[p], padY[p], padZ1[p]);
            if (s.ceiling() > 0) {
                int cy = padY[i] + s.ceiling();
                int x0 = Math.min(padX[i] - padHalf[i], padX[p] - padHalf[p]) - 1;
                int x1 = Math.max(padX[i] + padHalf[i], padX[p] + padHalf[p]) + 1;
                box(x0, cy, padZ1[i] - 1, x1, cy, padZ0[p] - 1);
            }
        }
    }

    public static ParkourCourse standard() {
        return new ParkourCourse(STANDARD);
    }

    private void box(int x0, int y0, int z0, int x1, int y1, int z1) {
        boxes.add(new int[]{x0, y0, z0, x1, y1, z1});
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    solid.add(BlockPos.asLong(x, y, z));
                }
            }
        }
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return solid.contains(BlockPos.asLong(x, y, z));
    }

    public List<Segment> segments() {
        return segments;
    }

    /** Solid boxes {x0, y0, z0, x1, y1, z1} in course coordinates (for /fill). */
    public List<int[]> boxes() {
        return Collections.unmodifiableList(boxes);
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

    /** Pad the given feet position stands on (course coordinates), or -1. */
    public int padAt(double x, double y, double z) {
        int fy = (int) Math.floor(y - 0.01) ;
        for (int p = 0; p < padX.length; p++) {
            if (fy == padY[p] && z + 0.3 > padZ0[p] && z - 0.3 < padZ1[p] + 1
                    && x + 0.3 > padX[p] - padHalf[p] && x - 0.3 < padX[p] + padHalf[p] + 1) {
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
