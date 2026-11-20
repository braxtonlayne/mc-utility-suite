package com.utilitysuite.combat.assistant;

/**
 * Mutable, reused per-tick view of the local player and the current target, filled by {@link CombatWorld#sample}.
 * Plain fields so the decision code reads it without allocation and tests can fill it directly.
 * Positions are feet positions; hazards are relative to the direction from the player toward the target.
 */
public final class CombatSnapshot {

    // ---- local player ---------------------------------------------------------------------------------------------
    public double px, py, pz, eyeY;
    public float yaw, pitch;
    public float health, maxHealth, absorption;
    public boolean onGround, inWater, climbing, usingItem, screenOpen;
    public float fallDistance;
    /** {@code getAttackStrengthScale(0.5f)}. */
    public float attackStrength;
    /** Entity interaction range (3.0 in survival). */
    public double reach = 3.0;
    public int selectedSlot;
    public boolean offhandTotem;
    /** Inventory index 0..35 of a totem outside the offhand, or -1. */
    public int totemInvSlot = -1;
    /** Hotbar indices 0..8, or -1. */
    public int gappleSlot = -1, axeSlot = -1, maceSlot = -1, windChargeSlot = -1;
    public boolean gappleOnCooldown, windChargeOnCooldown;

    // ---- target ---------------------------------------------------------------------------------------------------
    public double tx, ty, tz;
    public double minX, minY, minZ, maxX, maxY, maxZ;
    public double tvx, tvy, tvz;
    public boolean targetBlocking, lineOfSight, crosshairOnTarget;

    // ---- hazards (relative to the player->target direction) -------------------------------------------------------
    public boolean hazardFront, hazardBack, hazardLeft, hazardRight;

    /** Horizontal centre-to-centre distance player -> target (the spacing metric). */
    public double horizontalDistance() {
        double dx = tx - px;
        double dz = tz - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Squared 3D feet-to-feet distance (the disengage-radius metric). */
    public double distanceSq() {
        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Squared distance from the eye to the nearest point of the target's box (vanilla's reach metric). */
    public double reachDistanceSq() {
        double dx = Math.max(minX - px, Math.max(0.0, px - maxX));
        double dy = Math.max(minY - eyeY, Math.max(0.0, eyeY - maxY));
        double dz = Math.max(minZ - pz, Math.max(0.0, pz - maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean inReach() {
        return reachDistanceSq() <= reach * reach;
    }

    /** True while airborne and falling: the vanilla critical-hit condition (minus sprint/blindness details). */
    public boolean fallingForCrit() {
        return !onGround && fallDistance > 0f && !inWater && !climbing;
    }

    public float effectiveHealth() {
        return health + absorption;
    }
}
