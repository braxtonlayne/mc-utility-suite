package com.utilitysuite.world.changes;

import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R2 clear-snapshot: per chunk removal from the store and from the published diff. */
class SnapshotClearTest {

    private static ChunkSnapshot snap(int cx, int cz) {
        return new ChunkSnapshot(cx, cz, -4, 1L, new int[24], null, new long[0]);
    }

    @Test
    void removingOneChunkKeepsTheOthersAndSurvivesSaving() throws IOException {
        SnapshotStore store = new SnapshotStore();
        store.put(snap(0, 0));
        store.put(snap(1, 0));
        store.put(snap(-3, 7));
        store.consumeDirty();
        assertTrue(store.remove(1, 0));
        assertTrue(store.consumeDirty(), "a removal is saved");
        assertFalse(store.remove(1, 0));
        assertFalse(store.consumeDirty(), "removing nothing changes nothing");
        SnapshotStore back = new SnapshotStore();
        back.fromBytes(store.toBytes());
        assertEquals(2, back.size());
        assertNull(back.get(1, 0));
        assertEquals(7, back.get(-3, 7).chunkZ);
    }

    @Test
    void diffWithoutChunkDropsOnlyThatChunksMarks() {
        long inA = BlockPosPacking.pack(3, 64, 5); // chunk (0, 0)
        long inA2 = BlockPosPacking.pack(15, -10, 15); // chunk (0, 0)
        long inB = BlockPosPacking.pack(-1, 70, 5); // chunk (-1, 0)
        long inC = BlockPosPacking.pack(40, 12, -33); // chunk (2, -3)
        SnapshotDiff.Result r = new SnapshotDiff.Result(new long[] {inA, inB}, new long[] {inA2, inC},
                new long[] {inA}, 4, 3);
        SnapshotDiff.Result cut = SnapshotDiff.withoutChunk(r, 0, 0);
        assertArrayEquals(new long[] {inB}, cut.added());
        assertArrayEquals(new long[] {inC}, cut.removed());
        assertEquals(0, cut.emptied().length);
        assertEquals(2, cut.comparedChunks());
        assertSame(cut, SnapshotDiff.withoutChunk(cut, 9, 9), "nothing there: same result, no copy");
        assertNull(SnapshotDiff.withoutChunk(null, 0, 0));
        SnapshotDiff.Result neg = SnapshotDiff.withoutChunk(r, -1, 0);
        assertArrayEquals(new long[] {inA}, neg.added(), "negative chunk coordinates use floor division");
    }
}
