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

    /** Index of the route cell the body has reached (-1: none; then nothing ahead is watched). */
    default int progressIndex() {
        return -1;
    }

    /** Cells in the route. */
    default int length() {
        return 0;
    }

    /** Packed feet cell {@code i} of the route. */
    default long cell(int i) {
        return 0L;
    }
}
