package com.utilitysuite.combat.tactics;

/**
 * Mace smash planning (engine free, allocation free): the fall a smash damage tier needs, a wind-charge self-launch
 * (look down, jump, throw at the feet) or an elytra dive onto a target, and the tick to swing so the hit lands while the
 * fall is still going and the target is within reach.
 * <p>
 * Vanilla 1.21.1 smash bonus ({@code MaceItem.getAttackDamageBonus}), for a fall distance {@code f > 1.5} while not
 * gliding: {@code 4f} up to 3 blocks, {@code 12 + 2(f-3)} up to 8, then {@code 22 + (f-8)}; Density adds
 * {@code 0.5 * level} per block fallen. Free fall per tick: {@code y += vy; vy = (vy - 0.08) * 0.98}, horizontal speed
 * times {@code 0.91} in air.
 * <p>
 * Fair use only: the mace must already be the held item for at least one full tick before the swing and stays held
 * through the hit tick ({@link #swingAllowed}, {@link #swapAwayAllowed}). No swapping to another weapon mid-hit to apply
 * its enchantments or attributes (breach/attribute swap). The look-down for the launch is only a requested pitch for the
 * core's InputSmoother (smoothed, speed-capped); the throw waits for the camera to get there.
 */
public final class MaceDivePlanner {

    /** Fall distance must exceed this for a smash. */
    public static final float SMASH_MIN_FALL = 1.5f;
    public static final float GRAVITY = 0.08f;
    public static final float DRAG = 0.98f;
    public static final float AIR_FRICTION = 0.91f;
    public static final float JUMP_VY = 0.42f;
    public static final double EYE = 1.62;
    /**
     * Upward speed a wind charge burst at the feet adds to a jumping player. An estimate (about a 7-block rise together
     * with the jump), to be calibrated in game; callers may pass their own.
     */
    public static final double WIND_LAUNCH_VY = 0.7;
    /** Requested pitch for the launch throw, and the pitch the camera must reach before throwing. */
    public static final float LAUNCH_PITCH = 88f, LAUNCH_MIN_PITCH = 80f;
    /** Longest fall the planner simulates. */
    public static final int HORIZON = 120;

    public enum Tier {
        /** Any smash (fall over 1.5 blocks). */
        SMASH(1.5f),
        /** End of the steep part: 3 blocks, +12 damage. */
        HEAVY(3.0f),
        /** End of the second part: 8 blocks, +22 damage. */
        FULL(8.0f);

        public final float fall;

        Tier(float fall) {
            this.fall = fall;
        }
    }

    private MaceDivePlanner() {
    }

    // ---------------------------------------------------------------- damage tiers

    /** Smash bonus damage for a fall distance (0 when no smash). */
    public static float smashBonus(float fall, int density) {
        if (fall <= SMASH_MIN_FALL) {
            return 0f;
        }
        float b;
        if (fall <= 3f) {
            b = 4f * fall;
        } else if (fall <= 8f) {
            b = 12f + 2f * (fall - 3f);
        } else {
            b = 22f + (fall - 8f);
        }
        return b + 0.5f * Math.max(0, density) * fall;
    }

    /** Smallest fall distance (above the smash minimum) whose bonus reaches {@code bonus}. Bisection on a monotone curve. */
    public static float fallForBonus(float bonus, int density) {
        float lo = SMASH_MIN_FALL;
        if (smashBonus(lo + 1e-4f, density) >= bonus) {
            return lo + 1e-4f;
        }
        float hi = 8f;
        while (smashBonus(hi, density) < bonus) {
            hi *= 2f;
            if (hi > 4096f) {
                return Float.POSITIVE_INFINITY;
            }
        }
        for (int i = 0; i < 40; i++) {
            float mid = 0.5f * (lo + hi);
            if (smashBonus(mid, density) >= bonus) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    /** Fall distance the plan aims for to reach a tier (a hair above the boundary: the smash test is strict). */
    public static float fallFor(Tier tier) {
        return tier.fall + 0.05f;
    }

    // ---------------------------------------------------------------- fair swing rules

    /**
     * True when a smash swing may be made now: the mace has been held for at least one full tick (no swap on the swing
     * tick), the player is not gliding (gliding blocks the smash), and the fall reached {@code requiredFall}.
     */
    public static boolean swingAllowed(boolean holdingMace, int ticksSinceSlotChange, boolean fallFlying,
                                       float fallDistance, float requiredFall) {
        return holdingMace && ticksSinceSlotChange >= 1 && !fallFlying && fallDistance > SMASH_MIN_FALL
                && fallDistance >= requiredFall;
    }

    /** The hotbar may leave the mace only after the hit tick has passed (no swap mid-hit). */
    public static boolean swapAwayAllowed(int ticksSinceSwing) {
        return ticksSinceSwing >= 1;
    }

    // ---------------------------------------------------------------- plans

    /** Result of a plan; reuse one instance per caller (no allocation per plan). */
    public static final class Plan {
        public boolean feasible;
        /** Ticks from the plan start to the swing. */
        public int swingTick = -1;
        /** Fall distance at the swing and the bonus it gives. */
        public float fallAtSwing;
        public float bonusAtSwing;
        /** Highest feet height above the start (launch) reached. */
        public double apex;
        /** Elytra: horizontal distance before the target at which to stop gliding; launch: unused (0). */
        public double stopDistance;
        /** Horizontal distance covered from the start to the swing. */
        public double travelAtSwing;

        void clear() {
            feasible = false;
            swingTick = -1;
            fallAtSwing = 0f;
            bonusAtSwing = 0f;
            apex = 0;
            stopDistance = 0;
            travelAtSwing = 0;
        }
    }

    /**
     * Wind-charge self-launch from the ground: jump on tick 0, the burst adds {@code launchVy} on tick 1 (the throw at
     * the feet goes out on the jump tick after the camera is down), then free fall. Finds the last tick on the way down
     * (before landing) where the fall reached {@code requiredFall} and the eye is within {@code reach} of the target's box.
     *
     * @param targetDy   target feet height minus own feet height (negative: target below)
     * @param targetH    target box height
     * @param horizGap   horizontal distance from own feet to the nearest point of the target box (kept: a vertical launch)
     * @param density    Density enchantment level on the mace
     */
    public static Plan planWindLaunch(Plan out, double targetDy, double targetH, double horizGap, double reach,
                                      float requiredFall, double launchVy, int density) {
        out.clear();
        double y = 0;
        double vy = JUMP_VY;
        float fall = 0f;
        double apex = 0;
        double land = Math.min(0, targetDy); // own feet come down at the lower of the start and the target's level
        for (int t = 1; t <= HORIZON; t++) {
            if (t == 1) {
                vy = Math.max(vy, 0) + launchVy;
            }
            double ny = y + vy;
            if (ny <= land && vy < 0) {
                break; // this tick lands: the last valid tick before it (kept in out) is the swing
            }
            if (vy < 0) {
                fall -= (float) vy;
            }
            y = ny;
            apex = Math.max(apex, y);
            vy = (vy - GRAVITY) * DRAG;
            if (fall >= requiredFall && fall > SMASH_MIN_FALL
                    && inReach(y + EYE, targetDy, targetH, horizGap, reach)) {
                // keep the latest valid tick: the most fall, swung as the fall ends within reach
                out.feasible = true;
                out.swingTick = t;
                out.fallAtSwing = fall;
                out.bonusAtSwing = smashBonus(fall, density);
                out.travelAtSwing = 0;
            }
        }
        out.apex = apex;
        return out;
    }

    /**
     * Elytra dive: glide high over the target, stop gliding (the smash does not count while gliding), then free fall
     * onto it. Starting with feet {@code heightAbove} blocks above the target's feet, moving {@code hSpeed} blocks/tick
     * toward it and {@code vy} vertically at the moment gliding stops, finds the swing tick and how far before the
     * target to stop gliding ({@link Plan#stopDistance}) so the swing comes within reach with the required fall.
     * The fall distance is counted from the stop (vanilla keeps it near 1 while gliding level).
     */
    public static Plan planElytraDive(Plan out, double heightAbove, double targetH, double hSpeed, double vy,
                                      double reach, float requiredFall, int density) {
        out.clear();
        double y = heightAbove;
        double travel = 0;
        double h = hSpeed;
        float fall = 0f;
        for (int t = 1; t <= HORIZON; t++) {
            y += vy;
            travel += h;
            if (vy < 0) {
                fall -= (float) vy;
            }
            vy = (vy - GRAVITY) * DRAG;
            h *= AIR_FRICTION;
            if (y <= 0) {
                break; // this tick lands: the last valid tick before it (kept in out) is the swing
            }
            // directly above the target at the swing when stopped stopDistance before it; keep the latest valid tick
            if (fall >= requiredFall && fall > SMASH_MIN_FALL && inReach(y + EYE, 0, targetH, 0, reach)) {
                out.feasible = true;
                out.swingTick = t;
                out.fallAtSwing = fall;
                out.bonusAtSwing = smashBonus(fall, density);
                out.travelAtSwing = travel;
                out.stopDistance = travel;
            }
        }
        out.apex = heightAbove;
        return out;
    }

    /** Lowest stop height above the target for an elytra dive to reach {@code requiredFall} (search in 0.25 steps). */
    public static double minDiveHeight(Plan scratch, double targetH, double hSpeed, double vy, double reach,
                                       float requiredFall, int density) {
        for (double hgt = 0.5; hgt <= 96; hgt += 0.25) {
            if (planElytraDive(scratch, hgt, targetH, hSpeed, vy, reach, requiredFall, density).feasible) {
                return hgt;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Eye within reach of a box spanning {@code [dy, dy + h]} vertically at horizontal gap {@code gap}. */
    static boolean inReach(double eyeY, double dy, double h, double gap, double reach) {
        double vgap = eyeY > dy + h ? eyeY - (dy + h) : (eyeY < dy ? dy - eyeY : 0);
        return gap * gap + vgap * vgap <= reach * reach;
    }

    /**
     * Launch sequencing per tick (stateless): aim down until the smoothed pitch arrives, then jump and throw together.
     * Returns 0 = keep aiming (request {@link #LAUNCH_PITCH}), 1 = jump + throw now, -1 = not possible (airborne, no
     * wind charge or mace, or the plan is infeasible).
     */
    public static int launchStep(boolean onGround, boolean haveCharge, boolean haveMace, boolean planFeasible,
                                 float pitch) {
        if (!onGround || !haveCharge || !haveMace || !planFeasible) {
            return -1;
        }
        return pitch >= LAUNCH_MIN_PITCH ? 1 : 0;
    }
}
