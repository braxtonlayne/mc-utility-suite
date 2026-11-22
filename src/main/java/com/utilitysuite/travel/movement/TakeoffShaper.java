package com.utilitysuite.travel.movement;

/**
 * Takeoff shaping: in the last {@link #WINDOW_TICKS} ground ticks before an edge, when the user's own run-up would
 * miss the intent target, look for the cheapest change to the approach that turns the miss into a landing, the way a
 * player who knows strafe jumps would take off. Engine-free, deterministic, allocation free.
 * <p>
 * Each candidate is simulated in {@link MovementPhysics} from the current body: ground ticks with the shaped keys
 * until the last supported tick, a jump there (as the edge jump does), then the user's own keys in the air. Shapes:
 * <ul>
 *   <li><b>Sprint timing</b> (the user holds forward and is not sprinting): engage sprint on ground tick k (0 = now)
 *       of the run-up. Among the k that land, the one with the highest takeoff speed wins (earliest on a tie). Cost 1:
 *       vanilla keeps sprinting once it is on, so one tick is authored. Only k = 0 asks for sprint now; a later k
 *       waits and is re-decided next tick.</li>
 *   <li><b>Strafe at the current yaw</b> (the user holds no strafe key): hold the left or right key for the first
 *       1, 2 or all ground ticks. Cost = the strafe ticks.</li>
 *   <li><b>45-degree strafe</b> (only with {@code allowYaw}: turn gain on, not Legit, and a mouse already turning):
 *       the view 45 degrees off the travel line with the matching strafe key held, so travel keeps its direction while
 *       the input reaches full length (vanilla normalises W+A to 1 instead of 0.98: +2 % acceleration). It is
 *       reported as an {@link #idealYaw()} for the turn-gain request, never applied: no keys are filled for it, and
 *       once the user's own yaw gets there the at-current-yaw strafe shape covers it. Only offered when the mouse,
 *       sped up by at most 1.5x, reaches that yaw before the takeoff. Cost = ground ticks + {@link #YAW_COST}.</li>
 * </ul>
 * What the model says (see {@code integration/takeoff-shaping.notes.md}): the 45-degree strafe gains about 0.02 block
 * of flight with the view held straight at takeoff, and loses about 0.2 block when the view is still 45 degrees off at
 * the jump (the sprint-jump boost goes along the view). What actually moves landings by a tenth of a block or more is
 * the takeoff phase: where in its last supported tick the body jumps, which sprint timing and a strafe tick change.
 * <p>
 * A shaped landing must not cost the player's life; with SafeWalk ({@code safeOnly}) it must cost no health at all.
 */
public final class TakeoffShaper {

    /** Ground ticks before the edge in which shaping runs (0 = the takeoff tick itself). */
    public static final int WINDOW_TICKS = 6;
    /** A landing at least this far inside the target's edges (blocks) counts as landing with margin. */
    public static final double SAFE_MARGIN = 0.05;
    /** View offset of the strafe shape (degrees). */
    public static final float STRAFE_YAW = 45f;
    /** Extra cost of a shape that needs the user's view turned. */
    public static final int YAW_COST = 2;
    /** Mouse rate (deg/tick) below which no yaw shape is offered: a still mouse never needs a yaw change. */
    public static final double MIN_YAW_RATE = 0.5;
    /** Most the turn gain can speed the user's mouse up (the core clamps gains to 0.5..1.5). */
    public static final double MAX_GAIN = 1.5;
    /** Ground ticks a candidate may walk before its takeoff (one past the window). */
    static final int MAX_GROUND = WINDOW_TICKS + 2;
    /** Air ticks simulated after a takeoff before it counts as landing nowhere. */
    static final int AIR_TICKS = JumpSearch.HORIZON_TICKS;

    private final MovementPhysics body = new MovementPhysics();
    private final MovementPhysics probe = new MovementPhysics();

    // last candidate simulation
    private int cJumpTick;
    private double cMargin;
    private double cMiss;
    private double cSpeed;
    private double cLandX, cLandY, cLandZ;
    private float cDamage;

    // result of the last evaluate
    private boolean needed;
    private boolean found;
    private int strafe;
    private int strafeTicks;
    private int sprintTick = -1;
    private boolean yawShape;
    private float idealYaw;
    private boolean jumpNow;
    private int cost;
    private double gain;
    private double plainMiss;
    private double plainMargin;
    private double shapeMargin;
    private double plainSpeed;
    private double shapeSpeed;
    private int plainJumpTick = -1;
    private int ticks;

    /**
     * Evaluates the run-up. Needs a target on {@code search}; does nothing (and reports nothing needed) without one,
     * in the air, without forward held, or when no edge is within {@link #WINDOW_TICKS}.
     *
     * @param userF      the user's forward axis (shaping needs forward held)
     * @param userS      the user's strafe axis; a held strafe key is never changed
     * @param yawRate    the user's mouse rate (deg/tick), for yaw shapes
     * @param allowYaw   yaw shapes may be reported (turn gain on, not Legit, not automation)
     * @param allowSprint sprint may be engaged for the user (they are not sprinting and could)
     * @param safeOnly   SafeWalk: a landing that costs health is invalid
     * @return true when a shape was found that the plain run-up needs
     */
    public boolean evaluate(MovementPhysics now, float yaw, boolean sprinting, int userF, int userS, double yawRate,
                            boolean allowYaw, boolean allowSprint, boolean safeOnly, float health, JumpSearch search,
                            MovementPhysics.Voxels w) {
        needed = false;
        found = false;
        strafe = 0;
        strafeTicks = 0;
        sprintTick = -1;
        yawShape = false;
        idealYaw = yaw;
        jumpNow = false;
        cost = 0;
        gain = 0.0;
        plainMiss = 0.0;
        plainMargin = 0.0;
        shapeMargin = 0.0;
        plainSpeed = 0.0;
        shapeSpeed = 0.0;
        plainJumpTick = -1;
        ticks = 0;
        if (!now.onGround || userF <= 0 || !search.hasTarget()) {
            return false;
        }
        boolean plainOk = simulate(now, yaw, userF, userS, 0, 0, sprinting, -1, health, search, w);
        if (!plainOk && cJumpTick < 0) {
            return false; // no edge in reach: nothing to shape yet
        }
        plainJumpTick = cJumpTick;
        plainMargin = plainOk ? cMargin : -JumpSearch.MISS_NO_LANDING;
        plainMiss = plainOk ? cMiss : JumpSearch.MISS_NO_LANDING;
        plainSpeed = cSpeed;
        if (plainJumpTick > WINDOW_TICKS) {
            return false;
        }
        if (plainOk && safeOnly && cDamage > 0f) {
            plainMargin = -JumpSearch.MISS_NO_LANDING; // SafeWalk would hold this edge anyway
            plainMiss = JumpSearch.MISS_NO_LANDING;
        }
        if (plainMargin >= SAFE_MARGIN) {
            return false; // the user's own jump lands with margin: do nothing
        }
        needed = true;
        double need = plainMargin >= 0.0 ? SAFE_MARGIN : 0.0; // a narrow landing must gain margin to be worth it
        int bestCost = Integer.MAX_VALUE;
        double bestMargin = -Double.MAX_VALUE;
        double bestSpeed = 0.0;
        int groundTicks = plainJumpTick + 1;

        // sprint timing: the highest takeoff speed among the engage ticks that land
        if (allowSprint && !sprinting) {
            for (int k = 0; k <= plainJumpTick; k++) { // later than the plain takeoff, sprint never engages
                if (!valid(simulate(now, yaw, userF, userS, 0, 0, false, k, health, search, w), safeOnly, need)) {
                    continue;
                }
                if (cJumpTick < k) {
                    continue; // took off before sprint engaged: that is the plain jump
                }
                boolean better = 1 < bestCost || (1 == bestCost && cSpeed > bestSpeed + 1.0e-9);
                if (better) {
                    bestCost = 1;
                    bestMargin = cMargin;
                    bestSpeed = cSpeed;
                    take(0, 0, k, false, yaw, 1);
                }
            }
        }
        // strafe key at the current yaw, for the first 1, 2 or all ground ticks
        if (userS == 0) {
            for (int side = 1; side >= -1; side -= 2) {
                for (int v = 0; v < 3; v++) {
                    int n = v == 0 ? 1 : (v == 1 ? 2 : groundTicks);
                    if ((v == 1 && n >= groundTicks) || (v == 2 && groundTicks <= 2) || n > bestCost) {
                        continue;
                    }
                    if (!valid(simulate(now, yaw, userF, userS, side, n, sprinting, -1, health, search, w), safeOnly,
                            need)) {
                        continue;
                    }
                    if (n < bestCost || cMargin > bestMargin + 1.0e-9) {
                        bestCost = n;
                        bestMargin = cMargin;
                        bestSpeed = cSpeed;
                        take(side, n, -1, false, yaw, n);
                    }
                }
            }
        }
        // 45-degree strafe: a view the user is already turning toward, reachable before the takeoff
        if (allowYaw && userS == 0 && Math.abs(yawRate) >= MIN_YAW_RATE
                && Math.abs(yawRate) * MAX_GAIN * groundTicks >= STRAFE_YAW) {
            int side = yawRate > 0 ? 1 : -1; // view yaw + 45 travels straight with left (A), yaw - 45 with right (D)
            float shapeYaw = yaw + side * STRAFE_YAW;
            int c = groundTicks + YAW_COST;
            if (c <= bestCost && valid(simulate(now, shapeYaw, userF, userS, side, MAX_GROUND + 1, sprinting, -1,
                    health, search, w), safeOnly, need) && (c < bestCost || cMargin > bestMargin + 1.0e-9)) {
                bestCost = c;
                bestMargin = cMargin;
                bestSpeed = cSpeed;
                take(side, MAX_GROUND + 1, -1, true, shapeYaw, c);
            }
        }
        if (!found) {
            return false;
        }
        shapeMargin = bestMargin;
        shapeSpeed = bestSpeed;
        gain = bestMargin - plainMargin;
        return true;
    }

    private boolean valid(boolean landed, boolean safeOnly, double need) {
        return landed && cMargin > need && !(safeOnly && cDamage > 0f);
    }

    private void take(int side, int n, int sprintAt, boolean yawNeeded, float yawDeg, int c) {
        found = true;
        strafe = side;
        strafeTicks = n;
        sprintTick = sprintAt;
        yawShape = yawNeeded;
        idealYaw = yawDeg;
        cost = c;
        jumpNow = cJumpTick == 0;
    }

    /**
     * One candidate: ground ticks with forward, the shaped strafe for the first {@code strafeN} ticks (the user's
     * otherwise) and sprint from tick {@code sprintFrom} (-1: the user's state), a jump on the last supported tick,
     * then the user's keys in the air. Fills the {@code c*} fields.
     *
     * @return true when it lands on the target (horizontally within its edges, on its top)
     */
    private boolean simulate(MovementPhysics now, float yaw, int f, int userS, int shapeS, int strafeN,
                             boolean sprinting, int sprintFrom, float health, JumpSearch search,
                             MovementPhysics.Voxels w) {
        cJumpTick = -1;
        cMargin = -JumpSearch.MISS_NO_LANDING;
        cMiss = JumpSearch.MISS_NO_LANDING;
        cSpeed = 0.0;
        cDamage = 0f;
        body.copyFrom(now);
        boolean spr = sprinting;
        for (int i = 0; i <= MAX_GROUND; i++) {
            int s = i < strafeN ? shapeS : userS;
            spr = sprinting || (sprintFrom >= 0 && i >= sprintFrom);
            probe.copyFrom(body).tick(f, s, yaw, spr, false, w);
            ticks++;
            if (!probe.supported(w)) {
                cJumpTick = i;
                cSpeed = Math.sqrt(body.vx * body.vx + body.vz * body.vz);
                body.tick(f, s, yaw, spr, true, w);
                ticks++;
                break;
            }
            if (probe.horizontalCollision && probe.vx == 0.0 && probe.vz == 0.0) {
                return false; // walked into a wall: no takeoff from here
            }
            body.copyFrom(probe);
        }
        if (cJumpTick < 0) {
            return false;
        }
        double maxFall = 0.0;
        boolean airborne = !body.onGround;
        double targetTop = search.targetY() + 1;
        for (int t = 0; t < AIR_TICKS; t++) {
            if (body.onGround && airborne) {
                break;
            }
            if (airborne && body.vy < 0.0 && body.y < targetTop - 1.0e-3) {
                return false; // falling below the target's top: it cannot land on it any more
            }
            double fallBefore = body.fallDistance;
            body.tick(f, userS, yaw, spr, false, w);
            ticks++;
            maxFall = Math.max(maxFall, fallBefore);
            if (!body.onGround) {
                airborne = true;
            } else if (airborne) {
                cDamage = MovementPhysics.fallDamage(Math.max(maxFall, fallBefore));
                break;
            } else if (t > 2) {
                return false; // never left the ground (a jump into a ceiling)
            }
        }
        if (!(body.onGround && airborne) || cDamage >= health) {
            return false;
        }
        cLandX = body.x;
        cLandY = body.y;
        cLandZ = body.z;
        cMiss = search.missDistance(cLandX, cLandY, cLandZ);
        cMargin = margin(cLandX, cLandY, cLandZ, search);
        return cMiss <= 0.0;
    }

    /**
     * How far (blocks) a landing could slide outward and still stand on the target; negative = the miss distance.
     */
    static double margin(double x, double y, double z, JumpSearch search) {
        double miss = search.missDistance(x, y, z);
        if (miss > 0.0) {
            return -miss;
        }
        double lo = MovementPhysics.HALF_WIDTH - 1.0e-3;
        int tx = search.targetX();
        int tz = search.targetZ();
        double mx = Math.min(x - (tx - lo), (tx + 1 + lo) - x);
        double mz = Math.min(z - (tz - lo), (tz + 1 + lo) - z);
        return Math.min(mx, mz);
    }

    /** The plain run-up misses (or lands without margin) and some shape was looked for. */
    public boolean needed() {
        return needed;
    }

    /** A shape was found (the plain run-up needs it and it lands). */
    public boolean found() {
        return found;
    }

    /** Strafe key of the chosen shape (+1 left, -1 right, 0 none). */
    public int strafe() {
        return strafe;
    }

    /** The chosen shape fills the strafe key on this tick (not for a yaw shape: that only asks for turn gain). */
    public boolean strafeNow() {
        return found && !yawShape && strafe != 0 && strafeTicks > 0;
    }

    /** Ground ticks (from now) the chosen shape holds its strafe key. */
    public int strafeTicks() {
        return strafeTicks;
    }

    /** Ground tick (from now) the chosen shape engages sprint, or -1. */
    public int sprintTick() {
        return sprintTick;
    }

    /** The chosen shape engages sprint on this tick. */
    public boolean sprintNow() {
        return found && sprintTick == 0;
    }

    /** The chosen shape needs the view turned to {@link #idealYaw()} (turn gain only, never applied). */
    public boolean yawShape() {
        return found && yawShape;
    }

    /** The takeoff yaw the chosen shape assumes: the current yaw unless {@link #yawShape()}. */
    public float idealYaw() {
        return idealYaw;
    }

    /** The chosen shape jumps on this tick (its last supported tick). */
    public boolean jumpNow() {
        return found && jumpNow;
    }

    /** Authored ticks of the chosen shape. */
    public int cost() {
        return cost;
    }

    /** Predicted gain (blocks): shaped landing margin minus the plain one (a miss counts as a negative margin). */
    public double gainBlocks() {
        return gain;
    }

    /** Target miss of the plain run-up (blocks; {@link JumpSearch#MISS_NO_LANDING} when it lands nowhere). */
    public double plainMiss() {
        return plainMiss;
    }

    public double plainMargin() {
        return plainMargin;
    }

    public double shapeMargin() {
        return shapeMargin;
    }

    /** Horizontal speed (blocks/tick) on the takeoff tick, plain and shaped. */
    public double plainTakeoffSpeed() {
        return plainSpeed;
    }

    public double shapeTakeoffSpeed() {
        return shapeSpeed;
    }

    /** Ground ticks until the plain takeoff (0 = this tick), -1 when none was found. */
    public int plainJumpTick() {
        return plainJumpTick;
    }

    /** Physics ticks the last evaluate simulated. */
    public int lastTicks() {
        return ticks;
    }

    /**
     * Test/diagnostic hook: the takeoff speed of the sprint-timing candidate that engages sprint on ground tick
     * {@code k}, or NaN when that candidate does not land on the target (or takes off before sprint engages).
     */
    double sprintCandidateSpeed(MovementPhysics now, float yaw, int userF, int k, boolean safeOnly, float health,
                                JumpSearch search, MovementPhysics.Voxels w) {
        boolean landed = simulate(now, yaw, userF, 0, 0, 0, false, k, health, search, w);
        return valid(landed, safeOnly, 0.0) && cJumpTick >= k ? cSpeed : Double.NaN;
    }
}
