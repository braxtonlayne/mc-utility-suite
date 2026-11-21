package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot.Reflex;

/**
 * Flight recorder for the autopilot (idea A10): a ring buffer of the last {@link #CAPACITY} rows in primitive arrays
 * (tick, health, worst hazard, reflex, event, reason id). One sample row per tick (also while the autopilot is off when
 * "Record while off" is on; those rows carry {@link #FLAG_OFF} and {@code "off":true} in the JSON) plus one row per
 * reflex note, so the buffer covers well over {@link #WINDOW_TICKS} (10 s). Reasons are constant strings interned into a
 * small fixed table by identity, so recording is allocation free. {@link #snapshot} copies the last 10 s off the
 * ring (rare: a death or a big hit) and {@link Snapshot#toJson} formats it, off the client thread. Engine free.
 */
public final class BlackBox {

    public static final int CAPACITY = 512;
    public static final int WINDOW_TICKS = 200;
    public static final int MAX_REASONS = 64;

    public static final byte EV_SAMPLE = 0;
    public static final byte EV_ACTED = 1;
    public static final byte EV_DECLINED = 2;
    public static final byte EV_DONE = 3;
    public static final byte EV_DAMAGE = 4;
    /** Row flag: recorded while the autopilot was off (vitals only; nothing could act). */
    public static final byte FLAG_OFF = 1;
    private static final String[] EVENT_NAMES = {"SAMPLE", "ACTED", "DECLINED", "DONE", "DAMAGE"};

    private final long[] tick = new long[CAPACITY];
    private final float[] health = new float[CAPACITY];
    private final byte[] hazard = new byte[CAPACITY];
    private final byte[] reflex = new byte[CAPACITY];
    private final byte[] event = new byte[CAPACITY];
    private final short[] reason = new short[CAPACITY];
    /** Per-row flags ({@link #FLAG_OFF}). */
    private final byte[] flags = new byte[CAPACITY];
    private int head; // next write index
    private int size;

    private final String[] reasons = new String[MAX_REASONS];
    private int reasonCount;

    /** Records one row while the autopilot is on. Allocation free. */
    public void record(long t, float hp, HazardKind worst, Reflex rx, byte ev, String why) {
        record(t, hp, worst, rx, ev, why, false);
    }

    /** Records one row; {@code autopilotOff} marks it as recorded while the autopilot was off. Allocation free. */
    public void record(long t, float hp, HazardKind worst, Reflex rx, byte ev, String why, boolean autopilotOff) {
        int i = head;
        flags[i] = autopilotOff ? FLAG_OFF : 0;
        tick[i] = t;
        health[i] = hp;
        hazard[i] = (byte) (worst == null ? -1 : worst.ordinal());
        reflex[i] = (byte) (rx == null ? -1 : rx.ordinal());
        event[i] = ev;
        reason[i] = (short) intern(why);
        head = (i + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    /** Index of a constant reason in the fixed table, or -1 (none, or the table is full). Allocation free. */
    int intern(String why) {
        if (why == null) {
            return -1;
        }
        for (int i = 0; i < reasonCount; i++) {
            if (reasons[i] == why) {
                return i;
            }
        }
        for (int i = 0; i < reasonCount; i++) {
            if (reasons[i].equals(why)) {
                return i;
            }
        }
        if (reasonCount < MAX_REASONS) {
            reasons[reasonCount] = why;
            return reasonCount++;
        }
        return -1;
    }

    public int size() {
        return size;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    /** Copies the rows of the last {@link #WINDOW_TICKS} ticks before {@code nowTick}, oldest first. Allocates. */
    public Snapshot snapshot(long nowTick, String cause) {
        int start = (head - size + CAPACITY) % CAPACITY;
        int count = 0;
        for (int k = 0; k < size; k++) {
            if (nowTick - tick[(start + k) % CAPACITY] <= WINDOW_TICKS) {
                count++;
            }
        }
        Snapshot s = new Snapshot(cause, nowTick, count);
        int out = 0;
        for (int k = 0; k < size; k++) {
            int i = (start + k) % CAPACITY;
            if (nowTick - tick[i] > WINDOW_TICKS) {
                continue;
            }
            s.tick[out] = tick[i];
            s.health[out] = health[i];
            s.hazard[out] = hazard[i] < 0 ? null : HazardKind.all()[hazard[i]].name();
            s.reflex[out] = reflex[i] < 0 ? null : Reflex.values()[reflex[i]].name();
            s.event[out] = EVENT_NAMES[event[i]];
            s.reason[out] = reason[i] < 0 ? null : reasons[reason[i]];
            s.off[out] = (flags[i] & FLAG_OFF) != 0;
            out++;
        }
        return s;
    }

    /** An immutable copy of the recorder's window, safe to format on another thread. */
    public static final class Snapshot {
        public final String cause;
        public final long dumpTick;
        final long[] tick;
        final float[] health;
        final String[] hazard;
        final String[] reflex;
        final String[] event;
        final String[] reason;
        final boolean[] off;

        Snapshot(String cause, long dumpTick, int n) {
            this.cause = cause;
            this.dumpTick = dumpTick;
            tick = new long[n];
            health = new float[n];
            hazard = new String[n];
            reflex = new String[n];
            event = new String[n];
            reason = new String[n];
            off = new boolean[n];
        }

        public int rows() {
            return tick.length;
        }

        /** Tick of a row relative to the dump (0 = the dump tick, negative = earlier). */
        public long relTick(int row) {
            return tick[row] - dumpTick;
        }

        public String event(int row) {
            return event[row];
        }

        public String reflex(int row) {
            return reflex[row];
        }

        public String reason(int row) {
            return reason[row];
        }

        /** True when the row was recorded while the autopilot was off. */
        public boolean autopilotOff(int row) {
            return off[row];
        }

        /** Compact JSON: {"cause":..,"rows":[{"t":-3,"hp":12.5,"hz":"FALL","rx":"FALL_CLUTCH","ev":"ACTED",..}]}. */
        public String toJson() {
            StringBuilder sb = new StringBuilder(64 + rows() * 64);
            sb.append("{\"cause\":");
            str(sb, cause);
            sb.append(",\"rows\":[");
            for (int i = 0; i < rows(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"t\":").append(relTick(i)).append(",\"hp\":").append(health[i]);
                if (hazard[i] != null) {
                    sb.append(",\"hz\":\"").append(hazard[i]).append('"');
                }
                if (reflex[i] != null) {
                    sb.append(",\"rx\":\"").append(reflex[i]).append('"');
                }
                if (!"SAMPLE".equals(event[i])) {
                    sb.append(",\"ev\":\"").append(event[i]).append('"');
                }
                if (reason[i] != null) {
                    sb.append(",\"why\":");
                    str(sb, reason[i]);
                }
                if (off[i]) {
                    sb.append(",\"off\":true");
                }
                sb.append('}');
            }
            return sb.append("]}").toString();
        }

        private static void str(StringBuilder sb, String s) {
            if (s == null) {
                sb.append("null");
                return;
            }
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
