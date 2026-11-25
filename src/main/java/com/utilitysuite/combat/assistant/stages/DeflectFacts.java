package com.utilitysuite.combat.assistant.stages;

/**
 * The nearest deflectable projectile (ghast fireball, wind charge) not thrown by us. The Minecraft implementation
 * ({@code combat.assistant.mc.McDeflectFacts}) scans around the local player once per {@link #refresh}; tests use a
 * fake. Client thread only, allocation free.
 */
public interface DeflectFacts {

    void refresh();

    /** A candidate was found by the last {@link #refresh}; the getters below describe it. */
    boolean found();

    double x();

    double y();

    double z();

    /** Velocity, blocks per tick. */
    double vx();

    double vy();

    double vz();
}
