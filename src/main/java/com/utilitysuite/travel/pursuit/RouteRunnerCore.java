package com.utilitysuite.travel.pursuit;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IRoutePlanner;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.Packed;

/**
 * Engine-free state machine of the pursuit / flee route runner. Collaborators are small interfaces so it can be tested
 * with fakes. Call {@link #tick()} once per client tick; it returns in a few instructions while IDLE (or ARRIVED /
 * FAILED, which wait for the next pursue/flee call).
 * <p>
 * Planning: farther than {@link #LOCAL_RANGE} blocks the {@link IRoutePlanner} is asked (pursuit: distance to the
 * target; flee: distance still to gain); the run moves on a local plan meanwhile (pursuit: a direct two-cell route the
 * follower judges; flee: the escape planner). Inside the range the planner is never asked. Replans are throttled: at
 * most every {@link #MIN_REPLAN_TICKS}, on target drift, an off-route follower, a finished route or the cadence.
 * <p>
 * Obstruction: the next {@link #WATCH_CELLS} route cells ahead of the body are watched against what they were when they
 * came into view (feet and head clear, floor present, through a {@link CellProbe}). A cell that turns solid or loses its
 * floor replans at once (pursuit / flee; a caller's {@link #follow} route is the caller's), without waiting for the
 * cadence, and drops the held route meanwhile. Rechecks are event driven: {@link #onBlockChanged} marks the watch dirty
 * when a change touches a watched cell; without a block-change feed they run every {@link #POLL_TICKS}. Obstruction
 * replans are debounced ({@link #OBSTRUCTION_DEBOUNCE_TICKS}) so a flickering block cannot start a replan storm.
 * <p>
 * Allocation free per tick, except a new {@code RotationTarget} when the wanted yaw changed by more than
 * {@link #YAW_RESEND_DEG} (the record is the API's currency; this is bounded by the turn rate, not every tick).
 */
public final class RouteRunnerCore {

    public static final double LOCAL_RANGE = 12.0;
    public static final int MIN_REPLAN_TICKS = 10;
    public static final int CADENCE_TICKS = 30;
    public static final double DRIFT_BLOCKS = 3.0;
    public static final float YAW_RESEND_DEG = 4.0f;
    /** Route cells ahead of the body watched for obstruction. */
    public static final int WATCH_CELLS = 4;
    /** Least ticks between two obstruction replans. */
    public static final int OBSTRUCTION_DEBOUNCE_TICKS = 10;
    /** Recheck interval of the watched cells while no block-change feed is bound. */
    public static final int POLL_TICKS = 5;
    private static final double RESUME_HYSTERESIS = 0.5;
    private static final int MODE_PURSUE = 1;
    private static final int MODE_FLEE = 2;
    /** A route the caller planned ({@link #follow}): no planner, ARRIVED at its end. */
    private static final int MODE_FOLLOW = 3;
    private static final SimKey[] KEYS = SimKey.all();

    private final BodySource body;
    private final Steering steer;
    private final LocalFlee flee;
    private final CellProbe probe;
    private final long[] buf = new long[256];
    private final boolean[] held = new boolean[KEYS.length];

    private IInputSmoother input;
    private IRoutePlanner planner;

    private IRouteRunner.State state = IRouteRunner.State.IDLE;
    private ControlLease lease;
    private int mode;
    private double tx, ty, tz, range;
    private boolean look;
    private boolean lookSent;
    private float lastYaw;

    private int now;
    private int lastPlanTick;
    private boolean havePlan;
    private double plannedX, plannedZ;
    private boolean planPending;
    private boolean plannerUsed;
    private boolean followingPlannerRoute;
    private int lastSerial = -1;

    // obstruction watch (see the class doc); two buffers swapped per update, so it allocates nothing
    private long[] watchCell = new long[WATCH_CELLS];
    private int[] watchBits = new int[WATCH_CELLS];
    private long[] nextCell = new long[WATCH_CELLS];
    private int[] nextBits = new int[WATCH_CELLS];
    private int watchCount;
    private boolean watchDirty;
    private boolean feedBound;
    private int lastPollTick;
    private boolean obstructed;
    private int lastObstructionReplan = -OBSTRUCTION_DEBOUNCE_TICKS;
    private int obstructionReplans;

    public RouteRunnerCore(BodySource body, Steering steer, LocalFlee flee) {
        this(body, steer, flee, null);
    }

    /** @param probe reads route cells for the obstruction watch; null = no watch */
    public RouteRunnerCore(BodySource body, Steering steer, LocalFlee flee, CellProbe probe) {
        this.body = body;
        this.steer = steer;
        this.flee = flee;
        this.probe = probe;
    }

    /** A block-change feed calls {@link #onBlockChanged}: the watch is rechecked on changes only, never polled. */
    public void setBlockFeedBound(boolean bound) {
        feedBound = bound;
    }

    /**
     * A client block change. Marks the watch dirty when it touches a watched cell (its feet, head or floor block);
     * the recheck runs on the next {@link #tick()}. A few compares, allocation free.
     */
    public void onBlockChanged(long packedPos) {
        if (watchCount == 0 || idle()) {
            return;
        }
        int x = Packed.x(packedPos);
        int y = Packed.y(packedPos);
        int z = Packed.z(packedPos);
        for (int k = 0; k < watchCount; k++) {
            long c = watchCell[k];
            if (Packed.x(c) == x && Packed.z(c) == z) {
                int dy = y - Packed.y(c);
                if (dy >= -1 && dy <= 1) {
                    watchDirty = true;
                    return;
                }
            }
        }
    }

    /** Late binding of the services (they may be registered after this object is built). */
    public void bind(IInputSmoother input, IRoutePlanner planner) {
        this.input = input;
        this.planner = planner;
    }

    public IRouteRunner.State state() {
        return state;
    }

    /** True when {@link #tick()} has nothing to do. */
    public boolean idle() {
        return state != IRouteRunner.State.PLANNING && state != IRouteRunner.State.RUNNING;
    }

    /** True while a RUNNING run holds a route (for read-only overlays). */
    public boolean followingRoute() {
        return state == IRouteRunner.State.RUNNING && steer.hasRoute();
    }

    public boolean isAvailable() {
        return body.available();
    }

    public boolean isRunning(ControlLease l) {
        return l != null && sameLease(l) && !idle();
    }

    public boolean pursue(ControlLease l, double x, double y, double z, double stopRange, boolean lookAlong) {
        return start(MODE_PURSUE, l, x, y, z, stopRange, lookAlong);
    }

    public boolean flee(ControlLease l, double x, double y, double z, double minDistance, boolean lookAlong) {
        return start(MODE_FLEE, l, x, y, z, minDistance, lookAlong);
    }

    /**
     * Runs a route the caller planned (see {@code IRouteRunner#follow}): no planner, no replans; ARRIVED when the
     * steering finished the route, FAILED when it went off route. A new call replaces the route of a running follow
     * without releasing the keys.
     */
    public boolean follow(ControlLease l, long[] cells, int count, boolean lookAlong) {
        if (l == null || !l.isActive() || !l.covers(ControlChannel.MOVEMENT) || input == null || !body.available()
                || cells == null || count < 2) {
            return false;
        }
        if (!idle() && (mode != MODE_FOLLOW || !sameLease(l))) {
            endRun();
        }
        if (idle()) {
            lease = l;
            lookSent = false;
        }
        cancelPlan();
        mode = MODE_FOLLOW;
        look = lookAlong && l.covers(ControlChannel.CAMERA);
        int n = Math.min(count, buf.length);
        long end = cells[n - 1];
        tx = Packed.x(end) + 0.5;
        ty = Packed.y(end);
        tz = Packed.z(end) + 0.5;
        range = 0.0;
        steer.setRoute(cells, n);
        havePlan = true;
        state = IRouteRunner.State.RUNNING;
        return true;
    }

    private boolean start(int m, ControlLease l, double x, double y, double z, double r, boolean lookAlong) {
        if (l == null || !l.isActive() || !l.covers(ControlChannel.MOVEMENT) || input == null || !body.available()) {
            return false;
        }
        if (!idle() && (m != mode || !sameLease(l))) {
            endRun(); // another lease or mode takes over: release what the old run holds
        }
        tx = x;
        ty = y;
        tz = z;
        range = r;
        look = lookAlong && l.covers(ControlChannel.CAMERA);
        if (state == IRouteRunner.State.ARRIVED && m == mode && sameLease(l)
                && (!body.refresh() || !beyondArrival(RESUME_HYSTERESIS))) {
            return true; // still arrived
        }
        if (idle()) {
            lease = l;
            mode = m;
            state = IRouteRunner.State.PLANNING;
            havePlan = false;
            lookSent = false;
            followingPlannerRoute = false;
            steer.clear();
        }
        return true;
    }

    /** Ends the run for this lease; no-op for another lease. */
    public void stop(ControlLease l) {
        if (l == null || state == IRouteRunner.State.IDLE || !sameLease(l)) {
            return;
        }
        endRun();
    }

    /** World unload / logout: forget everything (the core input layer drops the keys itself). */
    public void reset() {
        if (state != IRouteRunner.State.IDLE) {
            endRun();
        }
    }

    private void endRun() {
        releaseKeys();
        cancelPlan();
        steer.clear();
        clearWatch();
        state = IRouteRunner.State.IDLE;
        lease = null;
    }

    private void arrive() {
        releaseKeys();
        cancelPlan();
        steer.clear();
        clearWatch();
        havePlan = false;
        state = IRouteRunner.State.ARRIVED;
    }

    private void fail() {
        releaseKeys();
        cancelPlan();
        steer.clear();
        clearWatch();
        havePlan = false;
        state = IRouteRunner.State.FAILED;
    }

    private void cancelPlan() {
        if (plannerUsed && planner != null) {
            planner.cancelPlan();
        }
        plannerUsed = false;
        planPending = false;
        followingPlannerRoute = false;
    }

    private void releaseKeys() {
        if (lease != null && input != null) {
            for (int i = 0; i < held.length; i++) {
                if (held[i]) {
                    input.setKeyHeld(lease, KEYS[i], false);
                }
            }
            if (lookSent) {
                input.clearRotation(lease);
            }
        }
        for (int i = 0; i < held.length; i++) {
            held[i] = false;
        }
        lookSent = false;
    }

    private boolean sameLease(ControlLease l) {
        return lease != null && (l == lease || l.id() == lease.id());
    }

    private boolean beyondArrival(double margin) {
        double dx = tx - body.x();
        double dz = tz - body.z();
        double h = Math.sqrt(dx * dx + dz * dz);
        return mode == MODE_PURSUE ? h > range + margin : h < range - margin;
    }

    public void tick() {
        if (state != IRouteRunner.State.PLANNING && state != IRouteRunner.State.RUNNING) {
            return;
        }
        if (lease == null || !lease.isActive()) {
            fail();
            return;
        }
        if (!body.refresh()) {
            reset();
            return;
        }
        now++;
        if (mode == MODE_FOLLOW) {
            if (steer.finished()) {
                arrive();
                return;
            }
            if (steer.offRoute()) {
                fail();
                return;
            }
            steer.update(body.yaw(), body.health());
            if (!applyKeys()) {
                fail();
                return;
            }
            if (look) {
                applyLook();
            }
            return;
        }
        double bx = body.x();
        double by = body.y();
        double bz = body.z();
        double dx = tx - bx;
        double dz = tz - bz;
        double h = Math.sqrt(dx * dx + dz * dz);
        if (mode == MODE_PURSUE ? h <= range : h >= range) {
            arrive();
            return;
        }
        consumePlannerResult();
        updateWatch();
        if (shouldReplan() && !replan(bx, by, bz, h)) {
            fail();
            return;
        }
        steer.update(body.yaw(), body.health());
        if (!applyKeys()) {
            fail();
            return;
        }
        if (look) {
            applyLook();
        }
    }

    private void consumePlannerResult() {
        if (!planPending || planner == null) {
            return;
        }
        IRoutePlanner.PlanState ps = planner.state();
        if (ps == IRoutePlanner.PlanState.READY && planner.planSerial() != lastSerial) {
            lastSerial = planner.planSerial();
            planPending = false;
            int n = planner.copyPath(buf);
            if (n > 1) {
                steer.setRoute(buf, n);
                followingPlannerRoute = true;
            }
            state = IRouteRunner.State.RUNNING;
        } else if (ps == IRoutePlanner.PlanState.FAILED || ps == IRoutePlanner.PlanState.NONE) {
            planPending = false;
            state = IRouteRunner.State.RUNNING;
        }
    }

    private boolean shouldReplan() {
        if (!havePlan) {
            return true;
        }
        if (obstructed && now - lastObstructionReplan >= OBSTRUCTION_DEBOUNCE_TICKS) {
            return true; // a cell ahead turned solid or lost its floor: at once, not on the cadence
        }
        int since = now - lastPlanTick;
        if (since < MIN_REPLAN_TICKS) {
            return false;
        }
        double mx = tx - plannedX;
        double mz = tz - plannedZ;
        return steer.offRoute() || steer.finished() || mx * mx + mz * mz > DRIFT_BLOCKS * DRIFT_BLOCKS
                || since >= CADENCE_TICKS;
    }

    /** @return false when no plan could be made at all. */
    private boolean replan(double bx, double by, double bz, double h) {
        lastPlanTick = now;
        havePlan = true;
        plannedX = tx;
        plannedZ = tz;
        boolean far = mode == MODE_PURSUE ? h > LOCAL_RANGE : range - h > LOCAL_RANGE;
        boolean blocked = obstructed;
        if (blocked) {
            obstructed = false;
            lastObstructionReplan = now;
            obstructionReplans++;
        }
        // an obstructed route is not walked on while the fresh one is planned
        boolean keepRoute = !blocked && followingPlannerRoute && steer.hasRoute() && !steer.offRoute()
                && !steer.finished();
        if (far && planner != null && planner.isAvailable()) {
            boolean started = mode == MODE_PURSUE
                    ? planner.planTo(floor(tx), floor(ty), floor(tz), 1)
                    : planner.planAway(floor(tx), floor(ty), floor(tz), (int) Math.ceil(range));
            if (started) {
                planPending = true;
                plannerUsed = true;
                if (keepRoute) {
                    return true; // keep walking the held route until the fresh one is READY
                }
                state = IRouteRunner.State.PLANNING;
                followingPlannerRoute = false;
                localRoute(bx, by, bz); // stopgap; PLANNING may wait without any route
                return true;
            }
        }
        if (planPending && planner != null) {
            planner.cancelPlan();
        }
        planPending = false;
        followingPlannerRoute = false;
        state = IRouteRunner.State.RUNNING;
        return localRoute(bx, by, bz) || steer.hasRoute();
    }

    private boolean localRoute(double bx, double by, double bz) {
        if (mode == MODE_PURSUE) {
            buf[0] = Packed.pack(floor(bx), floor(by + 0.01), floor(bz));
            buf[1] = Packed.pack(floor(tx), floor(ty), floor(tz));
            steer.setRoute(buf, 2);
            return true;
        }
        int n = flee.plan(tx, ty, tz, buf);
        if (n > 1) {
            steer.setRoute(buf, n);
            return true;
        }
        return false;
    }

    /**
     * Refreshes the watched window (the next {@link #WATCH_CELLS} route cells ahead of the body). A cell coming into
     * view is read once as its baseline; on a dirty watch (or the poll without a feed) every watched cell is read again
     * and one that lost CLEAR or FLOOR against its baseline marks the run obstructed. The baseline then follows the
     * cell, so a replan onto the same cells does not trigger again.
     */
    private void updateWatch() {
        if (probe == null || !steer.hasRoute()) {
            watchCount = 0;
            watchDirty = false;
            return;
        }
        boolean recheck = watchDirty || (!feedBound && now - lastPollTick >= POLL_TICKS);
        int from = Math.max(0, steer.progressIndex() + 1);
        int len = steer.length();
        int n = 0;
        for (int i = from; i < len && n < WATCH_CELLS; i++) {
            long c = steer.cell(i);
            int base = -1;
            for (int k = 0; k < watchCount; k++) {
                if (watchCell[k] == c) {
                    base = watchBits[k];
                    break;
                }
            }
            if (base < 0) {
                base = probe.cellBits(c);
            } else if (recheck) {
                int cur = probe.cellBits(c);
                if (((base & CellProbe.CLEAR) != 0 && (cur & CellProbe.CLEAR) == 0)
                        || ((base & CellProbe.FLOOR) != 0 && (cur & CellProbe.FLOOR) == 0)) {
                    obstructed = true;
                }
                base = cur;
            }
            nextCell[n] = c;
            nextBits[n] = base;
            n++;
        }
        long[] tc = watchCell;
        watchCell = nextCell;
        nextCell = tc;
        int[] tb = watchBits;
        watchBits = nextBits;
        nextBits = tb;
        watchCount = n;
        if (recheck) {
            watchDirty = false;
            lastPollTick = now;
        }
    }

    private void clearWatch() {
        watchCount = 0;
        watchDirty = false;
        obstructed = false;
    }

    /** Obstruction replans so far (tests, notes). */
    public int obstructionReplans() {
        return obstructionReplans;
    }

    private boolean applyKeys() {
        int f = steer.forward();
        int s = steer.strafe();
        return setKey(SimKey.FORWARD, f > 0) && setKey(SimKey.BACK, f < 0)
                && setKey(SimKey.LEFT, s > 0) && setKey(SimKey.RIGHT, s < 0)
                && setKey(SimKey.SPRINT, steer.sprint()) && setKey(SimKey.JUMP, steer.jump());
    }

    private boolean setKey(SimKey key, boolean down) {
        int i = key.ordinal();
        if (held[i] == down) {
            return true;
        }
        if (!input.setKeyHeld(lease, key, down)) {
            return false;
        }
        held[i] = down;
        return true;
    }

    private void applyLook() {
        float want = steer.wantYaw();
        if (lookSent) {
            float d = (want - lastYaw) % 360f;
            if (d > 180f) {
                d -= 360f;
            } else if (d < -180f) {
                d += 360f;
            }
            if (Math.abs(d) <= YAW_RESEND_DEG) {
                return;
            }
        }
        // allocation: one record per sent target, only when the wanted yaw moved (see class doc)
        if (input.setRotationTarget(lease, RotationTarget.visible(want, 0f))) {
            lastYaw = want;
            lookSent = true;
        } else {
            look = false; // CAMERA not usable for this run: keys only
        }
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
