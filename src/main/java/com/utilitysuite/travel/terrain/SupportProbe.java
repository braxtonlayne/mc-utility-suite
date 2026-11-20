package com.utilitysuite.travel.terrain;

/** Answers "would the player still stand on something if moved by this horizontal offset?". No allocation per call. */
@FunctionalInterface
public interface SupportProbe {

    /** True when ground lies within the configured drop depth below the player's box shifted by (offsetX, offsetZ). */
    boolean hasSupport(double offsetX, double offsetZ);
}
