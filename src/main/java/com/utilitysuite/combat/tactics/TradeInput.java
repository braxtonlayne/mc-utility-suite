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

    // ---- arena and tuning -----------------------------------------------------------------------------------------
    /** Hazards relative to our direction toward the opponent; a combination that moves into one is rejected. */
    public boolean hazardFront, hazardBack, hazardLeft, hazardRight;
    /** Ticks simulated ahead (6 to 8). */
    public int horizon = 7;
    /** Value of one hit taken, relative to one hit landed (1.0 = even trade; above 1 = more careful). */
    public double takenWeight = 1.2;
    /** Whether a jump may be part of the answer (false at the lip of a gap or under a low ceiling). */
    public boolean allowJump = true;
}
