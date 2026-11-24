package com.utilitysuite.travel.bridge;

import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.Packed;

/**
 * Plans the block positions of a flat bridge, engine-free. Positions form a 4-connected grid line (every block shares a
 * face with the previous one, so each can be placed against its predecessor), starting next to the start block.
 */
public final class BridgePlanner {

    /** Upper bound on planned length (budget and marker usually stop earlier). */
    public static final int MAX_LENGTH = 512;

    /** A bridge plan: packed positions in placement order; {@code toMarker} when it ends at a marked point. */
    public record Plan(long[] positions, boolean toMarker) {
        public int length() {
            return positions.length;
        }
    }

    private BridgePlanner() {
    }

    /**
     * Line from the start block toward the end block (both at the start's y; the end's y is ignored). The start itself
     * is excluded (the player stands on it); the end is included.
     */
    public static Plan between(int sx, int y, int sz, int ex, int ez) {
        int dx = Math.abs(ex - sx);
        int dz = Math.abs(ez - sz);
        int n = Math.min(dx + dz, MAX_LENGTH);
        long[] out = new long[n];
        int stepX = Integer.signum(ex - sx);
        int stepZ = Integer.signum(ez - sz);
        int x = sx;
        int z = sz;
        int ix = 0;
        int iz = 0;
        for (int i = 0; i < n; i++) {
            // Walk the axis whose next cell boundary is nearer (Red Blob "walk_grid"); ties go to x.
            if ((0.5 + ix) * dz <= (0.5 + iz) * dx && ix < dx) {
                x += stepX;
                ix++;
            } else {
                z += stepZ;
                iz++;
            }
            out[i] = Packed.pack(x, y, z);
        }
        return new Plan(out, true);
    }

    /**
     * Line from the start in the facing direction, snapped to the 8 compass directions, {@code length} blocks of travel
     * long (diagonals use 4-connected steps).
     */
    public static Plan facing(int sx, int y, int sz, double yawDeg, int length) {
        int len = Math.max(0, Math.min(length, MAX_LENGTH));
        double snapped = Math.round(Angles.wrap(yawDeg) / 45.0) * 45.0;
        int ddx = (int) Math.round(Angles.dirX(snapped));
        int ddz = (int) Math.round(Angles.dirZ(snapped));
        if (ddx != 0 && ddz != 0) {
            // Diagonal: len diagonal cells need 2*len 4-connected steps; keep the plan at len blocks placed.
            int half = (len + 1) / 2;
            Plan p = between(sx, y, sz, sx + ddx * half, sz + ddz * half);
            long[] trimmed = java.util.Arrays.copyOf(p.positions(), Math.min(len, p.length()));
            return new Plan(trimmed, false);
        }
        Plan p = between(sx, y, sz, sx + ddx * len, sz + ddz * len);
        return new Plan(p.positions(), false);
    }

    public static final int SUPPORT_PREVIOUS = 0;
    public static final int SUPPORT_SIDE_A = 1;
    public static final int SUPPORT_SIDE_B = 2;

    /**
     * Steady yaw for a diagonal route (both axes non-zero): straight back along the whole route direction, about 45
     * degrees for a true diagonal. Constant for the run, so the head does not swing between the X-step and Z-step
     * poses of an alternating grid line. NaN when the route is axis-aligned (the per-step yaw is already steady).
     */
    public static double steadyBackYaw(int sx, int sz, int ex, int ez) {
        int dx = ex - sx;
        int dz = ez - sz;
        if (dx == 0 || dz == 0) {
            return Double.NaN;
        }
        return Angles.yawTo(-dx, -dz);
    }

    /**
     * Picks which block supports placing target {@code (px, pz)}, given the previous plan block {@code (qx, qz)}
     * (grid-adjacent to the target). Candidates: the previous block's face, and the two side neighbours of the target
     * (perpendicular to the step) when already solid, so a stair-step can be placed against whichever face the eye
     * sees. The previous block wins whenever its face is visible (no flip-flopping); a side neighbour is used only
     * when the previous face is hidden and the side face is visible.
     *
     * @param sideAOccupied neighbour at target + perpendicular(+) is solid
     * @param sideBOccupied neighbour at target - perpendicular(+) is solid
     */
    public static int chooseSupport(int px, int pz, int qx, int qz, boolean sideAOccupied, boolean sideBOccupied,
                                    double eyeX, double eyeZ) {
        int dirX = px - qx;
        int dirZ = pz - qz;
        if (faceVisible(qx, qz, dirX, dirZ, eyeX, eyeZ)) {
            return SUPPORT_PREVIOUS;
        }
        int perpX = dirZ != 0 ? 1 : 0;
        int perpZ = dirX != 0 ? 1 : 0;
        if (sideAOccupied && faceVisible(px + perpX, pz + perpZ, -perpX, -perpZ, eyeX, eyeZ)) {
            return SUPPORT_SIDE_A;
        }
        if (sideBOccupied && faceVisible(px - perpX, pz - perpZ, perpX, perpZ, eyeX, eyeZ)) {
            return SUPPORT_SIDE_B;
        }
        return SUPPORT_PREVIOUS;
    }

    /** Whether the face of block {@code (bx, bz)} pointing along {@code (dirX, dirZ)} is in front of the eye. */
    public static boolean faceVisible(int bx, int bz, int dirX, int dirZ, double eyeX, double eyeZ) {
        if (dirX != 0) {
            return (eyeX - (bx + (dirX > 0 ? 1.0 : 0.0))) * dirX > 0.05;
        }
        return (eyeZ - (bz + (dirZ > 0 ? 1.0 : 0.0))) * dirZ > 0.05;
    }
}
