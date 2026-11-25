package com.utilitysuite.ui.holo;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Engine-free state of the diegetic holo-cards (V3): the dwell timer, what the client learned from menus it opened
 * (merchant offers per villager entity id; cook / brew progress per block), and the one {@link Card} on show.
 * Content is built only when the dwelt-on target changes ({@link #fillVillager}, {@link #fillStorage},
 * {@link #fillProgress}), so drawing a card allocates nothing. Client thread.
 */
public final class HoloCardBook {

    /** Rows a card shows at most. */
    public static final int ROWS = 4;
    public static final long NO_TARGET = Long.MIN_VALUE;
    /** Ticks per brew of a brewing stand. */
    public static final int BREW_TICKS = 400;
    static final String[] LEVELS = {"Novice", "Apprentice", "Journeyman", "Expert", "Master"};
    static final String LEARN = "open once to learn";
    static final String EMPTY = "empty";
    static final String UNKNOWN = "not opened yet";
    static final String ESTIMATE = "estimate";
    static final String IDLE = "idle";

    public enum Kind {
        NONE, VILLAGER, STORAGE, PROGRESS
    }

    /** The card on show. Fields are written on target change and read by the layer each frame. */
    public static final class Card {
        public Kind kind = Kind.NONE;
        public String title = "";
        public String subtitle = "";
        public int rows;
        public final String[] iconA = new String[ROWS];
        public final int[] countA = new int[ROWS];
        /** Second icon of a row (an offer's result), or null. */
        public final String[] iconB = new String[ROWS];
        public final int[] countB = new int[ROWS];
        /** Progress at {@link #progressAtMs}, or -1 for no ring. */
        public float progress = -1f;
        /** Progress gained per millisecond after {@link #progressAtMs} (0 when known to be idle). */
        public float progressPerMs;
        public long progressAtMs;
        public boolean estimate;

        /** The ring value at {@code nowMs}: the recorded progress advanced by the elapsed time, capped at 1. */
        public float progressAt(long nowMs) {
            return HoloCardBook.estimate(progress, progressPerMs, progressAtMs, nowMs);
        }

        void clear(Kind k, String t) {
            kind = k;
            title = t;
            subtitle = "";
            rows = 0;
            progress = -1f;
            progressPerMs = 0f;
            estimate = false;
            for (int i = 0; i < ROWS; i++) {
                iconA[i] = null;
                iconB[i] = null;
                countA[i] = 0;
                countB[i] = 0;
            }
        }
    }

    /** Offers seen in the last merchant screen of an entity: cost then result per row. */
    record Offers(String[] costIds, int[] costCounts, String[] resultIds, int[] resultCounts) {
    }

    /** Progress seen in the last menu of a block. */
    record Progress(String dimension, float progress, float perMs, long atMs) {
    }

    private final Int2ObjectOpenHashMap<Offers> offers = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Progress> progress = new Long2ObjectOpenHashMap<>();
    public final Card card = new Card();

    public int dwellTicks = 6;
    private long dwellKey = NO_TARGET;
    private int dwellCount;
    private long shownKey = NO_TARGET;
    /** True while the card should be visible (the layer fades on this). */
    public boolean active;

    // ---- dwell ------------------------------------------------------------------------------------------------------

    /**
     * One client tick with the target under the crosshair ({@link #NO_TARGET} for none). Returns true exactly when a
     * new target has been dwelt on long enough and its card should be (re)built; the card goes inactive at once when
     * the crosshair leaves the shown target.
     */
    public boolean dwell(long key) {
        if (key != dwellKey) {
            dwellKey = key;
            dwellCount = 0;
        }
        if (key != shownKey) {
            active = false;
        }
        if (key == NO_TARGET) {
            return false;
        }
        if (dwellCount < dwellTicks) {
            dwellCount++;
        }
        if (dwellCount >= dwellTicks && !active) {
            shownKey = key;
            active = true;
            return true;
        }
        return false;
    }

    public long shownKey() {
        return shownKey;
    }

    public static long entityKey(int entityId) {
        return (1L << 62) | (entityId & 0xFFFFFFFFL);
    }

    public void reset() {
        dwellKey = NO_TARGET;
        shownKey = NO_TARGET;
        dwellCount = 0;
        active = false;
        offers.clear();
        progress.clear();
        card.clear(Kind.NONE, "");
    }

    // ---- learning (menu close) --------------------------------------------------------------------------------------

    public void recordOffers(int entityId, String[] costIds, int[] costCounts, String[] resultIds,
                             int[] resultCounts) {
        offers.put(entityId, new Offers(costIds, costCounts, resultIds, resultCounts));
    }

    /**
     * Records the progress a block's menu showed. {@code ticksPerCycle} is the length of one cook or brew;
     * {@code running} false means it was idle (the estimate then stays put).
     */
    public void recordProgress(String dimension, long pos, float progress01, int ticksPerCycle, boolean running,
                               long nowMs) {
        float perMs = running && ticksPerCycle > 0 ? 1f / (ticksPerCycle * 50f) : 0f;
        progress.put(pos, new Progress(dimension, clamp01(progress01), perMs, nowMs));
    }

    public static float estimate(float p0, float perMs, long atMs, long nowMs) {
        if (p0 < 0f) {
            return -1f;
        }
        long dt = Math.max(0L, nowMs - atMs);
        return Math.min(1f, p0 + perMs * dt);
    }

    // ---- card content (target change only) -------------------------------------------------------------------------

    /** A villager: profession and level are synced to the client; offers only once its screen was opened. */
    public void fillVillager(int entityId, String profession, int level) {
        card.clear(Kind.VILLAGER, profession);
        int l = Math.max(1, Math.min(LEVELS.length, level));
        Offers o = offers.get(entityId);
        if (o == null) {
            card.subtitle = LEVELS[l - 1] + " - " + LEARN;
            return;
        }
        card.subtitle = LEVELS[l - 1];
        int n = Math.min(ROWS, o.resultIds().length);
        for (int i = 0; i < n; i++) {
            card.iconA[i] = o.costIds()[i];
            card.countA[i] = o.costCounts()[i];
            card.iconB[i] = o.resultIds()[i];
            card.countB[i] = o.resultCounts()[i];
        }
        card.rows = n;
    }

    /** A container: the top items by count from the storage index (null contents: never opened). */
    public void fillStorage(String title, Map<String, Integer> contents, boolean stale) {
        card.clear(Kind.STORAGE, title);
        if (contents == null) {
            card.subtitle = UNKNOWN;
            return;
        }
        if (contents.isEmpty()) {
            card.subtitle = EMPTY;
            return;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(contents.entrySet());
        sorted.sort((a, b) -> b.getValue() != a.getValue().intValue()
                ? Integer.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        int n = Math.min(ROWS, sorted.size());
        for (int i = 0; i < n; i++) {
            card.iconA[i] = sorted.get(i).getKey();
            card.countA[i] = sorted.get(i).getValue();
        }
        card.rows = n;
        if (stale) {
            card.subtitle = "may be out of date";
        }
    }

    /** A furnace-like block or brewing stand: a ring only when its menu was opened before (labelled estimate). */
    public void fillProgress(String title, String dimension, long pos) {
        card.clear(Kind.PROGRESS, title);
        Progress p = progress.get(pos);
        if (p == null || !p.dimension().equals(dimension)) {
            card.subtitle = LEARN;
            return;
        }
        card.progress = p.progress();
        card.progressPerMs = p.perMs();
        card.progressAtMs = p.atMs();
        card.estimate = true;
        card.subtitle = p.perMs() > 0f ? ESTIMATE : IDLE;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }
}
