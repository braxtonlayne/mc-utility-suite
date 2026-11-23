package com.utilitysuite.core.api;

/**
 * A camera target in degrees. {@code speed} scales the configured base turn speed (1.0 = default).
 * <p>
 * {@code tracking} marks the target as one sample of a continuously moving aim point (e.g. a strafing entity) that the
 * caller re-sends every tick. The smoother then follows it with a velocity-continuous pursuit controller instead of a
 * chain of eased start-stop curves. Only VISIBLE targets track; a SILENT target ignores the flag. Default false.
 *
 * @param yaw      Minecraft yaw (any range; the smoother wraps it)
 * @param pitch    -90..90 (clamped)
 * @param tracking follow a moving target continuously (see above)
 */
public record RotationTarget(float yaw, float pitch, RotationMode mode, float speed, boolean tracking) {

    public RotationTarget {
        pitch = Math.max(-90f, Math.min(90f, pitch));
        if (mode == null) {
            mode = RotationMode.VISIBLE;
        }
        if (!(speed > 0f)) {
            speed = 1f;
        }
    }

    /** A plain (non-tracking) target. */
    public RotationTarget(float yaw, float pitch, RotationMode mode, float speed) {
        this(yaw, pitch, mode, speed, false);
    }

    public static RotationTarget visible(float yaw, float pitch) {
        return new RotationTarget(yaw, pitch, RotationMode.VISIBLE, 1f);
    }

    public static RotationTarget silent(float yaw, float pitch) {
        return new RotationTarget(yaw, pitch, RotationMode.SILENT, 1f);
    }

    /** One sample of a moving VISIBLE aim point, followed continuously (send a new sample every tick). */
    public static RotationTarget tracking(float yaw, float pitch, float speed) {
        return new RotationTarget(yaw, pitch, RotationMode.VISIBLE, speed, true);
    }
}
