package com.utilitysuite.travel;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;

/**
 * Per-tick camera steering through the smoother with a dead band: a new {@link RotationTarget} (a record, so one small
 * allocation) is only sent when the wanted angles moved more than the dead band or the mode changed. The smoother
 * bends its running curve on small retargets, so this keeps motion smooth while avoiding a target per tick.
 */
public final class CameraAim {

    private final float deadBandDeg;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private RotationMode lastMode;
    private long lastLeaseId = -1;

    public CameraAim(float deadBandDeg) {
        this.deadBandDeg = deadBandDeg;
    }

    /** Steers toward (yaw, pitch). Returns false when the smoother refused (lease inactive / no CAMERA). */
    public boolean aim(IInputSmoother input, ControlLease lease, double yaw, double pitch, RotationMode mode,
                       float speed) {
        float y = (float) yaw;
        float p = (float) pitch;
        if (lease.id() == lastLeaseId && mode == lastMode
                && Math.abs(Angles.delta(lastYaw, y)) <= deadBandDeg && Math.abs(lastPitch - p) <= deadBandDeg) {
            return true;
        }
        if (!input.setRotationTarget(lease, new RotationTarget(y, p, mode, speed))) {
            return false;
        }
        lastYaw = y;
        lastPitch = p;
        lastMode = mode;
        lastLeaseId = lease.id();
        return true;
    }

    /** Forget the last target (after the lease changed or rotation was cleared). */
    public void reset() {
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        lastMode = null;
        lastLeaseId = -1;
    }
}
