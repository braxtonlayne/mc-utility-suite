package com.utilitysuite.core.input;

/**
 * Yaw co-steer: a bounded yaw step toward a module's ideal heading, ADDED to the user's own mouse motion per rendered
 * frame (see {@code docs/design/parkour-control-model-2026-11-22.md} section 6.2). Fed through
 * {@code SharedGrant.coSteer} (one request per tick), applied by {@link ManualInputHooks#onTurnYaw} in the existing
 * {@code MouseHandler.turnPlayer} hook, which runs every frame the mouse is grabbed, still mouse included.
 *
 * <h2>Follower ({@link #onFrame})</h2>
 * The follower keeps an angular speed {@code v} (deg/s) and per frame:
 * <ol>
 *   <li>error {@code e = wrapDegrees(ideal - (yaw + handOut))}: what is left after the hand's own (gained) motion of
 *       this frame, so a hand already turning toward the ideal leaves the follower less to add;</li>
 *   <li>target speed {@code sign(e) * min(rate, omega r, sqrt(2 a r))} with {@code r = min(|e|, allowance left)}:
 *       the {@code omega r} term is the critically damped tail (exponential settle, no oscillation), the square-root
 *       term brakes at the acceleration limit so it stops on the ideal (or at the end of the allowance);
 *       {@code rate = min(maxDegPerSec, 450) * authority};</li>
 *   <li>{@code v} moves toward the target by at most {@code a * dt} (no step in angular speed; ease in and out);
 *       {@code a = ACCEL * authority}, {@code omega = OMEGA * authority};</li>
 *   <li>step {@code v * dt}, clamped to never pass the ideal (and to zero, with {@code v} reset, when the hand already
 *       carried the camera past it), to the allowance left of {@code maxTotalDeg}, and hard-capped at
 *       {@link RotationLimits#MAX_DEG_PER_SEC} x frame time whatever is asked (frame time clamped to 0.1 s).</li>
 * </ol>
 * Hand motion whose sign opposes the follower's direction is summed into {@link #opposingDeg} (raw hand degrees,
 * before any gain) for the module's override detection. The hand's motion itself is never changed here.
 * <p>
 * The request lapses when {@link #request} was not called during the previous tick ({@link #beginTick}); a lapsed or
 * blocked frame adds exactly 0 and stops the follower. Pitch never comes here. Pure math, engine free, allocation
 * free, client thread only.
 */
public final class YawCoSteer {

    /** Acceleration limit at authority 1 (deg/s^2): 0 to 450 deg/s in 0.3 s. */
    public static final double ACCEL_DEG_PER_S2 = 1500.0;
    /** Critically damped settling rate at authority 1 (1/s): the speed near the ideal is about omega x error. */
    public static final double OMEGA_PER_SEC = 8.0;
    /** Below this error the follower is on the ideal (degrees). */
    public static final double ON_TARGET_DEG = 0.01;

    private boolean live;
    private boolean refreshed;
    private float ideal;
    private double rate;
    private double maxTotal;
    private double authority;

    private double velocity;
    private double totalAdded;
    private double opposing;
    private double addedThisTick;
    private double addedLastTick;

    /** One tick's request (see {@code SharedGrant.coSteer}); the caller has already checked grant, wantsYaw, level. */
    public void request(float idealYawDeg, double maxDegPerSec, double maxTotalDeg, double authority) {
        if (!Float.isFinite(idealYawDeg) || !(maxDegPerSec > 0.0) || !(maxTotalDeg > 0.0) || !(authority > 0.0)) {
            return; // NaN, zero or negative: nothing to do this tick
        }
        this.ideal = idealYawDeg;
        this.rate = Math.min(maxDegPerSec, RotationLimits.MAX_DEG_PER_SEC);
        this.maxTotal = maxTotalDeg;
        this.authority = Math.min(1.0, authority);
        this.live = true;
        this.refreshed = true;
    }

    /** ClientTickEvent.Pre: a request not renewed during the previous tick lapses. */
    public void beginTick() {
        addedLastTick = addedThisTick;
        addedThisTick = 0.0;
        if (live && !refreshed) {
            live = false;
            velocity = 0.0;
        }
        refreshed = false;
    }

    /** New or ended grant: request, speed, allowance and the opposing sum start over. */
    public void reset() {
        live = false;
        refreshed = false;
        velocity = 0.0;
        totalAdded = 0.0;
        opposing = 0.0;
    }

    /** A live request this tick (the frame hook's early-out). */
    public boolean isLive() {
        return live;
    }

    /** Degrees of hand motion that opposed the follower since the last {@link #reset}. */
    public double opposingDeg() {
        return opposing;
    }

    /** Absolute degrees added since the last {@link #reset}. */
    public double totalAddedDeg() {
        return totalAdded;
    }

    /** Absolute degrees added during the last completed tick (read-out). */
    public double addedDegLastTick() {
        return addedLastTick;
    }

    /** Current follower speed (deg/s, signed), for tests and read-outs. */
    public double velocityDegPerSec() {
        return velocity;
    }

    /**
     * One rendered frame. Returns the degrees to ADD to the hand's yaw this frame (0 when not live or blocked).
     *
     * @param currentYaw   camera yaw before this frame's turn (degrees)
     * @param rawHandDeg   the hand's own yaw this frame, before turn gain (degrees, signed)
     * @param handOutDeg   the hand's yaw after turn gain (what the camera turns by without the co-steer)
     * @param frameSeconds time since the previous mouse frame (seconds)
     * @param blocked      screen open, automated look running, player riding or spectating
     */
    public double onFrame(float currentYaw, double rawHandDeg, double handOutDeg, double frameSeconds,
                          boolean blocked) {
        if (!live) {
            return 0.0;
        }
        if (blocked || !Double.isFinite(frameSeconds) || frameSeconds <= 0.0 || !Double.isFinite(rawHandDeg)
                || !Double.isFinite(handOutDeg)) {
            velocity = 0.0;
            return 0.0;
        }
        double dt = Math.min(frameSeconds, ManualLookAssist.MAX_FRAME_SECONDS);
        double err = RotationCurve.wrapDegrees((double) ideal - currentYaw - handOutDeg);
        double dir = velocity != 0.0 ? Math.signum(velocity) : Math.signum(err);
        if (rawHandDeg != 0.0 && dir != 0.0 && Math.signum(rawHandDeg) != dir) {
            opposing += Math.abs(rawHandDeg);
        }
        double absErr = Math.abs(err);
        double left = maxTotal - totalAdded;
        double accel = ACCEL_DEG_PER_S2 * authority;
        if (left <= 0.0) {
            velocity = 0.0; // the braking below already brought it to rest at the end of the allowance
            return 0.0;
        }
        if (absErr <= ON_TARGET_DEG) {
            // on the ideal: the follower's speed winds down at the acceleration limit, nothing more is added
            double maxDv = accel * dt;
            velocity = Math.abs(velocity) <= maxDv ? 0.0 : velocity - Math.signum(velocity) * maxDv;
            return 0.0;
        }
        double reach = Math.min(absErr, left); // brake for the end of the allowance as for the ideal
        // discrete-time braking speed: slowing by accel*dt per frame from here stops within `reach`
        double brake = accel * (Math.sqrt(dt * dt * 0.25 + 2.0 * reach / accel) - dt * 0.5);
        double targetSpeed = Math.min(rate * authority, Math.min(OMEGA_PER_SEC * authority * reach, brake));
        double target = err > 0.0 ? targetSpeed : -targetSpeed;
        double dv = target - velocity;
        double maxDv = accel * dt;
        velocity += dv > maxDv ? maxDv : (dv < -maxDv ? -maxDv : dv);
        double step = velocity * dt;
        if ((step > 0.0) != (err > 0.0)) {
            velocity = 0.0; // the hand carried the camera past the ideal: never push back across it
            return 0.0;
        }
        double abs = Math.abs(step);
        double cap = RotationLimits.MAX_DEG_PER_SEC * dt;
        if (abs > absErr) {
            abs = absErr;
        }
        if (abs > left) {
            abs = left;
        }
        if (abs > cap) {
            abs = cap;
        }
        totalAdded += abs;
        addedThisTick += abs;
        return err > 0.0 ? abs : -abs;
    }
}
