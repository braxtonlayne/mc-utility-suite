package com.utilitysuite.core.api;

/**
 * Source of {@link BodySim} scratch bodies that collide with the live client level. The provider refreshes its own
 * per-tick block cache; outside a world every block reads as air. Registered by the movement module.
 */
public interface IBodySimProvider {

    /** A new scratch body. Allocates: call once at construction time, never per tick. */
    BodySim newBody();
}
