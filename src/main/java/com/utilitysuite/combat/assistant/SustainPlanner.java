package com.utilitysuite.combat.assistant;

/**
 * Emergency sustain decision, shared by the assistant and the coach. Order: a totem goes into the offhand when lethal
 * damage is imminent (effective health at or below the larger of {@value #LETHAL_FLOOR} and 110% of the last hit);
 * below the low-health threshold a golden apple is eaten when one is in the hotbar, otherwise the player backs off.
 * Pure and allocation free.
 */
public final class SustainPlanner {

    public enum Action { NONE, EQUIP_TOTEM, EAT, RETREAT }

    public static final float LETHAL_FLOOR = 4f;
    /** Health above the threshold needed to stop retreating (hysteresis). */
    public static final float RECOVER_MARGIN = 4f;

    private SustainPlanner() {
    }

    public static boolean lethalImminent(float effectiveHealth, float lastHitDamage) {
        return effectiveHealth <= Math.max(LETHAL_FLOOR, lastHitDamage * 1.1f);
    }

    public static Action decide(CombatSnapshot s, float lowThreshold, float lastHitDamage, boolean totems,
                                boolean gapples) {
        float eff = s.effectiveHealth();
        if (totems && !s.offhandTotem && s.totemInvSlot >= 0 && lethalImminent(eff, lastHitDamage)) {
            return Action.EQUIP_TOTEM;
        }
        if (eff <= lowThreshold) {
            if (gapples && s.gappleSlot >= 0 && !s.gappleOnCooldown) {
                return Action.EAT;
            }
            return Action.RETREAT;
        }
        return Action.NONE;
    }

    /**
     * {@link #decide} with the post-totem-pop pressure rule: while {@code pressureHold} (the opponent just popped and
     * health is above the critical threshold, see {@code PotionPlanner.pressureHoldsRetreat}) the player neither backs
     * off nor stops to eat; the lethal-damage totem still goes into the offhand.
     */
    public static Action decide(CombatSnapshot s, float lowThreshold, float lastHitDamage, boolean totems,
                                boolean gapples, boolean pressureHold) {
        Action a = decide(s, lowThreshold, lastHitDamage, totems, gapples);
        if (pressureHold && (a == Action.EAT || a == Action.RETREAT)) {
            return Action.NONE;
        }
        return a;
    }

    /** Player-inventory menu slot for an inventory index (hotbar 0..8 -> 36..44, main 9..35 unchanged). */
    public static int menuSlotOf(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }
}
