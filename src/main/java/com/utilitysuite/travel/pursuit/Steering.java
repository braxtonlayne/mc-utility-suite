package com.utilitysuite.travel.pursuit;

/** What the run core needs from a route follower (shaped like {@code travel.movement.RouteFollower}). */
public interface Steering {

    void setRoute(long[] packed, int count);

    void clear();

    boolean hasRoute();

    /** One decision for this tick, for the body the matching {@link BodySource} last refreshed. */
    void update(float yaw, float health);

    /** +1 forward, -1 back, 0 none. */
    int forward();

    /** +1 left, -1 right, 0 none. */
    int strafe();

    boolean jump();

    boolean sprint();

    float wantYaw();

    boolean finished();

    boolean offRoute();
}
