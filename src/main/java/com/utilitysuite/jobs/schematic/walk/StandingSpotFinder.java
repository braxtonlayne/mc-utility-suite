package com.utilitysuite.jobs.schematic.walk;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import com.utilitysuite.jobs.schematic.world.WorldProbe;
import net.minecraft.core.BlockPos;

/**
 * Finds a spot to stand on from which a build cell can be placed with vanilla reach. Engine-agnostic (only
 * {@link WorldProbe} and packed positions), so it is unit-testable without a world.
 * <p>
 * A candidate is a feet block with an air (or replaceable) feet and head cell above a solid floor ({@code W_OTHER}),
 * neither of which is a block cell of the build itself, whose eye point (feet centre + {@value #EYE_HEIGHT}) is
 * within {@code reach - }{@value #REACH_MARGIN} of the target's centre and of the centre of an exposed support face:
 * a solid neighbour of the target (the same "support face" Build Assist clicks against), seen from the target's side
 * of the face with no solid block on the straight line from the eye to the face centre. Cheap checks only; the
 * navigator does the real pathing. Not for the hot path: called once per stall, reuses its arrays.
 */
public final class StandingSpotFinder {

    public static final double EYE_HEIGHT = 1.62;
    /** Slack for where the navigator actually stops inside the feet block. */
    public static final double REACH_MARGIN = 0.75;
    private static final double LOS_STEP = 0.2;

    // Same order as net.minecraft.core.Direction.values(): DOWN, UP, NORTH, SOUTH, WEST, EAST.
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private final long[] best;
    private final double[] bestDist;

    public StandingSpotFinder(int maxCandidates) {
        best = new long[Math.max(1, maxCandidates)];
        bestDist = new double[best.length];
    }

    /**
     * @param world      the world
     * @param volume     the schematic (its block cells are never used as standing cells), or null
     * @param ox         schematic origin in the world
     * @param tx         target cell in the world
     * @param px         player feet position (candidates are sorted by distance to it)
     * @param reach      the player's block interaction range
     * @param out        receives packed feet positions ({@link BlockPos#asLong}), nearest to the player first
     * @return the number of positions written to {@code out}
     */
    public int find(WorldProbe world, SchematicVolume volume, int ox, int oy, int oz, int tx, int ty, int tz,
                    double px, double py, double pz, double reach, long[] out) {
        int max = Math.min(out.length, best.length);
        if (max == 0) {
            return 0;
        }
        double r = reach - REACH_MARGIN;
        if (r <= 0) {
            return 0;
        }
        double r2 = r * r;
        int rad = (int) Math.ceil(r) + 1;
        int count = 0;
        for (int fy = ty - rad - 2; fy <= ty + rad; fy++) {
            for (int fx = tx - rad; fx <= tx + rad; fx++) {
                for (int fz = tz - rad; fz <= tz + rad; fz++) {
                    double ex = fx + 0.5, ey = fy + EYE_HEIGHT, ez = fz + 0.5;
                    double cx = tx + 0.5 - ex, cy = ty + 0.5 - ey, cz = tz + 0.5 - ez;
                    if (cx * cx + cy * cy + cz * cz > r2) {
                        continue;
                    }
                    if (!isStandable(world, volume, ox, oy, oz, tx, ty, tz, fx, fy, fz)
                            || !seesSupportFace(world, tx, ty, tz, fx, fy, fz, ex, ey, ez, r2)) {
                        continue;
                    }
                    double dx = fx + 0.5 - px, dy = fy - py, dz = fz + 0.5 - pz;
                    double d = dx * dx + dy * dy + dz * dz;
                    // Insertion into the sorted top-N.
                    int i = count < max ? count++ : max;
                    if (i == max && d >= bestDist[max - 1]) {
                        continue;
                    }
                    if (i == max) {
                        i = max - 1;
                    }
                    while (i > 0 && bestDist[i - 1] > d) {
                        best[i] = best[i - 1];
                        bestDist[i] = bestDist[i - 1];
                        i--;
                    }
                    best[i] = BlockPos.asLong(fx, fy, fz);
                    bestDist[i] = d;
                }
            }
        }
        System.arraycopy(best, 0, out, 0, count);
        return count;
    }

    /** Two passable cells (feet, head) over a solid floor, none of them the target or a block cell of the build. */
    static boolean isStandable(WorldProbe world, SchematicVolume volume, int ox, int oy, int oz,
                               int tx, int ty, int tz, int fx, int fy, int fz) {
        if (fx == tx && fz == tz && (fy == ty || fy + 1 == ty)) {
            return false;
        }
        if (isBuildCell(volume, ox, oy, oz, fx, fy, fz) || isBuildCell(volume, ox, oy, oz, fx, fy + 1, fz)) {
            return false;
        }
        return world.probe(fx, fy - 1, fz, -1) == PlacementStatus.W_OTHER
                && world.probe(fx, fy, fz, -1) == PlacementStatus.W_EMPTY
                && world.probe(fx, fy + 1, fz, -1) == PlacementStatus.W_EMPTY;
    }

    static boolean isBuildCell(SchematicVolume v, int ox, int oy, int oz, int x, int y, int z) {
        if (v == null) {
            return false;
        }
        int lx = x - ox, ly = y - oy, lz = z - oz;
        return v.contains(lx, ly, lz) && v.isBlock(v.paletteIndex(lx, ly, lz));
    }

    /** Any solid neighbour of the target whose shared face is in reach and visible from the eye. */
    private static boolean seesSupportFace(WorldProbe world, int tx, int ty, int tz, int fx, int fy, int fz,
                                           double ex, double ey, double ez, double r2) {
        for (int d = 0; d < 6; d++) {
            if (world.probe(tx + DX[d], ty + DY[d], tz + DZ[d], -1) != PlacementStatus.W_OTHER) {
                continue;
            }
            // Face centre between the target and its support.
            double hx = tx + 0.5 + DX[d] * 0.5, hy = ty + 0.5 + DY[d] * 0.5, hz = tz + 0.5 + DZ[d] * 0.5;
            double vx = hx - ex, vy = hy - ey, vz = hz - ez;
            if (vx * vx + vy * vy + vz * vz > r2) {
                continue;
            }
            // The clicked face points from the support into the target: the eye must be on that side.
            if (vx * DX[d] + vy * DY[d] + vz * DZ[d] <= 0.05) {
                continue;
            }
            if (clearLine(world, tx, ty, tz, fx, fy, fz, ex, ey, ez, hx - DX[d] * 0.02, hy - DY[d] * 0.02,
                    hz - DZ[d] * 0.02)) {
                return true;
            }
        }
        return false;
    }

    /** Sampled line of sight: no solid cell between eye and end, other than the stand cells and the target. */
    private static boolean clearLine(WorldProbe world, int tx, int ty, int tz, int fx, int fy, int fz,
                                     double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / LOS_STEP));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(ax + dx * t), y = (int) Math.floor(ay + dy * t), z = (int) Math.floor(az + dz * t);
            if (x == fx && z == fz && (y == fy || y == fy + 1)) {
                continue;
            }
            if (x == tx && y == ty && z == tz) {
                continue;
            }
            int w = world.probe(x, y, z, -1);
            if (w == PlacementStatus.W_OTHER || w == PlacementStatus.W_UNLOADED) {
                return false;
            }
        }
        return true;
    }
}
