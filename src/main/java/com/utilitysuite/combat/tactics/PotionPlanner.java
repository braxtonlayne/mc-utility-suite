package com.utilitysuite.combat.tactics;

/**
 * Combat potion decision (engine free, allocation free). Each tick the caller fills the public input fields and calls
 * {@link #step}; the answer says whether to drink a hotbar healing/regeneration potion, aim at the feet for a splash, or
 * throw the splash. The aim itself is only a requested pitch ({@link #FEET_PITCH}): the caller hands it to the core's
 * InputSmoother, which smooths and speed-caps every rotation (no head snapping), and the throw is released only once the
 * camera has actually arrived ({@link #THROW_MIN_PITCH}).
 * <p>
 * Rules:
 * <ul>
 *   <li>Heal (instant health) below {@code healHealth}; regeneration below {@code regenHealth} when not already
 *       regenerating. Absorption counts toward health.</li>
 *   <li>Splash beats drinking while being chased (a 32-tick drink is a free combo for the chaser) and when no drinkable
 *       potion is in the hotbar; drinking is used when standing free and a drinkable one is there.</li>
 *   <li>Post-pop pressure: for {@code pressureTicks} after the opponent pops a totem, do not stop to heal (it is at its
 *       weakest and its next totem is not in hand yet) unless health is at or below {@code criticalHealth}.
 *       {@link #pressureHoldsRetreat} lets the sustain retreat use the same rule.</li>
 * </ul>
 */
public final class PotionPlanner {

    public enum Action { NONE, DRINK, AIM_FEET, THROW_SPLASH }

    /** What the potion would fix. */
    public enum Need { NONE, HEAL, REGEN }

    /** Requested pitch for a splash at the feet (straight down is 90). */
    public static final float FEET_PITCH = 88f;
    /** The throw is released only when the smoothed camera is at least this far down. */
    public static final float THROW_MIN_PITCH = 80f;
    /** Aim attempts give up after this many ticks (the user took the camera, or the smoother is slowed). */
    public static final int AIM_TIMEOUT = 12;
    /** Ticks after a throw or drink before another potion decision (the potion lands, health updates). */
    public static final int POTION_COOLDOWN = 20;

    // ---- settings (copied from CombatSettings by the caller) ----
    public boolean enabled;
    public float healHealth = 8f;
    public float regenHealth = 12f;
    public float criticalHealth = 6f;
    public int pressureTicks = 100;
    public boolean preferSplashWhenChased = true;

    // ---- per-tick inputs ----
    public float health;
    public float absorption;
    public boolean regenerating;
    /** Hotbar slots, -1 when absent. */
    public int drinkHealSlot = -1, drinkRegenSlot = -1, splashHealSlot = -1, splashRegenSlot = -1;
    public boolean beingChased;
    public boolean onGround = true;
    public boolean inWater;
    public boolean usingItem;
    public boolean screenOpen;
    /** The user is moving the camera or pressing keys themselves (manual input wins). */
    public boolean manualOverride;
    public float pitch;

    // ---- outputs ----
    private int slot = -1;
    private Need need = Need.NONE;
    private boolean splash;

    // ---- state ----
    private int aimTicks = -1;
    private int cooldown;
    private long lastPopTick = Long.MIN_VALUE / 2;

    /** The opponent popped a totem on this tick (from the existing pop detection). */
    public void onOpponentPop(long tick) {
        lastPopTick = tick;
    }

    /** True while the post-pop pressure window is open. */
    public boolean pressureActive(long tick) {
        return tick - lastPopTick >= 0 && tick - lastPopTick < pressureTicks;
    }

    /**
     * Post-pop pressure for the sustain retreat: true when a retreat to heal should be skipped because the opponent
     * just popped and health is still above the critical threshold.
     */
    public static boolean pressureHoldsRetreat(boolean pressureActive, float effectiveHealth, float criticalHealth) {
        return pressureActive && effectiveHealth > criticalHealth;
    }

    public Action step(long tick) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (!enabled || manualOverride || screenOpen || usingItem) {
            aimTicks = -1;
            return Action.NONE;
        }
        if (aimTicks >= 0) {
            // an aim is in progress: throw once the smoothed camera got there, give up on timeout
            if (slot < 0 || ++aimTicks > AIM_TIMEOUT) {
                aimTicks = -1;
                cooldown = POTION_COOLDOWN;
                return Action.NONE;
            }
            if (pitch >= THROW_MIN_PITCH) {
                aimTicks = -1;
                cooldown = POTION_COOLDOWN;
                return Action.THROW_SPLASH;
            }
            return Action.AIM_FEET;
        }
        if (cooldown > 0) {
            return Action.NONE;
        }
        need = need(health + absorption, regenerating, healHealth, regenHealth);
        if (need == Need.NONE) {
            return Action.NONE;
        }
        float eff = health + absorption;
        if (pressureHoldsRetreat(pressureActive(tick), eff, criticalHealth)) {
            return Action.NONE;
        }
        int drink = need == Need.HEAL ? drinkHealSlot : drinkRegenSlot;
        int thrown = need == Need.HEAL ? splashHealSlot : splashRegenSlot;
        if (drink < 0 && thrown < 0 && need == Need.HEAL) {
            // no instant health: regeneration still helps
            drink = regenerating ? -1 : drinkRegenSlot;
            thrown = regenerating ? -1 : splashRegenSlot;
        }
        boolean useSplash = thrown >= 0 && (drink < 0 || (preferSplashWhenChased && beingChased));
        if (useSplash) {
            if (inWater) {
                return Action.NONE; // the bottle bursts above the water surface far from the feet
            }
            slot = thrown;
            splash = true;
            aimTicks = 0;
            return pitch >= THROW_MIN_PITCH ? throwNow() : Action.AIM_FEET;
        }
        if (drink >= 0) {
            slot = drink;
            splash = false;
            cooldown = POTION_COOLDOWN;
            return Action.DRINK;
        }
        return Action.NONE;
    }

    private Action throwNow() {
        aimTicks = -1;
        cooldown = POTION_COOLDOWN;
        return Action.THROW_SPLASH;
    }

    /** Which potion effect the current health and effects call for. */
    public static Need need(float effectiveHealth, boolean regenerating, float healHealth, float regenHealth) {
        if (effectiveHealth <= healHealth) {
            return Need.HEAL;
        }
        if (!regenerating && effectiveHealth <= regenHealth) {
            return Need.REGEN;
        }
        return Need.NONE;
    }

    /** Hotbar slot of the potion chosen by the last DRINK / AIM_FEET / THROW_SPLASH. */
    public int slot() {
        return slot;
    }

    public boolean splash() {
        return splash;
    }

    public Need lastNeed() {
        return need;
    }

    /** True while an aim at the feet is in progress (the camera is requested by this planner). */
    public boolean aiming() {
        return aimTicks >= 0;
    }

    public void reset() {
        aimTicks = -1;
        cooldown = 0;
        slot = -1;
        need = Need.NONE;
        lastPopTick = Long.MIN_VALUE / 2;
    }
}
