package com.utilitysuite.core.api;

/**
 * Source of {@link BodySim} scratch bodies that collide with the live client level. The provider refreshes its own
 * per-tick block cache; outside a world every block reads as air. Registered by the movement module.
 */
public interface IBodySimProvider {

    /** A new scratch body. Allocates: call once at construction time, never per tick. */
    BodySim newBody();

    /**
     * Switches every body of this provider between the full-cube world view (false, the default) and the exact-shape
     * view Movement Assist plans on (slabs, stairs and fence tops at their real heights, step-up, slipperiness). Takes
     * effect on the next tick of any body; also used by the pursuit runner. Providers without an exact view ignore it.
     */
    default void useExactShapes(boolean on) {
    }

    /** Whether bodies currently read the exact-shape view. */
    default boolean exactShapes() {
        return false;
    }
}
