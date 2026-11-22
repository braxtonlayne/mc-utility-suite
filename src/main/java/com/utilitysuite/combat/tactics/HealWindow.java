package com.utilitysuite.combat.tactics;

/**
 * Engine-free planner for healing in windows: heal when it is safe, and decide in hits left rather than half-hearts.
 * <ul>
 *   <li>{@link #wantHeal}: effective health at or below {@code k} of the opponent's measured hits (the fixed low-health
 *       threshold while no hit has been measured), and never later than that threshold.</li>
 *   <li>{@link #safeTicks}: how many ticks the opponent cannot land a hit, from its distance and separating speed (a
 *       knockback hit that sent it away), its attack cooldown, and a broken line of sight.</li>
 *   <li>{@link #plan}: eat in a window; with none, fight for a knockback hit while ours is charged and in reach,
 *       otherwise make distance along the flee route.</li>
 * </ul>
 * Pure static functions, allocation free.
 */
public final class HealWindow {

    /** Ticks to eat a golden apple (and most food). */
    public static final int EAT_TICKS = 32;
    /** Ticks of the eat that may still overlap the opponent's return (a hit does not interrupt eating). */
    public static final int EAT_MARGIN = 8;
    /** Our attack charge from which a swing in reach is worth taking for its knockback. */
    public static final float KNOCKBACK_CHARGE = 0.9f;
    /** Extra ticks the opponent needs to find us after line of sight broke (it must path around the cover). */
    public static final int NO_SIGHT_BONUS = 12;
    /** Approach speeds (blocks per tick): a sprint-jumping player, and a typical melee mob. */
    public static final double PLAYER_APPROACH = 0.33;
    public static final double MOB_APPROACH = 0.25;
    /** Horizontal speed kept per tick in the air (vanilla air drag 0.91). */
    public static final double AIR_DECAY = 0.91;
    /**
     * Ticks a sprint-knocked opponent stays airborne (the 0.4 upward knockback at 0.08 gravity, 0.98 drag) and so
     * cannot turn back.
     */
    public static final int KNOCK_AIR_TICKS = 9;
    /** Separating speeds below this are drift, not knockback (blocks per tick). */
    public static final double KNOCKBACK_MIN_SPEED = 0.15;
    /** Reach margin: the opponent swings a little before its centre is exactly at reach. */
    public static final double REACH_MARGIN = 0.4;
    /** Cap of the reported safe ticks (a far or lost opponent). */
    public static final int MAX_SAFE = 200;
    /** Hunger below which a top-up is worth it (natural regeneration needs 18). */
    public static final int TOPUP_BELOW = 18;

    public enum Plan {
        /** A window is open: eat now. */
        EAT,
        /** In reach with a charged attack: keep fighting for a knockback hit (the built-in loop swings). */
        FIGHT_FOR_KNOCKBACK,
        /** Make distance along the flee route around cover, then eat once the window opens. */
        RETREAT
    }

    private HealWindow() {
    }

    /** Hits the opponent needs to take us out (infinite while no hit is measured). */
    public static double hitsLeft(float effectiveHealth, double damagePerHit) {
        return damagePerHit > 0.0 ? effectiveHealth / damagePerHit : Double.POSITIVE_INFINITY;
    }

    /**
     * Whether to heal now. In hits left once a hit is measured, never later than the fixed threshold.
     *
     * @param health       health without absorption (nothing to heal at full health, whatever the hits left)
     * @param damagePerHit measured damage per hit taken (half-hearts), or 0 when none has been measured
     * @param hitsLeftK    heal at or below this many hits left (setting, 1.5..4)
     * @param lowThreshold the emergency low-health threshold (half-hearts)
     */
    public static boolean wantHeal(float effectiveHealth, float health, float maxHealth, double damagePerHit,
                                   double hitsLeftK, float lowThreshold) {
        if (effectiveHealth <= lowThreshold) {
            return true;
        }
        return damagePerHit > 0.0 && health < maxHealth && effectiveHealth <= hitsLeftK * damagePerHit;
    }

    /**
     * Ticks the opponent cannot land a full hit on us. It needs to be in reach and charged, so this is the larger of
     * the time it needs to close the gap and its cooldown; a broken line of sight adds the time to find us.
     *
     * @param distance       horizontal centre distance
     * @param separating     speed of the opponent away from us (blocks per tick; negative when closing)
     * @param knockTicksLeft ticks of knockback flight left (0 when it was not just hit; {@link #knockTicksLeft})
     * @param reach          the opponent's reach (3.0 for players and most mobs)
     * @param approachSpeed  how fast it closes once it turns back ({@link #PLAYER_APPROACH}, {@link #MOB_APPROACH})
     * @param attackReadyIn  ticks until its attack is charged ({@code OpponentModel.attackReadyIn})
     * @param lineOfSight    it can see us
     */
    public static int safeTicks(double distance, double separating, int knockTicksLeft, double reach,
                                double approachSpeed, int attackReadyIn, boolean lineOfSight) {
        double gap = distance - reach + REACH_MARGIN;
        if (gap < 0.0) {
            gap = 0.0;
        }
        double coastTicks = 0.0;
        if (separating > KNOCKBACK_MIN_SPEED && knockTicksLeft > 0) {
            // a knocked opponent flies on (air drag only) until it lands, and only then can it turn back
            gap += separating * (1.0 - Math.pow(AIR_DECAY, knockTicksLeft)) / (1.0 - AIR_DECAY);
            coastTicks = knockTicksLeft;
        } else if (separating < 0.0) {
            approachSpeed = Math.max(approachSpeed, -separating);
        }
        double reachTicks = gap <= 0.0 ? 0.0 : coastTicks + gap / Math.max(0.05, approachSpeed);
        double safe = Math.max(reachTicks, attackReadyIn);
        if (!lineOfSight) {
            safe += NO_SIGHT_BONUS;
        }
        return safe >= MAX_SAFE ? MAX_SAFE : (int) safe;
    }

    /**
     * Knockback flight left from the target's hurt time (10 on the hit tick, counting down) and whether it is
     * airborne: {@value #KNOCK_AIR_TICKS} ticks after the hit at most, 0 once it is back on the ground.
     */
    public static int knockTicksLeft(int targetHurtTime, boolean targetOnGround) {
        if (targetHurtTime <= 0) {
            return 0;
        }
        int sinceHit = 10 - targetHurtTime;
        int left = KNOCK_AIR_TICKS - sinceHit;
        if (left <= 0 || (targetOnGround && sinceHit > 1)) {
            return 0;
        }
        return left;
    }

    /** Whether {@code safeTicks} covers most of an eat of {@code eatTicks}. */
    public static boolean windowOpen(int safeTicks, int eatTicks) {
        return safeTicks >= eatTicks - EAT_MARGIN;
    }

    /**
     * What to do while a heal is wanted.
     *
     * @param ourCharge our attack strength 0..1
     * @param inReach   the target is in our reach
     */
    public static Plan plan(int safeTicks, int eatTicks, boolean inReach, float ourCharge) {
        if (windowOpen(safeTicks, eatTicks)) {
            return Plan.EAT;
        }
        if (inReach && ourCharge >= KNOCKBACK_CHARGE) {
            return Plan.FIGHT_FOR_KNOCKBACK;
        }
        return Plan.RETREAT;
    }

    /**
     * Whether to top up hunger between exchanges: below {@link #TOPUP_BELOW}, the food wastes nothing (its nutrition
     * fits in the missing hunger) and the window covers the whole eat.
     */
    public static boolean topUp(int hunger, int nutrition, int safeTicks, int eatTicks) {
        return hunger < TOPUP_BELOW && nutrition > 0 && nutrition <= 20 - hunger && safeTicks >= eatTicks;
    }
}
