package com.utilitysuite.travel.pursuit;

/** The player's body for the run core. */
public interface BodySource {

    /** Reads the body for this tick; false when there is no player (the run is reset). */
    boolean refresh();

    /** True when a player exists (no refresh). */
    boolean available();

    double x();

    double y();

    double z();

    float yaw();

    float health();
}
