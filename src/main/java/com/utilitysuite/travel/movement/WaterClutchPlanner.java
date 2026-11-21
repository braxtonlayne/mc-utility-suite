package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Water bucket clutch: while falling toward a landing that would kill or badly hurt, where and on which tick to pour
 * water so the player is in it before (or as) they land. Engine free; nothing allocated per call.
 * <p>
 * The trajectory comes from the physics replica with the player's keys. Candidates, best first:
 * <ol>
 *   <li>the landing cell itself (click the top of the block landed on): no damage at all;</li>
 *   <li>a cell along the fall next to a wall (click the wall's face): fall distance resets in the water, so only the
 *       drop below it counts.</li>
 * </ol>
 * A use tick is valid when, from the eye at the start of that tick, the clicked face is visible and within
 * {@link #REACH}, the head turn from the current look fits in time at the given speed, and the player is in the water
 * cell on a later tick (or lands in it that tick: vanilla resets fall distance when the move ends in water). At full
 * fall speed (3.9 blocks/tick) the landing cell is often reachable on the last tick only, or not at all; the planner
 * reports what it found and the executor re-plans every tick.
 */
public final class WaterClutchPlanner {

    public static final int HORIZON = 120;
    /** Block reach from the eye (survival), less a small margin for the tick's own motion. */
    public static final double REACH = 4.4;
    public static final double EYE = 1.62;
    static final double BODY_HEIGHT = 1.8;
    /** A water source's surface height in its cell (8/9), a little less for margin. */
    static final double WATER_TOP = 0.85;
    /** Direction data values of the faces clicked. */
    public static final int UP_FACE = 1, WEST_FACE = 4;

    private static final int[] FX = {0, 0, 0, 0, -1, 1};
    private static final int[] FY = {-1, 1, 0, 0, 0, 0};
    private static final int[] FZ = {0, 0, -1, 1, 0, 0};

    private final MovementPhysics sim = new MovementPhysics();
    /** Positions at the START of each tick (index k = before tick k+1 moves). */
    private final double[] px = new double[HORIZON + 1];
    private final double[] py = new double[HORIZON + 1];
    private final double[] pz = new double[HORIZON + 1];
    private int ticks;
    private boolean lands;
    private double landY;
    private float landDamage;

    // result
    private long support;
    private int face;
    private long waterCell;
    private int useTick;
    private float damageAfter;
    private float aimYaw;
    private float aimPitch;

    /**
     * @param minDamage clutch only when the landing would deal at least this much (or kill)
     * @return true when a clutch is needed and possible; {@link #useTick()} 0 means "use now"
     */
    public boolean plan(MovementPhysics now, float heading, boolean sprint, int f, int s, float health,
                        float minDamage, float lookYaw, float lookPitch, double degPerTick,
                        MovementPhysics.Voxels w) {
        useTick = -1;
        if (now.onGround || !predict(now, heading, sprint, f, s, w)) {
            return false;
        }
        if (landDamage < minDamage && landDamage < health) {
            return false;
        }
        double bestDamage = landDamage;
        int lx = floor(px[ticks]);
        int lz = floor(pz[ticks]);
        int ly = floor(landY + 1.0e-6);
        // 1. the landing cell: click the top of the block under it
        tryCell(Packed.pack(lx, ly, lz), ticks, 0f, lookYaw, lookPitch, degPerTick, w);
        if (useTick >= 0) {
            return true;
        }
        // 2. cells the hitbox passes through on the way down, latest (least damage) first. The head cell counts: at
        //    terminal speed the feet cell is always out of reach from the eye a tick earlier, the head cell is not.
        //    In water at the end of tick j the fall distance resets, so only the drop from there counts.
        for (int j = ticks - 1; j >= 1 && useTick < 0; j--) {
            float after = MovementPhysics.fallDamage(Math.max(0.0, py[j] - landY));
            if (after >= bestDamage || after >= health) {
                continue;
            }
            int cx = floor(px[j]);
            int cz = floor(pz[j]);
            for (int cy = floor(py[j] + BODY_HEIGHT - 0.01); cy >= floor(py[j]) && useTick < 0; cy--) {
                if (py[j] >= cy + WATER_TOP || py[j] + BODY_HEIGHT <= cy) {
                    continue; // the hitbox misses the water in this cell
                }
                tryCell(Packed.pack(cx, cy, cz), j, after, lookYaw, lookPitch, degPerTick, w);
            }
        }
        return useTick >= 0;
    }

    /** Trajectory until landing; false when it never lands within the horizon. */
    private boolean predict(MovementPhysics now, float heading, boolean sprint, int f, int s,
                            MovementPhysics.Voxels w) {
        sim.copyFrom(now);
        px[0] = sim.x;
        py[0] = sim.y;
        pz[0] = sim.z;
        double fall = now.fallDistance;
        for (int t = 1; t <= HORIZON; t++) {
            double yBefore = sim.y;
            sim.tick(f, s, heading, sprint, false, w);
            px[t] = sim.x;
            py[t] = sim.y;
            pz[t] = sim.z;
            fall += Math.max(0.0, yBefore - sim.y);
            if (sim.onGround) {
                ticks = t;
                landY = sim.y;
                landDamage = MovementPhysics.fallDamage(fall);
                lands = true;
                return true;
            }
        }
        lands = false;
        return false;
    }

    /**
     * Water into {@code cell}, which the body occupies from trajectory tick {@code inTick}: the latest start-of-tick
     * eye (k < inTick) that can reach a face next to it, with the head turn done in time. Earliest workable tick is
     * not the goal: the latest leaves the most turn time.
     */
    private void tryCell(long cell, int inTick, float after, float lookYaw, float lookPitch, double degPerTick,
                         MovementPhysics.Voxels w) {
        int cx = Packed.x(cell);
        int cy = Packed.y(cell);
        int cz = Packed.z(cell);
        if (w.solid(cx, cy, cz)) {
            return;
        }
        for (int k = inTick - 1; k >= 0; k--) {
            double ex = px[k];
            double ey = py[k] + EYE;
            double ez = pz[k];
            for (int d = 0; d < 6; d++) {
                int nx = cx - FX[d];
                int ny = cy - FY[d];
                int nz = cz - FZ[d];
                if (!w.solid(nx, ny, nz)) {
                    continue;
                }
                double hx = FX[d] != 0 ? cx + (FX[d] > 0 ? 0.0 : 1.0) : cx + 0.5;
                double hy = FY[d] != 0 ? cy + (FY[d] > 0 ? 0.0 : 1.0) : cy + 0.5;
                double hz = FZ[d] != 0 ? cz + (FZ[d] > 0 ? 0.0 : 1.0) : cz + 0.5;
                if (FX[d] * (ex - hx) + FY[d] * (ey - hy) + FZ[d] * (ez - hz) <= 0.02) {
                    continue; // face not visible from this eye
                }
                double dx = hx - ex;
                double dy = hy - ey;
                double dz = hz - ez;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > REACH) {
                    continue;
                }
                double horiz = Math.sqrt(dx * dx + dz * dz);
                // straight down: keep the yaw (atan2 of a near-zero offset would swing the head for nothing)
                float yaw = horiz < 0.35 ? lookYaw : (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
                double angle = BlockAssistPlanner.angle(lookYaw, lookPitch, yaw, pitch);
                if (Math.ceil(angle / Math.max(1.0e-3, degPerTick)) + 1 > k + 1) {
                    continue; // the head cannot get there in time
                }
                support = Packed.pack(nx, ny, nz);
                face = d;
                waterCell = cell;
                useTick = k;
                damageAfter = after;
                aimYaw = yaw;
                aimPitch = pitch;
                return;
            }
        }
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---- result -------------------------------------------------------------------------------------------------------

    /** Block to click. */
    public long support() {
        return support;
    }

    /** Face of {@link #support()} (Direction 3D data value). */
    public int face() {
        return face;
    }

    public long waterCell() {
        return waterCell;
    }

    /** Ticks from now until the bucket must be used (0 = this tick). */
    public int useTick() {
        return useTick;
    }

    /** Fall damage left with the clutch (0 for a landing in water). */
    public float damageAfter() {
        return damageAfter;
    }

    /** Fall damage without it. */
    public float landDamage() {
        return landDamage;
    }

    /** Ticks until landing without it. */
    public int ticksToLand() {
        return ticks;
    }

    public boolean lands() {
        return lands;
    }

    public float aimYaw() {
        return aimYaw;
    }

    public float aimPitch() {
        return aimPitch;
    }
}
