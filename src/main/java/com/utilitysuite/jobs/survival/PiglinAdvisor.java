package com.utilitysuite.jobs.survival;

import java.util.List;
import java.util.Set;

/**
 * Engine-free decisions for the piglin helpers (roadmap I4): the opt-in bartering loop and the gold-safety guard.
 * The client side turns the world into these records once per decision (a few times a second, not per frame) and
 * performs the result through the core (toss key / use under a lease, container clicks via IContainerClicker).
 */
public final class PiglinAdvisor {

    /** Vanilla: piglins notice the player (and get angry without gold) within 16 blocks. */
    public static final double AGGRO_RANGE = 16.0;
    /** Bartering hand-off: use the ingot on a piglin within reach, else toss it within this range. */
    public static final double GIVE_RANGE = 3.0;
    public static final double TOSS_RANGE = 6.0;
    /** {@code PiglinAi.ADMIRE_DURATION}: a piglin holds the ingot this long before dropping loot. */
    public static final int ADMIRE_TICKS = 119;

    public static final Set<String> GOLD_ARMOUR = Set.of("minecraft:golden_helmet", "minecraft:golden_chestplate",
            "minecraft:golden_leggings", "minecraft:golden_boots");

    /** Barter loot not worth picking up by default (on top of {@link InventoryPressurePolicy#DEFAULT_JUNK}). */
    public static final Set<String> BARTER_JUNK = Set.of("minecraft:gravel", "minecraft:soul_sand",
            "minecraft:nether_brick", "minecraft:blackstone", "minecraft:leather", "minecraft:spectral_arrow",
            "minecraft:crying_obsidian");

    /** Blocks whose presence nearby marks a bastion (opening containers there angers piglins). */
    public static final Set<String> BASTION_MARKERS = Set.of("minecraft:gold_block", "minecraft:gilded_blackstone");

    private PiglinAdvisor() {
    }

    /** One visible piglin. {@code admiring} = holding an item in its off hand to admire (busy bartering). */
    public record Piglin(int entityId, double distance, boolean adult, boolean angry, boolean admiring) {
    }

    /** One dropped item entity. */
    public record Drop(int entityId, String itemId, double distance) {
    }

    public enum Act { NONE, GIVE, TOSS, COLLECT, WAIT }

    /** {@code targetId} = piglin to give/toss to, or the item entity to walk to. */
    public record Decision(Act act, int targetId) {
        public static final Decision NONE = new Decision(Act.NONE, -1);
        public static final Decision WAIT = new Decision(Act.WAIT, -1);
    }

    /**
     * Bartering loop step.
     *
     * @param ingotsInHand   gold ingots in the main hand (0 = stop)
     * @param ticksSinceGive ticks since the last give/toss (cadence gate)
     * @param cadenceTicks   minimum ticks between gives
     * @param collectRange   pick up worthwhile drops within this distance
     * @param pinnedJunk     extra item ids the user marked as junk (may be empty)
     */
    public static Decision barter(int ingotsInHand, List<Piglin> piglins, List<Drop> drops, int ticksSinceGive,
                                  int cadenceTicks, double collectRange, Set<String> pinnedJunk) {
        for (int i = 0; i < piglins.size(); i++) {
            Piglin p = piglins.get(i);
            if (p.angry() && p.distance() <= AGGRO_RANGE) {
                return Decision.NONE; // never barter next to a hostile piglin
            }
        }
        Drop drop = bestDrop(drops, collectRange, pinnedJunk);
        if (ingotsInHand > 0 && ticksSinceGive >= cadenceTicks) {
            Piglin best = null;
            for (int i = 0; i < piglins.size(); i++) {
                Piglin p = piglins.get(i);
                if (!p.adult() || p.angry() || p.admiring() || p.distance() > TOSS_RANGE) {
                    continue;
                }
                if (best == null || p.distance() < best.distance()) {
                    best = p;
                }
            }
            if (best != null) {
                return new Decision(best.distance() <= GIVE_RANGE ? Act.GIVE : Act.TOSS, best.entityId());
            }
        }
        if (drop != null) {
            return new Decision(Act.COLLECT, drop.entityId());
        }
        for (int i = 0; i < piglins.size(); i++) {
            if (piglins.get(i).admiring()) {
                return Decision.WAIT; // loot is coming
            }
        }
        return ingotsInHand > 0 ? Decision.WAIT : Decision.NONE;
    }

    /**
     * Allocation-free give/toss step for the tick path (same rules as {@link #barter} without drop collection).
     *
     * @param nearestIdleAdult distance to the nearest adult, calm piglin that is not admiring (+inf when none)
     * @return GIVE / TOSS (to that piglin), WAIT, or NONE (stop: no ingots or a hostile piglin in range)
     */
    public static Act giveStep(int ingotsInHand, boolean hostileInRange, double nearestIdleAdult, int ticksSinceGive,
                               int cadenceTicks) {
        if (hostileInRange || ingotsInHand <= 0) {
            return Act.NONE;
        }
        if (ticksSinceGive < cadenceTicks || nearestIdleAdult > TOSS_RANGE) {
            return Act.WAIT;
        }
        return nearestIdleAdult <= GIVE_RANGE ? Act.GIVE : Act.TOSS;
    }

    public static boolean isJunk(String itemId, Set<String> pinnedJunk) {
        if (InventoryPressurePolicy.VALUABLES.contains(itemId)) {
            return false;
        }
        return BARTER_JUNK.contains(itemId) || InventoryPressurePolicy.DEFAULT_JUNK.contains(itemId)
                || pinnedJunk.contains(itemId);
    }

    private static Drop bestDrop(List<Drop> drops, double range, Set<String> pinnedJunk) {
        Drop best = null;
        for (int i = 0; i < drops.size(); i++) {
            Drop d = drops.get(i);
            if (d.distance() > range || isJunk(d.itemId(), pinnedJunk)) {
                continue;
            }
            if (best == null || d.distance() < best.distance()) {
                best = d;
            }
        }
        return best;
    }

    // --- Gold safety guard -----------------------------------------------------------------------------------

    public enum Guard { SAFE, WARN_NO_GOLD, EQUIP_GOLD, WARN_CONTAINER }

    /**
     * Guard verdict.
     *
     * @param wornArmour         ids of the four worn armour pieces (empty string for none), any order
     * @param nearestPiglin      distance to the nearest adult piglin (or piglin brute), +inf when none
     * @param goldInInventory    true when a gold armour piece is in the inventory
     * @param autoEquip          the setting that allows equipping one
     * @param openingContainer   the player is about to open a container
     * @param bastionMarkersNear gold blocks / gilded blackstone counted near the container
     */
    public static Guard guard(List<String> wornArmour, double nearestPiglin, boolean goldInInventory,
                              boolean autoEquip, boolean openingContainer, int bastionMarkersNear) {
        if (openingContainer && (bastionMarkersNear > 0 || nearestPiglin <= AGGRO_RANGE)) {
            return Guard.WARN_CONTAINER;
        }
        if (nearestPiglin > AGGRO_RANGE || wearsGold(wornArmour)) {
            return Guard.SAFE;
        }
        return autoEquip && goldInInventory ? Guard.EQUIP_GOLD : Guard.WARN_NO_GOLD;
    }

    public static boolean wearsGold(List<String> wornArmour) {
        for (int i = 0; i < wornArmour.size(); i++) {
            if (GOLD_ARMOUR.contains(wornArmour.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which gold piece to equip: prefer one whose armour slot is empty (no downgrade), else boots then helmet (cheapest
     * protection loss). Arrays are indexed boots=0, leggings=1, chestplate=2, helmet=3.
     *
     * @param slotEmpty      worn slot is empty
     * @param goldAvailable  a gold piece for that slot is in the inventory
     * @param allowReplace   allow swapping out a worn non-gold piece
     * @return armour slot index 0..3, or -1 for none
     */
    public static int pickGoldSlot(boolean[] slotEmpty, boolean[] goldAvailable, boolean allowReplace) {
        for (int s = 0; s < 4; s++) {
            if (slotEmpty[s] && goldAvailable[s]) {
                return s;
            }
        }
        if (!allowReplace) {
            return -1;
        }
        if (goldAvailable[0]) {
            return 0;
        }
        if (goldAvailable[3]) {
            return 3;
        }
        for (int s = 1; s < 3; s++) {
            if (goldAvailable[s]) {
                return s;
            }
        }
        return -1;
    }
}
