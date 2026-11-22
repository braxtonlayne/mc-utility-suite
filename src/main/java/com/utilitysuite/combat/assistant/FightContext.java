package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.core.api.SimKey;

/**
 * What a {@link FightStage} may do and read. Implemented by the {@link CombatAssistant}: every action goes through
 * its lease and the core input layer (smoothed, capped rotations; no direct key or camera access), and is a no-op
 * when the lease is not held. Allocation free; client thread only; valid only during the call it was passed to.
 */
public interface FightContext {

    // ---- reads --------------------------------------------------------------------------------------------------

    CombatSettings settings();

    CombatWorld world();

    OpponentModel opponents();

    int targetId();

    boolean targetIsPlayer();

    /** The fight clock (ticks while engaged), the time base of {@link OpponentModel}. */
    long clock();

    /** The built-in loop is eating (the sustain layer holds USE). */
    boolean eating();

    /** The weapon's hotbar slot for this fight, or -1 before it is drawn. */
    int weaponSlot();

    /** Half-hearts the last hit taken cost. */
    float lastHitDamage();

    // ---- actions (this tick) ------------------------------------------------------------------------------------

    /** Smoothed aim at the target with the configured lead. */
    void aimAtTarget(CombatSnapshot s);

    /** Smoothed aim at an absolute yaw and pitch ({@code speed} scales the configured turn speed; capped in core). */
    void aimAt(float yaw, float pitch, float speed);

    /** Movement keys for this tick: forward -1 / 0 / +1, side as {@link StrafePlanner}, sprint. */
    void move(int forward, int side, boolean sprint);

    /** Backs away from the target unless a hazard is behind. */
    void retreatMove(CombatSnapshot s);

    /** Runs the flee / make-distance route for this tick; false when no route runs (fall back to keys). */
    boolean retreatRoute(CombatSnapshot s);

    /** One attack tap (counts as a swing). */
    void tapAttack();

    /** Holds or releases a key under the fight's lease (cached: repeated calls cost nothing). */
    void hold(SimKey key, boolean down);

    /** A key tap of {@code holdTicks} after {@code delayTicks}. */
    void tap(SimKey key, int holdTicks, int delayTicks);

    void selectHotbar(int slot);

    /** Back to the fight's weapon. */
    void selectWeapon();

    /**
     * Starts eating the consumable in a hotbar slot. The built-in sustain layer finishes it (backing off, facing the
     * target) and restores the weapon, so it needs "Emergency sustain" on; a no-op while already eating.
     */
    void startEating(CombatSnapshot s, int hotbarSlot);

    // ---- one-tick requests to the built-in loop (cleared at the start of every tick) -----------------------------

    /** No swing from the hit planner this tick (a raised shield in front, a held block). */
    void vetoSwing();

    /** No crit hop or other jump from the hit planner this tick. */
    void vetoJump();

    /** Hit kind policy for this tick in place of the "Hit choice" setting. */
    void hitPolicy(HitPlanner.Policy policy);

    /** Forces the strafe side of the built-in movement this tick ({@link StrafePlanner#LEFT} / RIGHT / NONE). */
    void forceSide(int side);

    /** Moves the spacing band out by this many blocks this tick (keep out of a blast or a reach). */
    void widenSpacing(double blocks);

    /** No feints this tick (the opponent's shield is disabled, a kill window). */
    void pressAdvantage();

    /**
     * Asks for a switch to another living target after this tick. The stage is responsible for the engagement
     * rules: in reactive mode only an entity that is already attacking the player, never an ally or a bystander.
     */
    void requestTarget(int entityId);
}
