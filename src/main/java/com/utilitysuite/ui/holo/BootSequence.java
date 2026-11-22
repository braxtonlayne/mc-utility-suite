package com.utilitysuite.ui.holo;

/**
 * The first-open-per-session boot timeline of the menu (design stem-ui 8.2), 900 ms:
 * <ul>
 *   <li>0-120 ms: a 1 px accent line grows from the window centre to full width;</li>
 *   <li>120-300 ms: the frame unfolds vertically, the brackets snap in (ease-out-back), a 2-frame flicker;</li>
 *   <li>300-520 ms: the sidebar, list and content columns reveal left to right, 40 ms apart;</li>
 *   <li>520-900 ms: a readout counts up to 100 % and then fades to faint.</li>
 * </ul>
 * Skippable by any input and never blocking it (the screen keeps routing input; this only shapes drawing). Plays only
 * with {@link Motion#FULL}. Engine free: every curve is a pure function of the elapsed milliseconds.
 */
public final class BootSequence {

    public static final long LINE_END_MS = 120L;
    public static final long UNFOLD_END_MS = 300L;
    public static final long COLUMNS_END_MS = 520L;
    public static final long TOTAL_MS = 900L;
    public static final long COLUMN_STAGGER_MS = 40L;
    public static final long COLUMN_MS = 140L;
    public static final long FLICKER_MS = 33L;
    public static final long COUNT_MS = 240L;

    public enum Phase { LINE, UNFOLD, COLUMNS, READOUT, DONE }

    private static boolean playedThisSession;

    private long startMs;
    private boolean running;

    /**
     * Starts the sequence when it has not played yet this session and motion is FULL. Returns whether it runs; the
     * session flag is spent either way, so a menu opened with motion reduced never boots later.
     */
    public boolean begin(long nowMs, Motion motion) {
        boolean first = !playedThisSession;
        playedThisSession = true;
        running = first && motion == Motion.FULL;
        startMs = nowMs;
        return running;
    }

    /** Any input ends the sequence at once (the input itself is still handled by the screen). */
    public void skip() {
        running = false;
    }

    /** Elapsed ms while running, or -1 when not running or finished. */
    public long elapsed(long nowMs) {
        if (!running) {
            return -1L;
        }
        long t = nowMs - startMs;
        if (t < 0L) {
            t = 0L;
        }
        if (t >= TOTAL_MS) {
            running = false;
            return -1L;
        }
        return t;
    }

    public static Phase phaseAt(long t) {
        if (t < 0L || t >= TOTAL_MS) {
            return Phase.DONE;
        }
        if (t < LINE_END_MS) {
            return Phase.LINE;
        }
        if (t < UNFOLD_END_MS) {
            return Phase.UNFOLD;
        }
        return t < COLUMNS_END_MS ? Phase.COLUMNS : Phase.READOUT;
    }

    private static float span(long t, long from, long len) {
        if (t <= from) {
            return 0f;
        }
        if (t >= from + len) {
            return 1f;
        }
        return (t - from) / (float) len;
    }

    /** Width fraction of the centre line, 0..1 during LINE, 1 after. */
    public static float line(long t) {
        return AnimStore.easeOut(span(t, 0L, LINE_END_MS));
    }

    /** Height fraction of the frame, 0 during LINE, 0..1 during UNFOLD, 1 after. */
    public static float unfold(long t) {
        return AnimStore.easeOut(span(t, LINE_END_MS, UNFOLD_END_MS - LINE_END_MS));
    }

    /** Bracket arm scale with the snap overshoot (may exceed 1 briefly), 0 before UNFOLD. */
    public static float brackets(long t) {
        return AnimStore.easeOutBack(span(t, LINE_END_MS, UNFOLD_END_MS - LINE_END_MS));
    }

    /** True for the two-frame brightness dip as the frame first appears. */
    public static boolean flicker(long t) {
        return t >= LINE_END_MS && t < LINE_END_MS + FLICKER_MS;
    }

    /** Reveal fraction (scissor width) of column {@code i} (0 sidebar, 1 list, 2 content). */
    public static float column(int i, long t) {
        return AnimStore.easeOut(span(t, UNFOLD_END_MS + i * COLUMN_STAGGER_MS, COLUMN_MS));
    }

    /** The readout's count, 0..100, counting up from the start of READOUT. */
    public static int readoutPercent(long t) {
        return Math.round(100f * span(t, COLUMNS_END_MS, COUNT_MS));
    }

    /** The readout's brightness: 1 while counting, then fading toward faint (0) by the end. */
    public static float readoutBright(long t) {
        return 1f - span(t, COLUMNS_END_MS + COUNT_MS, TOTAL_MS - COLUMNS_END_MS - COUNT_MS);
    }

    public static void resetSessionForTests() {
        playedThisSession = false;
    }
}
