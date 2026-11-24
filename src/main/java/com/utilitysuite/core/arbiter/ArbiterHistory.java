package com.utilitysuite.core.arbiter;

import com.utilitysuite.core.api.ArbiterEventKind;
import com.utilitysuite.core.api.IArbiterHistory;
import com.utilitysuite.core.api.LeaseDenial;
import com.utilitysuite.core.api.LeaseEndReason;

/**
 * Ring buffer behind {@link IArbiterHistory}: fixed parallel arrays sized once, written by {@link ActionArbiter} on the
 * client thread. {@link #record} stores references the caller already holds (module ids, enum constants, the
 * arbiter's constant tags), so it allocates nothing. Pure Java.
 */
final class ArbiterHistory implements IArbiterHistory {

    private final int capacity;
    private final long[] ticks;
    private final ArbiterEventKind[] kinds;
    private final String[] owners;
    private final int[] masks;
    private final LeaseEndReason[] ends;
    private final LeaseDenial[] denials;
    private final String[] bys;
    private final int[] repeats;
    /** Slot of the newest entry. */
    private int head = -1;
    private int size;
    private long revision;
    private long now;
    /** The sticky "last stop" view (null on that view itself). */
    private final ArbiterHistory stops;

    ArbiterHistory(int capacity, boolean withStops) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity < 1");
        }
        this.capacity = capacity;
        ticks = new long[capacity];
        kinds = new ArbiterEventKind[capacity];
        owners = new String[capacity];
        masks = new int[capacity];
        ends = new LeaseEndReason[capacity];
        denials = new LeaseDenial[capacity];
        bys = new String[capacity];
        repeats = new int[capacity];
        stops = withStops ? new ArbiterHistory(1, false) : null;
    }

    /** Advances the clock reported by {@link #now()}. */
    void setNow(long tick) {
        now = tick;
        if (stops != null) {
            stops.now = tick;
        }
    }

    /** Records one event (folded into the newest entry when identical to it). Allocation free. */
    void record(long tick, ArbiterEventKind kind, String owner, int mask, LeaseEndReason end, LeaseDenial denial,
                String by) {
        String o = owner == null ? "" : owner;
        String b = by == null ? "" : by;
        put(tick, kind, o, mask, end, denial, b);
        if (stops != null && stops(kind, end)) {
            stops.put(tick, kind, o, mask, end, denial, b);
        }
    }

    /** Preemptions, manual overrides, denials, timeouts and Stop-All: the events that explain a stop. */
    static boolean stops(ArbiterEventKind kind, LeaseEndReason end) {
        return kind == ArbiterEventKind.PREEMPTED || kind == ArbiterEventKind.DENIED
                || kind == ArbiterEventKind.ENDED && end != LeaseEndReason.RELEASED;
    }

    private void put(long tick, ArbiterEventKind kind, String owner, int mask, LeaseEndReason end, LeaseDenial denial,
                     String by) {
        revision++;
        if (size > 0) {
            int h = head;
            if (kinds[h] == kind && masks[h] == mask && ends[h] == end && denials[h] == denial
                    && owners[h].equals(owner) && bys[h].equals(by)) {
                ticks[h] = tick;
                if (repeats[h] < Integer.MAX_VALUE) {
                    repeats[h]++;
                }
                return;
            }
        }
        int h = head + 1 == capacity ? 0 : head + 1;
        head = h;
        ticks[h] = tick;
        kinds[h] = kind;
        owners[h] = owner;
        masks[h] = mask;
        ends[h] = end;
        denials[h] = denial;
        bys[h] = by;
        repeats[h] = 1;
        if (size < capacity) {
            size++;
        }
    }

    private int slot(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        int s = head - i;
        return s < 0 ? s + capacity : s;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public long now() {
        return now;
    }

    @Override
    public long tick(int i) {
        return ticks[slot(i)];
    }

    @Override
    public ArbiterEventKind kind(int i) {
        return kinds[slot(i)];
    }

    @Override
    public String owner(int i) {
        return owners[slot(i)];
    }

    @Override
    public int channelMask(int i) {
        return masks[slot(i)];
    }

    @Override
    public LeaseEndReason endReason(int i) {
        return ends[slot(i)];
    }

    @Override
    public LeaseDenial denial(int i) {
        return denials[slot(i)];
    }

    @Override
    public String by(int i) {
        return bys[slot(i)];
    }

    @Override
    public int repeats(int i) {
        return repeats[slot(i)];
    }

    @Override
    public IArbiterHistory lastStop() {
        return stops != null ? stops : IArbiterHistory.EMPTY;
    }
}
