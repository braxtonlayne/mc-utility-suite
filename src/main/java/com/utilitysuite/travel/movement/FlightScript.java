package com.utilitysuite.travel.movement;

/**
 * A short, verified, time-varying input plan for one jump: the keys (and, when allowed, the ideal view yaw) for each
 * tick from "now" through the run-up, the takeoff and the flight to the touchdown, together with the body positions
 * the replica predicted for them. Produced by a {@link TechniqueSource} (corner, neo, headhitter, 45-degree) and by the
 * approach controller; replayed by {@link MovementAssistBrain}, which drops the script as soon as the real body leaves
 * the predicted line by more than {@link #DRIFT_LIMIT}.
 * <p>
 * Index 0 is the input of the next physics tick. Preallocated primitive arrays: filling, copying and reading a script
 * never allocates.
 */
public final class FlightScript {

    public static final int MAX_TICKS = 48;
    /** Blocks the body may be off the predicted position of a tick before the script is dropped. */
    public static final double DRIFT_LIMIT = 0.08;

    /** Ticks in the script (0 = empty). */
    public int length;
    /** Index of the tick whose input carries the jump (the last supported tick), -1 when the body is already airborne. */
    public int takeoffIndex = -1;
    /** Index of the tick after which the body stands on the target. */
    public int landIndex = -1;
    /** Forward axis per tick: -1 / 0 / +1. */
    public final byte[] forward = new byte[MAX_TICKS];
    /** Strafe axis per tick: +1 left, -1 right, 0. */
    public final byte[] strafe = new byte[MAX_TICKS];
    public final boolean[] jump = new boolean[MAX_TICKS];
    /** Sprint state the plan was rolled with for that tick (false = sprint released or withheld). */
    public final boolean[] sprint = new boolean[MAX_TICKS];
    /** View yaw the plan was rolled with for that tick (degrees); the start yaw throughout unless {@link #usesYaw}. */
    public final float[] yaw = new float[MAX_TICKS];
    /** Predicted body position after that tick. */
    public final double[] x = new double[MAX_TICKS];
    public final double[] y = new double[MAX_TICKS];
    public final double[] z = new double[MAX_TICKS];
    /** The plan turns the view: only valid where yaw co-steer is allowed (never on LEGIT). */
    public boolean usesYaw;
    /** Largest view change between two consecutive ticks (degrees); must stay under the co-steer rate of the level. */
    public float maxYawStep;
    /** Smallest distance from the predicted stop point to an edge of the target top (blocks; negative = off the top). */
    public double margin;
    /** Constant name of the technique for the status line and notes ("corner", "neo", ...). */
    public String name = "";

    public void clear() {
        length = 0;
        takeoffIndex = -1;
        landIndex = -1;
        usesYaw = false;
        maxYawStep = 0f;
        margin = 0.0;
        name = "";
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public void copyFrom(FlightScript o) {
        length = o.length;
        takeoffIndex = o.takeoffIndex;
        landIndex = o.landIndex;
        usesYaw = o.usesYaw;
        maxYawStep = o.maxYawStep;
        margin = o.margin;
        name = o.name;
        int n = o.length;
        System.arraycopy(o.forward, 0, forward, 0, n);
        System.arraycopy(o.strafe, 0, strafe, 0, n);
        System.arraycopy(o.jump, 0, jump, 0, n);
        System.arraycopy(o.sprint, 0, sprint, 0, n);
        System.arraycopy(o.yaw, 0, yaw, 0, n);
        System.arraycopy(o.x, 0, x, 0, n);
        System.arraycopy(o.y, 0, y, 0, n);
        System.arraycopy(o.z, 0, z, 0, n);
    }

    /** True when the body (after tick {@code index}) is within {@link #DRIFT_LIMIT} of the prediction. */
    public boolean onLine(int index, double bx, double by, double bz) {
        if (index < 0 || index >= length) {
            return false;
        }
        double dx = bx - x[index];
        double dy = by - y[index];
        double dz = bz - z[index];
        return dx * dx + dy * dy + dz * dz <= DRIFT_LIMIT * DRIFT_LIMIT;
    }
}
