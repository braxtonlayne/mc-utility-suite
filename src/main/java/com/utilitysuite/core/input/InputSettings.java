package com.utilitysuite.core.input;

/**
 * Keys of the input core's settings in {@code global.json} (under the {@code core} object, next to the camera speed).
 * Read by {@code CoreBootstrap.applyCoreSettings}.
 */
public final class InputSettings {

    /** Humanized turn timing, reaction spread and aim spread (default on). */
    public static final String KEY_HUMANIZATION = "humanization";
    /** Fixed skill in [0, 1]; any value outside (default -1) uses the per-session skill. */
    public static final String KEY_SKILL_OVERRIDE = "humanization_skill";
    /** Camera eases out with decaying momentum when the mouse takes over (default on). */
    public static final String KEY_MOMENTUM_RELEASE = "camera_momentum_release";

    private InputSettings() {
    }
}
