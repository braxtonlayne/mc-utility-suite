package com.utilitysuite.combat.coach;

/**
 * Incoming projectile / blast danger around the local player. The Minecraft implementation tracks candidate entities
 * from join/leave events (no scanning); tests use a stub. Client thread only, allocation free.
 */
public interface ThreatSource {

    int NONE = 0;
    int PROJECTILE = 1;
    int BLAST = 2;

    /** Re-evaluates the tracked candidates against the player's current position. */
    void evaluate();

    /** {@link #NONE}, {@link #PROJECTILE} or {@link #BLAST} (the most severe current danger). */
    int level();

    /** Direction of that danger relative to the player's view, degrees (0 = ahead, positive = to the right). */
    float relativeYawDeg();

    ThreatSource NONE_SOURCE = new ThreatSource() {
        @Override
        public void evaluate() {
        }

        @Override
        public int level() {
            return NONE;
        }

        @Override
        public float relativeYawDeg() {
            return 0f;
        }
    };
}
