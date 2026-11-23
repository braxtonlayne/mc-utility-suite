package com.utilitysuite.travel.movement;

/**
 * The candidate families of {@link TechniqueLibrary} (design 2026-11-22, section 8): corner cut, corner, headhitter,
 * neo and 45-degree. Each family is a small mixed-radix grid of integer parameters; {@link #decode} turns a cursor index
 * into one {@link Candidate} without allocating, so the library can walk the grid a few candidates per tick and resume
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
    /** Corner cut: pass an obstacle beside the line on its open side and turn in behind it (target toward a side). */
    static final int CUT = 4;
    static final int COUNT = 5;

    static final String NAME_CORNER = "corner";
    static final String NAME_HEADHITTER = "headhitter";
    static final String NAME_NEO = "neo";
    static final String NAME_FORTY_FIVE = "forty-five";
    static final String NAME_CUT = "corner-cut";

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
    /*
     * Neo with the view (J36 study, 2026-11-23): a pillar standing in the gap cell of a 1-wide strip leaves keys-only
     * play one tick-perfect input (2 of 34k brute-forced sequences land), while turning the view ~20 degrees toward
     * the swing-out side through the run-up and the outward strafe, then back to the line for the inward strafe, lands
     * in hundreds. The variant strafes out from the start of the script with that view, keeps the outward strafe for
     * a few air ticks, coasts, then strafes back in with the view goal on the original line.
     */
    /** Neo with the view: turn toward the swing-out side, most likely first. */
    private static final float[] NEO_TURN_ANGLES = {20f, 15f, 25f};
    /** Neo with the view: air ticks of outward strafe after the takeoff. */
    private static final int[] NEO_TURN_OUT = {2, 1, 3};
    /** Neo with the view: air ticks of forward only before the inward strafe. */
    private static final int[] NEO_TURN_COAST = {1, 0, 2};
    /** 45-degree: air ticks on the line before the view turns (0..2). */
    private static final int FORTY_FIVE_DELAYS = 3;
    static final float FORTY_FIVE_ANGLE = 45f;
    /** Corner cut: takeoff tick offsets (+1 = the late jump, the longest reach past the corner). */
    private static final int[] CUT_OFFSETS = {1, 0, -1};
    /** Corner cut: ground ticks of strafe away from the target side, from the start of the script (room to turn in). */
    private static final int[] CUT_AWAY_TICKS = {0, 2, 4};
    /** Corner cut: air ticks of forward only before the inward strafe key. */
    private static final int[] CUT_STRAIGHT_TICKS = {4, 2, 0, 6};
    /** Corner cut: air ticks of forward + inward strafe before forward is released (0 = held to the touchdown). */
    private static final int[] CUT_RELEASE_TICKS = {0, 4, 2};
    /** Corner cut yaw variants: takeoff turned toward the target by this share of the aim angle. */
    private static final float[] CUT_TAKEOFF_SHARE = {1.0f, 0.5f};
    /** Corner cut yaw variants: the view goal behind the corner, as a turn from the starting view (capped). */
    private static final float[] CUT_TURN_GOALS = {25f, 45f, 70f};
    /** A target less than this off the view line is not a corner cut. */
    static final float CUT_MIN_AIM = 3f;
    /** Default cap on the view change of a script (the brain passes the level's own). */
    static final float DEFAULT_MAX_TURN = 45f;

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
        /** Ground ticks {@code [0, earlyEnd)} from the start of the roll hold {@link #earlyStrafe} (re-based like jump). */
        int earlyEnd;
        int earlyStrafe;
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
            earlyEnd = o.earlyEnd;
            earlyStrafe = o.earlyStrafe;
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
            earlyEnd = 0;
            earlyStrafe = 0;
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
            case CUT:
                return NAME_CUT;
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
                return yawAllowed
                        ? NEO_TURN_OUT.length * NEO_TURN_COAST.length * EDGE_OFFSETS.length * 2 * NEO_TURN_ANGLES.length
                        : EDGE_OFFSETS.length * 2 * NEO_GROUND_VARIANTS * NEO_PARALLEL_VARIANTS;
            case FORTY_FIVE:
                return yawAllowed ? EDGE_OFFSETS.length * 2 * FORTY_FIVE_DELAYS : 0;
            case CUT:
                return CUT_OFFSETS.length * CUT_AWAY_TICKS.length * CUT_STRAIGHT_TICKS.length
                        * CUT_RELEASE_TICKS.length
                        * (yawAllowed ? CUT_TAKEOFF_SHARE.length * CUT_TURN_GOALS.length : 1);
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
        return decode(template, index, yawAllowed, edgeTick, baseYaw, aimYaw, DEFAULT_MAX_TURN, out);
    }

    /**
     * @param maxTurnDeg the most a script may turn the view from {@code baseYaw} (the level's co-steer total)
     */
    static boolean decode(int template, int index, boolean yawAllowed, int edgeTick, float baseYaw, float aimYaw,
                          float maxTurnDeg, Candidate out) {
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
                if (yawAllowed) {
                    int outTicks = NEO_TURN_OUT[index % NEO_TURN_OUT.length];
                    index /= NEO_TURN_OUT.length;
                    int coast = NEO_TURN_COAST[index % NEO_TURN_COAST.length];
                    index /= NEO_TURN_COAST.length;
                    int jump = edgeTick + EDGE_OFFSETS[index % EDGE_OFFSETS.length];
                    index /= EDGE_OFFSETS.length;
                    int side = index % 2 == 0 ? 1 : -1;
                    index /= 2;
                    float angle = Math.min(NEO_TURN_ANGLES[index % NEO_TURN_ANGLES.length], maxTurnDeg);
                    if (jump < 0 || angle <= 0f) {
                        return false;
                    }
                    // strafe s with the view turned by s * angle: the run angles out past the pillar's side
                    out.reset(NEO, jump, baseYaw + side * angle);
                    out.groundStrafe = side;
                    out.groundStrafeTicks = jump + 1; // from the start of the script
                    if (outTicks > 0) {
                        out.segment(side, outTicks, Float.NaN);
                    }
                    if (coast > 0) {
                        out.segment(0, coast, Float.NaN);
                    }
                    out.segment(-side, 0, baseYaw);
                    return true;
                }
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
            case CUT: {
                float aim = aimYaw - baseYaw;
                if (Math.abs(aim) < CUT_MIN_AIM) {
                    return false; // the target is on the line: nothing to cut
                }
                // strafe +1 (left) travels toward the yaw - 90 side, which is where a smaller aim yaw points
                int side = aim < 0f ? 1 : -1;
                float turnSign = aim < 0f ? -1f : 1f;
                int straight = CUT_STRAIGHT_TICKS[index % CUT_STRAIGHT_TICKS.length];
                index /= CUT_STRAIGHT_TICKS.length;
                int release = CUT_RELEASE_TICKS[index % CUT_RELEASE_TICKS.length];
                index /= CUT_RELEASE_TICKS.length;
                int away = CUT_AWAY_TICKS[index % CUT_AWAY_TICKS.length];
                index /= CUT_AWAY_TICKS.length;
                int jump = edgeTick + CUT_OFFSETS[index % CUT_OFFSETS.length];
                index /= CUT_OFFSETS.length;
                if (jump < 0 || away > jump) {
                    return false;
                }
                float takeoff = baseYaw;
                float goal = Float.NaN;
                if (yawAllowed) {
                    float share = CUT_TAKEOFF_SHARE[index % CUT_TAKEOFF_SHARE.length];
                    index /= CUT_TAKEOFF_SHARE.length;
                    float turn = CUT_TURN_GOALS[index % CUT_TURN_GOALS.length];
                    int turnIndex = index % CUT_TURN_GOALS.length;
                    if (turn > maxTurnDeg) {
                        if (turnIndex > 0 && CUT_TURN_GOALS[turnIndex - 1] >= maxTurnDeg) {
                            return false; // the same candidate as the previous goal under this cap
                        }
                        turn = maxTurnDeg;
                    }
                    if (turn <= 0f) {
                        return false;
                    }
                    takeoff = baseYaw + turnSign * Math.min(Math.abs(aim) * share, turn);
                    goal = baseYaw + turnSign * turn;
                }
                out.reset(CUT, jump, takeoff);
                out.earlyEnd = away;
                out.earlyStrafe = -side;
                if (straight > 0) {
                    out.segment(0, straight, Float.NaN);
                }
                if (release > 0) {
                    out.segment(side, release, goal);
                    out.segment(side, 0, goal);
                    out.segForward[out.segments - 1] = 0;
                } else {
                    out.segment(side, 0, goal);
                }
                return true;
            }
            default:
                return false;
        }
    }

    private TechniqueTemplates() {
    }
}
