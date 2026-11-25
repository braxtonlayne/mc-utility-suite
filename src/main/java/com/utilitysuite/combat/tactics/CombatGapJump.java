package com.utilitysuite.combat.tactics;

import com.utilitysuite.travel.movement.JumpSearch;
import com.utilitysuite.travel.movement.MovementPhysics;

/**
 * Lets the combat movement/strafe controller take a gap jump mid-fight. It never invents jumps: the jump must be one the
 * parkour validator approves ({@link JumpSearch#shouldEdgeJump}: at an edge, jumping lands strictly better than walking
 * off, on {@link MovementPhysics} with the real voxels). On top of that, combat gating:
 * <ul>
 *   <li>off unless enabled; never while the user's own movement keys are active (manual input wins);</li>
 *   <li>with SafeWalk on, a jump whose landing costs any health is invalid (the user can still jump themselves);
 *       otherwise a landing must leave at least {@link #HEALTH_MARGIN} health;</li>
 *   <li>the landing must not carry the player away from the fight: it stays within {@code maxTargetDistance} of the
 *       target, or (when chasing) gets closer than it started; a retreat may move away;</li>
 *   <li>not while the own swing is charged and the target is already in reach (hit first, jump after).</li>
 * </ul>
 * Engine free and allocation free (one reused {@link JumpSearch}).
 */
public final class CombatGapJump {

    public enum Verdict { NO, JUMP }

    /** Health that must remain after the landing's fall damage (when SafeWalk is off). */
    public static final float HEALTH_MARGIN = 4f;

    public boolean enabled;
    public double maxTargetDistance = 6.0;

    private final JumpSearch search;
    private Verdict last = Verdict.NO;
    private String reason = "off";

    public CombatGapJump() {
        this(new JumpSearch());
    }

    public CombatGapJump(JumpSearch search) {
        this.search = search;
    }

    /**
     * @param now           the player's body on this tick (not modified)
     * @param yawDeg        the movement yaw the controller is about to run along (camera keeps looking where it moves)
     * @param sprint        sprinting
     * @param health        current effective health
     * @param safeWalk      SafeWalk is guarding edges
     * @param manualMove    the user is pressing movement keys
     * @param tx            target feet x
     * @param tz            target feet z
     * @param retreating    the controller is backing off / fleeing
     * @param hitReadyInReach the own swing is charged and the target is within reach this tick
     */
    public Verdict decide(MovementPhysics now, float yawDeg, boolean sprint, float health, boolean safeWalk,
                          boolean manualMove, double tx, double tz, boolean retreating, boolean hitReadyInReach,
                          MovementPhysics.Voxels w) {
        last = Verdict.NO;
        if (!enabled) {
            reason = "off";
            return last;
        }
        if (manualMove) {
            reason = "manual";
            return last;
        }
        if (hitReadyInReach) {
            reason = "hit first";
            return last;
        }
        if (!now.onGround) {
            reason = "airborne";
            return last;
        }
        if (!search.shouldEdgeJump(now, yawDeg, sprint, health, w)) {
            reason = "validator";
            return last;
        }
        float dmg = search.landDamage();
        if (safeWalk ? dmg > 0f : health - dmg < HEALTH_MARGIN) {
            reason = "damage";
            return last;
        }
        if (!retreating) {
            double sx = now.x - tx;
            double sz = now.z - tz;
            double lx = search.landX() - tx;
            double lz = search.landZ() - tz;
            double before = sx * sx + sz * sz;
            double after = lx * lx + lz * lz;
            if (after > maxTargetDistance * maxTargetDistance && after >= before) {
                reason = "leaves fight";
                return last;
            }
        }
        reason = "ok";
        last = Verdict.JUMP;
        return last;
    }

    public Verdict last() {
        return last;
    }

    /** Why the last decision said no (diagnostics; constant strings, no allocation). */
    public String reason() {
        return reason;
    }

    public JumpSearch search() {
        return search;
    }
}
