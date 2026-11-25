package com.utilitysuite.combat.assistant.stages;

/**
 * Facts the healing stage needs beyond {@link com.utilitysuite.combat.assistant.CombatSnapshot}: hunger and the best
 * plain food in the hotbar, plus counts kept for later features (splash healing, staging). Implemented by
 * {@code McHealFacts} in game and by a fake in tests. {@link #refresh} is called by the stage at most every few
 * ticks; the getters return the cached values. Client thread only, allocation free.
 */
public interface HealFacts {

    /** Re-reads the facts (a hotbar and inventory scan; budgeted by the caller). */
    void refresh();

    /** Hunger 0..20. */
    int hunger();

    /** Saturation 0..20. */
    float saturation();

    /** Hotbar index 0..8 of the plain food that wastes least at the current hunger (golden apples excluded), or -1. */
    int bestFoodHotbarSlot();

    int bestFoodNutrition();

    float bestFoodSaturation();

    /** Ticks to eat the best food (32 for most food, 16 for dried kelp). */
    int bestFoodEatTicks();

    /** Hotbar index of a splash potion of healing, or -1. */
    int splashHealingSlot();

    // ---- combat potions ({@link PotionStage}); defaults keep older test doubles valid -----------------------------

    /** Hotbar index of a drinkable potion of healing, or -1. */
    default int drinkHealingSlot() {
        return -1;
    }

    /** Hotbar index of a drinkable potion of regeneration, or -1. */
    default int drinkRegenSlot() {
        return -1;
    }

    /** Hotbar index of a splash potion of regeneration, or -1. */
    default int splashRegenSlot() {
        return -1;
    }

    /** The player has the regeneration effect now. */
    default boolean regenerating() {
        return false;
    }

    /** Golden apples (both kinds) in the whole inventory. */
    int goldenAppleCount();
}
