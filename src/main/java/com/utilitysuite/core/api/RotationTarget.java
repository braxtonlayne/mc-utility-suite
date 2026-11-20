package com.utilitysuite.core.api;

/**
 * A camera target in degrees. {@code speed} scales the configured base turn speed (1.0 = default).
 *
 * @param yaw   Minecraft yaw (any range; the smoother wraps it)
 * @param pitch -90..90 (clamped)
 */
public record RotationTarget(float yaw, float pitch, RotationMode mode, float speed) {

    public RotationTarget {
        pitch = Math.max(-90f, Math.min(90f, pitch));
        if (mode == null) {
            mode = RotationMode.VISIBLE;
        }
        if (!(speed > 0f)) {
            speed = 1f;
        }
    }

    public static RotationTarget visible(float yaw, float pitch) {
        return new RotationTarget(yaw, pitch, RotationMode.VISIBLE, 1f);
    }

    public static RotationTarget silent(float yaw, float pitch) {
        return new RotationTarget(yaw, pitch, RotationMode.SILENT, 1f);
    }
}
