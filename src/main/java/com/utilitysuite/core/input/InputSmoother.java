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
    private float requestedYaw;
    private float requestedPitch;

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
        if (mode == RotationMode.SILENT && silentActive) {
            fromYaw = SilentRotation.yaw();
            fromPitch = SilentRotation.pitch();
        } else {
            fromYaw = backend.yaw();
            fromPitch = backend.pitch();
        }
        if (mode == RotationMode.VISIBLE && silentActive) {
            silentActive = false;
            SilentRotation.clear();
        }
        curve.start(fromYaw, fromPitch, target.yaw(), target.pitch(), target.speed());
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
        if (silentActive) {
            silentActive = false;
            SilentRotation.clear();
        }
    }

    @Override
    public boolean isRotationSettled(float toleranceDeg) {
        return !hasTarget || curve.remainingError() <= toleranceDeg;
    }

    /** True while packets carry silent angles (the rendered camera belongs to the user). */
    public boolean isSilentRotationActive() {
        return silentActive;
    }

    /** True while a VISIBLE rotation is still moving the camera. */
    public boolean isVisibleRotationMoving() {
        return hasTarget && rotationMode == RotationMode.VISIBLE && curve.isActive();
    }

    /** Per rendered frame: advances a VISIBLE rotation and writes it to the player. */
    public void onFrame(double dtSeconds) {
        if (!hasTarget || rotationMode != RotationMode.VISIBLE || !curve.isActive()) {
            return;
        }
        if (rotationOwner == null || !rotationOwner.isActive()) {
            clearRotationInternal();
            return;
        }
        if (!backend.isReady()) {
            return;
        }
        curve.advance(Math.min(Math.max(dtSeconds, 0.0), 0.1));
        backend.setRotation(curve.yaw(), curve.pitch());
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

    // ---- cleanup ----------------------------------------------------------------------------------------------------

    @Override
    public void releaseFor(ControlLease lease) {
        if (lease == null) {
            return;
        }
        for (int k = 0; k < KEYS.length; k++) {
            if (managed[k] && keyOwner[k] == lease) {
                unmanage(k);
            }
        }
        if (rotationOwner == lease) {
            clearRotationInternal();
        }
        cancel(lease);
    }

    @Override
    public void releaseAll() {
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
        clearClicks();
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    /** ClientTickEvent.Pre: tap expiry, stale-lease cleanup and re-asserting held keys. */
    public void preTick() {
        tick++;
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
                SilentRotation.set(curve.yaw(), curve.pitch());
            }
        }
    }

    /** Current smoother tick counter (advanced by {@link #preTick()}). */
    public long tickCount() {
        return tick;
    }
}
