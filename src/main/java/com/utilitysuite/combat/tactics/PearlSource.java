package com.utilitysuite.combat.tactics;

/**
 * Ender pearls in flight, by thrower. The Minecraft implementation ({@code combat.assistant.mc.PearlWatch}) tracks
 * them from entity join/leave events (no level scans); tests use a stub. Client thread only, allocation free.
 */
public interface PearlSource {

    /**
     * The newest pearl in flight thrown by the entity: position in {@code out[0..2]}, velocity (blocks per tick) in
     * {@code out[3..5]}.
     *
     * @return false when that entity has no pearl in flight
     */
    boolean pearlOf(int throwerEntityId, double[] out);

    PearlSource NONE = (throwerEntityId, out) -> false;
}
