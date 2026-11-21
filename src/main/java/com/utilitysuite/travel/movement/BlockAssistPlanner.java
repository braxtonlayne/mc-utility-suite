package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Parkour with blocks: while the player is in the air after a jump, would one placed block keep the run going? Engine
 * free (any {@link MovementPhysics.Voxels}); nothing allocated per call.
 * <p>
 * The value of a future is a rollout in the physics replica: the player's own keys until they land, then what a
 * runner does next (hold forward, sprint, jump on every landing) for the rest of {@link #ROLLOUT_TICKS}; the value is
 * the progress along the heading minus {@link JumpSearch#FALL_WEIGHT} per point of fall damage, or
 * {@link JumpSearch#NO_LANDING} when a landing would kill or never comes. The baseline is that rollout as things
 * stand; each candidate adds one block under a point of the current trajectory, solid from the tick it can really be
 * placed:
 * <ul>
 *   <li>the cell is air and the player's body does not overlap it at the placement tick;</li>
 *   <li>a solid neighbour (existing world, no chains) has its face toward the cell visible from the predicted eye,
 *       within {@link #REACH};</li>
 *   <li>the head turn from the current look to that face fits in time at the given turn speed (the no-snap cap), plus
 *       a tick to switch the hotbar slot when needed.</li>
 * </ul>
 * The best candidate is kept when it beats the baseline by {@link #MARGIN} blocks (or lands where the baseline does
 * not). Covers the wall jump (a block under the player near the top of the jump: land on it, jump the wall) and the
 * gap that is one block too long (a block mid-gap).
 */
public final class BlockAssistPlanner {

    public static final int TRAJECTORY_TICKS = 30;
    public static final int ROLLOUT_TICKS = 60;
    public static final int MAX_CANDIDATES = 48;
    public static final double REACH = 4.5;
    public static final double EYE_HEIGHT = 1.62;
    public static final double MARGIN = 1.5;
    /** Look where you move: each degree the aim yaw strays from the run heading weighs like this many degrees of turn. */
    public static final double NATURAL_HEADING_WEIGHT = 1.5;

    /** NATURAL look style: favour faces along the run. 0 (FREE): any face, whatever is quickest. */
    public double headingWeight = NATURAL_HEADING_WEIGHT;

    /** Direction ids in {@code net.minecraft.core.Direction#get3DDataValue} order. */
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final int[] FX = {0, 0, 0, 0, -1, 1};
    private static final int[] FY = {-1, 1, 0, 0, 0, 0};
    private static final int[] FZ = {0, 0, -1, 1, 0, 0};

    private final MovementPhysics sim = new MovementPhysics();
    private final PlusOne plus = new PlusOne();
    private final double[] tx = new double[TRAJECTORY_TICKS + 1];
    private final double[] ty = new double[TRAJECTORY_TICKS + 1];
    private final double[] tz = new double[TRAJECTORY_TICKS + 1];
    private int trajectory;
    private final long[] candidates = new long[MAX_CANDIDATES];
    private int candidateCount;

    // result
    private long cell;
    private long support;
    private int face;
    private int placeTick;
    private double value;
    private double baseline;
    private float aimYaw;
    private float aimPitch;

    /**
     * @param now        current state (airborne); not modified
     * @param heading    movement yaw (the player's camera yaw when the jump started; keys are relative to it)
     * @param lookYaw    current look (camera or server angle the aim starts from)
     * @param degPerTick head-turn budget per tick (keep under {@code RotationLimits.MAX_DEG_PER_TICK})
     * @param slotTicks  ticks before a block is in hand (0 when already holding one, 1 for a hotbar switch)
     * @return true when a block placement is worth it; read {@link #cell()}, {@link #support()}, {@link #face()} ...
     */
    public boolean plan(MovementPhysics now, float heading, boolean sprint, int forward, int strafe, float health,
                        float lookYaw, float lookPitch, double degPerTick, int slotTicks, MovementPhysics.Voxels w) {
        cell = 0;
        placeTick = -1;
        if (now.onGround) {
            return false;
        }
        baseline = rollout(now, heading, sprint, forward, strafe, health, w, Long.MIN_VALUE, 0);
        traceTrajectory(now, heading, sprint, forward, strafe, w);
        collectCandidates(w);
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidateCount; i++) {
            long c = candidates[i];
            int t = earliestPlacement(c, heading, lookYaw, lookPitch, degPerTick, slotTicks, w);
            if (t < 0) {
                continue;
            }
            double v = rollout(now, heading, sprint, forward, strafe, health, w, c, t + 1);
            if (v > best) {
                best = v;
                cell = c;
                placeTick = t;
                // face/support/aim of the chosen placement (recomputed: earliestPlacement keeps the last check)
                support = lastSupport;
                face = lastFace;
                aimYaw = lastAimYaw;
                aimPitch = lastAimPitch;
            }
        }
        value = best;
        if (placeTick < 0 || best <= JumpSearch.NO_LANDING) {
            placeTick = -1;
            return false;
        }
        boolean worth = baseline <= JumpSearch.NO_LANDING || best >= baseline + MARGIN;
        if (!worth) {
            placeTick = -1;
        }
        return worth;
    }

    // ---- trajectory and candidates ------------------------------------------------------------------------------------

    private void traceTrajectory(MovementPhysics now, float heading, boolean sprint, int f, int s,
                                 MovementPhysics.Voxels w) {
        sim.copyFrom(now);
        tx[0] = sim.x;
        ty[0] = sim.y;
        tz[0] = sim.z;
        trajectory = 1;
        for (int t = 1; t <= TRAJECTORY_TICKS; t++) {
            sim.tick(f, s, heading, sprint, false, w);
            tx[t] = sim.x;
            ty[t] = sim.y;
            tz[t] = sim.z;
            trajectory = t + 1;
            if (sim.onGround) {
                break;
            }
        }
    }

    private void collectCandidates(MovementPhysics.Voxels w) {
        candidateCount = 0;
        for (int t = 1; t < trajectory; t++) {
            int bx = floor(tx[t]);
            int bz = floor(tz[t]);
            int by = floor(ty[t] - 1.0e-6) - 1; // the cell whose top the feet are just above
            addCandidate(Packed.pack(bx, by, bz), w);
            addCandidate(Packed.pack(bx, by - 1, bz), w);
        }
    }

    private void addCandidate(long c, MovementPhysics.Voxels w) {
        if (candidateCount >= MAX_CANDIDATES || w.solid(Packed.x(c), Packed.y(c), Packed.z(c))) {
            return;
        }
        for (int i = 0; i < candidateCount; i++) {
            if (candidates[i] == c) {
                return;
            }
        }
        candidates[candidateCount++] = c;
    }

    // ---- placement feasibility ----------------------------------------------------------------------------------------

    private long lastSupport;
    private int lastFace;
    private float lastAimYaw;
    private float lastAimPitch;

    /** Earliest trajectory tick a block can be placed into {@code c}, or -1. Leaves the face/aim in last*. */
    private int earliestPlacement(long c, float heading, float lookYaw, float lookPitch, double degPerTick, int slotTicks,
                                  MovementPhysics.Voxels w) {
        int cx = Packed.x(c);
        int cy = Packed.y(c);
        int cz = Packed.z(c);
        for (int t = 1; t < trajectory; t++) {
            if (overlaps(tx[t], ty[t], tz[t], cx, cy, cz)) {
                continue; // the block would be placed into the player
            }
            double ex = tx[t];
            double ey = ty[t] + EYE_HEIGHT;
            double ez = tz[t];
            double bestAngle = Double.MAX_VALUE;
            double bestCost = Double.MAX_VALUE;
            for (int d = 0; d < 6; d++) {
                // neighbour N = C - dir, clicked on its face pointing at C (dir)
                int nx = cx - FX[d];
                int ny = cy - FY[d];
                int nz = cz - FZ[d];
                if (!w.solid(nx, ny, nz)) {
                    continue;
                }
                // face plane between N and C; the eye must be on C's side to see it
                double hx = FX[d] != 0 ? cx + (FX[d] > 0 ? 0.0 : 1.0) : cx + 0.5;
                double hy = FY[d] != 0 ? cy + (FY[d] > 0 ? 0.0 : 1.0) : cy + 0.5;
                double hz = FZ[d] != 0 ? cz + (FZ[d] > 0 ? 0.0 : 1.0) : cz + 0.5;
                double side = FX[d] * (ex - hx) + FY[d] * (ey - hy) + FZ[d] * (ez - hz);
                if (side <= 0.02) {
                    continue;
                }
                double dx = hx - ex;
                double dy = hy - ey;
                double dz = hz - ez;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > REACH) {
                    continue;
                }
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                double angle = angle(lookYaw, lookPitch, yaw, pitch);
                // prefer faces that keep the head along the run (ahead or below-ahead) over ones behind or aside
                double cost = angle + headingWeight * Math.abs(wrap((double) yaw - heading));
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAngle = angle;
                    lastSupport = Packed.pack(nx, ny, nz);
                    lastFace = d;
                    lastAimYaw = yaw;
                    lastAimPitch = pitch;
                }
            }
            if (bestAngle == Double.MAX_VALUE) {
                continue;
            }
            int needed = slotTicks + (int) Math.ceil(bestAngle / Math.max(1.0e-3, degPerTick)) + 1;
            if (needed <= t) {
                return t;
            }
        }
        return -1;
    }

    private static boolean overlaps(double x, double y, double z, int cx, int cy, int cz) {
        double h = MovementPhysics.HALF_WIDTH;
        return x + h > cx && x - h < cx + 1 && z + h > cz && z - h < cz + 1
                && y < cy + 1 && y + MovementPhysics.HEIGHT > cy;
    }

    static double angle(float yawA, float pitchA, float yawB, float pitchB) {
        double dy = wrap((double) yawB - yawA);
        double dp = (double) pitchB - pitchA;
        return Math.sqrt(dy * dy + dp * dp);
    }

    static double wrap(double d) {
        d %= 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // ---- rollout ------------------------------------------------------------------------------------------------------

    /** Value of the future with (optionally) one extra block solid from sim tick {@code from}. */
    private double rollout(MovementPhysics now, float heading, boolean sprint, int f, int s, float health,
                           MovementPhysics.Voxels w, long extra, int from) {
        plus.base = w;
        plus.extra = extra;
        plus.from = from;
        sim.copyFrom(now);
        double hx = -Math.sin(Math.toRadians(heading));
        double hz = Math.cos(Math.toRadians(heading));
        boolean landed = false;
        boolean airborne = !now.onGround;
        double damage = 0;
        for (int t = 1; t <= ROLLOUT_TICKS; t++) {
            plus.tick = t;
            double fallBefore = sim.fallDistance;
            double yBefore = sim.y;
            boolean jump = landed && sim.onGround;
            sim.tick(landed ? 1 : f, landed ? 0 : s, heading, sprint, jump, plus);
            if (!sim.onGround) {
                airborne = true;
            } else if (airborne) {
                airborne = false;
                float dmg = MovementPhysics.fallDamage(fallBefore + Math.max(0.0, yBefore - sim.y));
                if (dmg >= health - damage) {
                    return JumpSearch.NO_LANDING;
                }
                damage += dmg;
                landed = true;
            }
            if (sim.y < now.y - 64) {
                return JumpSearch.NO_LANDING;
            }
        }
        if (!landed) {
            return JumpSearch.NO_LANDING;
        }
        return (sim.x - now.x) * hx + (sim.z - now.z) * hz - JumpSearch.FALL_WEIGHT * damage;
    }

    /** The world plus one block that exists from a given tick (the planned placement). */
    private static final class PlusOne implements MovementPhysics.Voxels {
        MovementPhysics.Voxels base;
        long extra = Long.MIN_VALUE;
        int from;
        int tick;

        @Override
        public boolean solid(int x, int y, int z) {
            if (extra != Long.MIN_VALUE && tick >= from && Packed.x(extra) == x && Packed.y(extra) == y
                    && Packed.z(extra) == z) {
                return true;
            }
            return base.solid(x, y, z);
        }
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---- result -------------------------------------------------------------------------------------------------------

    /** Cell to place the block into. */
    public long cell() {
        return cell;
    }

    /** Existing block to click. */
    public long support() {
        return support;
    }

    /** Face of {@link #support()} to click, Direction 3D data value (0 down ... 5 east). */
    public int face() {
        return face;
    }

    /** Trajectory tick (from now) the placement was planned for. */
    public int placeTick() {
        return placeTick;
    }

    public double value() {
        return value;
    }

    public double baseline() {
        return baseline;
    }

    /** Planned aim (from the predicted eye at {@link #placeTick()}). */
    public float aimYaw() {
        return aimYaw;
    }

    public float aimPitch() {
        return aimPitch;
    }

    /** Centre of the clicked face (aim point), world coordinates; {@code out} gets x, y, z. */
    public static void faceCenter(long support, int face, double[] out) {
        int nx = Packed.x(support);
        int ny = Packed.y(support);
        int nz = Packed.z(support);
        out[0] = nx + 0.5 + FX[face] * 0.5;
        out[1] = ny + 0.5 + FY[face] * 0.5;
        out[2] = nz + 0.5 + FZ[face] * 0.5;
    }
}
