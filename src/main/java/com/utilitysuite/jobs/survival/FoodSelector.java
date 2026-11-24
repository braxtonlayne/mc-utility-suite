package com.utilitysuite.jobs.survival;

import java.util.Set;

/** Auto-Eat decisions: when to eat and which safe food wastes the least. Allocation-free. */
public final class FoodSelector {

    /**
     * Foods with harmful side effects (or too valuable to eat for hunger) that are never auto-eaten. Golden apples are
     * the one exception: the urgent {@link #choose(String[], int[], float[], int, boolean, boolean, boolean)} may pick
     * them when the "eat golden apples in danger" setting is on.
     */
    public static final Set<String> UNSAFE = Set.of("minecraft:rotten_flesh", "minecraft:spider_eye",
            "minecraft:poisonous_potato", "minecraft:pufferfish", "minecraft:suspicious_stew",
            "minecraft:chorus_fruit", "minecraft:chicken", "minecraft:raw_chicken", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple");

    /** Hunger below this triggers a top-up before a long sprint leg (regeneration needs 18). */
    public static final int SPRINT_TOPUP_BELOW = 18;

    /** Slots 0-8 are the hotbar, 9-35 the main inventory. */
    public static final int HOTBAR = 9;

    /** Score nudge so that, between equal foods, the one already in the hotbar wins (no pull needed). */
    private static final double PULL_PENALTY = 0.01;

    private FoodSelector() {
    }

    /**
     * @param hunger    0..20
     * @param health    0..maxHealth
     * @param threshold eat when hunger is at or below this
     */
    public static boolean shouldEat(int hunger, float health, float maxHealth, int threshold, float healthThreshold) {
        if (hunger >= 20) {
            return false;
        }
        if (hunger <= threshold) {
            return true;
        }
        // regeneration needs hunger >= 18: top up when hurt
        return health < maxHealth * healthThreshold && hunger < 18;
    }

    /** As the five-argument form, also eating before a long sprint leg when hunger is below 18. */
    public static boolean shouldEat(int hunger, float health, float maxHealth, int threshold, float healthThreshold,
                                    boolean longLegAhead) {
        if (hunger >= 20) {
            return false;
        }
        return (longLegAhead && hunger < SPRINT_TOPUP_BELOW)
                || shouldEat(hunger, health, maxHealth, threshold, healthThreshold);
    }

    /** Hotbar-only choice, kept for callers that scan slots 0-8. */
    public static int choose(String[] ids, int[] nutrition, float[] saturation, int hunger) {
        return choose(ids, nutrition, saturation, hunger, false);
    }

    /**
     * Best slot or -1 over any number of slots (36 = hotbar then main inventory). Prefers food that does not
     * overfill, then higher saturation; {@code preferSaturation} (long jobs) weights saturation three times as much so
     * the food that lasts longest wins. Unsafe foods are skipped. Between equal scores a hotbar slot wins.
     *
     * @param ids        item id per slot (null for empty / non-food)
     * @param nutrition  nutrition per slot
     * @param saturation saturation per slot
     */
    public static int choose(String[] ids, int[] nutrition, float[] saturation, int hunger,
                             boolean preferSaturation) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            if (id == null || nutrition[i] <= 0 || UNSAFE.contains(id)) {
                continue;
            }
            double score = score(nutrition[i], saturation[i], hunger, preferSaturation)
                    - (i >= HOTBAR ? PULL_PENALTY : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Golden apples: in {@link #UNSAFE} (never eaten for hunger), allowed only in danger behind their own setting. */
    public static final String GOLDEN_APPLE = "minecraft:golden_apple";
    public static final String ENCHANTED_GOLDEN_APPLE = "minecraft:enchanted_golden_apple";

    /** Urgent-score bonus that puts golden apples above any ordinary food (Regeneration + Absorption beat food). */
    private static final double GOLDEN_BONUS = 1000.0;
    /** The enchanted apple ranks just under the plain one: it is the rarer item, so the plain one goes first. */
    private static final double ENCHANTED_BONUS = 900.0;

    /** True for the golden and enchanted golden apple. */
    public static boolean isGolden(String id) {
        return GOLDEN_APPLE.equals(id) || ENCHANTED_GOLDEN_APPLE.equals(id);
    }

    /**
     * Danger-aware choice. Not urgent: exactly {@link #choose(String[], int[], float[], int, boolean)}. Urgent (health
     * low or a threat active): the food that heals the most wins (nutrition plus saturation, overfill ignored: the
     * saturation still feeds fast regeneration), and with {@code allowGolden} the golden apple, then the enchanted
     * one, rank above everything. At full hunger only golden apples can be eaten, so only they are considered. Other
     * unsafe foods stay excluded. Between equal scores a hotbar slot wins. Allocation free.
     */
    public static int choose(String[] ids, int[] nutrition, float[] saturation, int hunger, boolean preferSaturation,
                             boolean urgent, boolean allowGolden) {
        if (!urgent) {
            return choose(ids, nutrition, saturation, hunger, preferSaturation);
        }
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            if (id == null || nutrition[i] <= 0) {
                continue;
            }
            boolean golden = isGolden(id);
            if (golden ? !allowGolden : (UNSAFE.contains(id) || hunger >= 20)) {
                continue;
            }
            double score = healScore(id, nutrition[i], saturation[i]) - (i >= HOTBAR ? PULL_PENALTY : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** How much one food heals when it matters: nutrition plus saturation, golden apples on top. */
    public static double healScore(String id, int nutrition, float saturation) {
        double base = nutrition + saturation;
        if (GOLDEN_APPLE.equals(id)) {
            return GOLDEN_BONUS + base;
        }
        if (ENCHANTED_GOLDEN_APPLE.equals(id)) {
            return ENCHANTED_BONUS + base;
        }
        return base;
    }

    /**
     * Whether a hazard forbids starting to eat: standing in lava, burning next to lava, or an explosion about to go
     * off. Eating roots the player for 1.6 s at walking-slow speed; getting out comes first.
     */
    public static boolean eatingBlocked(boolean inLava, boolean onFire, boolean lavaAdjacent, boolean explosion) {
        return inLava || (onFire && lavaAdjacent) || explosion;
    }

    /**
     * Score of one food at the current hunger: what lands in the hunger bar, weighted by saturation ({@code
     * preferSaturation} weights it three times as much), minus twice the overfill. Shared by {@link #choose} and the
     * fight's top-up in the combat healing stage.
     */
    public static double score(int nutrition, float saturation, int hunger, boolean preferSaturation) {
        int waste = Math.max(0, nutrition - (20 - hunger));
        int effective = nutrition - waste; // what actually lands in the hunger bar
        return effective * (1.0 + saturation * (preferSaturation ? 3.0 : 1.0)) - waste * 2.0;
    }

    /**
     * Hotbar slot that receives food pulled from the pack: an empty slot if there is one (nothing to restore), else
     * the selected slot.
     *
     * @param occupied hotbar slots (indices 0-8) that hold an item
     */
    public static int chooseSwapTarget(boolean[] occupied, int selected) {
        for (int i = 0; i < HOTBAR; i++) {
            if (!occupied[i]) {
                return i;
            }
        }
        return selected >= 0 && selected < HOTBAR ? selected : 0;
    }

    /** Pulling is allowed only with no screen open and no block break in progress. */
    public static boolean canPull(boolean screenOpen, boolean breakingBlock) {
        return !screenOpen && !breakingBlock;
    }

    /**
     * Whether the swap-back click is needed after eating: only when the food came from the pack and the setting is on.
     */
    public static boolean needsSwapBack(boolean pulled, boolean swapBackSetting) {
        return pulled && swapBackSetting;
    }

    /**
     * The pull / eat / swap-back cycle of food taken from the pack, as a small state machine that survives the lease
     * ending early (preempted, Stop-All, manual input). Once a pull lands it remembers the inventory slot, the hotbar
     * slot and what each held; when the lease ends before the swap-back, the restore stays owed and is done at the
     * next safe moment, but only while the slots still hold what the cycle left there. Engine-free (items are opaque
     * int keys, {@link #EMPTY} for an empty slot), allocation free.
     */
    public static final class SwapBack {

        /** Item key of an empty slot. */
        public static final int EMPTY = -1;

        public enum Step {
            /** Nothing owed. */
            NONE,
            /** Owed but not now (a screen is open, another module holds the container channel, clicks in flight). */
            WAIT,
            /** Click the SWAP of {@link #source()} with hotbar button {@link #hotbar()} now. */
            RESTORE,
            /** One of the stacks moved since the pull (the user, or the swap-back already landed): forget it. */
            DROP
        }

        private int source = -1;
        private int hotbar = -1;
        private int foodItem = EMPTY;
        private int displacedItem = EMPTY;
        private boolean owed;
        private boolean deferred;

        /**
         * The pull landed: {@code foodItem} moved from inventory index {@code source} into hotbar index {@code hotbar},
         * whose previous item {@code displacedItem} (or {@link #EMPTY}) went to {@code source}.
         */
        public void pulled(int source, int hotbar, int foodItem, int displacedItem) {
            this.source = source;
            this.hotbar = hotbar;
            this.foodItem = foodItem;
            this.displacedItem = displacedItem;
            owed = true;
            deferred = false;
        }

        /** The lease ended before the swap-back finished: keep it owed for the next safe moment. */
        public void leaseEnded() {
            if (owed) {
                deferred = true;
            }
        }

        /** Restore done, dropped, swap-back off, or the world unloaded: nothing owed. */
        public void forget() {
            owed = false;
            deferred = false;
            source = -1;
            hotbar = -1;
            foodItem = EMPTY;
            displacedItem = EMPTY;
        }

        public boolean owed() {
            return owed;
        }

        /** Owed after the lease ended early: the module restores on its own at the next safe moment. */
        public boolean deferred() {
            return owed && deferred;
        }

        public int source() {
            return source;
        }

        public int hotbar() {
            return hotbar;
        }

        /**
         * What to do now about the owed restore.
         *
         * @param screenOpen  a screen, or a container other than the player inventory, is open
         * @param channelBusy another module holds the container channel, clicks are in flight, the user is working
         *                    the hotbar or automation is stopped
         * @param hotbarItem  item key now in {@link #hotbar()}
         * @param sourceItem  item key now in {@link #source()}
         */
        public Step decide(boolean screenOpen, boolean channelBusy, int hotbarItem, int sourceItem) {
            if (!owed) {
                return Step.NONE;
            }
            if (screenOpen || channelBusy) {
                return Step.WAIT;
            }
            if (sourceItem != displacedItem) {
                return Step.DROP;
            }
            if (foodItem != EMPTY && hotbarItem == foodItem) {
                return Step.RESTORE;
            }
            if (hotbarItem == EMPTY && displacedItem != EMPTY) {
                return Step.RESTORE; // the food was eaten up: bring the displaced item home
            }
            return Step.DROP; // something else is in the hotbar slot now
        }
    }
}
