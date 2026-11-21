package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlLease;

/**
 * Sneak and sprint states that outgoing packets should report instead of the real ones (packet layer only: the local
 * player keeps moving as it really is). Written by {@link InputSmoother#setServerPose}, read by
 * {@code LocalPlayerServerPoseMixin} inside {@code LocalPlayer.sendPosition()}. Each override belongs to a lease and
 * lapses the moment that lease ends, even if its owner never clears it. Client thread only; plain static fields so the
 * per-tick packet path costs nothing.
 */
public final class ServerPose {

    private static ControlLease owner;
    /** 0 = follow the real state, 1 = report on, 2 = report off. */
    private static byte sneak;
    private static byte sprint;

    private ServerPose() {
    }

    /** The sneak state to send, given the real one. */
    public static boolean sneak(boolean real) {
        return apply(sneak, real);
    }

    /** The sprint state to send, given the real one. */
    public static boolean sprint(boolean real) {
        return apply(sprint, real);
    }

    private static boolean apply(byte mode, boolean real) {
        if (mode == 0 || owner == null || !owner.isActive()) {
            return real;
        }
        return mode == 1;
    }

    static void set(ControlLease lease, Boolean sneakOverride, Boolean sprintOverride) {
        owner = lease;
        sneak = code(sneakOverride);
        sprint = code(sprintOverride);
    }

    static ControlLease owner() {
        return owner;
    }

    static void clear() {
        owner = null;
        sneak = 0;
        sprint = 0;
    }

    private static byte code(Boolean b) {
        return b == null ? 0 : b ? (byte) 1 : (byte) 2;
    }
}
