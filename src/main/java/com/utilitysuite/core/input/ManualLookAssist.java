package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IManualLookAssist;

/**
 * Turn gain on the user's own mouse ("turn gain, not turn injection"): scales the YAW part of the hardware mouse
 * motion a little toward a module's ideal heading. Nothing here writes a rotation: the mouse hook asks
 * {@link #onFrameYaw} for the size of the yaw delta the user's hand produced this frame and gets back a slightly larger
 * or smaller delta of the same sign. A still mouse produces a zero delta, and zero in is zero out, so the camera never
 * moves by itself. Pitch never passes through this class.
 *
 * <h2>Math ({@link #scale})</h2>
 * <ol>
 *   <li>Error {@code e = wrapDegrees(ideal - yaw)}. Inside the dead zone ({@link #DEAD_ZONE_DEG}) the gain is 1.</li>
 *   <li>Motion with the sign of {@code e} turns toward the ideal and uses the toward gain, otherwise the away gain;
 *       both are clamped to [{@link IManualLookAssist#MIN_GAIN}, {@link IManualLookAssist#MAX_GAIN}].</li>
 *   <li>The gain fades linearly to 1.0 as {@code |e|} shrinks from {@link #FADE_DEG} to the dead zone.</li>
 *   <li>No overshoot: amplified toward motion stops at the ideal yaw (the extra degrees are limited to
 *       {@code |e| - |raw|}); motion that already reaches or crosses the ideal on its own is not amplified at all.</li>
 *   <li>The degrees added or removed never exceed {@link RotationLimits#MAX_DEG_PER_SEC} x frame time (frame time
 *       clamped to [0, 0.1] s, so the cap only ever gets stricter).</li>
 * </ol>
 * Together these give, for every input: {@code sign(out) == sign(raw)}, {@code |out - raw| <= cap} and
 * {@code 0.5|raw| <= |out| <= 1.5|raw|}. NaN or infinite inputs pass the raw delta through.
 *
 * <h2>Requests</h2>
 * One request per module id in a small fixed table (no allocation per call); the most recently set live request
 * applies. A request lapses when its ttl reaches 0 ({@link #beginTick} counts down once per client tick).
 * {@link #clearAll} runs on Stop-All and world unload (through {@link InputSmoother#releaseAll}).
 * <p>
 * Client thread only.
 */
public final class ManualLookAssist implements IManualLookAssist {

    /** Around the ideal yaw the gain is exactly 1 (degrees). */
    public static final double DEAD_ZONE_DEG = 0.5;
    /** The gain reaches its full value only this far (degrees) from the ideal yaw; closer it fades toward 1. */
    public static final double FADE_DEG = 10.0;
    /** Longest frame time the cap is computed from (seconds): a hitch never widens what the gain may add. */
    public static final double MAX_FRAME_SECONDS = 0.1;
    /** Mouse turn units per degree: {@code Entity.turn} multiplies its arguments by 0.15. */
    public static final double DEG_PER_TURN_UNIT = 0.15;

    static final int CAPACITY = 8;

    private final String[] ids = new String[CAPACITY];
    private final float[] ideal = new float[CAPACITY];
    private final double[] toward = new double[CAPACITY];
    private final double[] away = new double[CAPACITY];
    private final int[] ttl = new int[CAPACITY];
    private final long[] setSeq = new long[CAPACITY];
    private long seq;
    /** Slot of the most recently set live request, or -1. */
    private int active = -1;

    private InputSmoother smoother;

    // per-tick read-outs: running sums of the current tick, values of the last completed tick
    private double tickYawDeg;
    private double tickAssistDeg;
    private double lastYawDeg;
    private double lastAssistDeg;

    /** Automated-look state comes from this smoother (null in unit tests: never blocked by it). */
    public void setSmoother(InputSmoother smoother) {
        this.smoother = smoother;
    }

    // ---- contract ---------------------------------------------------------------------------------------------------

    @Override
    public void setYawGain(String moduleId, float idealYawDeg, double towardGain, double awayGain, int ttlTicks) {
        if (moduleId == null || !Float.isFinite(idealYawDeg)) {
            return;
        }
        if (ttlTicks <= 0) {
            clearYawGain(moduleId);
            return;
        }
        int slot = find(moduleId);
        if (slot < 0) {
            slot = freeOrOldest();
        }
        ids[slot] = moduleId;
        ideal[slot] = idealYawDeg;
        toward[slot] = clampGain(towardGain);
        away[slot] = clampGain(awayGain);
        ttl[slot] = ttlTicks;
        setSeq[slot] = ++seq;
        active = slot;
    }

    @Override
    public void clearYawGain(String moduleId) {
        if (moduleId == null) {
            return;
        }
        int slot = find(moduleId);
        if (slot >= 0) {
            ids[slot] = null;
            ttl[slot] = 0;
            if (slot == active) {
                active = latestLive();
            }
        }
    }

    @Override
    public double manualYawRateDegPerTick() {
        return lastYawDeg;
    }

    @Override
    public double assistedYawDegLastTick() {
        return lastAssistDeg;
    }

    /** Stop-All and world unload: every request ends at once. The read-outs are reset too. */
    public void clearAll() {
        for (int i = 0; i < CAPACITY; i++) {
            ids[i] = null;
            ttl[i] = 0;
        }
        active = -1;
        tickYawDeg = 0.0;
        tickAssistDeg = 0.0;
        lastYawDeg = 0.0;
        lastAssistDeg = 0.0;
    }

    /** True while a request is live. */
    public boolean hasLiveRequest() {
        return active >= 0;
    }

    // ---- per tick / per frame ---------------------------------------------------------------------------------------

    /** ClientTickEvent.Pre: rolls the read-outs over and counts every request's ttl down (lapsing at 0). */
    public void beginTick() {
        lastYawDeg = tickYawDeg;
        lastAssistDeg = tickAssistDeg;
        tickYawDeg = 0.0;
        tickAssistDeg = 0.0;
        boolean changed = false;
        for (int i = 0; i < CAPACITY; i++) {
            if (ids[i] != null && --ttl[i] <= 0) {
                ids[i] = null;
                ttl[i] = 0;
                changed = true;
            }
        }
        if (changed) {
            active = latestLive();
        }
    }

    /**
     * Per mouse frame: the yaw delta (degrees) the user's hand produced and the frame time; returns the delta to
     * apply. {@code blocked} = an outside condition (screen, player state) forbids the gain; the smoother's automated
     * look state is checked here. Records the read-outs. Allocation free; the no-request path is a few operations.
     */
    public double onFrameYaw(float currentYaw, double rawDeg, double frameSeconds, boolean blocked) {
        int a = active;
        double out = rawDeg;
        if (a >= 0 && rawDeg != 0.0 && !blocked && !automatedLookRunning()) {
            out = scale(currentYaw, rawDeg, frameSeconds, ideal[a], toward[a], away[a]);
        }
        if (Double.isFinite(out)) {
            tickYawDeg += out;
            tickAssistDeg += Math.abs(out - rawDeg);
        }
        return out;
    }

    /** The scaled delta for the current live request (no bookkeeping, no gating). Raw when no request is live. */
    public double scaleYawDelta(float currentYaw, double rawDeg, double frameSeconds) {
        int a = active;
        return a < 0 ? rawDeg : scale(currentYaw, rawDeg, frameSeconds, ideal[a], toward[a], away[a]);
    }

    private boolean automatedLookRunning() {
        InputSmoother s = smoother;
        return s != null && s.isAutomatedLookRunning();
    }

    // ---- pure math --------------------------------------------------------------------------------------------------

    /**
     * Scales one frame's raw yaw delta. Pure and engine free; see the class javadoc for the rules and guarantees.
     *
     * @param yaw          current camera yaw (degrees, any range)
     * @param rawDeg       the yaw delta the user's mouse produced this frame (degrees, signed)
     * @param frameSeconds time since the previous mouse frame (seconds)
     * @param idealYaw     the heading the gain pulls toward (degrees, any range)
     * @param towardGain   gain for motion toward the ideal (clamped to [0.5, 1.5])
     * @param awayGain     gain for motion away from it (clamped to [0.5, 1.5])
     */
    public static double scale(double yaw, double rawDeg, double frameSeconds, double idealYaw, double towardGain,
                               double awayGain) {
        if (rawDeg == 0.0 || !Double.isFinite(rawDeg) || !Double.isFinite(yaw) || !Double.isFinite(idealYaw)
                || !Double.isFinite(frameSeconds) || frameSeconds <= 0.0) {
            return rawDeg;
        }
        double err = RotationCurve.wrapDegrees(idealYaw - yaw);
        double absErr = Math.abs(err);
        if (absErr <= DEAD_ZONE_DEG) {
            return rawDeg;
        }
        boolean towardIdeal = (err > 0.0) == (rawDeg > 0.0);
        double gain = clampGain(towardIdeal ? towardGain : awayGain);
        double fade = Math.min(1.0, (absErr - DEAD_ZONE_DEG) / (FADE_DEG - DEAD_ZONE_DEG));
        gain = 1.0 + (gain - 1.0) * fade;
        double absRaw = Math.abs(rawDeg);
        double change = absRaw * (gain - 1.0); // degrees added (+) or removed (-) along the motion
        if (towardIdeal && change > 0.0) {
            change = Math.min(change, Math.max(0.0, absErr - absRaw)); // stop at the ideal yaw, never past it
        }
        double cap = RotationLimits.MAX_DEG_PER_SEC * Math.min(frameSeconds, MAX_FRAME_SECONDS);
        if (change > cap) {
            change = cap;
        } else if (change < -cap) {
            change = -cap;
        }
        return rawDeg > 0.0 ? absRaw + change : -(absRaw + change);
    }

    static double clampGain(double g) {
        if (Double.isNaN(g)) {
            return 1.0;
        }
        return g < MIN_GAIN ? MIN_GAIN : (g > MAX_GAIN ? MAX_GAIN : g);
    }

    // ---- table ------------------------------------------------------------------------------------------------------

    private int find(String moduleId) {
        for (int i = 0; i < CAPACITY; i++) {
            String id = ids[i];
            if (id != null && (id == moduleId || id.equals(moduleId))) {
                return i;
            }
        }
        return -1;
    }

    private int freeOrOldest() {
        int oldest = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (ids[i] == null) {
                return i;
            }
            if (setSeq[i] < setSeq[oldest]) {
                oldest = i;
            }
        }
        return oldest;
    }

    private int latestLive() {
        int best = -1;
        for (int i = 0; i < CAPACITY; i++) {
            if (ids[i] != null && (best < 0 || setSeq[i] > setSeq[best])) {
                best = i;
            }
        }
        return best;
    }
}
