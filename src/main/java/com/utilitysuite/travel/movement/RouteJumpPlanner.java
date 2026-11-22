package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Forward-simulated jumps for {@link RouteFollower}: does a jump (now, or after a short run-up) land on the aimed route
 * cells with zero fall damage, does a sprint hop on a flat gain ground, which air keys bring a flight down on target.
 * Every simulated tick is counted against a per-update budget ({@link #begin}); a search that runs out of budget answers
 * "no", so the follower walks the route as planned instead. Allocation free (three reused bodies).
 * <p>
 * Aim first ({@link #aimCell} or {@link #aimRoute}), then search. A landing counts when the feet cell after touching
 * down is an aimed cell and the fall (apex to floor) costs no health.
 */
public final class RouteJumpPlanner {

    /** Longest flight simulated (ticks); a sprint jump over 4 blocks lasts about 12. */
    public static final int FLIGHT_MAX = 30;
    /** Longest run-up searched before a take-off (ticks). */
    public static final int RUNUP_MAX = 14;
    /** A sprint hop is taken only when it gains at least this much over sprinting the same ticks (as the assist). */
    public static final double HOP_GAIN = MovementAssistBrain.HOP_GAIN;

    private static final int[] FWD = {1, 1, 1, 0, 0, -1, -1, -1, 0};
    private static final int[] STR = {0, 1, -1, 1, -1, 0, 1, -1, 0};

    private final MovementPhysics run = new MovementPhysics();
    private final MovementPhysics fly = new MovementPhysics();
    private final MovementPhysics walk = new MovementPhysics();

    private int ticks;
    private int budget = Integer.MAX_VALUE;

    // aim
    private long[] aimRoute;
    private int aimLo, aimHi;
    private int aimX, aimY, aimZ;
    private int floorY;
    private int landedIndex = -1;
    // last flight
    private double landX, landY, landZ;
    private float landDamage;
    // last air steer
    private int steerForward;
    private int steerStrafe;

    public void begin(int tickBudget) {
        ticks = 0;
        budget = tickBudget;
    }

    /** Ticks simulated since {@link #begin}. */
    public int ticks() {
        return ticks;
    }

    public boolean exhausted() {
        return ticks >= budget;
    }

    /** Aims at one feet cell: a landing counts only there. */
    public void aimCell(int x, int y, int z) {
        aimRoute = null;
        aimX = x;
        aimY = y;
        aimZ = z;
        floorY = y;
    }

    /**
     * Aims at route cells {@code lo..hi} (the array is read, not copied): a landing on any of them counts;
     * {@code nominal} is the cell air steering centres on.
     */
    public void aimRoute(long[] route, int lo, int hi, int nominal) {
        aimRoute = route;
        aimLo = lo;
        aimHi = hi;
        long c = route[nominal];
        aimX = Packed.x(c);
        aimY = Packed.y(c);
        aimZ = Packed.z(c);
        floorY = aimY;
        for (int i = lo; i <= hi; i++) {
            floorY = Math.min(floorY, Packed.y(route[i]));
        }
    }

    /** The route index (0 for an aimed single cell) a landing counts as, or -1. */
    private int landing(int x, int y, int z) {
        if (aimRoute == null) {
            return x == aimX && y == aimY && z == aimZ ? 0 : -1;
        }
        return RouteCorridor.indexOf(aimRoute, aimLo, aimHi, x, y, z);
    }

    private boolean step(MovementPhysics p, int f, int s, float yaw, boolean sprint, boolean jump,
                         MovementPhysics.Voxels w) {
        if (ticks >= budget) {
            return false;
        }
        ticks++;
        p.tick(f, s, yaw, sprint, jump, w);
        return true;
    }

    /**
     * Runs {@code runTicks} ticks holding the keys (staying on the ground, no wall bump), then jumps and holds the keys
     * until landing. True when it lands on an aimed cell with zero fall damage.
     */
    public boolean jumpLands(MovementPhysics start, int runTicks, int f, int s, float yaw, boolean sprint,
                             MovementPhysics.Voxels w) {
        run.copyFrom(start);
        for (int i = 0; i < runTicks; i++) {
            if (!step(run, f, s, yaw, sprint, false, w) || !run.onGround || run.horizontalCollision) {
                return false;
            }
        }
        return run.onGround && flight(run, f, s, yaw, sprint, true, w);
    }

    /**
     * Searches the take-off: run 0..{@link #RUNUP_MAX} ticks sprinting forward at {@code yaw}, then jump. Returns the
     * first run-up length that lands on an aimed cell without damage, or -1.
     */
    public int searchTakeoff(MovementPhysics start, float yaw, MovementPhysics.Voxels w) {
        if (!start.onGround) {
            return -1;
        }
        run.copyFrom(start);
        for (int d = 0; d <= RUNUP_MAX; d++) {
            if (d > 0) {
                if (!step(run, 1, 0, yaw, true, false, w) || !run.onGround || run.horizontalCollision) {
                    return -1;
                }
            }
            if (flight(run, 1, 0, yaw, true, true, w)) {
                return d;
            }
            if (exhausted() || !run.supported(w)) {
                return -1; // stepped off the edge: no later take-off
            }
        }
        return -1;
    }

    /**
     * Holds the keys from {@code from} (jumping on the first tick when asked) until landing. True when the body lands
     * on an aimed cell with zero fall damage; the landing is in {@link #landX()} etc.
     */
    public boolean flight(MovementPhysics from, int f, int s, float yaw, boolean sprint, boolean jumpFirst,
                          MovementPhysics.Voxels w) {
        fly.copyFrom(from);
        boolean wasAir = !from.onGround;
        for (int t = 1; t <= FLIGHT_MAX; t++) {
            double fallBefore = fly.fallDistance;
            double yBefore = fly.y;
            if (!step(fly, f, s, yaw, sprint, jumpFirst && t == 1, w)) {
                return false;
            }
            if (!fly.onGround) {
                wasAir = true;
                if (fly.y < floorY - 3) {
                    return false; // fell past the target
                }
            } else if (wasAir) {
                landX = fly.x;
                landY = fly.y;
                landZ = fly.z;
                landDamage = MovementPhysics.fallDamage(fallBefore + Math.max(0, yBefore - fly.y));
                landedIndex = landing(MovementPhysics.floor(fly.x), MovementPhysics.floor(fly.y + 0.01),
                        MovementPhysics.floor(fly.z));
                return landDamage <= 0f && landedIndex >= 0;
            } else if (t > 2) {
                return false; // never left the ground (ceiling, or no jump)
            }
        }
        return false;
    }

    /**
     * Mid-air: the keys (held for the rest of the flight, yaw kept) that land on an aimed cell closest to the nominal
     * one. True when some combination lands; read {@link #steerForward()} / {@link #steerStrafe()}.
     */
    public boolean airSteer(MovementPhysics now, float yaw, boolean sprint, MovementPhysics.Voxels w) {
        double best = Double.MAX_VALUE;
        boolean found = false;
        for (int i = 0; i < FWD.length; i++) {
            if (flight(now, FWD[i], STR[i], yaw, sprint, false, w)) {
                double ex = landX - (aimX + 0.5);
                double ez = landZ - (aimZ + 0.5);
                double e = ex * ex + ez * ez;
                if (e < best) {
                    best = e;
                    steerForward = FWD[i];
                    steerStrafe = STR[i];
                    found = true;
                }
            }
            if (exhausted()) {
                break;
            }
        }
        return found;
    }

    /**
     * A sprint hop along the chord: jumping now (sprint, forward, yaw kept) lands at the same height, without a wall
     * bump, on the chord (unit direction (ux, uz) from the body, at most {@code maxAlong} along it, within
     * {@code maxSide} of it), and gets at least {@link #HOP_GAIN} farther than sprinting the same ticks.
     */
    public boolean hopGains(MovementPhysics now, float yaw, double ux, double uz, double maxAlong, double maxSide,
                            MovementPhysics.Voxels w) {
        run.copyFrom(now);
        walk.copyFrom(now);
        if (!step(run, 1, 0, yaw, true, true, w) || !step(walk, 1, 0, yaw, true, false, w)) {
            return false;
        }
        int t = 1;
        while (!run.onGround && t < 20) {
            if (!step(run, 1, 0, yaw, true, false, w) || !step(walk, 1, 0, yaw, true, false, w)) {
                return false;
            }
            t++;
        }
        if (!run.onGround || run.y != now.y || !walk.onGround || walk.y != now.y || run.horizontalCollision) {
            return false;
        }
        double ax = run.x - now.x;
        double az = run.z - now.z;
        double along = ax * ux + az * uz;
        double side = Math.abs(ax * uz - az * ux);
        double walked = (walk.x - now.x) * ux + (walk.z - now.z) * uz;
        landX = run.x;
        landY = run.y;
        landZ = run.z;
        return along <= maxAlong && side <= maxSide && along > walked + HOP_GAIN;
    }

    /** Route index of the last counted landing (0 for a single aimed cell). */
    public int landedIndex() {
        return landedIndex;
    }

    public double landX() {
        return landX;
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return landZ;
    }

    public float landDamage() {
        return landDamage;
    }

    public int steerForward() {
        return steerForward;
    }

    public int steerStrafe() {
        return steerStrafe;
    }
}
