package com.utilitysuite.travel.movement;

/**
 * How much of a jump the assist may author. Every correction the movement assist makes (a filled air-steer key, an
 * assisted edge jump, a sneak tap, a turn-gain tick) asks here first and reports its use, so the player keeps
 * authorship of their own jumps:
 * <ul>
 *   <li><b>Cap</b>: at most {@link #cap()} corrected ticks per jump (a jump runs from one takeoff to the next).
 *       0 = no corrections at all.</li>
 *   <li><b>Scaled by the miss</b>: {@link #help(double)} is 0 when the jump already lands and 1 when it misses by a
 *       block or more; it scales graded help (turn-gain strength) and gates key corrections (none when it lands).</li>
 *   <li><b>Fades with skill</b>: a ring of the last {@link #RING} jumps records whether each landed without any
 *       correction. Above {@link #FADE_FROM} unassisted success the help fades toward {@link #FLOOR}.</li>
 * </ul>
 * Engine-free, allocation free, client thread.
 */
public final class AuthorshipBudget {

    public static final int DEFAULT_CAP = 6;
    public static final int RING = 10;
    /** Jumps recorded before the success rate is trusted. */
    public static final int MIN_SAMPLES = 5;
    public static final double FADE_FROM = 0.8;
    public static final double FLOOR = 0.25;
    /** Miss (blocks) that earns full help. */
    public static final double FULL_HELP_MISS = 1.0;

    private int cap = DEFAULT_CAP;
    private int used;
    private final boolean[] ring = new boolean[RING];
    private int ringCount;
    private int ringNext;

    public int cap() {
        return cap;
    }

    public void setCap(int cap) {
        this.cap = Math.max(0, cap);
    }

    /** A new jump left the ground; {@code assistedTakeoff}: the takeoff itself was the assist's jump (counts as one). */
    public void onTakeoff(boolean assistedTakeoff) {
        used = assistedTakeoff ? 1 : 0;
    }

    /** The jump touched down; {@code landedClean}: on the intended block / without damage. */
    public void onTouchdown(boolean landedClean) {
        ring[ringNext] = landedClean && used == 0;
        ringNext = (ringNext + 1) % RING;
        if (ringCount < RING) {
            ringCount++;
        }
    }

    /** Share of recent jumps that landed without any correction (0 with no history). */
    public double unassistedRate() {
        if (ringCount == 0) {
            return 0.0;
        }
        int ok = 0;
        for (int i = 0; i < ringCount; i++) {
            if (ring[i]) {
                ok++;
            }
        }
        return ok / (double) ringCount;
    }

    /** 1.0 for a player who needs help, fading linearly to {@link #FLOOR} as unassisted success goes 80% to 100%. */
    public double fade() {
        if (ringCount < MIN_SAMPLES) {
            return 1.0;
        }
        double r = unassistedRate();
        if (r <= FADE_FROM) {
            return 1.0;
        }
        double t = Math.min(1.0, (r - FADE_FROM) / (1.0 - FADE_FROM));
        return 1.0 - t * (1.0 - FLOOR);
    }

    /** 0..1: how much help a jump predicted to miss by {@code missBlocks} gets (0 when it lands, or the cap is 0). */
    public double help(double missBlocks) {
        if (cap <= 0 || !(missBlocks > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, missBlocks / FULL_HELP_MISS) * fade();
    }

    /**
     * Ticks this jump may be corrected for: none when it already lands (or the cap is 0), otherwise the cap faded by
     * the player's skill (at least one). The miss itself scales the strength of graded help (turn gain) through
     * {@link #help}; a key tick is all or nothing, so it only gates the count.
     */
    public int allowance(double missBlocks) {
        if (help(missBlocks) <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(cap * fade()));
    }

    /** Corrected ticks still available this jump. */
    public int remaining(double missBlocks) {
        return Math.max(0, allowance(missBlocks) - used);
    }

    /** True when one more corrected tick fits this jump's allowance. Does not use it. */
    public boolean allows(double missBlocks) {
        return used < allowance(missBlocks);
    }

    /** Asks for one corrected tick: true (and counted) when it fits. */
    public boolean tryUse(double missBlocks) {
        if (!allows(missBlocks)) {
            return false;
        }
        used++;
        return true;
    }

    /** Counts a tick the assist corrected without asking first (a turn-gain tick reported by the core). */
    public void noteUse() {
        used++;
    }

    public int usedThisJump() {
        return used;
    }

    public void reset() {
        used = 0;
        ringCount = 0;
        ringNext = 0;
    }
}
