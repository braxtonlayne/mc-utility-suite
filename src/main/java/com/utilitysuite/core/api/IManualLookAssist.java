package com.utilitysuite.core.api;

/**
 * Turn gain on the user's own mouse: scales the yaw of the player's hardware mouse motion a little, toward an ideal
 * heading. It never injects rotation: with a still mouse the camera does not move, and every degree turned comes from
 * the user's hand. It needs no {@link ControlLease} (the camera stays the user's) and reports no manual override.
 * <p>
 * Limits enforced by the core, whatever a module asks for: gains are clamped to
 * [{@link #MIN_GAIN}, {@link #MAX_GAIN}]; the degrees the gain adds or removes never exceed
 * {@link IInputSmoother#MAX_TURN_DEG_PER_SEC}; a request lapses by itself after its ttl; nothing applies while a
 * screen is open, while an automated rotation is running, or after Stop-All. Pitch is touched only through the opt-in {@link #setPitchGain}.
 * Client thread only.
 */
public interface IManualLookAssist {

    double MIN_GAIN = 0.5;
    double MAX_GAIN = 1.5;

    /** Does nothing and reports a still mouse. */
    IManualLookAssist NONE = new IManualLookAssist() {
        @Override
        public void setYawGain(String moduleId, float idealYawDeg, double towardGain, double awayGain, int ttlTicks) {
        }

        @Override
        public void clearYawGain(String moduleId) {
        }

        @Override
        public double manualYawRateDegPerTick() {
            return 0.0;
        }

        @Override
        public double assistedYawDegLastTick() {
            return 0.0;
        }
    };

    /**
     * For the next {@code ttlTicks} ticks, mouse yaw motion that turns toward {@code idealYawDeg} is multiplied by
     * {@code towardGain} and motion that turns away from it by {@code awayGain} (1.0 = untouched). The gain fades to
     * 1.0 as the camera reaches the ideal yaw, so it cannot overshoot. Call every tick while it should apply; one
     * request per module, the latest call wins. Allocation free.
     */
    void setYawGain(String moduleId, float idealYawDeg, double towardGain, double awayGain, int ttlTicks);

    void clearYawGain(String moduleId);

    /**
     * Opt-in pitch counterpart of {@link #setYawGain}: same math and limits on the user's own mouse pitch motion
     * (+ = looking down), pulled toward {@code idealPitchDeg}. A NaN or infinite ideal pitch means off (clears this
     * module's request). A still mouse never moves the camera. Default: ignored.
     */
    default void setPitchGain(String moduleId, float idealPitchDeg, double towardGain, double awayGain, int ttlTicks) {
    }

    default void clearPitchGain(String moduleId) {
    }

    /**
     * The user's own yaw rate over the last tick in degrees per tick (+ = yaw increasing), from hardware mouse motion
     * only and including any gain applied. For extrapolating where the user will be looking next tick.
     */
    double manualYawRateDegPerTick();

    /** Absolute degrees of yaw the gain added or removed during the last tick (for authorship accounting). */
    double assistedYawDegLastTick();
}
