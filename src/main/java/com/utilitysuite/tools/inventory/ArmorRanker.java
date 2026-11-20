package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.IContainerClicker.Click;

/**
 * Auto-armor ranking: per armor slot, the highest-scoring piece in the inventory that beats what is equipped.
 * Pure Java, allocation free.
 * <p>
 * Never touches a slot holding something that is not armor (an elytra stays on for flying, a carved pumpkin stays on
 * for endermen) or a piece that cannot be removed (curse of binding), and never equips a piece the adapter marked with
 * a negative score (binding curse on the candidate, nearly broken).
 */
public final class ArmorRanker {

    private ArmorRanker() {
    }

    /**
     * Ranking score: armor points dominate, then toughness, then Protection level.
     * Netherite (8 def, 3 tough) beats plain diamond (8, 2); diamond with Protection IV beats plain netherite.
     */
    public static int score(int defense, float toughness, int protectionLevel) {
        return defense * 100 + Math.round(toughness * 10f) * 4 + protectionLevel * 15;
    }

    /**
     * Best inventory slot for an armor slot, or -1 when nothing beats the equipped piece.
     *
     * @param armorIndex 0 feet, 1 legs, 2 chest, 3 head
     */
    public static int bestFor(InventoryModel inv, int armorIndex) {
        if (inv.equippedLocked[armorIndex]) {
            return -1;
        }
        boolean occupied = inv.equippedCount[armorIndex] > 0;
        if (occupied && !inv.equippedIsArmor[armorIndex]) {
            return -1;
        }
        int floor = occupied ? inv.equippedScore[armorIndex] : Integer.MIN_VALUE;
        int best = -1;
        int bestScore = floor;
        for (int s = 0; s < InventoryModel.SLOTS; s++) {
            if (inv.count[s] <= 0 || inv.armorType[s] != armorIndex) {
                continue;
            }
            int sc = inv.armorScore[s];
            if (sc >= 0 && sc > bestScore) {
                best = s;
                bestScore = sc;
            }
        }
        return best;
    }

    /**
     * Plans equipping every upgrade (player inventory menu). Empty armor slot: one {@code QUICK_MOVE}. Occupied: pick
     * up the new piece, click the armor slot (swaps), put the old piece where the new one was.
     *
     * @return number of pieces planned
     */
    public static int plan(InventoryModel inv, ClickPlan out) {
        int planned = 0;
        for (int a = 0; a < InventoryModel.ARMOR_SLOTS; a++) {
            int s = bestFor(inv, a);
            if (s < 0) {
                continue;
            }
            int menuSlot = InventoryModel.inventoryMenuSlot(s);
            if (inv.equippedCount[a] <= 0) {
                if (!out.add(menuSlot, 0, Click.QUICK_MOVE)) {
                    break;
                }
            } else {
                if (!out.hasRoom(3)) {
                    break;
                }
                out.add(menuSlot, 0, Click.PICKUP);
                out.add(InventoryModel.armorMenuSlot(a), 0, Click.PICKUP);
                out.add(menuSlot, 0, Click.PICKUP);
            }
            planned++;
        }
        return planned;
    }

    /** True when at least one slot has an upgrade (cheap pre-check before the hostile scan). */
    public static boolean hasUpgrade(InventoryModel inv) {
        for (int a = 0; a < InventoryModel.ARMOR_SLOTS; a++) {
            if (bestFor(inv, a) >= 0) {
                return true;
            }
        }
        return false;
    }
}
