package com.utilitysuite.travel.movement;

/**
 * Fall-clutch fallback chain (roadmap C8): when the placed clutch of {@link FallClutchPlanner} and the rest of the
 * {@link ClutchLadder} find nothing, walk a dimension-aware ordered chain of fallbacks and take the first feasible one:
 * <ol>
 *   <li>{@link Fallback#WATER_BUCKET} (never where water evaporates: the Nether);</li>
 *   <li>{@link Fallback#POWDER_SNOW};</li>
 *   <li>{@link Fallback#SLIME_OR_HAY} (slime cancels the damage, hay leaves a fifth);</li>
 *   <li>{@link Fallback#COBWEB};</li>
 *   <li>{@link Fallback#BOAT} placed on the landing block;</li>
 *   <li>{@link Fallback#TWISTING_VINES} placed on the landing block (climbable: entering it resets the fall);</li>
 *   <li>{@link Fallback#WIND_CHARGE} thrown straight down just before impact: the burst launches the player up and
 *       resets the fall distance.</li>
 * </ol>
 * Every fallback is validated: the item is in the hotbar, the head can make a smoothed look-down (keeping the yaw, so
 * the look stays along the movement direction) at no more than the planned turn rate before the use tick, and, for
 * placed items, the landing block takes the placement and its top face is within reach on that tick. Engine free and
 * allocation free: the caller fills a reusable height array (feet above the landing surface at the start of each tick,
 * index {@code ticksToImpact} = 0) and calls {@link #choose}.
 */
public final class ClutchChain {

    /** The fallbacks, in chain order. */
    public enum Fallback {
        WATER_BUCKET(true, 1), POWDER_SNOW(true, 1), SLIME_OR_HAY(true, 1), COBWEB(true, 1), BOAT(true, 2),
        TWISTING_VINES(true, 2), WIND_CHARGE(false, 0);

        /** Placed on the landing block's top face (needs reach and a placeable landing). */
        public final boolean placed;
        /**
         * Ticks the placement needs before the feet arrive: a boat is an entity the server spawns a tick later; vines
         * must exist before the tick that starts inside their cell (placed during that tick, the player's cached
         * feet block state is still air and the climb check misses them: playtest 2026-11-25, used at 0.75 blocks up
         * and landed with 8.5 damage).
         */
        public final int lead;

        Fallback(boolean placed, int lead) {
            this.placed = placed;
            this.lead = lead;
        }

        public int bit() {
            return 1 << ordinal();
        }
    }

    public static final Fallback[] CHAIN = Fallback.values();
    /** Every fallback the planner does not already cover (what the in-game chain executes). */
    public static final int NEW_FALLBACKS = Fallback.BOAT.bit() | Fallback.TWISTING_VINES.bit()
            | Fallback.WIND_CHARGE.bit();

    /** Pitch of the look-down for a placement onto the landing block (straight down, the yaw kept). */
    public static final float PLACE_PITCH = 85f;
    /** Pitch for the wind charge throw. */
    public static final float THROW_PITCH = 88f;
    /** Pitch the wind charge still works from (anything steeper lands the burst under the feet). */
    public static final float THROW_MIN_PITCH = 75f;
    /** Wind charge launch speed (blocks per tick); the thrower's own fall adds to it. */
    public static final double WIND_SPEED = 1.5;
    /** Distance from the burst the knockback (and the fall reset) still reaches the player. */
    public static final double WIND_REACH = 2.0;
    /** Spawn height of the charge above the feet (eye less a little). */
    public static final double WIND_SPAWN = 1.52;

    private Fallback chosen;
    private int useTick = -1;
    private float aimPitch;

    /**
     * Picks the first feasible fallback.
     *
     * @param items         bit set of {@link Fallback#bit()} in the hotbar (and allowed)
     * @param nether        water evaporates here (dimension type ultra-warm)
     * @param heights       feet height above the landing surface at the start of each tick, 0..ticksToImpact
     * @param ticksToImpact ticks until the feet land without a clutch
     * @param lookPitch     current pitch (degrees, down positive)
     * @param degPerTick    planned turn rate (already capped by the core smoother)
     * @param placeable     the landing block takes a placement on its top face and the cell above it is free
     * @return the fallback, or null when none is feasible
     */
    public Fallback choose(int items, boolean nether, double[] heights, int ticksToImpact, float lookPitch,
                           double degPerTick, boolean placeable) {
        chosen = null;
        useTick = -1;
        if (items == 0 || ticksToImpact <= 0) {
            return null;
        }
        for (Fallback f : CHAIN) {
            if ((items & f.bit()) == 0 || (f == Fallback.WATER_BUCKET && nether)
                    || (f == Fallback.TWISTING_VINES && !entersVines(heights, ticksToImpact))) {
                continue;
            }
            int u = f.placed ? placeTick(f, heights, ticksToImpact, lookPitch, degPerTick, placeable)
                    : windTick(heights, ticksToImpact, lookPitch, degPerTick);
            if (u >= 0) {
                chosen = f;
                useTick = u;
                aimPitch = f.placed ? PLACE_PITCH : THROW_PITCH;
                return f;
            }
        }
        return null;
    }

    /**
     * Vines placed on the landing block only reset the fall if some tick starts with the feet inside the vine cell
     * (climbing is checked at the start of a tick, before the move): the last airborne tick must start less than one
     * block above the landing. A fast fall whose last tick starts higher skips through the cell and lands with full
     * damage.
     */
    static boolean entersVines(double[] heights, int ticksToImpact) {
        return ticksToImpact >= 1 && heights[ticksToImpact - 1] > 0.0 && heights[ticksToImpact - 1] < VINE_CELL;
    }

    /** Height of the climbable cell the feet must start a tick in. */
    public static final double VINE_CELL = 1.0;

    /**
     * Latest tick a placement on the landing top face can be used: the look-down done, the face within reach of the
     * eye, and {@link Fallback#lead} ticks left before the feet land. -1 when none.
     */
    static int placeTick(Fallback f, double[] heights, int ticksToImpact, float lookPitch, double degPerTick,
                         boolean placeable) {
        if (!placeable) {
            return -1;
        }
        int turn = FallClutchPlanner.turnTicks(Math.abs(PLACE_PITCH - lookPitch), degPerTick);
        for (int t = ticksToImpact - f.lead; t >= turn && t >= 0; t--) {
            if (heights[t] + FallClutchPlanner.EYE <= FallClutchPlanner.REACH) {
                return t;
            }
        }
        return -1;
    }

    /**
     * Latest tick to throw the wind charge straight down so it bursts on the ground before the feet land and with the
     * player still inside the burst's reach. -1 when none.
     */
    static int windTick(double[] heights, int ticksToImpact, float lookPitch, double degPerTick) {
        int turn = FallClutchPlanner.turnTicks(Math.abs(THROW_PITCH - lookPitch), degPerTick);
        if (lookPitch >= THROW_MIN_PITCH) {
            turn = 0; // already looking down far enough
        }
        for (int u = ticksToImpact - 1; u >= turn && u >= 0; u--) {
            int burst = burstTick(heights, ticksToImpact, u);
            if (burst >= 0 && burst < ticksToImpact && heights[burst] <= WIND_REACH) {
                return u;
            }
        }
        return -1;
    }

    /** Tick the charge thrown at the start of tick {@code u} hits the ground, or -1 when it would not before impact. */
    static int burstTick(double[] heights, int ticksToImpact, int u) {
        double fall = u + 1 <= ticksToImpact ? Math.max(0.0, heights[u] - heights[u + 1]) : 0.0;
        double speed = WIND_SPEED + fall;
        double left = heights[u] + WIND_SPAWN;
        for (int t = u + 1; t <= ticksToImpact; t++) {
            left -= speed;
            if (left <= 0.0) {
                return t;
            }
        }
        return -1;
    }

    /** Last choice (null when none). */
    public Fallback chosen() {
        return chosen;
    }

    /** Ticks from now until the fallback must be used (0 = this tick), -1 when none. */
    public int useTick() {
        return useTick;
    }

    /** Pitch to turn to (the yaw is kept). */
    public float aimPitch() {
        return aimPitch;
    }
}
