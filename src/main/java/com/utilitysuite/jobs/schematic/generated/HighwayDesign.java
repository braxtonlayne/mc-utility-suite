package com.utilitysuite.jobs.schematic.generated;

import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Engine-free generator for highway segments between two waypoints, as {@link SchematicVolume}s Build Assist can place
 * (format {@code "generated"}, name {@code generated:highway/<mode>#<segment>}). The path is snapped to the nearest of
 * the eight compass directions (straight or 45-degree diagonal) from the start; segments of {@code segmentLength}
 * steps are generated one at a time ahead of the player ({@link #segmentAhead}) so the job never holds the whole road.
 * <p>
 * Layers are relative to the floor (one below the start waypoint's feet, volume y 0):
 * <ul>
 *   <li>{@link Mode#TUNNEL} (Nether roof or tunnel): 3-wide floor (placed only where missing when a probe says the
 *   cell is already solid), {@code height} rows of air above it (returned as {@link Segment#digCells} for the digging
 *   path), optional spawn-proofing (bottom-slab floor or floor buttons), and a seal block in wall/ceiling cells where
 *   the probe reports fluid.</li>
 *   <li>{@link Mode#ICE} (for the boat driver): ice floor 1-2 wide, one-block side rails on a rail footing, two rows
 *   of headroom.</li>
 * </ul>
 * Diagonal paths add the cell beside each step on the X side so the floor stays edge-connected (boats and walking
 * cannot cross a corner-only gap).
 */
public final class HighwayDesign {

    public enum Mode { TUNNEL, ICE }

    public enum SpawnProof { NONE, SLAB, BUTTON }

    /** Design settings (pro defaults). */
    public static final class Spec {
        public Mode mode = Mode.TUNNEL;
        /** Floor width; tunnel default 3, ice 1-2. */
        public int width = 3;
        /** Air rows above the tunnel floor. */
        public int height = 3;
        public int segmentLength = 32;
        public String floorBlock = "minecraft:netherrack";
        public String iceBlock = "minecraft:packed_ice";
        public String railBlock = "minecraft:netherrack";
        public String sealBlock = "minecraft:netherrack";
        public SpawnProof spawnProof = SpawnProof.NONE;
        public String slabBlock = "minecraft:blackstone_slab";
        public String buttonBlock = "minecraft:polished_blackstone_button";
    }

    /** World facts the generator may consult (optional). */
    public interface Probe {
        boolean solidAt(int x, int y, int z);

        boolean fluidAt(int x, int y, int z);
    }

    /** One generated segment; the volume's world position is {@code anchor + offset + cell}. */
    public record Segment(int index, int fromStep, int toStep, SchematicVolume volume, int anchorX, int anchorY,
                          int anchorZ, long[] digCells) {
    }

    private HighwayDesign() {
    }

    /** X step of the snapped direction (-1, 0, 1). */
    public static int dirX(int dx, int dz) {
        return snap(dx, dz)[0];
    }

    /** Z step of the snapped direction (-1, 0, 1). */
    public static int dirZ(int dx, int dz) {
        return snap(dx, dz)[1];
    }

    private static int[] snap(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return new int[]{0, 0};
        }
        double ang = Math.atan2(dz, dx);
        int oct = (int) Math.round(ang / (Math.PI / 4)) & 7;
        int[][] dirs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        return dirs[oct];
    }

    /** Steps along the snapped direction to reach (the projection of) the end waypoint. */
    public static int steps(int dx, int dz) {
        int sx = dirX(dx, dz);
        int sz = dirZ(dx, dz);
        if (sx != 0 && sz != 0) {
            return Math.max(0, Math.min(dx * sx, dz * sz));
        }
        return Math.max(0, sx != 0 ? dx * sx : dz * sz);
    }

    public static int segmentCount(int steps, int segmentLength) {
        return steps <= 0 ? 0 : (steps + segmentLength) / segmentLength;
    }

    /**
     * Segment index that should exist given the player's progress (steps along the path) and a look-ahead in steps:
     * the segment containing {@code progress + lookahead}.
     */
    public static int segmentAhead(int progressSteps, int lookahead, int segmentLength, int totalSegments) {
        int idx = Math.max(0, progressSteps + lookahead) / segmentLength;
        return Math.min(idx, Math.max(0, totalSegments - 1));
    }

    /**
     * Generates segment {@code index} of the road from start feet position (sx, sy, sz) towards (ex, ez).
     *
     * @param probe optional world facts (null: place the full floor, never seal)
     */
    public static Segment segment(Spec spec, int sx, int sy, int sz, int ex, int ez, int index, Probe probe) {
        int dx = dirX(ex - sx, ez - sz);
        int dz = dirZ(ex - sx, ez - sz);
        int total = steps(ex - sx, ez - sz);
        int from = index * spec.segmentLength;
        int to = Math.min(total, from + spec.segmentLength - 1);
        if (from > to) {
            throw new IllegalArgumentException("Segment " + index + " beyond the path (" + total + " steps)");
        }
        int floorY = sy - 1;
        Bounds b = new Bounds();
        emit(spec, sx, floorY, sz, dx, dz, from, to, null, b);
        SchematicVolume.Builder vb = SchematicVolume.builder("generated:highway/"
                        + spec.mode.name().toLowerCase(java.util.Locale.ROOT) + "#" + index, "generated",
                b.maxX - b.minX + 1, b.maxY - b.minY + 1, b.maxZ - b.minZ + 1)
                .offset(b.minX - sx, b.minY - sy, b.minZ - sz);
        LongArrayList dig = new LongArrayList();
        Writer w = new Writer(vb, b, probe, dig);
        emit(spec, sx, floorY, sz, dx, dz, from, to, w, null);
        return new Segment(index, from, to, vb.build(), sx, sy, sz, dig.toLongArray());
    }

    // ---- cell emission ------------------------------------------------------------------------------------------

    private static final int FLOOR = 0;
    private static final int OPEN = 1;
    private static final int RAIL = 2;
    private static final int SHELL = 3;
    private static final int BUTTON = 4;

    private static void emit(Spec spec, int sx, int floorY, int sz, int dx, int dz, int from, int to, Writer w,
                             Bounds b) {
        boolean diagonal = dx != 0 && dz != 0;
        int px = -dz;
        int pz = dx;
        int width = spec.mode == Mode.ICE ? Math.max(1, Math.min(2, spec.width)) : Math.max(1, spec.width);
        int kMin = -(width - 1) / 2;
        int kMax = width / 2;
        int height = spec.mode == Mode.ICE ? 2 : Math.max(2, spec.height);
        for (int i = from; i <= to; i++) {
            int cx = sx + i * dx;
            int cz = sz + i * dz;
            for (int k = kMin - 1; k <= kMax + 1; k++) {
                boolean edge = k < kMin || k > kMax;
                int x = cx + k * px;
                int z = cz + k * pz;
                column(spec, x, floorY, z, edge, height, w, b);
                if (diagonal) {
                    column(spec, x + dx, floorY, z, edge, height, w, b);
                }
            }
        }
    }

    private static void column(Spec spec, int x, int floorY, int z, boolean edge, int height, Writer w, Bounds b) {
        if (spec.mode == Mode.ICE) {
            if (edge) {
                put(x, floorY, z, RAIL, spec, w, b);
                put(x, floorY + 1, z, RAIL, spec, w, b);
            } else {
                put(x, floorY, z, FLOOR, spec, w, b);
                for (int y = 1; y <= height; y++) {
                    put(x, floorY + y, z, OPEN, spec, w, b);
                }
            }
            return;
        }
        if (edge) {
            for (int y = 1; y <= height; y++) {
                put(x, floorY + y, z, SHELL, spec, w, b);
            }
            return;
        }
        put(x, floorY, z, FLOOR, spec, w, b);
        for (int y = 1; y <= height; y++) {
            put(x, floorY + y, z, y == 1 && spec.spawnProof == SpawnProof.BUTTON ? BUTTON : OPEN, spec, w, b);
        }
        put(x, floorY + height + 1, z, SHELL, spec, w, b);
    }

    private static void put(int x, int y, int z, int kind, Spec spec, Writer w, Bounds b) {
        if (b != null) {
            b.add(x, y, z);
            return;
        }
        w.write(x, y, z, kind, spec);
    }

    private static final class Bounds {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        void add(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
    }

    /** Rank of a cell kind: a stronger kind wins where columns overlap (diagonals, rails next to floor). */
    private static int rank(int kind) {
        return switch (kind) {
            case OPEN, BUTTON -> 4;
            case FLOOR -> 3;
            case RAIL -> 2;
            case SHELL -> 1;
            default -> 0;
        };
    }

    private static final class Writer {
        final SchematicVolume.Builder vb;
        final Bounds b;
        final Probe probe;
        final LongArrayList dig;
        final byte[] ranks;

        Writer(SchematicVolume.Builder vb, Bounds b, Probe probe, LongArrayList dig) {
            this.vb = vb;
            this.b = b;
            this.probe = probe;
            this.dig = dig;
            this.ranks = new byte[vb.sizeX() * vb.sizeY() * vb.sizeZ()];
        }

        void write(int x, int y, int z, int kind, Spec spec) {
            int lx = x - b.minX;
            int ly = y - b.minY;
            int lz = z - b.minZ;
            int cell = (ly * vb.sizeZ() + lz) * vb.sizeX() + lx;
            int r = rank(kind);
            if (r <= ranks[cell]) {
                return;
            }
            ranks[cell] = (byte) r;
            switch (kind) {
                case FLOOR -> {
                    if (spec.mode == Mode.ICE) {
                        vb.set(lx, ly, lz, spec.iceBlock);
                    } else if (spec.spawnProof == SpawnProof.SLAB) {
                        vb.set(lx, ly, lz, spec.slabBlock + "[type=bottom]");
                    } else if (probe == null || !probe.solidAt(x, y, z)) {
                        vb.set(lx, ly, lz, spec.floorBlock);
                    }
                }
                case OPEN -> {
                    vb.set(lx, ly, lz, SchematicVolume.AIR);
                    if (probe == null || probe.solidAt(x, y, z) || probe.fluidAt(x, y, z)) {
                        dig.add(PackedPos.pack(x, y, z));
                    }
                }
                case BUTTON -> {
                    vb.set(lx, ly, lz, spec.buttonBlock + "[face=floor]");
                    if (probe == null || probe.solidAt(x, y, z)) {
                        dig.add(PackedPos.pack(x, y, z));
                    }
                }
                case RAIL -> vb.set(lx, ly, lz, spec.railBlock);
                case SHELL -> {
                    if (probe != null && probe.fluidAt(x, y, z)) {
                        vb.set(lx, ly, lz, spec.sealBlock);
                    }
                }
                default -> {
                }
            }
        }
    }
}
