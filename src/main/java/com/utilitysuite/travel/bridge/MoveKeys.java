package com.utilitysuite.travel.bridge;

import com.utilitysuite.travel.Angles;

/**
 * Chooses movement keys that move the player toward a world direction given where the (real) camera faces, engine-free.
 * Movement keys are camera-relative, so with a SILENT rotation (the camera stays where the user put it) the bridge
 * still walks along its line. Result is a bit mask of {@link #FORWARD}, {@link #BACK}, {@link #LEFT}, {@link #RIGHT};
 * the direction is snapped to the nearest of the 8 key combinations.
 */
public final class MoveKeys {

    public static final int FORWARD = 1;
    public static final int BACK = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;

    /** Indexed by octant of (desired - camera yaw): 0 = ahead, 2 = +90 deg (right), 4 = behind, 6 = -90 deg (left). */
    private static final int[] OCTANTS = {
            FORWARD, FORWARD | RIGHT, RIGHT, BACK | RIGHT, BACK, BACK | LEFT, LEFT, FORWARD | LEFT
    };

    private MoveKeys() {
    }

    /**
     * @param cameraYawDeg the player's real yaw
     * @param moveYawDeg   the yaw of the direction to move in
     */
    public static int forDirection(double cameraYawDeg, double moveYawDeg) {
        double rel = Angles.delta(cameraYawDeg, moveYawDeg); // positive = clockwise = to the right
        int octant = (int) Math.floorMod(Math.round(rel / 45.0), 8L);
        return OCTANTS[octant];
    }
}
