package com.utilitysuite.world.changes;

import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotDiffTest {

    private static final int SECTIONS = 4;
    private static final int MIN_SECTION = -4;

    private static byte[][] ground() {
        byte[][] b = new byte[SECTIONS][];
        b[0] = new byte[ChunkSnapshot.SECTION_VOLUME];
        java.util.Arrays.fill(b[0], ChunkSnapshot.blockHash(1, false)); // stone
        return b;
    }

    private static ChunkSnapshot snap(byte[][] blocks, long... containers) {
        int[] ph = new int[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            ph[i] = blocks[i] == null ? 0 : java.util.Arrays.hashCode(blocks[i]);
        }
        return new ChunkSnapshot(3, -2, MIN_SECTION, 1000L, ph, blocks, containers);
    }

    @Test
    void blockHashReservesZeroForAir() {
        assertEquals(0, ChunkSnapshot.blockHash(0, true));
        for (int id = 0; id < 5000; id++) {
            assertTrue(ChunkSnapshot.blockHash(id, false) != 0);
        }
        int a = ChunkSnapshot.paletteHash(new int[] {5, 9, 12}, 3);
        int b = ChunkSnapshot.paletteHash(new int[] {12, 5, 9}, 3);
        assertEquals(a, b);
    }

    @Test
    void detailedDiffReportsAddedRemovedAndEmptied() {
        long chestPos = BlockPosPacking.pack(50, -60, -20);
        ChunkSnapshot before = snap(ground(), chestPos, BlockPosPacking.pack(51, -60, -20));
        byte[][] now = ground();
        now[0][ChunkSnapshot.index(0, 0, 0)] = 0; // mined
        now[1] = new byte[ChunkSnapshot.SECTION_VOLUME];
        now[1][ChunkSnapshot.index(2, 3, 4)] = ChunkSnapshot.blockHash(77, false); // placed
        ChunkSnapshot after = snap(now);

        SnapshotDiff diff = new SnapshotDiff();
        diff.compare(before, after, p -> p == chestPos);
        SnapshotDiff.Result r = diff.result();
        assertArrayEquals(new long[] {BlockPosPacking.pack(48, -64, -32)}, r.removed());
        assertArrayEquals(new long[] {BlockPosPacking.pack(50, -64 + 16 + 3, -32 + 4)}, r.added());
        assertArrayEquals(new long[] {chestPos}, r.emptied());
        assertEquals(2, r.changedSections());
        assertEquals(1, r.comparedChunks());
    }

    @Test
    void identicalAndCoarseSnapshots() {
        SnapshotDiff diff = new SnapshotDiff();
        diff.compare(snap(ground()), snap(ground()), null);
        assertTrue(diff.result().isEmpty());

        diff.reset();
        ChunkSnapshot coarseA = new ChunkSnapshot(0, 0, 0, 0, new int[] {1, 0}, null, null);
        ChunkSnapshot coarseB = new ChunkSnapshot(0, 0, 0, 0, new int[] {2, 0}, null, null);
        diff.compare(coarseA, coarseB, null);
        assertEquals(1, diff.result().changedSections());
        assertEquals(0, diff.result().added().length);
    }

    @Test
    void storeRoundTripsCompactly() throws Exception {
        SnapshotStore store = new SnapshotStore();
        store.put(snap(ground(), 42L));
        store.put(new ChunkSnapshot(9, 9, -4, 5, new int[] {7, 0, 0, 0}, null, null));
        assertTrue(store.consumeDirty());
        byte[] bytes = store.toBytes();
        assertTrue(bytes.length < 2000, "gzip should shrink uniform sections, got " + bytes.length);

        SnapshotStore back = new SnapshotStore();
        back.fromBytes(bytes);
        assertEquals(2, back.size());
        ChunkSnapshot s = back.get(3, -2);
        assertNotNull(s);
        assertArrayEquals(new long[] {42L}, s.containers);
        assertNull(s.blocks[1]);
        assertArrayEquals(ground()[0], s.blocks[0]);
        assertNull(back.get(9, 9).blocks);
    }
}
