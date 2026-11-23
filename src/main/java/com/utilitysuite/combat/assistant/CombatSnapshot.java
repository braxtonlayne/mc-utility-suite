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
    /** Hunger points 0..20 (natural regeneration needs 18+). */
    public int foodLevel = 20;
    /** Food saturation (synced by the server with health): above 0 at food level 20, regeneration is the fast one. */
    public float saturation = 5f;
    public boolean onGround, inWater, climbing, usingItem, screenOpen;
    public float fallDistance;
    /** The local player's velocity (blocks per tick) and sprint state, for forward simulation. */
    public double pvx, pvy, pvz;
    public boolean sprinting;
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
    /**
     * Best sustain consumable (golden apple, enchanted golden apple, drinkable healing/regeneration potion) in the
     * main inventory (index 9..35), or -1. {@link #gappleSlot} is the same for the hotbar.
     */
    public int sustainInvSlot = -1;
    /** First empty hotbar index 0..8, or -1. */
    public int emptyHotbarSlot = -1;

    // ---- target ---------------------------------------------------------------------------------------------------
    public double tx, ty, tz;
    public double minX, minY, minZ, maxX, maxY, maxZ;
    public double tvx, tvy, tvz;
    /**
     * The target's actual horizontal motion over the last tick (position minus last tick's position). On the client a
     * remote player's {@link #tvx} is the last motion packet (knockback) and can stay stale; this cannot.
     */
    public double tdx, tdz;
    /** The target's body yaw. */
    public float targetYaw;
    /** True on the one tick the target's arm swing started (its attack cooldown was reset this tick). */
    public boolean targetSwingStarted;
    /** The target's attack speed with its held item, attacks per second (4.0 = bare hand, 1.6 = sword). */
    public double targetAttackSpeed = 4.0;
    /** The target's hurt animation ticks left (10 right after a hit, 0 = a new hit deals full damage again). */
    public int targetHurtTime;
    public boolean targetBlocking, lineOfSight, crosshairOnTarget;
    /** Target health plus absorption. */
    public float targetHealth = 20f;

    // ---- hit choice and gear read (filled by the world sampler; the defaults are an even sword-vs-sword fight) -------
    /** The local player's attack speed attribute (attacks per second; 1.6 = sword). */
    public double ownAttackSpeed = 1.6;
    /** The local player's attack damage attribute with the held item (7 = diamond sword). */
    public double ownAttackDamage = 7.0;
    /** The local player's armour and armour toughness attributes. */
    public double ownArmor, ownToughness;
    /** The main-hand item can sweep (NeoForge {@code ItemAbilities.SWORD_SWEEP}: swords). */
    public boolean sweepWeapon;
    /** The target's synced armour and toughness (players and mobs both sync these attributes). */
    public double targetArmor, targetToughness;
    /** The target's estimated melee damage per hit before armour (base plus held-item modifiers). */
    public double targetAttackDamage = 7.0;
    /** The target is eating or drinking (using an item that is not a raised shield). */
    public boolean targetUsingItem;
    /** The target is a mob whose hits launch (iron golem, ravager, hoglin, zoglin, warden). */
    public boolean targetHeavyHitter;
    /**
     * Living entities other than the target and the player inside the vanilla sweep box (the target's box grown by
     * 1 / 0.25 / 1, within reach of the player), refreshed every few ticks: hostiles, and everything a sweep must
     * never touch (players, pets, villagers, neutral and passive mobs).
     */
    public int sweepHostiles, sweepProtected;

    // ---- movement model for the body sims (filled only when wantMovementModel is set before sampling) ----------
    /** Input: the "Exact world shapes" setting is on, so seed the simulated bodies with the real movement state. */
    public boolean wantMovementModel;
    /**
     * MOVEMENT_SPEED attribute without the sprint modifier (Speed and Slowness included), Jump Boost velocity
     * ({@code getJumpBoostPower}) and the sprint flag, for us and for the target. The target's stay at the plain
     * values (0.1, 0, false) for a mob: the body sims model players. Plain values whenever wantMovementModel is off.
     */
    public double selfSpeed = 0.1, targetSpeed = 0.1;
    public double selfJumpBoost, targetJumpBoost;
    public boolean selfBodySprinting, targetBodySprinting;

    // ---- hazards (relative to the player->target direction) -------------------------------------------------------
    public boolean hazardFront, hazardBack, hazardLeft, hazardRight;

    // ---- terrain for tactics (filled only when wantTerrain is set before sampling)
    /** Input: the caller wants the terrain probes below (they cost a few dozen block lookups). */
    public boolean wantTerrain;
    public boolean targetOnGround = true;
    /**
     * Horizontal distance from the player to the near edge of a drop on the straight line toward the target, or -1
     * when the line has no drop with ground beyond it before the target.
     */
    public double gapNear = -1;
    /** Horizontal distance from the player to the far edge of that drop, or -1. */
    public double gapFar = -1;
    /** Open blocks below the target's feet (capped; {@link #DEEP} for lava). Large while it jumps over a pit. */
    public int targetDropBelow;
    /** Unit horizontal direction from the target toward the deepest drop within two blocks of it. */
    public double knockX, knockZ;
    /** Depth of that drop (0 = none nearby, {@link #DEEP} = lava or the void). */
    public int knockDepth;
    /**
     * Our own edge danger: depth of the first drop within four blocks along the push a hit from the target gives us
     * (from the target through us; 0 = none, {@link #DEEP} = lava or the void), and the unit direction to it.
     */
    public int selfKnockDepth;
    public double selfKnockX, selfKnockZ;

    // ---- arena read (filled by the assistant from combat.tactics.ArenaField when knock vectoring is on) ------------
    /**
     * 0..1 value of pushing the target along ({@link #knockFieldX}, {@link #knockFieldZ}); 0 = nothing worth steering
     * toward. Independent of the probe-based {@link #knockDepth} above.
     */
    public double knockValue;
    /** Unit horizontal direction from the target toward the feature (lava, drop, cobweb, wall). */
    public double knockFieldX, knockFieldZ;
    /** {@code ArenaField.KNOCK_*} kind of that feature (0 = none). */
    public int knockKind;
    /** Open blocks above the player's floor from the arena read (4 = open sky or unknown; 2 = no room to jump). */
    public int headroom = 4;

    /** Depth value used for lava, the void and very deep drops. */
    public static final int DEEP = 64;

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

    /**
     * The vanilla critical-hit condition of the local player ({@code Player.attack}, 1.21.1): airborne, falling
     * ({@code fallDistance > 0}), not climbing, not in water and <b>not sprinting</b> (a sprint hit is never critical).
     * Blindness and riding are not sampled. Charge above 0.9 is checked by the caller.
     */
    public boolean fallingForCrit() {
        return !onGround && fallDistance > 0f && !inWater && !climbing && !sprinting;
    }

    /** Horizontal speed of the local player, blocks per tick. */
    public double horizontalSpeed() {
        return Math.sqrt(pvx * pvx + pvz * pvz);
    }

    public float effectiveHealth() {
        return health + absorption;
    }
}
