package com.utilitysuite.core.api;

/**
 * The autopilot's picture of what is about to hurt the player: one slot per {@link HazardKind}, each with a time to
 * impact and an expected damage. Sources (fall planner, threat tracker, hazard scanner, combat snapshot, health, air,
 * fire) report only when they have something to say, and reports expire on their own. Reflex modules read it, and
 * ask {@link IAutopilot#mayAct} before acting. Fixed arrays underneath: every call here is allocation free.
 * Client thread only.
 */
public interface IDangerModel {

    /** Bearing value meaning "no direction" (a fall, drowning, low health). Test with {@code Float.isNaN}. */
    float NO_BEARING = Float.NaN;

    /**
     * Reports or refreshes a hazard. A nearer report (fewer ticks) of the same kind replaces a farther one.
     *
     * @param ticksToImpact  client ticks until it hurts (0 = now)
     * @param expectedDamage expected damage in health points (half hearts) after armor, best estimate
     * @param bearingYawDeg  world yaw toward the source, or {@link #NO_BEARING}
     */
    void report(HazardKind kind, int ticksToImpact, float expectedDamage, float bearingYawDeg);

    /** The source says this hazard is gone (landed safely, the arrow missed). */
    void clear(HazardKind kind);

    /** True when at least one hazard is live. The cheap early-out for reflex ticks. */
    boolean any();

    boolean has(HazardKind kind);

    /** Ticks until impact, or {@code Integer.MAX_VALUE} when the kind is not live. */
    int ticksToImpact(HazardKind kind);

    /** Expected damage, or 0 when the kind is not live. */
    float expectedDamage(HazardKind kind);

    /** World yaw toward the source, or {@link #NO_BEARING}. */
    float bearingYawDeg(HazardKind kind);

    /** The live hazard with the most expected damage per tick left, or null when none is live. */
    HazardKind worst();

    /** A model that never holds anything; returned while the autopilot is absent. */
    IDangerModel NONE = new IDangerModel() {
        @Override
        public void report(HazardKind kind, int ticksToImpact, float expectedDamage, float bearingYawDeg) {
        }

        @Override
        public void clear(HazardKind kind) {
        }

        @Override
        public boolean any() {
            return false;
        }

        @Override
        public boolean has(HazardKind kind) {
            return false;
        }

        @Override
        public int ticksToImpact(HazardKind kind) {
            return Integer.MAX_VALUE;
        }

        @Override
        public float expectedDamage(HazardKind kind) {
            return 0f;
        }

        @Override
        public float bearingYawDeg(HazardKind kind) {
            return NO_BEARING;
        }

        @Override
        public HazardKind worst() {
            return null;
        }
    };
}
