package com.utilitysuite.combat.boss;

import com.utilitysuite.combat.tactics.RangedBallistics;

/**
 * Engine-free classification for the boss overlays (roadmap W13): ender dragon phase and perch hints, end crystal
 * cages and bow shots, dragon-breath clouds, wither armour phase and blue-skull trajectories. Allocation free: callers
 * pass primitives and read results from return values or the reusable {@link RangedBallistics} instance.
 */
public final class BossFightAdvisor {

    private BossFightAdvisor() {
    }

    /** Broad dragon state, from the synced phase id ({@code EnderDragonPhase.getId}). */
    public enum DragonState { CIRCLING, STRAFING, LANDING, PERCHED, TAKING_OFF, CHARGING, DYING, HOVERING, UNKNOWN }

    /**
     * Maps the vanilla phase id: 0 holding pattern, 1 strafe player, 2 landing approach, 3 landing, 4 takeoff,
     * 5 sitting flaming, 6 sitting scanning, 7 sitting attacking, 8 charging player, 9 dying, 10 hovering.
     */
    public static DragonState dragonState(int phaseId) {
        return switch (phaseId) {
            case 0 -> DragonState.CIRCLING;
            case 1 -> DragonState.STRAFING;
            case 2, 3 -> DragonState.LANDING;
            case 5, 6, 7 -> DragonState.PERCHED;
            case 4 -> DragonState.TAKING_OFF;
            case 8 -> DragonState.CHARGING;
            case 9 -> DragonState.DYING;
            case 10 -> DragonState.HOVERING;
            default -> DragonState.UNKNOWN;
        };
    }

    /**
     * Fallback when the phase id is not readable: perched if within 6 blocks horizontally of the fountain (0, 0) and
     * nearly still.
     */
    public static boolean looksPerched(double dragonX, double dragonZ, double speedPerTick) {
        return dragonX * dragonX + dragonZ * dragonZ < 36.0 && speedPerTick < 0.15;
    }

    /**
     * Longest time (ticks) the dragon stays in a perched sub-phase before moving on: scanning 100 (then takeoff or
     * charge), flaming 200 (four 10-tick flame bursts with warm-up), attacking 40 (roar, then flaming).
     */
    public static int perchPhaseMaxTicks(int phaseId) {
        return switch (phaseId) {
            case 5 -> 200;
            case 6 -> 100;
            case 7 -> 40;
            default -> 0;
        };
    }

    /** Seconds left (rounded up, >= 0) of the current perched sub-phase given ticks spent in it; -1 when not perched. */
    public static int perchSecondsLeft(int phaseId, int ticksInPhase) {
        int max = perchPhaseMaxTicks(phaseId);
        if (max == 0) {
            return -1;
        }
        int left = Math.max(0, max - ticksInPhase);
        return (left + 19) / 20;
    }

    /**
     * Chance per holding-pattern loop that the dragon heads to the fountain: {@code 1 / (crystalsAlive + 3)} (vanilla
     * {@code random.nextInt(crystals + 3) == 0}). Returned as the denominator.
     */
    public static int perchChanceDenominator(int crystalsAlive) {
        return Math.max(0, crystalsAlive) + 3;
    }

    /**
     * A crystal is caged when iron bars surround it: count bars found in the ring of 8 horizontal neighbours at the
     * crystal's block level (and the block above). Vanilla cages have bars on all sides; 4+ means a cage worth
     * flagging (arrows will not reach the crystal through it from most angles).
     */
    public static boolean caged(int ironBarsAround) {
        return ironBarsAround >= 4;
    }

    /** Result of a bow-shot check on a crystal. */
    public enum ShotVerdict { CLEAR, OUT_OF_RANGE, CAGED, BLOCKED }

    /**
     * Whether a fully drawn bow from the player reaches the crystal on the low arc.
     *
     * @param ballistics reusable solver (its elevation/flight time hold the shot on CLEAR)
     * @param sightClear the caller's ray check along the solved arc (true when nothing solid is in the way)
     */
    public static ShotVerdict bowShot(RangedBallistics ballistics, double horizontalDist, double dy, boolean isCaged,
                                      boolean sightClear) {
        if (isCaged) {
            return ShotVerdict.CAGED;
        }
        if (!ballistics.solve(horizontalDist, dy, RangedBallistics.BOW_FULL_SPEED, RangedBallistics.ARROW_WATER_DRAG,
                false)) {
            return ShotVerdict.OUT_OF_RANGE;
        }
        return sightClear ? ShotVerdict.CLEAR : ShotVerdict.BLOCKED;
    }

    /**
     * Inside a dragon-breath cloud ({@code AreaEffectCloud}): within {@code radius} horizontally and from the cloud's
     * y up to y + 0.5 (its height), with {@code margin} blocks of extra caution.
     */
    public static boolean inCloud(double px, double py, double pz, double cx, double cy, double cz, double radius,
                                  double margin) {
        double dx = px - cx, dz = pz - cz, r = radius + margin;
        return dx * dx + dz * dz <= r * r && py >= cy - 1.0 - margin && py <= cy + 0.5 + margin;
    }

    // --- Wither ------------------------------------------------------------------------------------------------

    /** Wither armour phase ({@code WitherBoss.isPowered}): at or below half health arrows bounce, so melee only. */
    public static boolean witherArmoured(float health, float maxHealth) {
        return health <= maxHealth / 2.0f;
    }

    /** {@code WitherSkull.getInertia}: blue (dangerous) 0.73, black 0.95. */
    public static double skullInertia(boolean dangerous) {
        return dangerous ? 0.73 : 0.95;
    }

    /** {@code AbstractHurtingProjectile.accelerationPower} default. */
    public static final double SKULL_ACCEL = 0.1;

    /**
     * Closest approach of a wither skull to a point within {@code maxTicks}, simulating vanilla flight
     * ({@code pos += vel; vel = (vel + norm(vel) * 0.1) * inertia}). Straight line, no gravity. Returns the squared
     * distance; the tick of closest approach is not needed by the overlay.
     */
    public static double skullClosestSq(double sx, double sy, double sz, double vx, double vy, double vz,
                                        boolean dangerous, double tx, double ty, double tz, int maxTicks) {
        double inertia = skullInertia(dangerous);
        double best = sq(sx - tx, sy - ty, sz - tz);
        for (int t = 0; t < maxTicks; t++) {
            sx += vx;
            sy += vy;
            sz += vz;
            double d = sq(sx - tx, sy - ty, sz - tz);
            if (d < best) {
                best = d;
            }
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len > 1.0e-9) {
                double k = SKULL_ACCEL / len;
                vx += vx * k;
                vy += vy * k;
                vz += vz * k;
            }
            vx *= inertia;
            vy *= inertia;
            vz *= inertia;
        }
        return best;
    }

    /** True when the skull passes within {@code radius} of the player's centre in the next {@code maxTicks}. */
    public static boolean skullIncoming(double sx, double sy, double sz, double vx, double vy, double vz,
                                        boolean dangerous, double px, double py, double pz, double radius,
                                        int maxTicks) {
        return skullClosestSq(sx, sy, sz, vx, vy, vz, dangerous, px, py, pz, maxTicks) <= radius * radius;
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    /** Short status for the HUD line. */
    public static String witherHint(float health, float maxHealth) {
        return witherArmoured(health, maxHealth) ? "Wither armoured: melee only" : "Wither: arrows work";
    }

    public static String dragonHint(int phaseId, int ticksInPhase, int crystalsAlive) {
        DragonState s = dragonState(phaseId);
        return switch (s) {
            case PERCHED -> "Dragon perched: hit the head (" + perchSecondsLeft(phaseId, ticksInPhase) + "s)";
            case LANDING -> "Dragon landing: get under the fountain";
            case CHARGING -> "Dragon charging you";
            case STRAFING -> "Dragon strafing: fireball incoming";
            case CIRCLING -> "Dragon circling (perch 1/" + perchChanceDenominator(crystalsAlive) + " per loop)";
            case TAKING_OFF -> "Dragon taking off";
            case HOVERING, DYING, UNKNOWN -> "Dragon";
        };
    }
}
