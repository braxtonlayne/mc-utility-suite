package com.utilitysuite.combat.assistant;

/**
 * Engine-agnostic decisions for the extra sustain behaviours: which consumable to pull from the main inventory and
 * into which hotbar slot, when to refill the offhand totem, and when to jump-reset a knockback hit. Pure and
 * allocation free; the Minecraft side classifies items into the {@code KIND_*} codes.
 */
public final class SustainExtras {

    public static final int KIND_NONE = 0;
    public static final int KIND_REGEN_POTION = 1;
    public static final int KIND_HEALING_POTION = 2;
    public static final int KIND_GOLDEN_APPLE = 3;
    public static final int KIND_ENCHANTED_GOLDEN_APPLE = 4;

    /** Ticks between cheap offhand-totem checks. */
    public static final int TOTEM_CHECK_INTERVAL = 4;
    /** Ticks to wait after a refill click before trying again. */
    public static final int TOTEM_REFILL_COOLDOWN = 10;

    private SustainExtras() {
    }

    /** Preference of a consumable kind: higher is better, 0 means "not a sustain item". */
    public static int rank(int kind) {
        return kind < KIND_NONE || kind > KIND_ENCHANTED_GOLDEN_APPLE ? 0 : kind;
    }

    /** True when {@code candidate} should replace the current best ({@code bestKind}); ties keep the first found. */
    public static boolean better(int candidate, int bestKind) {
        return rank(candidate) > rank(bestKind);
    }

    /**
     * Hotbar slot (0..8) that receives a pulled item: the first empty one, otherwise slot 8 (or 7 when 8 is the
     * weapon) so the weapon slot is never displaced.
     */
    public static int hotbarSlotForPull(int emptyHotbarSlot, int weaponSlot) {
        if (emptyHotbarSlot >= 0 && emptyHotbarSlot < 9 && emptyHotbarSlot != weaponSlot) {
            return emptyHotbarSlot;
        }
        return weaponSlot == 8 ? 7 : 8;
    }

    /** Whether a main-inventory consumable should be pulled into the hotbar now. */
    public static boolean shouldPull(boolean wholeInventory, boolean allowedHere, int hotbarConsumableSlot,
                                     int inventoryConsumableSlot, boolean pullInFlight, boolean screenOpen) {
        return wholeInventory && allowedHere && hotbarConsumableSlot < 0 && inventoryConsumableSlot >= 9
                && inventoryConsumableSlot < 36 && !pullInFlight && !screenOpen;
    }

    /** Ticks after the engagement in which a consumable is staged into the hotbar (pros stage before they need it). */
    public static final int STAGE_WINDOW_TICKS = 40;

    /**
     * Staged variant of {@link #shouldPull}: in the first {@value #STAGE_WINDOW_TICKS} ticks of an engagement, pull a
     * main-inventory consumable whatever the health, so the heal is one hotbar switch away when the window comes.
     * Not while eating, and only once per engagement ({@code stagedAlready}).
     */
    public static boolean shouldStagePull(boolean wholeInventory, boolean allowedHere, int hotbarConsumableSlot,
                                          int inventoryConsumableSlot, boolean pullInFlight, boolean screenOpen,
                                          int ticksEngaged, boolean eating, boolean stagedAlready) {
        return !stagedAlready && !eating && ticksEngaged >= 0 && ticksEngaged < STAGE_WINDOW_TICKS
                && shouldPull(wholeInventory, allowedHere, hotbarConsumableSlot, inventoryConsumableSlot,
                pullInFlight, screenOpen);
    }

    /**
     * Whether to move a totem into the offhand now: the module is armed with the setting on, the server is allowed,
     * the offhand lacks a totem, one exists in the inventory (index 0..35), no screen is open, the user is not
     * working the hotbar and no refill is cooling down.
     */
    public static boolean shouldReplenishTotem(boolean armed, boolean keepTotem, boolean allowedHere,
                                               boolean offhandTotem, int totemInvSlot, boolean screenOpen,
                                               boolean manualHotbar, int cooldown) {
        return armed && keepTotem && allowedHere && !offhandTotem && totemInvSlot >= 0 && totemInvSlot < 36
                && !screenOpen && !manualHotbar && cooldown <= 0;
    }

    /** Whether to tap JUMP for a knockback hit: setting on, allowed, engaged, on the ground and not eating. */
    public static boolean shouldJumpReset(boolean jumpReset, boolean allowedHere, boolean engaged,
                                          boolean knockbackHit, boolean onGround, boolean eating) {
        return jumpReset && allowedHere && engaged && knockbackHit && onGround && !eating;
    }
}
