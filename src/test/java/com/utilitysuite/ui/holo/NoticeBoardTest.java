package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.INoticeBoard;
import com.utilitysuite.core.api.visual.NoticeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NoticeBoardTest {

    private final long[] now = {1_000L};
    private final boolean[] clean = {false};
    private final NoticeBoard board = new NoticeBoard(() -> now[0], () -> clean[0]);

    @Test
    void identicalTitlesWithinTwoSecondsMergeIntoOneNoticeWithACounter() {
        board.post(NoticeKind.INFO, "Light Levels on", null, 1_000L);
        board.post(NoticeKind.INFO, "Light Levels on", "again", 2_500L);
        board.post(NoticeKind.INFO, "Light Levels on", null, 4_400L);
        assertEquals(1, board.count());
        NoticeBoard.Notice n = board.at(0);
        assertEquals(3, n.count);
        assertEquals("Light Levels on x3", n.shown);
        assertEquals("Light Levels on", n.title);
        assertEquals(4_400L + NoticeBoard.LIFE_MS, n.expiresAt, "a merge restarts the life");
    }

    @Test
    void aGapOfMoreThanTwoSecondsStartsANewNotice() {
        board.post(NoticeKind.INFO, "A", null, 0L);
        board.post(NoticeKind.INFO, "A", null, 1_500L);
        board.post(NoticeKind.INFO, "A", null, 3_600L); // 2.1 s after the last post, the notice is still alive (until 4.0 s)
        assertEquals(2, board.count());
        assertEquals("A x2", board.at(0).shown);
        assertEquals("A", board.at(1).shown);
    }

    @Test
    void differentKindsOrTitlesDoNotMerge() {
        board.post(NoticeKind.INFO, "A", null, 0L);
        board.post(NoticeKind.WARN, "A", null, 10L);
        board.post(NoticeKind.INFO, "B", null, 20L);
        assertEquals(3, board.count());
    }

    @Test
    void lifeIsTwoAndAHalfSecondsAndFourForWarnAndError() {
        board.post(NoticeKind.INFO, "i", null, 0L);
        board.post(NoticeKind.DONE, "d", null, 0L);
        board.post(NoticeKind.WARN, "w", null, 0L);
        assertEquals(2_500L, board.at(0).expiresAt);
        assertEquals(2_500L, board.at(1).expiresAt);
        assertEquals(4_000L, board.at(2).expiresAt);
        assertEquals(3, board.expire(2_499L));
        assertEquals(1, board.expire(2_500L));
        assertEquals("w", board.at(0).title);
        assertEquals(1, board.expire(3_999L));
        assertEquals(0, board.expire(4_000L));
        board.post(NoticeKind.ERROR, "e", null, 5_000L);
        assertEquals(9_000L, board.at(0).expiresAt);
    }

    @Test
    void aFourthNoticeDropsTheOldestInfo() {
        board.post(NoticeKind.WARN, "warn", null, 0L);
        board.post(NoticeKind.INFO, "info 1", null, 1L);
        board.post(NoticeKind.INFO, "info 2", null, 2L);
        assertEquals(3, board.count());
        board.post(NoticeKind.DONE, "done", null, 3L);
        assertEquals(3, board.count());
        assertEquals("warn", board.at(0).title);
        assertEquals("info 2", board.at(1).title);
        assertEquals("done", board.at(2).title);
    }

    @Test
    void withoutAnInfoToDropTheExtraNoticesWaitAndThePoolIsBounded() {
        for (int i = 0; i < 5; i++) {
            board.post(NoticeKind.ERROR, "e" + i, null, i);
        }
        assertEquals(5, board.count());
        for (int i = 5; i < 20; i++) {
            board.post(NoticeKind.ERROR, "e" + i, null, i);
        }
        assertEquals(NoticeBoard.CAPACITY, board.count());
        assertEquals("e12", board.at(0).title);
        assertEquals("e19", board.at(NoticeBoard.CAPACITY - 1).title);
    }

    @Test
    void aNewInfoIsNeverTheOneDropped() {
        board.post(NoticeKind.ERROR, "a", null, 0L);
        board.post(NoticeKind.ERROR, "b", null, 1L);
        board.post(NoticeKind.ERROR, "c", null, 2L);
        board.post(NoticeKind.INFO, "new", null, 3L);
        assertEquals(4, board.count());
        assertEquals("new", board.at(3).title);
    }

    @Test
    void postsAreDroppedWhileCleanScreenIsOn() {
        clean[0] = true;
        board.post(NoticeKind.ERROR, "x", null, 0L);
        assertEquals(0, board.count());
        clean[0] = false;
        board.post(NoticeKind.ERROR, "x", null, 10L);
        assertEquals(1, board.count());
    }

    @Test
    void usesItsClockAndIgnoresEmptyTitles() {
        INoticeBoard api = board;
        api.post(NoticeKind.INFO, "t", null);
        api.post(NoticeKind.INFO, "", null);
        api.post(null, "x", null);
        api.post(NoticeKind.INFO, null, null);
        assertEquals(1, board.count());
        assertEquals(1_000L, board.at(0).postedAt);
        now[0] = 10_000L;
        assertEquals(0, board.expire(board.now()));
    }

    @Test
    void removedSlotsAreReusedAndReleaseTheirStrings() {
        board.post(NoticeKind.INFO, "gone", "detail", 0L);
        NoticeBoard.Notice first = board.at(0);
        board.expire(10_000L);
        assertNull(first.title);
        board.post(NoticeKind.INFO, "again", null, 10_001L);
        assertSame(first, board.at(0));
        assertEquals("again", first.title);
        assertNull(first.detail);
    }
}
