package com.utilitysuite.combat.tactics;

/**
 * Mutable, reused input of {@link HitTradeSolver}: both fighters at the current tick. Plain fields (no allocation);
 * the caller fills it from the combat snapshot and the opponent model every tick. Positions are feet positions,
 * velocities blocks per tick, times game ticks.
 */
public final class TradeInput {

    // ---- self -----------------------------------------------------------------------------------------------------
    public double sx, sy, sz, svx, svy, svz;
    public boolean sOnGround;
    /** Our yaw; the solver keeps us facing the opponent over the horizon (the aim does that in the game). */
    public float sYaw;
    public boolean sSprinting;
    /** Entity interaction range (3.0 in survival). */
    public double sReach = 3.0;
    /** Ticks until our attack is at full charge (0 = ready now). */
    public int sReadyIn;
    /** Our full cooldown period in ticks (20 / attack speed; 12.5 for a sword). */
    public double sCooldownTicks = 12.5;
    /** Remaining ticks of our hurt immunity from a hit already taken (0 = can be hit now). */
    public int sHurtTicks;
    /**
     * We hold a raised (effective) shield over the horizon: the opponent's hits count as zero (both fighters face each
     * other in the rollout, so every hit comes from the front) and cost it the cooldown, no hurt immunity starts.
     * Default false.
     */
    public boolean sBlocking;
    /**
     * Our movement model for the simulated body ({@link com.utilitysuite.core.api.BodySim#movement}): walking speed
     * attribute without the sprint modifier, Jump Boost velocity, sprinting right now. The defaults are the plain
     * model the solver used before these existed; the combat world fills real values only with "Exact world shapes".
     */
    public double sSpeed = com.utilitysuite.core.api.BodySim.BASE_SPEED;
    public double sJumpBoost;
    public boolean sBodySprinting;

    // ---- opponent -------------------------------------------------------------------------------------------------
    public double ox, oy, oz, ovx, ovy, ovz;
    public boolean oOnGround;
    public double oReach = 3.0;
    /** Ticks until the opponent's attack is at full charge ({@code OpponentModel.attackReadyIn}); 0 = ready now. */
    public int oReadyIn;
    public double oCooldownTicks = 12.5;
    /** Keys the opponent is assumed to hold over the horizon, relative to facing us (forward +1 = closing in). */
    public int oForward = 1;
    /** +1 = to its own left, -1 = right, 0 = none. */
    public int oStrafe;
    public boolean oSprinting = true;
    /** False for a mob that does not swing a weapon on a cooldown (it then hits whenever in reach, every 20 ticks). */
    public boolean oUsesCooldown = true;
    /** Remaining ticks of the opponent's hurt immunity from a hit we already landed (0 = can be hit now). */
    public int oHurtTicks;
    /**
     * The opponent holds a raised (effective) shield facing us over the horizon: our hits count as zero, burn our
     * cooldown and cost the shield's knockback ({@code LivingEntity.blockedByShield}, strength 0.5). Default false.
     */
    public boolean oShieldUp;
    /** The opponent's movement model, as {@link #sSpeed}, {@link #sJumpBoost}, {@link #sBodySprinting}. */
    public double oSpeed = com.utilitysuite.core.api.BodySim.BASE_SPEED;
    public double oJumpBoost;
    public boolean oBodySprinting;

    // ---- arena and tuning -----------------------------------------------------------------------------------------
    /** Hazards relative to our direction toward the opponent; a combination that moves into one is rejected. */
    public boolean hazardFront, hazardBack, hazardLeft, hazardRight;
    /** Ticks simulated ahead (6 to 8; clamped to 1..12). */
    public int horizon = 7;
    /** Value of one hit taken, relative to one hit landed (1.0 = even trade; above 1 = more careful). */
    public double takenWeight = 1.2;
    /** Whether a jump may be part of the answer (false at the lip of a gap or under a low ceiling). */
    public boolean allowJump = true;
    /** Ticks a fighter needs to sprint again after a sprint hit cleared its sprint (a w-tap; 0 = instant). */
    public int resprintTicks = 2;
}
