package com.utilitysuite.core.api;

/**
 * Human-like timing and aim variation, owned by the core so every module draws from one per-session skill value and
 * one primitive random stream. Nothing here allocates. It never makes a turn faster than
 * {@link IInputSmoother#MAX_TURN_DEG_PER_SEC}; it only stretches and varies. Client thread only.
 */
public interface IHumanizer {

    /** Per-session skill in [0, 1]: 0 = slow and loose, 1 = quick and tight. Fixed for the session. */
    double skill();

    /**
     * Reaction delay in ticks for a NON-emergency action, log-normal around {@code medianTicks}, clamped to
     * [minTicks, maxTicks]. Emergency reflexes do not use it.
     */
    int reactionTicks(int medianTicks, int minTicks, int maxTicks);

    /**
     * Time a turn of {@code angleDeg} onto a target {@code targetWidthDeg} wide should take (Fitts law), in seconds;
     * never less than {@code angleDeg / MAX_TURN_DEG_PER_SEC}.
     */
    double turnSeconds(double angleDeg, double targetWidthDeg);

    /**
     * An aim point inside a hitbox, as offsets from its center: {@code out[0]} sideways in [-halfWidth, halfWidth],
     * {@code out[1]} vertical in [-halfHeight, halfHeight], denser near the center. {@code out} is caller owned.
     */
    void aimOffset(double halfWidth, double halfHeight, double[] out);

    /** Next uniform double in [0, 1) from the shared primitive generator. */
    double nextUnit();

    /**
     * Asks for a skill value in [0, 1] in place of the per-session one (the combat rank dial: one notch above the
     * current opponent). One request at a time, the latest call wins; a fixed skill the user set in the settings
     * always wins over a request. Call {@link #clearSkillRequest} when the reason ends (disengage, Stop-All).
     * Allocation free; cheap enough to call every tick.
     */
    default void requestSkill(String moduleId, double skill) {
    }

    /** Drops that module's request; no-op when another module holds it. */
    default void clearSkillRequest(String moduleId) {
    }

    /** Neutral fallback used before the core registers one: no delay spread, capped-speed turns, center aim. */
    IHumanizer NEUTRAL = new IHumanizer() {
        @Override
        public double skill() {
            return 1.0;
        }

        @Override
        public int reactionTicks(int medianTicks, int minTicks, int maxTicks) {
            return Math.max(minTicks, Math.min(maxTicks, medianTicks));
        }

        @Override
        public double turnSeconds(double angleDeg, double targetWidthDeg) {
            return Math.abs(angleDeg) / IInputSmoother.MAX_TURN_DEG_PER_SEC;
        }

        @Override
        public void aimOffset(double halfWidth, double halfHeight, double[] out) {
            out[0] = 0;
            out[1] = 0;
        }

        @Override
        public double nextUnit() {
            return 0.5;
        }
    };
}
