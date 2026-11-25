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
        BarterInputs in = new BarterInputs(Math.max(1, piglins.size()), Math.max(1, drops.size()));
        for (int i = 0; i < piglins.size(); i++) {
            Piglin p = piglins.get(i);
            in.addPiglin(p.entityId(), p.distance(), p.adult(), p.angry(), p.admiring());
        }
        for (int i = 0; i < drops.size(); i++) {
            Drop d = drops.get(i);
            in.addDrop(d.entityId(), d.distance(), isJunk(d.itemId(), pinnedJunk));
        }
        MutableDecision out = new MutableDecision();
        barter(ingotsInHand, in, ticksSinceGive, cadenceTicks, collectRange, out);
        return switch (out.act) {
            case NONE -> Decision.NONE;
            case WAIT -> Decision.WAIT;
            default -> new Decision(out.act, out.targetId);
        };
    }

    /**
     * Reused, primitive decision inputs for the allocation-free {@link #barter(int, BarterInputs, int, int, double,
     * MutableDecision)}: the client clears and refills one instance at the barter cadence. Entries past the capacity
     * are dropped (the caller's own scan is capped the same way). Single thread.
     */
    public static final class BarterInputs {
        final int[] piglinId;
        final double[] piglinDist;
        final boolean[] piglinAdult;
        final boolean[] piglinAngry;
        final boolean[] piglinAdmiring;
        int piglins;
        final int[] dropId;
        final double[] dropDist;
        final boolean[] dropJunk;
        int drops;

        public BarterInputs(int maxPiglins, int maxDrops) {
            piglinId = new int[maxPiglins];
            piglinDist = new double[maxPiglins];
            piglinAdult = new boolean[maxPiglins];
            piglinAngry = new boolean[maxPiglins];
            piglinAdmiring = new boolean[maxPiglins];
            dropId = new int[maxDrops];
            dropDist = new double[maxDrops];
            dropJunk = new boolean[maxDrops];
        }

        public void clear() {
            piglins = 0;
            drops = 0;
        }

        /** @return false when full (the piglin is ignored) */
        public boolean addPiglin(int entityId, double distance, boolean adult, boolean angry, boolean admiring) {
            int i = piglins;
            if (i >= piglinId.length) {
                return false;
            }
            piglinId[i] = entityId;
            piglinDist[i] = distance;
            piglinAdult[i] = adult;
            piglinAngry[i] = angry;
            piglinAdmiring[i] = admiring;
            piglins = i + 1;
            return true;
        }

        /** @param junk {@link #isJunk} for the drop's item (the client caches it per item type) */
        public boolean addDrop(int entityId, double distance, boolean junk) {
            int i = drops;
            if (i >= dropId.length) {
                return false;
            }
            dropId[i] = entityId;
            dropDist[i] = distance;
            dropJunk[i] = junk;
            drops = i + 1;
            return true;
        }

        public int piglinCount() {
            return piglins;
        }

        public int dropCount() {
            return drops;
        }
    }

    /** Reused result holder of the allocation-free barter step. */
    public static final class MutableDecision {
        public Act act = Act.NONE;
        /** Piglin to give/toss to, or the item entity to walk to; -1 for none. */
        public int targetId = -1;

        void set(Act a, int id) {
            act = a;
            targetId = id;
        }
    }

    /**
     * Allocation-free bartering loop step (same rules as the list-based {@link #barter(int, List, List, int, int,
     * double, Set)}, which delegates here): writes the decision into {@code out} and returns its act.
     */
    public static Act barter(int ingotsInHand, BarterInputs in, int ticksSinceGive, int cadenceTicks,
                             double collectRange, MutableDecision out) {
        int n = in.piglins;
        for (int i = 0; i < n; i++) {
            if (in.piglinAngry[i] && in.piglinDist[i] <= AGGRO_RANGE) {
                out.set(Act.NONE, -1); // never barter next to a hostile piglin
                return Act.NONE;
            }
        }
        int drop = -1;
        for (int i = 0; i < in.drops; i++) {
            if (in.dropDist[i] > collectRange || in.dropJunk[i]) {
                continue;
            }
            if (drop < 0 || in.dropDist[i] < in.dropDist[drop]) {
                drop = i;
            }
        }
        if (ingotsInHand > 0 && ticksSinceGive >= cadenceTicks) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!in.piglinAdult[i] || in.piglinAngry[i] || in.piglinAdmiring[i] || in.piglinDist[i] > TOSS_RANGE) {
                    continue;
                }
                if (best < 0 || in.piglinDist[i] < in.piglinDist[best]) {
                    best = i;
                }
            }
            if (best >= 0) {
                Act a = in.piglinDist[best] <= GIVE_RANGE ? Act.GIVE : Act.TOSS;
                out.set(a, in.piglinId[best]);
                return a;
            }
        }
        if (drop >= 0) {
            out.set(Act.COLLECT, in.dropId[drop]);
            return Act.COLLECT;
        }
        for (int i = 0; i < n; i++) {
            if (in.piglinAdmiring[i]) {
                out.set(Act.WAIT, -1); // loot is coming
                return Act.WAIT;
            }
        }
        Act a = ingotsInHand > 0 ? Act.WAIT : Act.NONE;
        out.set(a, -1);
        return a;
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
