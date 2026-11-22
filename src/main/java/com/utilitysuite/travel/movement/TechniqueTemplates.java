package com.utilitysuite.travel.movement;

/**
 * The candidate families of {@link TechniqueLibrary} (design 2026-11-22, section 8): corner, headhitter, neo and
 * 45-degree. Each family is a small mixed-radix grid of integer parameters; {@link #decode} turns a cursor index into
 * one {@link Candidate} without allocating, so the library can walk the grid a few candidates per tick and resume
 * from a cursor on the next call.
 * <p>
 * Every candidate holds forward for its whole length (letting go of W always stops a technique) and expresses the
 * view only as goals: the takeoff yaw and one yaw goal per air segment, reached at the level's co-steer rate. With
 * {@code yawAllowed} false every family offers keys-only variants at the user's own yaw.
 * <p>
 * Takeoff ticks are relative to the plain edge tick {@code k} (the last supported tick of a straight run-up from
 * now), so a cursor stays meaningful while the body advances along the approach.
 */
final class TechniqueTemplates {

    static final int CORNER = 0;
    static final int HEADHITTER = 1;
    static final int NEO = 2;
    static final int FORTY_FIVE = 3;
    static final int COUNT = 4;

    static final String NAME_CORNER = "corner";
    static final String NAME_HEADHITTER = "headhitter";
    static final String NAME_NEO = "neo";
    static final String NAME_FORTY_FIVE = "forty-five";

    /** Most air segments of a candidate. */
    static final int MAX_SEGMENTS = 3;

    /** Takeoff tick offsets from the plain edge tick, most likely first (+1 = vanilla's late jump off the edge). */
    private static final int[] EDGE_OFFSETS = {0, -1, 1, -2, -3, -4};
    /** Headhitter: the plain edge tick and earlier ones (an early jump under a ceiling). */
    private static final int HEADHITTER_OFFSETS = 8;
    /** Corner: air ticks of outward keys before the inward keys. */
    private static final int[] CORNER_OUT_TICKS = {1, 2, 3, 5};
    /** Corner: air ticks of inward keys before the back key brakes to the touchdown (0 = no brake). */
    private static final int[] CORNER_BRAKES = {0, 2, 4};
    /** Corner keys-only variants: ground ticks of outward strafe before the takeoff (0..3). */
    private static final int CORNER_KEY_VARIANTS = 4;
    /** Corner yaw variants: takeoff angled away by 10..45 degrees in 5 steps. */
    private static final int CORNER_YAW_VARIANTS = 5;
    static final float CORNER_MIN_ANGLE = 10f;
    static final float CORNER_MAX_ANGLE = 45f;
    /** Neo: ground ticks of strafe-away before the takeoff. */
    private static final int NEO_GROUND_VARIANTS = 6;
    /** Neo: air ticks parallel to the wall before strafing back in (0..5). */
    private static final int NEO_PARALLEL_VARIANTS = 6;
    /** 45-degree: air ticks on the line before the view turns (0..2). */
    private static final int FORTY_FIVE_DELAYS = 3;
    static final float FORTY_FIVE_ANGLE = 45f;

    /**
     * One parametrised script. Ground ticks 0..{@link #jump} hold forward with the user's strafe, except the last
     * {@link #groundStrafeTicks} which hold {@link #groundStrafe}; the view moves toward {@link #takeoffYaw}. Tick
     * {@link #jump} carries the jump. Air segment k then holds forward + {@code segStrafe[k]} for {@code segTicks[k]}
     * ticks (the last segment runs to the touchdown) with the view moving toward {@code segYaw[k]} (NaN = hold).
     */
    static final class Candidate {
        int template;
        /** Index of the takeoff tick from the start of the roll (may go negative after a re-base: already airborne). */
        int jump;
        int groundStrafe;
        int groundStrafeTicks;
        float takeoffYaw;
        int segments;
        final int[] segForward = new int[MAX_SEGMENTS];
        final int[] segStrafe = new int[MAX_SEGMENTS];
        final int[] segTicks = new int[MAX_SEGMENTS];
        final float[] segYaw = new float[MAX_SEGMENTS];

        void copyFrom(Candidate o) {
            template = o.template;
            jump = o.jump;
            groundStrafe = o.groundStrafe;
            groundStrafeTicks = o.groundStrafeTicks;
            takeoffYaw = o.takeoffYaw;
            segments = o.segments;
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                segForward[i] = o.segForward[i];
                segStrafe[i] = o.segStrafe[i];
                segTicks[i] = o.segTicks[i];
                segYaw[i] = o.segYaw[i];
            }
        }

        private void reset(int template, int jump, float yaw) {
            this.template = template;
            this.jump = jump;
            groundStrafe = 0;
            groundStrafeTicks = 0;
            takeoffYaw = yaw;
            segments = 0;
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                segForward[i] = 1;
                segStrafe[i] = 0;
                segTicks[i] = 0;
                segYaw[i] = Float.NaN;
            }
        }

        private void segment(int strafe, int ticks, float yaw) {
            segForward[segments] = 1;
            segStrafe[segments] = strafe;
            segTicks[segments] = ticks;
            segYaw[segments] = yaw;
            segments++;
        }
    }

    static String name(int template) {
        switch (template) {
            case CORNER:
                return NAME_CORNER;
            case HEADHITTER:
                return NAME_HEADHITTER;
            case NEO:
                return NAME_NEO;
            default:
                return NAME_FORTY_FIVE;
        }
    }

    /** Number of candidates of a family (0 = the family is not offered with these permissions). */
    static int size(int template, boolean yawAllowed) {
        int sweeps = yawAllowed ? 2 : 1;
        switch (template) {
            case CORNER:
                return EDGE_OFFSETS.length * 2 * cornerVariants(yawAllowed) * CORNER_OUT_TICKS.length * sweeps
                        * CORNER_BRAKES.length;
            case HEADHITTER:
                return HEADHITTER_OFFSETS * 3 * sweeps;
            case NEO:
                return EDGE_OFFSETS.length * 2 * NEO_GROUND_VARIANTS * NEO_PARALLEL_VARIANTS * sweeps;
            case FORTY_FIVE:
                return yawAllowed ? EDGE_OFFSETS.length * 2 * FORTY_FIVE_DELAYS : 0;
            default:
                return 0;
        }
    }

    private static int cornerVariants(boolean yawAllowed) {
        return CORNER_KEY_VARIANTS + (yawAllowed ? CORNER_YAW_VARIANTS : 0);
    }

    /** Takeoff angle away from the line of the corner yaw variant {@code v} (0..4): 10, 18.75, 27.5, 36.25, 45. */
    static float cornerAngle(int v) {
        return CORNER_MIN_ANGLE + v * ((CORNER_MAX_ANGLE - CORNER_MIN_ANGLE) / (CORNER_YAW_VARIANTS - 1));
    }

    /**
     * Fills {@code out} with candidate {@code index} of a family.
     *
     * @param edgeTick the plain edge tick from now (last supported tick of a straight run-up)
     * @param baseYaw  the user's view yaw now
     * @param aimYaw   the yaw from the body to the target's centre, unwrapped near {@code baseYaw}
     * @return false when this index names no candidate here (takeoff tick before now)
     */
    static boolean decode(int template, int index, boolean yawAllowed, int edgeTick, float baseYaw, float aimYaw,
                          Candidate out) {
        int sweeps = yawAllowed ? 2 : 1;
        switch (template) {
            case CORNER: {
                int brake = CORNER_BRAKES[index % CORNER_BRAKES.length];
                index /= CORNER_BRAKES.length;
                int sweep = index % sweeps;
                index /= sweeps;
                int outTicks = CORNER_OUT_TICKS[index % CORNER_OUT_TICKS.length];
                index /= CORNER_OUT_TICKS.length;
                int variants = cornerVariants(yawAllowed);
                int variant = index % variants;
                index /= variants;
                int side = index % 2 == 0 ? 1 : -1;
                index /= 2;
                int jump = edgeTick + EDGE_OFFSETS[index % EDGE_OFFSETS.length];
                if (jump < 0) {
                    return false;
                }
                if (variant < CORNER_KEY_VARIANTS) {
                    out.reset(CORNER, jump, baseYaw);
                    out.groundStrafe = side;
                    out.groundStrafeTicks = variant;
                } else {
                    // strafe +1 (left) travels toward the yaw - 90 side: angling the view "outward" for side s is -s
                    out.reset(CORNER, jump, baseYaw - side * cornerAngle(variant - CORNER_KEY_VARIANTS));
                }
                float in = sweep == 1 ? aimYaw : Float.NaN;
                out.segment(side, outTicks, Float.NaN);
                if (brake > 0) {
                    // a narrow top behind the corner: inward keys, then the back key to the touchdown (air brake)
                    out.segment(-side, brake, in);
                    out.segment(-side, 0, in);
                    out.segForward[2] = -1;
                } else {
                    out.segment(-side, 0, in);
                }
                return true;
            }
            case HEADHITTER: {
                int yawMode = index % sweeps;
                index /= sweeps;
                int a = index % 3;
                index /= 3;
                int jump = edgeTick - (index % HEADHITTER_OFFSETS);
                if (jump < 0) {
                    return false;
                }
                out.reset(HEADHITTER, jump, yawMode == 1 ? aimYaw : baseYaw);
                out.segment(a == 0 ? 0 : (a == 1 ? 1 : -1), 0, Float.NaN);
                return true;
            }
            case NEO: {
                int sweep = index % sweeps;
                index /= sweeps;
                int parallel = index % NEO_PARALLEL_VARIANTS;
                index /= NEO_PARALLEL_VARIANTS;
                int ground = 1 + index % NEO_GROUND_VARIANTS;
                index /= NEO_GROUND_VARIANTS;
                int side = index % 2 == 0 ? 1 : -1;
                index /= 2;
                int jump = edgeTick + EDGE_OFFSETS[index % EDGE_OFFSETS.length];
                if (jump < 0) {
                    return false;
                }
                out.reset(NEO, jump, baseYaw);
                out.groundStrafe = side;
                out.groundStrafeTicks = ground;
                out.segment(0, parallel, Float.NaN);
                out.segment(-side, 0, sweep == 1 ? aimYaw : Float.NaN);
                return true;
            }
            case FORTY_FIVE: {
                if (!yawAllowed) {
                    return false;
                }
                int delay = index % FORTY_FIVE_DELAYS;
                index /= FORTY_FIVE_DELAYS;
                int side = index % 2 == 0 ? 1 : -1;
                index /= 2;
                int jump = edgeTick + EDGE_OFFSETS[index % EDGE_OFFSETS.length];
                if (jump < 0) {
                    return false;
                }
                // takeoff on the line (the sprint-jump boost goes along the view), then the view 45 degrees off with
                // the matching strafe key: yaw + 45 travels straight with left, yaw - 45 with right
                out.reset(FORTY_FIVE, jump, aimYaw);
                out.segment(0, delay, Float.NaN);
                out.segment(side, 0, aimYaw + side * FORTY_FIVE_ANGLE);
                return true;
            }
            default:
                return false;
        }
    }

    private TechniqueTemplates() {
    }
}
