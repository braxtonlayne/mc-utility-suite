package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.Packed;

/**
 * Follows a planned route as a corridor, not a rail: steers at the farthest route cell that the simulated body can
 * reach in a straight run or jump, so corners are cut, flats are sprint-jumped and gaps the route walks around are
 * jumped when the jump is safe. Engine-free (positions are {@code BlockPos.asLong}-packed feet cells, see
 * {@code travel.Packed}); the caller applies the keys and turns the camera toward {@link #wantYaw()} through the core.
 * <p>
 * Per {@link #update} (one per game tick):
 * <ol>
 *   <li><b>Progress</b>: the farthest cell in a window ahead that the body has reached (within {@link #REACH}), or
 *       passed on a validated straight chord; never goes backwards. {@link #offRoute()} when the body is more than
 *       {@link #OFF_H} blocks (or {@link #OFF_DOWN} down) from every nearby cell and from the chord it was running.</li>
 *   <li><b>Steering</b>: the farthest cell within {@link #LOOK} ahead reachable in a straight walk
 *       ({@link RouteCorridor}); {@link #wantYaw()} faces it. Keys are chosen for the CURRENT yaw (the head lags under
 *       the rotation cap): the forward/strafe combination whose push best follows the wanted direction; no sprint
 *       while the yaw error is large or a sharp corner is close.</li>
 *   <li><b>Sprint hops</b> on flats when a simulated sprint jump lands on the chord at the same height and gains
 *       ground ({@link RouteJumpPlanner#hopGains}); one-block steps up are jumped.</li>
 *   <li><b>Jumps</b> ({@link RouteJumpPlanner}): when the route needs a jump (the next cell is across a gap) or a jump
 *       lands on a later cell of a detour, a forward-simulated run-up + take-off search picks the target; the jump key
 *       goes down on the latest-but-one tick that still lands it with zero fall damage. Mid-air the keys are re-chosen
 *       when the held ones would miss. Jumps chain one after another as each landing replans.</li>
 *   <li><b>Edge guard</b>: the walking keys are simulated two ticks ahead; a step off a drop the route does not take
 *       brakes instead. No jump is ever pressed at a lip unless a simulated landing on a route cell backs it.</li>
 * </ol>
 * Budgets per update: {@link #SIM_BUDGET} simulated ticks in total ({@link #PLAN_BUDGET} of them for the jump search,
 * which resumes on the next tick where it stopped) and {@link #SAMPLE_BUDGET} corridor samples. Allocation free after
 * construction.
 */
public final class RouteFollower {

    /** Longest route kept (cells); a longer one is truncated and replanned on the way. */
    public static final int MAX_ROUTE = 256;

    /** Steering look-ahead (cells). */
    public static final int LOOK = 12;
    /** Jump-target look-ahead (cells). */
    public static final int JUMP_LOOK = 16;
    /** Progress / off-route window ahead (cells). */
    public static final int WINDOW = 16;
    /** A cell counts as reached within this horizontal distance of its centre. */
    public static final double REACH = 0.8;
    public static final double OFF_H = 3.0;
    public static final double OFF_DOWN = 4.0;
    /** Simulated ticks per update, all searches together. */
    public static final int SIM_BUDGET = 480;
    /** Simulated ticks per update for finding a new jump. */
    public static final int PLAN_BUDGET = 240;
    /** Of {@link #SIM_BUDGET}, reserved for the edge-guard and step-off probes (at most 3 per update). */
    static final int GUARD_TICKS = 3;
    /** Corridor line samples per update. */
    public static final int SAMPLE_BUDGET = 600;
    /** Jump targets farther than this (blocks, horizontally) are not searched yet. */
    public static final double JUMP_RANGE = 6.5;
    /** A detour is jumped only when the route around is this many times longer than straight across... */
    public static final double DETOUR_RATIO = 1.6;
    /** ...and at least this many blocks longer. */
    public static final double DETOUR_MIN = 2.0;
    /** No sprint above this yaw error (degrees). */
    public static final double SPRINT_MAX_ERROR = 25.0;
    /** A sprint hop needs the camera this close to the chord (degrees). */
    public static final double HOP_MAX_ERROR = 8.0;
    /** A sprint hop needs at least this much straight run ahead (blocks). */
    public static final double HOP_MIN_RUN = 4.0;

    private static final int[] KF = {1, 1, 1, 0, 0, -1, -1, -1};
    private static final int[] KS = {0, 1, -1, 1, -1, 0, 1, -1};
    private static final double INV_SQRT2 = 0.7071067811865476;

    private final long[] route = new long[MAX_ROUTE];
    private int length;
    private int progress;

    private int forward;
    private int strafe;
    private boolean jump;
    private boolean sprint;
    private float wantYaw;
    private boolean finished;
    private boolean offRoute;

    private final RouteCorridor corridor = new RouteCorridor();
    private final RouteJumpPlanner jumps = new RouteJumpPlanner();
    private final MovementPhysics probe = new MovementPhysics();

    // steering
    private int steerIndex;
    private boolean steerWalkable;
    private double chordX, chordZ;
    // jump plan
    private int planTarget = -1;
    private int blockedTarget = -1;
    private int planCursor = -1;
    private int planProgress = -1;
    private int planLo;
    private int planHi;
    private boolean flying;
    private int flightTarget = -1;
    private float flightYaw;
    // diagnostics
    private int lastSimTicks;
    private int guardTicks;
    private int lastSamples;
    private int jumpsTaken;

    /** Replaces the route with the first {@code count} positions of {@code packed} (copied). */
    public void setRoute(long[] packed, int count) {
        length = Math.min(Math.max(count, 0), MAX_ROUTE);
        System.arraycopy(packed, 0, route, 0, length);
        progress = 0;
        finished = length == 0;
        offRoute = false;
        resetPlan();
    }

    public void clear() {
        length = 0;
        progress = 0;
        finished = false;
        offRoute = false;
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
        resetPlan();
    }

    private void resetPlan() {
        steerIndex = 0;
        steerWalkable = false;
        planTarget = -1;
        blockedTarget = -1;
        planCursor = -1;
        planProgress = -1;
        flying = false;
        flightTarget = -1;
    }

    public boolean hasRoute() {
        return length > 0;
    }

    /**
     * One decision per tick.
     *
     * @param now    the player's body this tick
     * @param yaw    the player's current yaw (the keys are chosen for this yaw; {@link #wantYaw()} is where to turn)
     * @param health for fall-damage limits (a jump that would cost health is never taken)
     */
    public void update(MovementPhysics now, float yaw, float health, MovementPhysics.Voxels w) {
        int heldForward = forward;
        int heldStrafe = strafe;
        boolean heldSprint = sprint;
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
        jumps.begin(SIM_BUDGET - GUARD_TICKS);
        guardTicks = 0;
        corridor.begin(SAMPLE_BUDGET);
        try {
            if (length == 0 || finished) {
                wantYaw = yaw;
                return;
            }
            advanceProgress(now);
            int last = length - 1;
            if (progress == last && now.onGround && feetIn(now, route[last])) {
                finished = true;
                wantYaw = yaw;
                return;
            }
            offRoute = !nearRoute(now);
            if (offRoute) {
                flying = false;
                planTarget = -1;
                wantYaw = yaw;
                return;
            }
            if (!now.onGround) {
                airborne(now, yaw, heldForward, heldStrafe, heldSprint, w);
                return;
            }
            if (flying) {
                flying = false; // landed
                flightTarget = -1;
                planTarget = -1;
            }
            int fy = MovementPhysics.floor(now.y + 0.01);
            computeSteer(now, fy, w);
            if (planTarget >= 0 && (planTarget <= progress || planTarget >= length)) {
                planTarget = -1;
            }
            if (planTarget < 0 && health > 0f) {
                searchPlan(now, fy, w);
            }
            if (planTarget >= 0 && executePlan(now, yaw, w)) {
                return;
            }
            walk(now, yaw, fy, w);
        } finally {
            lastSimTicks = jumps.ticks() + guardTicks;
            lastSamples = corridor.samples();
        }
    }

    // ---- progress ----------------------------------------------------------------------------------------------------

    private void advanceProgress(MovementPhysics now) {
        int end = Math.min(length - 1, progress + WINDOW);
        int p = progress;
        for (int i = progress + 1; i <= end; i++) {
            long c = route[i];
            double dx = now.x - (Packed.x(c) + 0.5);
            double dz = now.z - (Packed.z(c) + 0.5);
            double dy = now.y - Packed.y(c);
            if (dx * dx + dz * dz <= REACH * REACH && dy >= -0.5 && dy <= 1.5) {
                p = i;
            }
        }
        // cells a validated straight chord skips (corner cuts) count as passed once the body is nearer the target
        if (planTarget < 0 && !flying && steerWalkable && steerIndex > p && steerIndex < length) {
            long t = route[steerIndex];
            double tx = Packed.x(t) + 0.5;
            double tz = Packed.z(t) + 0.5;
            double bd = Math.hypot(now.x - tx, now.z - tz);
            for (int i = p + 1; i < steerIndex; i++) {
                long c = route[i];
                if (Math.hypot(Packed.x(c) + 0.5 - tx, Packed.z(c) + 0.5 - tz) > bd + 0.5
                        && Math.abs(now.y - Packed.y(c)) <= 1.5) {
                    p = i;
                }
            }
        }
        progress = p;
    }

    private boolean nearRoute(MovementPhysics now) {
        int from = Math.max(0, progress - 2);
        int to = Math.min(length - 1, progress + WINDOW);
        for (int i = from; i <= to; i++) {
            long c = route[i];
            double dx = now.x - (Packed.x(c) + 0.5);
            double dz = now.z - (Packed.z(c) + 0.5);
            double dy = now.y - Packed.y(c);
            if (dx * dx + dz * dz <= OFF_H * OFF_H && dy >= -OFF_DOWN && dy <= OFF_H) {
                return true;
            }
        }
        // the chord being run (or flown) toward the steering / jump target
        int target = flying ? flightTarget : planTarget >= 0 ? planTarget : steerIndex;
        if (target < 0 || target >= length) {
            return false;
        }
        long c = route[target];
        double tx = Packed.x(c) + 0.5;
        double tz = Packed.z(c) + 0.5;
        double dy = now.y - Packed.y(c);
        return dy >= -OFF_DOWN && dy <= OFF_H && segmentDistance(now.x, now.z, chordX, chordZ, tx, tz) <= 2.0;
    }

    private static double segmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double l2 = vx * vx + vz * vz;
        double t = l2 < 1.0e-9 ? 0 : ((px - ax) * vx + (pz - az) * vz) / l2;
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        double ex = ax + vx * t - px;
        double ez = az + vz * t - pz;
        return Math.sqrt(ex * ex + ez * ez);
    }

    private static boolean feetIn(MovementPhysics now, long c) {
        return MovementPhysics.floor(now.x) == Packed.x(c) && MovementPhysics.floor(now.z) == Packed.z(c)
                && MovementPhysics.floor(now.y + 0.01) == Packed.y(c);
    }

    // ---- steering ----------------------------------------------------------------------------------------------------

    private void computeSteer(MovementPhysics now, int fy, MovementPhysics.Voxels w) {
        int last = length - 1;
        int old = steerIndex;
        if (progress >= last) {
            steerIndex = last;
            steerWalkable = true;
        } else {
            int end = Math.min(last, progress + LOOK);
            int best = -1;
            int fails = 0;
            for (int j = progress + 1; j <= end && !corridor.exhausted(); j++) {
                if (corridor.straight(now.x, now.z, fy, route, progress, j, w)) {
                    best = j;
                    fails = 0;
                } else if (++fails >= 2) {
                    break;
                }
            }
            steerWalkable = best >= 0;
            steerIndex = best >= 0 ? best : progress + 1;
        }
        if (steerIndex != old) {
            chordX = now.x;
            chordZ = now.z;
        }
    }

    private void walk(MovementPhysics now, float yaw, int fy, MovementPhysics.Voxels w) {
        long t = route[steerIndex];
        double tx = Packed.x(t) + 0.5;
        double tz = Packed.z(t) + 0.5;
        double dx = tx - now.x;
        double dz = tz - now.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean last = steerIndex == length - 1;
        if (dist < 0.05) {
            wantYaw = yaw;
            return;
        }
        wantYaw = (float) Angles.yawTo(dx, dz);
        double err = Math.abs(Angles.delta(yaw, wantYaw));
        chooseKeys(yaw, dx / dist, dz / dist);
        boolean sharpCorner = false;
        if (!last && dist < 2.0) {
            long n = route[steerIndex + 1];
            double nx = Packed.x(n) - Packed.x(t);
            double nz = Packed.z(n) - Packed.z(t);
            double nl = Math.sqrt(nx * nx + nz * nz);
            sharpCorner = nl > 0 && (nx * dx + nz * dz) / (nl * dist) < 0.5;
        }
        sprint = forward > 0 && err < SPRINT_MAX_ERROR && !sharpCorner && (dist >= 2.5 || !last);
        if (last) {
            double v = Math.sqrt(now.vx * now.vx + now.vz * now.vz);
            double stop = v * 0.546 / (1 - 0.546);
            if (dist <= stop + 0.1) {
                forward = 0;
                strafe = 0;
                sprint = false;
            }
        }
        // one-block step up just ahead
        if ((forward != 0 || strafe != 0) && stepAhead(now, fy, dx / dist, dz / dist, w)) {
            jump = true;
            return;
        }
        if (sprint && forward == 1 && strafe == 0 && steerWalkable && err < HOP_MAX_ERROR && dist >= HOP_MIN_RUN
                && Packed.y(t) == fy && jumps.hopGains(now, yaw, dx / dist, dz / dist, dist - 0.3, 0.45, w)) {
            jump = true;
            jumpsTaken++;
            return;
        }
        edgeGuard(now, yaw, fy, w);
    }

    /** The forward/strafe keys whose push (at the current yaw) points closest to (ux, uz). */
    private void chooseKeys(float yaw, double ux, double uz) {
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        double best = -2;
        for (int i = 0; i < KF.length; i++) {
            double ix = KS[i];
            double iz = KF[i];
            if (ix != 0 && iz != 0) {
                ix *= INV_SQRT2;
                iz *= INV_SQRT2;
            }
            double mx = ix * cos - iz * sin;
            double mz = iz * cos + ix * sin;
            double d = mx * ux + mz * uz;
            if (d > best + 1.0e-9) {
                best = d;
                forward = KF[i];
                strafe = KS[i];
            }
        }
    }

    private boolean stepAhead(MovementPhysics now, int fy, double ux, double uz, MovementPhysics.Voxels w) {
        int ax = MovementPhysics.floor(now.x + ux * 0.6);
        int az = MovementPhysics.floor(now.z + uz * 0.6);
        int bx = MovementPhysics.floor(now.x);
        int bz = MovementPhysics.floor(now.z);
        if (ax == bx && az == bz) {
            return false;
        }
        if (!w.solid(ax, fy, az) || !RouteCorridor.passable(ax, fy + 1, az, w) || w.solid(bx, fy + 2, bz)) {
            return false;
        }
        int to = Math.min(length - 1, steerIndex + 1);
        for (int i = progress; i <= to; i++) {
            if (Packed.y(route[i]) > fy) {
                return true;
            }
        }
        return false;
    }

    /** Simulates the walking keys two ticks; a step off a drop the route does not take brakes instead. */
    private void edgeGuard(MovementPhysics now, float yaw, int fy, MovementPhysics.Voxels w) {
        if (forward == 0 && strafe == 0) {
            return;
        }
        probe.copyFrom(now);
        probe.tick(forward, strafe, yaw, sprint, false, w);
        guardTicks++;
        probe.tick(forward, strafe, yaw, sprint, false, w);
        guardTicks++;
        if (probe.supported(w)) {
            return;
        }
        int px = MovementPhysics.floor(probe.x);
        int pz = MovementPhysics.floor(probe.z);
        int drop = -1;
        for (int d = 1; d <= 3; d++) {
            if (w.solid(px, fy - 1 - d, pz)) {
                drop = d;
                break;
            }
        }
        if (drop == 1 || (drop > 1 && RouteCorridor.indexOf(route, progress,
                Math.min(length - 1, progress + WINDOW), px, fy - drop, pz) >= 0)) {
            return;
        }
        brake(now, yaw);
    }

    /** Keys that push against the current horizontal velocity (or none when nearly stopped). */
    private void brake(MovementPhysics now, float yaw) {
        sprint = false;
        jump = false;
        double v = Math.sqrt(now.vx * now.vx + now.vz * now.vz);
        if (v < 0.01) {
            forward = 0;
            strafe = 0;
            return;
        }
        chooseKeys(yaw, -now.vx / v, -now.vz / v);
    }

    // ---- jumps -------------------------------------------------------------------------------------------------------

    /** Budgeted search for a jump target; resumes next tick where the budget ran out. */
    private void searchPlan(MovementPhysics now, int fy, MovementPhysics.Voxels w) {
        if (planProgress != progress) {
            planProgress = progress;
            planCursor = -1;
            blockedTarget = -1;
        }
        int top = Math.min(length - 1, progress + JUMP_LOOK);
        int lower = steerWalkable ? steerIndex + 1 : progress + 1;
        if (planCursor > top || planCursor < lower) {
            planCursor = top;
        }
        long pc = route[progress];
        double px = Packed.x(pc) + 0.5;
        double pz = Packed.z(pc) + 0.5;
        while (planCursor >= lower && jumps.ticks() < PLAN_BUDGET) {
            int k = planCursor--;
            if (k == blockedTarget) {
                continue;
            }
            long c = route[k];
            int kx = Packed.x(c);
            int ky = Packed.y(c);
            int kz = Packed.z(c);
            double dx = kx + 0.5 - now.x;
            double dz = kz + 0.5 - now.z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            int dy = ky - fy;
            if (hd < 1.5 || hd > JUMP_RANGE || dy < -2 || dy > 1 || !RouteCorridor.standable(kx, ky, kz, w)) {
                continue;
            }
            boolean needed = (!steerWalkable && k == progress + 1)
                    || (steerWalkable && k == steerIndex + 1 && !walkableStep(steerIndex, k, w));
            if (!needed) {
                double routeLen = 0;
                for (int i = progress; i < k; i++) {
                    routeLen += horizontal(route[i], route[i + 1]);
                }
                double across = Math.hypot(kx + 0.5 - px, kz + 0.5 - pz);
                if (routeLen < across * DETOUR_RATIO || routeLen - across < DETOUR_MIN) {
                    continue;
                }
            }
            float yawK = (float) Angles.yawTo(dx, dz);
            aim(k, needed);
            if (jumps.searchTakeoff(now, yawK, w) >= 0) {
                planTarget = k;
                planCursor = -1;
                chordX = now.x;
                chordZ = now.z;
                return;
            }
        }
        if (planCursor < lower) {
            planCursor = -1; // full pass done: start again from the top next tick
        }
    }

    /**
     * Landing cells for a jump at route cell {@code k}: k and up to two after it, plus the one before it unless k is the
     * cell right across a gap the route itself jumps (then the one before is the take-off side).
     */
    private void aim(int k, boolean needed) {
        planLo = needed ? k : Math.max(progress + 1, k - 1);
        planHi = Math.min(length - 1, k + 2);
        jumps.aimRoute(route, planLo, planHi, k);
    }

    private boolean walkableStep(int from, int to, MovementPhysics.Voxels w) {
        long a = route[from];
        return corridor.straight(Packed.x(a) + 0.5, Packed.z(a) + 0.5, Packed.y(a), route, from, to, w);
    }

    private static double horizontal(long a, long b) {
        return Math.hypot(Packed.x(b) - Packed.x(a), Packed.z(b) - Packed.z(a));
    }

    /**
     * Runs toward the planned target and presses jump on the latest-but-one tick that still lands it. Returns false
     * when the plan was dropped (the caller then walks the route).
     */
    private boolean executePlan(MovementPhysics now, float yaw, MovementPhysics.Voxels w) {
        int k = planTarget;
        long c = route[k];
        int kx = Packed.x(c);
        int ky = Packed.y(c);
        int kz = Packed.z(c);
        double dx = kx + 0.5 - now.x;
        double dz = kz + 0.5 - now.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        wantYaw = (float) Angles.yawTo(dx, dz);
        chooseKeys(yaw, dx / hd, dz / hd);
        sprint = forward > 0;
        jumps.aimRoute(route, planLo, planHi, k);
        boolean l0 = jumps.jumpLands(now, 0, forward, strafe, yaw, sprint, w);
        if (l0) {
            boolean l1 = jumps.jumpLands(now, 1, forward, strafe, yaw, sprint, w);
            boolean l2 = l1 && jumps.jumpLands(now, 2, forward, strafe, yaw, sprint, w);
            if (!l2) {
                jump = true;
                jumpsTaken++;
                flying = true;
                flightTarget = k;
                flightYaw = yaw;
                wantYaw = yaw;
                chordX = now.x;
                chordZ = now.z;
                return true;
            }
            return true; // a later take-off still lands: keep running
        }
        // not yet: does the plan still hold (ideal yaw), and is the next step still on the ground?
        probe.copyFrom(now);
        probe.tick(forward, strafe, yaw, sprint, false, w);
        guardTicks++;
        boolean stepsOff = !probe.supported(w);
        if (stepsOff || (jumps.searchTakeoff(now, wantYaw, w) < 0 && !jumps.exhausted())) {
            blockedTarget = k;
            planTarget = -1;
            if (stepsOff) {
                brake(now, yaw);
                return true;
            }
            return false;
        }
        return true;
    }

    private void airborne(MovementPhysics now, float yaw, int heldForward, int heldStrafe, boolean heldSprint,
                          MovementPhysics.Voxels w) {
        forward = heldForward;
        strafe = heldStrafe;
        sprint = heldSprint;
        if (!flying || flightTarget < 0) {
            return; // hop, step or a route drop: keep the keys and the look
        }
        wantYaw = flightYaw;
        jumps.aimRoute(route, planLo, planHi, flightTarget);
        if (!jumps.flight(now, forward, strafe, yaw, sprint, false, w)
                && jumps.airSteer(now, yaw, sprint, w)) {
            forward = jumps.steerForward();
            strafe = jumps.steerStrafe();
        }
    }

    // ---- outputs -----------------------------------------------------------------------------------------------------

    /** +1 forward key, -1 back, 0 none. */
    public int forward() {
        return forward;
    }

    /** +1 left key, -1 right, 0 none. */
    public int strafe() {
        return strafe;
    }

    public boolean jump() {
        return jump;
    }

    public boolean sprint() {
        return sprint;
    }

    /** Yaw along the route at the steering point (look where you move). */
    public float wantYaw() {
        return wantYaw;
    }

    /** The body stands on the last route cell. */
    public boolean finished() {
        return finished;
    }

    /** The body left the corridor (knocked away, fell): the caller should replan. */
    public boolean offRoute() {
        return offRoute;
    }

    /** Index of the route cell the body has reached. */
    public int progressIndex() {
        return progress;
    }

    public int length() {
        return length;
    }

    /** Route cell {@code i} (packed), for overlays. */
    public long cell(int i) {
        return route[i];
    }

    /** Index of the cell currently steered at. */
    public int steerIndex() {
        return steerIndex;
    }

    /** Index of the route cell a committed jump aims at, or -1. */
    public int jumpTarget() {
        return flying ? flightTarget : planTarget;
    }

    /** Simulated ticks spent by the last update (at most {@link #SIM_BUDGET}). */
    public int lastSimTicks() {
        return lastSimTicks;
    }

    /** Corridor samples spent by the last update (at most {@link #SAMPLE_BUDGET}). */
    public int lastSamples() {
        return lastSamples;
    }

    /** Jumps pressed since construction (hops and planned jumps), for diagnostics and tests. */
    public int jumpsTaken() {
        return jumpsTaken;
    }
}
