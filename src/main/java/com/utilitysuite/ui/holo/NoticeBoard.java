package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.INoticeBoard;
import com.utilitysuite.core.api.visual.NoticeKind;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The short-message queue behind {@code Services.visuals().notices()} (design 8.10). Engine free: the clock and the
 * Clean-Screen gate are passed in, so tests need neither. A small fixed pool of {@link Notice} slots, oldest first.
 * <ul>
 *     <li>Life {@value #LIFE_MS} ms, {@value #LIFE_LONG_MS} ms for WARN and ERROR.</li>
 *     <li>Identical titles (and kind) posted within {@value #MERGE_MS} ms of the previous post of a live notice merge
 *     into it: counter {@code xN} on the title, detail replaced, life restarted.</li>
 *     <li>More than {@value #MAX_VISIBLE} live notices drops the oldest INFO (if there is none, the extra ones just
 *     wait; the layer shows the newest {@value #MAX_VISIBLE}). The pool itself holds {@value #CAPACITY}: past that
 *     the oldest notice is dropped.</li>
 *     <li>While Clean-Screen is on, posts are dropped, not queued.</li>
 * </ul>
 * Posting allocates (strings); reading ({@link #expire}, {@link #count}, {@link #at}) allocates nothing. Client thread.
 */
public final class NoticeBoard implements INoticeBoard {

    public static final int CAPACITY = 8;
    public static final int MAX_VISIBLE = 3;
    public static final long LIFE_MS = 2500L;
    public static final long LIFE_LONG_MS = 4000L;
    public static final long MERGE_MS = 2000L;

    /** One notice slot. Fields are read by the layer; only the board writes them. */
    public static final class Notice {
        public NoticeKind kind;
        /** The bare title (merge key). */
        public String title;
        /** Title as drawn: the title, with " xN" appended when merged. */
        public String shown;
        /** One line of detail, or null. */
        public String detail;
        public int count;
        /** Unique per notice (animation key). */
        public long seq;
        public long postedAt;
        public long expiresAt;
        public long lifeMs;
        // layer state: set and read by NoticeLayer only
        float width = -1f;
        boolean entered;
    }

    private final Notice[] slots = new Notice[CAPACITY];
    private int size;
    private long nextSeq = 1L;
    private final LongSupplier clock;
    private final BooleanSupplier cleanScreen;

    public NoticeBoard(LongSupplier clock, BooleanSupplier cleanScreen) {
        this.clock = clock;
        this.cleanScreen = cleanScreen;
        for (int i = 0; i < CAPACITY; i++) {
            slots[i] = new Notice();
        }
    }

    /** The clock the board and its layer share, in milliseconds. */
    public long now() {
        return clock.getAsLong();
    }

    @Override
    public void post(NoticeKind kind, String title, String detail) {
        post(kind, title, detail, clock.getAsLong());
    }

    /** {@link #post(NoticeKind, String, String)} at an explicit time (tests, and a caller that has the time). */
    public void post(NoticeKind kind, String title, String detail, long now) {
        if (kind == null || title == null || title.isEmpty() || (cleanScreen != null && cleanScreen.getAsBoolean())) {
            return;
        }
        expire(now);
        long life = kind == NoticeKind.WARN || kind == NoticeKind.ERROR ? LIFE_LONG_MS : LIFE_MS;
        for (int i = size - 1; i >= 0; i--) {
            Notice n = slots[i];
            if (n.kind == kind && n.title.equals(title) && now - n.postedAt <= MERGE_MS) {
                n.count++;
                n.shown = title + " x" + n.count;
                n.detail = detail;
                n.postedAt = now;
                n.expiresAt = now + life;
                n.width = -1f;
                return;
            }
        }
        if (size == CAPACITY) {
            removeAt(0);
        }
        Notice n = slots[size++];
        n.kind = kind;
        n.title = title;
        n.shown = title;
        n.detail = detail;
        n.count = 1;
        n.seq = nextSeq++;
        n.postedAt = now;
        n.expiresAt = now + life;
        n.lifeMs = life;
        n.width = -1f;
        n.entered = false;
        dropExtraInfo();
    }

    private void dropExtraInfo() {
        while (size > MAX_VISIBLE) {
            int victim = -1;
            for (int i = 0; i < size - 1; i++) { // never the notice that was just posted
                if (slots[i].kind == NoticeKind.INFO) {
                    victim = i;
                    break;
                }
            }
            if (victim < 0) {
                return;
            }
            removeAt(victim);
        }
    }

    /** Removes the notices whose life is over. Returns the number that are left. */
    public int expire(long now) {
        for (int i = 0; i < size; ) {
            if (slots[i].expiresAt <= now) {
                removeAt(i);
            } else {
                i++;
            }
        }
        return size;
    }

    private void removeAt(int i) {
        Notice gone = slots[i];
        for (int j = i; j < size - 1; j++) {
            slots[j] = slots[j + 1];
        }
        slots[size - 1] = gone;
        size--;
        gone.title = null;
        gone.shown = null;
        gone.detail = null;
        gone.kind = null;
    }

    public int count() {
        return size;
    }

    /** The i-th live notice, oldest first. */
    public Notice at(int i) {
        return slots[i];
    }

    public void clear() {
        while (size > 0) {
            removeAt(size - 1);
        }
    }
}
