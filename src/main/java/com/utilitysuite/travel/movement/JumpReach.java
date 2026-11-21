package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * "Blocks within jumping distance": from a standing state, simulates a sprint jump (forward + sprint held to
 * landing) at {@link #HEADINGS} headings and records the distinct landing blocks that take no fall damage. Engine-free
 * and allocation free (fixed arrays); recomputed a few times a second for the overlay and by planners.
 */
public final class JumpReach {

    public static final int HEADINGS = 16;
    /** Landings more than this far below the start are left out (they hurt or are not "reach"). */
    public static final double MAX_DROP = 3.0;

    private final MovementPhysics sim = new MovementPhysics();
    private final long[] cells = new long[HEADINGS];
    private final boolean[] up = new boolean[HEADINGS];
    private int count;

    /**
     * @param from a state on the ground; not modified
     * @return the number of distinct landing blocks found (the block the player lands on, packed)
     */
    public int compute(MovementPhysics from, MovementPhysics.Voxels w) {
        count = 0;
        if (!from.onGround) {
            return 0;
        }
        for (int h = 0; h < HEADINGS; h++) {
            float yaw = h * (360f / HEADINGS);
            sim.copyFrom(from);
            sim.vx = 0; // a fresh run-up from standing in this direction would differ; use a sprint start
            sim.vz = 0;
            boolean airborne = false;
            for (int t = 0; t < 40; t++) {
                sim.tick(1, 0, yaw, true, t == 0, w);
                if (!sim.onGround) {
                    airborne = true;
                } else if (airborne) {
                    break;
                }
            }
            if (!sim.onGround || !airborne || sim.y < from.y - MAX_DROP) {
                continue;
            }
            long cell = Packed.pack(MovementPhysics.floor(sim.x), MovementPhysics.floor(sim.y - 0.01),
                    MovementPhysics.floor(sim.z));
            if (cell == Packed.pack(MovementPhysics.floor(from.x), MovementPhysics.floor(from.y - 0.01),
                    MovementPhysics.floor(from.z))) {
                continue;
            }
            boolean dup = false;
            for (int i = 0; i < count; i++) {
                if (cells[i] == cell) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                up[count] = sim.y > from.y + 0.5;
                cells[count++] = cell;
            }
        }
        return count;
    }

    public int count() {
        return count;
    }

    public long cell(int i) {
        return cells[i];
    }

    /** True when that landing is higher than the start (a jump up onto a block). */
    public boolean isUp(int i) {
        return up[i];
    }
}
