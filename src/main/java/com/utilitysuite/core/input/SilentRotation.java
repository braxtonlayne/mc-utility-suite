package com.utilitysuite.core.input;

/**
 * Angles that outgoing movement packets should carry while a SILENT rotation is active. Written by
 * {@link InputSmoother}, read by {@code LocalPlayerSilentRotationMixin} around {@code LocalPlayer.sendPosition()}.
 * Client thread only; plain static fields so the per-tick packet path costs nothing.
 */
public final class SilentRotation {

    private static boolean active;
    private static float yaw;
    private static float pitch;

    private SilentRotation() {
    }

    public static boolean isActive() {
        return active;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    static void set(float newYaw, float newPitch) {
        yaw = newYaw;
        pitch = newPitch;
        active = true;
    }

    static void clear() {
        active = false;
    }
}
