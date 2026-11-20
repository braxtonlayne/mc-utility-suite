package com.utilitysuite.jobs.survival;

/**
 * Auto-Tool decision: the fastest valid hotbar tool that keeps the desired drop and spares nearly broken valuable
 * tools. Works on a caller-filled, reused array of {@link Candidate}s so the decision itself allocates nothing.
 */
public final class ToolSelector {

    /** Mutable per-slot facts, refilled by the Minecraft adapter on each decision. */
    public static final class Candidate {
        public int slot;
        public boolean present;
        /** Mining speed of this stack on the block (1.0 for the empty hand). */
        public float speed;
        /** The block drops its items with this tool. */
        public boolean correctForDrops;
        public int silkTouch;
        public int fortune;
        /** Remaining durability 0..1 (1 for unbreakable / not damageable). */
        public float durability;
        /** Enchanted or made of diamond/netherite. */
        public boolean valuable;

        public Candidate set(int slot, float speed, boolean correct, int silk, int fortune, float durability,
                             boolean valuable) {
            this.slot = slot;
            this.present = true;
            this.speed = speed;
            this.correctForDrops = correct;
            this.silkTouch = silk;
            this.fortune = fortune;
            this.durability = durability;
            this.valuable = valuable;
            return this;
        }
    }

    /** What the player wants from the block. */
    public enum DropPreference {
        /** Any valid drop. */
        ANY,
        /** The block itself (glass, ender chest, ores for storage): prefer Silk Touch. */
        SILK,
        /** Most items (ores): avoid Silk Touch, prefer Fortune. */
        FORTUNE
    }

    private ToolSelector() {
    }

    /**
     * @param requiresTool       the block only drops with a correct tool
     * @param protectBelow       valuable tools under this durability fraction are skipped (e.g. 0.10)
     * @param currentSlot        selected slot now; returned when nothing is strictly better
     * @return the slot to select (0..8)
     */
    public static int choose(Candidate[] slots, int count, boolean requiresTool, DropPreference pref,
                             float protectBelow, int currentSlot) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        double currentScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            Candidate c = slots[i];
            if (!c.present) {
                continue;
            }
            double s = score(c, requiresTool, pref, protectBelow);
            if (c.slot == currentSlot) {
                currentScore = s;
            }
            if (s > bestScore) {
                bestScore = s;
                best = c.slot;
            }
        }
        if (best < 0 || bestScore == Double.NEGATIVE_INFINITY || bestScore <= currentScore) {
            return currentSlot;
        }
        return best;
    }

    /** Score of one slot; NEGATIVE_INFINITY when the tool must not be used. */
    static double score(Candidate c, boolean requiresTool, DropPreference pref, float protectBelow) {
        if (c.valuable && c.durability < protectBelow) {
            return Double.NEGATIVE_INFINITY;
        }
        if (requiresTool && !c.correctForDrops) {
            return Double.NEGATIVE_INFINITY;
        }
        if (pref == DropPreference.FORTUNE && c.silkTouch > 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double s = c.speed;
        if (pref == DropPreference.SILK && c.silkTouch > 0) {
            s += 1000;
        }
        if (pref == DropPreference.FORTUNE) {
            s += 100.0 * c.fortune;
        }
        if (c.valuable && pref == DropPreference.ANY) {
            s -= 0.01; // equal speed: spare the valuable tool
        }
        return s;
    }

    /** True when the held tool should be swapped out for durability protection. */
    public static boolean needsProtection(Candidate held, float protectBelow) {
        return held.present && held.valuable && held.durability < protectBelow;
    }
}
