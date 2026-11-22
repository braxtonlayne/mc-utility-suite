package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;

import java.util.random.RandomGenerator;

/**
 * The only path through which automation changes keys, camera and hotbar, plus queued container clicks. All game
 * access goes through an {@link InputBackend}, so this class is unit-testable. Client thread only.
 *
 * <h2>Lifecycle per client tick</h2>
 * <ol>
 *   <li>{@link #preTick()} (ClientTickEvent.Pre): ends expired taps, drops keys whose lease is no longer active and
 *       re-asserts held keys before vanilla reads them.</li>
 *   <li>Modules run (ClientTickEvent.Post) and call the API.</li>
 *   <li>{@link #postTick()}: runs at most one due container click and advances a SILENT rotation (packets are sent
 *       once per tick, so silent motion advances per tick).</li>
 *   <li>{@link #onFrame(double)} (every rendered frame): advances a VISIBLE rotation and writes the player's yaw/pitch,
 *       so camera motion is smooth at any frame rate.</li>
 * </ol>
 * Steady-state paths allocate nothing: key state is kept in arrays indexed by {@link SimKey#ordinal()} and clicks in a
 * fixed ring buffer.
 */
public final class InputSmoother implements IInputSmoother, IContainerClicker {

    /** Retarget the guard's smoothed turn only when the wanted look moved more than this (degrees). */
    static final float GUARD_DEAD_BAND = 0.3f;
    private boolean guardOn;
    private boolean guardRecorded;
    private float guardYaw;
    private float guardPitch;
    private float guardTargetYaw = Float.NaN;
    private float guardTargetPitch;
    private int foreignTurns;
    private float foreignHeading = Float.NaN;
    private long foreignHeadingTick = Long.MIN_VALUE / 2;
    private java.util.function.Supplier<ControlLease> cameraHolderLookup;

    private static final SimKey[] KEYS = SimKey.all();
    private static final double TICK_SECONDS = 0.05;
    /** A retarget closer than this to the running target bends the running curve instead of restarting it. */
    private static final double RETARGET_BLEND_DEG = 15.0;
    private static final int CLICK_CAPACITY = 64;
    private static final Click[] CLICKS = Click.values();

    private final InputBackend backend;
    private final RandomGenerator random;
    private final RotationCurve curve;

    // ---- keys -------------------------------------------------------------------------------------------------------
    private final ControlLease[] keyOwner = new ControlLease[KEYS.length];
    private final boolean[] managed = new boolean[KEYS.length];
    private final boolean[] held = new boolean[KEYS.length];
    /** Tick counter value after which a tap releases; 0 = no tap running. */
    private final long[] tapUntil = new long[KEYS.length];
    private long tick = 1;

    // ---- rotation ---------------------------------------------------------------------------------------------------
    private ControlLease rotationOwner;
    private RotationMode rotationMode = RotationMode.VISIBLE;
    private boolean hasTarget;
    private boolean silentActive;
    /** A SILENT rotation ended: the server-side angle eases back to the camera at the capped speed, then clears. */
    private boolean silentReturning;
    /** The no-snap limiter is behind the curve: keep stepping after the curve itself has finished. */
    private boolean lagging;
    private final float[] limited = new float[2];
    private float requestedYaw;
    private float requestedPitch;

    // ---- humanization and momentum release --------------------------------------------------------------------------
    /** Target width Fitts's law assumes for a plain look target (degrees, about a mob at mid range). */
    static final double DEFAULT_TARGET_WIDTH_DEG = 3.0;
    private Humanizer humanizer;
    private final MomentumRelease momentum = new MomentumRelease();
    private boolean momentumEnabled = true;
    private ManualLookAssist manualLook;
    /** Set while the arbiter ends leases for a manual mouse look, so the lease-end path can hand over momentum. */
    private boolean manualLookPreempting;
    private int userYawSign;
    private int userPitchSign;
    /** Angular velocity of the last VISIBLE frame the smoother wrote (degrees per second). */
    private double lastVelYaw;
    private double lastVelPitch;

    // ---- container clicks (ring buffer) -----------------------------------------------------------------------------
    private final ControlLease[] clickLease = new ControlLease[CLICK_CAPACITY];
    private final int[] clickSlot = new int[CLICK_CAPACITY];
    private final int[] clickButton = new int[CLICK_CAPACITY];
    private final byte[] clickType = new byte[CLICK_CAPACITY];
    private final long[] clickDue = new long[CLICK_CAPACITY];
    private int clickHead;
    private int clickSize;
    private long lastClickDue;
    private double clickMeanMs = 140.0;
    private double clickStdMs = 40.0;
    private double clickMinMs = 70.0;
    private double clickMaxMs = 320.0;

    public InputSmoother(InputBackend backend, RandomGenerator random) {
        this.backend = backend;
        this.random = random;
        this.curve = new RotationCurve(random);
    }

    /** The curve, for configuration (speed, overshoot, drift). */
    public RotationCurve curve() {
        return curve;
    }

    /**
     * Human turn timing (Fitts's law) for non-emergency turns that do not ask for a speed of their own; null = off.
     * Humanized turns only get slower than the configured base speed, never faster, and stay under the no-snap cap.
     */
    public void setHumanizer(Humanizer humanizer) {
        this.humanizer = humanizer;
    }

    public Humanizer humanizer() {
        return humanizer;
    }

    /** Camera momentum release when manual mouse input preempts an automated turn (default on). */
    public void setMomentumRelease(boolean on) {
        momentumEnabled = on;
        if (!on) {
            momentum.cancel();
        }
    }

    public boolean isMomentumReleaseOn() {
        return momentumEnabled;
    }

    /** True while released camera momentum is still decaying. */
    public boolean isMomentumActive() {
        return momentum.isActive();
    }

    /**
     * The user's raw mouse motion this frame (from the look mixin). Only the signs are kept: momentum never pushes
     * against the direction the user moves.
     */
    public void noteManualMouse(double dx, double dy) {
        userYawSign = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        userPitchSign = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
    }

    /** Brackets the arbiter call for a manual mouse look, so ending the camera lease releases with momentum. */
    public void setManualLookPreempting(boolean on) {
        manualLookPreempting = on;
    }

    /** Main-motion time a humanized turn takes, or 0 when the base speed decides (off, emergency, explicit speed). */
    private double humanTurnSeconds(ControlLease lease, RotationTarget target, float fromYaw, float fromPitch) {
        Humanizer h = humanizer;
        if (h == null || !h.isEnabled() || target.speed() != 1f
                || lease.priority() == com.utilitysuite.core.api.ControlPriority.EMERGENCY) {
            return 0.0;
        }
        double dist = RotationCurve.turnDistance(fromYaw, fromPitch, target.yaw(), target.pitch());
        return h.turnSeconds(dist, DEFAULT_TARGET_WIDTH_DEG);
    }

    /** Gaussian spacing of queued container clicks, milliseconds. */
    public void setClickTiming(double meanMs, double stdMs, double minMs, double maxMs) {
        this.clickMeanMs = meanMs;
        this.clickStdMs = stdMs;
        this.clickMinMs = Math.min(minMs, maxMs);
        this.clickMaxMs = Math.max(minMs, maxMs);
    }

    private static boolean valid(ControlLease lease, ControlChannel channel) {
        return lease != null && lease.isActive() && lease.covers(channel);
    }

    // ---- camera -----------------------------------------------------------------------------------------------------

    @Override
    public boolean setRotationTarget(ControlLease lease, RotationTarget target) {
        if (target == null || !valid(lease, ControlChannel.CAMERA) || !backend.isReady()) {
            return false;
        }
        RotationMode mode = target.mode();
        boolean sameStream = hasTarget && rotationOwner == lease && rotationMode == mode;
        if (sameStream) {
            double dYaw = RotationCurve.wrapDegrees((double) target.yaw() - requestedYaw);
            double dPitch = (double) target.pitch() - requestedPitch;
            if (Math.abs(dYaw) < 0.01 && Math.abs(dPitch) < 0.01) {
                return true; // unchanged target: keep the running motion
            }
            if (curve.isActive() && Math.abs(dYaw) < RETARGET_BLEND_DEG && Math.abs(dPitch) < RETARGET_BLEND_DEG) {
                curve.retarget(target.yaw(), target.pitch());
                requestedYaw = target.yaw();
                requestedPitch = target.pitch();
                return true;
            }
        }
        float fromYaw;
        float fromPitch;
        if (mode == RotationMode.SILENT && (silentActive || silentReturning)) {
            // continue from the angle the server last saw, never from the camera (that would be a server-side snap)
            fromYaw = SilentRotation.yaw();
            fromPitch = SilentRotation.pitch();
            silentReturning = false;
        } else {
            fromYaw = backend.yaw();
            fromPitch = backend.pitch();
        }
        if (mode == RotationMode.VISIBLE && silentActive) {
            silentActive = false;
            silentReturning = true; // the server angle eases back to the camera instead of jumping
        }
        lagging = false;
        momentum.cancel(); // automation owns the camera again
        curve.start(fromYaw, fromPitch, target.yaw(), target.pitch(), target.speed(),
                humanTurnSeconds(lease, target, fromYaw, fromPitch));
        rotationOwner = lease;
        rotationMode = mode;
        hasTarget = true;
        requestedYaw = target.yaw();
        requestedPitch = target.pitch();
        if (mode == RotationMode.SILENT) {
            silentActive = true;
            SilentRotation.set(curve.yaw(), curve.pitch());
        }
        return true;
    }

    @Override
    public void clearRotation(ControlLease lease) {
        if (lease != null && lease == rotationOwner) {
            clearRotationInternal();
        }
    }

    private void clearRotationInternal() {
        hasTarget = false;
        rotationOwner = null;
        lagging = false;
        if (silentActive) {
            silentActive = false;
            silentReturning = true; // eased back in postTick: no server-side snap on release or Stop-All
        }
    }

    @Override
    public boolean isRotationSettled(float toleranceDeg) {
        return !hasTarget || (!lagging && curve.remainingError() <= toleranceDeg);
    }

    /** True while the server-side angle is easing back to the camera after a SILENT rotation. */
    public boolean isSilentReturning() {
        return silentReturning;
    }

    /** True while packets carry silent angles (the rendered camera belongs to the user). */
    public boolean isSilentRotationActive() {
        return silentActive;
    }

    /** True while a VISIBLE rotation is still moving the camera. */
    public boolean isVisibleRotationMoving() {
        return hasTarget && rotationMode == RotationMode.VISIBLE && (curve.isActive() || lagging);
    }

    /**
     * True while anything automated drives a look angle: a VISIBLE rotation is moving, a SILENT rotation is active or
     * released camera momentum is still decaying. The turn gain stays off then ({@link ManualLookAssist}).
     */
    boolean isAutomatedLookRunning() {
        return silentActive || momentum.isActive() || isVisibleRotationMoving();
    }

    /** Turn gain to end on Stop-All and world unload (both run {@link #releaseAll}). */
    public void setManualLookAssist(ManualLookAssist assist) {
        this.manualLook = assist;
    }

    /** Per rendered frame: advances a VISIBLE rotation and writes it to the player. */
    public void onFrame(double dtSeconds) {
        if (momentum.isActive()) {
            advanceMomentum(dtSeconds);
        }
        if (!hasTarget || rotationMode != RotationMode.VISIBLE || (!curve.isActive() && !lagging)) {
            lastVelYaw = 0.0;
            lastVelPitch = 0.0;
            return;
        }
        if (rotationOwner == null || !rotationOwner.isActive()) {
            clearRotationInternal();
            return;
        }
        if (!backend.isReady()) {
            return;
        }
        double dt = Math.min(Math.max(dtSeconds, 0.0), 0.1);
        curve.advance(dt);
        // no-snap rule: whatever the curve asks for, the camera moves at most the hard cap from where it is now
        // (measured from the live camera, so the user's own mouse is never limited or fought)
        float fromYaw = backend.yaw();
        float fromPitch = backend.pitch();
        lagging = !RotationLimits.step(fromYaw, fromPitch, curve.yaw(), curve.pitch(),
                RotationLimits.MAX_DEG_PER_SEC * dt, limited);
        backend.setRotation(limited[0], limited[1]);
        if (dt > 0.0) {
            lastVelYaw = ((double) limited[0] - fromYaw) / dt;
            lastVelPitch = ((double) limited[1] - fromPitch) / dt;
        }
    }

    /** Adds one frame of decaying released momentum on top of whatever the user's mouse did. */
    private void advanceMomentum(double dtSeconds) {
        if (!backend.isReady()) {
            momentum.cancel();
            return;
        }
        double dt = Math.min(Math.max(dtSeconds, 0.0), 0.1);
        int ys = userYawSign;
        int ps = userPitchSign;
        userYawSign = 0;
        userPitchSign = 0;
        if (momentum.advance(dt, ys, ps)) {
            float pitch = (float) (backend.pitch() + momentum.pitchStep());
            pitch = pitch < -90f ? -90f : (pitch > 90f ? 90f : pitch);
            backend.setRotation((float) (backend.yaw() + momentum.yawStep()), pitch);
        }
    }

    // ---- keys -------------------------------------------------------------------------------------------------------

    @Override
    public boolean setKeyHeld(ControlLease lease, SimKey key, boolean down) {
        if (key == null || !valid(lease, key.channel())) {
            return false;
        }
        int k = key.ordinal();
        if (down) {
            boolean wasManaged = managed[k];
            held[k] = true;
            manage(k, lease);
            if (!wasManaged && clicksMatter(key)) {
                backend.clickKey(key);
            }
        } else if (managed[k]) {
            held[k] = false;
            keyOwner[k] = lease;
            if (tapUntil[k] == 0) {
                unmanage(k);
            }
        }
        return true;
    }

    @Override
    public boolean scheduleKeyTap(ControlLease lease, SimKey key, int minTicks, int maxTicks) {
        if (key == null || !valid(lease, key.channel())) {
            return false;
        }
        int lo = Math.max(1, Math.min(minTicks, maxTicks));
        int hi = Math.max(lo, Math.max(minTicks, maxTicks));
        int duration = lo == hi ? lo : lo + random.nextInt(hi - lo + 1);
        int k = key.ordinal();
        long until = tick + duration;
        if (until > tapUntil[k]) {
            tapUntil[k] = until;
        }
        manage(k, lease);
        if (clicksMatter(key)) {
            backend.clickKey(key);
        }
        return true;
    }

    @Override
    public boolean isKeyHeldByAutomation(SimKey key) {
        return managed[key.ordinal()];
    }

    private static boolean clicksMatter(SimKey key) {
        return key == SimKey.ATTACK || key == SimKey.USE;
    }

    private void manage(int k, ControlLease lease) {
        keyOwner[k] = lease;
        if (!managed[k]) {
            managed[k] = true;
            backend.pressKey(KEYS[k]);
        }
    }

    private void unmanage(int k) {
        boolean was = managed[k];
        managed[k] = false;
        held[k] = false;
        tapUntil[k] = 0;
        keyOwner[k] = null;
        if (was) {
            backend.releaseKey(KEYS[k]);
        }
    }

    // ---- hotbar -----------------------------------------------------------------------------------------------------

    @Override
    public boolean selectHotbarSlot(ControlLease lease, int slot) {
        if (slot < 0 || slot > 8 || !valid(lease, ControlChannel.HOTBAR) || !backend.isReady()) {
            return false;
        }
        backend.selectHotbarSlot(slot);
        return true;
    }

    // ---- container clicks -------------------------------------------------------------------------------------------

    @Override
    public boolean queueClick(ControlLease lease, int slotIndex, int button, Click click) {
        if (click == null || !valid(lease, ControlChannel.HOTBAR) || clickSize >= CLICK_CAPACITY) {
            return false;
        }
        int gap = msToTicks(nextGaussianDelayMs(clickMeanMs, clickStdMs, clickMinMs, clickMaxMs));
        long base = clickSize == 0 ? Math.max(tick, lastClickDue) : lastClickDue;
        long due = base + gap;
        int idx = (clickHead + clickSize) % CLICK_CAPACITY;
        clickLease[idx] = lease;
        clickSlot[idx] = slotIndex;
        clickButton[idx] = button;
        clickType[idx] = (byte) click.ordinal();
        clickDue[idx] = due;
        clickSize++;
        lastClickDue = due;
        return true;
    }

    @Override
    public boolean placeRecipe(ControlLease lease, String recipeId, boolean craftAll) {
        if (recipeId == null || !valid(lease, ControlChannel.HOTBAR) || !backend.isReady()) {
            return false;
        }
        return backend.placeRecipe(recipeId, craftAll);
    }

    @Override
    public int pending() {
        return clickSize;
    }

    @Override
    public void cancel(ControlLease lease) {
        if (clickSize == 0) {
            return;
        }
        int write = 0;
        for (int read = 0; read < clickSize; read++) {
            int from = (clickHead + read) % CLICK_CAPACITY;
            if (clickLease[from] == lease) {
                continue;
            }
            int to = (clickHead + write) % CLICK_CAPACITY;
            if (to != from) {
                clickLease[to] = clickLease[from];
                clickSlot[to] = clickSlot[from];
                clickButton[to] = clickButton[from];
                clickType[to] = clickType[from];
                clickDue[to] = clickDue[from];
            }
            write++;
        }
        for (int i = write; i < clickSize; i++) {
            clickLease[(clickHead + i) % CLICK_CAPACITY] = null;
        }
        clickSize = write;
    }

    private void clearClicks() {
        for (int i = 0; i < CLICK_CAPACITY; i++) {
            clickLease[i] = null;
        }
        clickHead = 0;
        clickSize = 0;
    }

    private static int msToTicks(double ms) {
        return Math.max(1, (int) Math.round(ms / 50.0));
    }

    // ---- timing -----------------------------------------------------------------------------------------------------

    @Override
    public double nextGaussianDelayMs(double mean, double stdDev, double min, double max) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        double v = stdDev > 0 ? mean + stdDev * random.nextGaussian() : mean;
        if (Double.isNaN(v)) {
            v = (lo + hi) * 0.5;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public int nextGaussianTicks(double meanTicks, double stdDevTicks, int minTicks, int maxTicks) {
        int lo = Math.min(minTicks, maxTicks);
        int hi = Math.max(minTicks, maxTicks);
        double v = nextGaussianDelayMs(meanTicks, stdDevTicks, lo, hi);
        long r = Math.round(v);
        return (int) (r < lo ? lo : (r > hi ? hi : r));
    }

    // ---- server-visible pose ------------------------------------------------------------------------------------------

    @Override
    public boolean setServerPose(ControlLease lease, Boolean sneak, Boolean sprint) {
        if (!valid(lease, ControlChannel.CAMERA)) {
            return false;
        }
        if (sneak == null && sprint == null) {
            if (ServerPose.owner() == lease) {
                ServerPose.clear();
            }
            return true;
        }
        ServerPose.set(lease, sneak, sprint);
        return true;
    }

    // ---- cleanup ----------------------------------------------------------------------------------------------------

    @Override
    public void releaseFor(ControlLease lease) {
        if (lease == null) {
            return;
        }
        if (ServerPose.owner() == lease) {
            ServerPose.clear();
        }
        for (int k = 0; k < KEYS.length; k++) {
            if (managed[k] && keyOwner[k] == lease) {
                unmanage(k);
            }
        }
        if (rotationOwner == lease) {
            boolean handOver = manualLookPreempting && momentumEnabled && rotationMode == RotationMode.VISIBLE
                    && (curve.isActive() || lagging);
            clearRotationInternal();
            if (handOver) {
                momentum.start(lastVelYaw, lastVelPitch, backend.invertMouseY()); // A10: ease out instead of stopping dead
            }
        }
        cancel(lease);
    }

    @Override
    public void releaseAll() {
        ServerPose.clear();
        for (int k = 0; k < KEYS.length; k++) {
            if (managed[k]) {
                unmanage(k);
            } else {
                keyOwner[k] = null;
                held[k] = false;
                tapUntil[k] = 0;
            }
        }
        clearRotationInternal();
        momentum.cancel(); // Stop-All and world unload stop the camera at once
        if (manualLook != null) {
            manualLook.clearAll(); // turn gain requests end with every other automation
        }
        lastVelYaw = 0.0;
        lastVelPitch = 0.0;
        clearClicks();
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    // ---- foreign rotation guard ------------------------------------------------------------------------------------

    @Override
    public void setForeignRotationGuard(boolean on) {
        guardOn = on;
        if (!on) {
            guardRecorded = false;
        }
    }

    /** Who holds CAMERA (asked only when a foreign turn needs a smoothed target). */
    public void setCameraHolderLookup(java.util.function.Supplier<ControlLease> lookup) {
        cameraHolderLookup = lookup;
    }

    /**
     * Right before the movement packet ({@code LocalPlayer.sendPosition} HEAD, the last point code outside the core
     * can have turned the player this tick; a path library turns there): undoes a rotation made since
     * {@link #preTick()} and steers toward it through the smoother for the CAMERA holder instead. Nothing sends or
     * renders the snapped angles.
     */
    public void captureForeignRotation() {
        if (!guardOn || !guardRecorded || !backend.isReady()) {
            return;
        }
        guardRecorded = false;
        float y = backend.yaw();
        float p = backend.pitch();
        if (Math.abs(wrapDeg(y - guardYaw)) < 0.01f && Math.abs(p - guardPitch) < 0.01f) {
            return;
        }
        backend.setRotation(guardYaw, guardPitch);
        foreignTurns++;
        foreignHeading = y;
        foreignHeadingTick = tick;
        boolean moved = Float.isNaN(guardTargetYaw) || Math.abs(wrapDeg(y - guardTargetYaw)) > GUARD_DEAD_BAND
                || Math.abs(p - guardTargetPitch) > GUARD_DEAD_BAND || !hasTarget;
        if (moved && cameraHolderLookup != null) {
            ControlLease holder = cameraHolderLookup.get();
            if (holder != null && holder.isActive() && holder.covers(ControlChannel.CAMERA)
                    && setRotationTarget(holder, RotationTarget.visible(y, p))) {
                guardTargetYaw = y;
                guardTargetPitch = p;
            }
        }
    }

    /**
     * The heading foreign code last wanted (captured this tick or the one before), or NaN: its movement keys were meant
     * along it while the camera still turns toward it.
     */
    public float foreignHeading() {
        return guardOn && tick - foreignHeadingTick <= 2 ? foreignHeading : Float.NaN;
    }

    /** Rotations undone by the foreign rotation guard (diagnostics). */
    public boolean isForeignRotationGuardOn() {
        return guardOn;
    }

    public int foreignTurns() {
        return foreignTurns;
    }

    private static float wrapDeg(float d) {
        d %= 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** ClientTickEvent.Pre: tap expiry, stale-lease cleanup and re-asserting held keys. */
    public void preTick() {
        tick++;
        if (guardOn && backend.isReady()) {
            guardYaw = backend.yaw();
            guardPitch = backend.pitch();
            guardRecorded = true;
        }
        for (int k = 0; k < KEYS.length; k++) {
            if (!managed[k]) {
                continue;
            }
            if (tapUntil[k] != 0 && tick > tapUntil[k]) {
                tapUntil[k] = 0;
            }
            ControlLease owner = keyOwner[k];
            if (owner == null || !owner.isActive() || (!held[k] && tapUntil[k] == 0)) {
                unmanage(k);
            } else {
                backend.holdKey(KEYS[k]);
            }
        }
        if (hasTarget && (rotationOwner == null || !rotationOwner.isActive())) {
            clearRotationInternal();
        }
    }

    /** ClientTickEvent.Post: one due container click, SILENT rotation advance. */
    public void postTick() {
        if (clickSize > 0 && clickDue[clickHead] <= tick) {
            int i = clickHead;
            ControlLease lease = clickLease[i];
            clickLease[i] = null;
            clickHead = (clickHead + 1) % CLICK_CAPACITY;
            clickSize--;
            if (valid(lease, ControlChannel.HOTBAR) && backend.isReady()) {
                backend.clickContainerSlot(clickSlot[i], clickButton[i], CLICKS[clickType[i]]);
            }
        }
        if (hasTarget && rotationMode == RotationMode.SILENT) {
            if (rotationOwner == null || !rotationOwner.isActive()) {
                clearRotationInternal();
            } else {
                curve.advance(TICK_SECONDS);
                // no-snap rule for the server-visible head: at most the per-tick cap between two packets
                lagging = !RotationLimits.step(SilentRotation.yaw(), SilentRotation.pitch(), curve.yaw(),
                        curve.pitch(), RotationLimits.MAX_DEG_PER_TICK, limited);
                SilentRotation.set(limited[0], limited[1]);
            }
        }
        if (silentReturning) {
            if (!backend.isReady()) {
                silentReturning = false; // no world, no server watching: nothing to ease
                SilentRotation.clear();
            } else if (RotationLimits.step(SilentRotation.yaw(), SilentRotation.pitch(), backend.yaw(),
                    backend.pitch(), RotationLimits.MAX_DEG_PER_TICK, limited)) {
                silentReturning = false;
                SilentRotation.clear(); // packets carry the camera angle again; it is where the server head now is
            } else {
                SilentRotation.set(limited[0], limited[1]);
            }
        }
    }

    /** Current smoother tick counter (advanced by {@link #preTick()}). */
    public long tickCount() {
        return tick;
    }
}
