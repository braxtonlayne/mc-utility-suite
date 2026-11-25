package com.utilitysuite.world.overlays;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R5 trail forensics: per-cell timestamps, backward-compatible saves, recency maths and speed classification. */
class TrailRecencyTest {

    private static final long T0 = 1_700_000_000_000L;

    private static ChunkAgeTracker trackerWithNewChunk(int cx, int cz, long loadTick, long wallMs) {
        ChunkAgeTracker t = new ChunkAgeTracker();
        t.configure(60, 2);
        t.onChunkLoad(cx, cz, loadTick, true);
        t.onFluidUpdate(cx, cz, loadTick + 5, wallMs);
        t.onFluidUpdate(cx, cz, loadTick + 10, wallMs + 250);
        return t;
    }

    @Test
    void firstNewClassificationStampsLoadTickAndWallTime() {
        ChunkAgeTracker t = trackerWithNewChunk(4, -9, 1000L, T0);
        assertEquals(ChunkAgeTracker.NEW, t.stateOf(4, -9));
        assertEquals(1000L, t.seenTickOf(4, -9), "game time of the load");
        assertEquals(T0 + 250 - 10 * 50, t.seenWallOf(4, -9), "wall time moved back to the load");
        t.onFluidUpdate(4, -9, 1020L, T0 + 99_999);
        assertEquals(T0 + 250 - 10 * 50, t.seenWallOf(4, -9), "later updates keep the first stamp");
        assertEquals(0L, t.seenWallOf(5, -9), "unknown chunk");
        assertTrue(t.consumeDirty());
        assertFalse(t.consumeDirty());
    }

    @Test
    void version2RoundTripsTimestamps() throws IOException {
        ChunkAgeTracker src = trackerWithNewChunk(-12, 34, 500L, T0);
        src.onChunkLoad(1, 1, 10L, true);
        src.settle(100L); // OLD, no stamp
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            src.write(out);
        }
        ChunkAgeTracker dst = new ChunkAgeTracker();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            dst.read(in);
        }
        assertEquals(2, dst.size());
        assertEquals(ChunkAgeTracker.NEW, dst.stateOf(-12, 34));
        assertEquals(500L, dst.seenTickOf(-12, 34));
        assertEquals(src.seenWallOf(-12, 34), dst.seenWallOf(-12, 34));
        assertEquals(ChunkAgeTracker.OLD, dst.stateOf(1, 1));
        assertEquals(0L, dst.seenWallOf(1, 1));
    }

    @Test
    void version1SavesStillLoadWithoutTimestamps() throws IOException {
        ChunkAgeTracker src = trackerWithNewChunk(7, 7, 200L, T0);
        src.onPaletteCheck(7, 7, false, false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            src.writeV1(out);
        }
        ChunkAgeTracker dst = new ChunkAgeTracker();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            dst.read(in);
        }
        assertEquals(ChunkAgeTracker.NEW, dst.stateOf(7, 7));
        assertEquals(ChunkAgeTracker.PALETTE_NEW, dst.flagsOf(7, 7));
        assertEquals(0L, dst.seenTickOf(7, 7), "v1 has no stamps: unknown");
        assertEquals(TrailRecency.UNKNOWN_FADE, TrailRecency.fade(dst.seenWallOf(7, 7), T0, 60_000), 1e-6);
        assertEquals("age unknown", TrailRecency.ageLabel(dst.seenWallOf(7, 7), T0));
    }

    @Test
    void loadingMergesAndLiveClassificationWins() throws IOException {
        ChunkAgeTracker saved = trackerWithNewChunk(3, 3, 10L, T0);
        saved.onChunkLoad(0, 0, 10L, true);
        saved.settle(100L); // (0, 0) is OLD in the file
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            saved.write(out);
        }
        ChunkAgeTracker live = trackerWithNewChunk(0, 0, 900L, T0 + 5000); // seen NEW this session
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            live.read(in);
        }
        assertEquals(ChunkAgeTracker.NEW, live.stateOf(0, 0));
        assertEquals(900L, live.seenTickOf(0, 0));
        assertEquals(ChunkAgeTracker.NEW, live.stateOf(3, 3), "file entries merged in");
    }

    @Test
    void fadeAndAgeLabels() {
        long hour = 3_600_000L;
        assertEquals(1f, TrailRecency.fade(T0, T0, hour), 1e-6);
        assertEquals(1f - (1f - TrailRecency.MIN_FADE) * 0.5f, TrailRecency.fade(T0, T0 + hour / 2, hour), 1e-6);
        assertEquals(TrailRecency.MIN_FADE, TrailRecency.fade(T0, T0 + 5 * hour, hour), 1e-6);
        assertEquals(1f, TrailRecency.fade(T0, T0 + 5 * hour, 0), 1e-6, "0 = fading off");
        assertEquals(1f, TrailRecency.fade(T0 + 1000, T0, hour), 1e-6, "clock skew counts as fresh");
        assertEquals(0x80FF4040, TrailRecency.withAlpha(0xFFFF4040, 128 / 255f));
        assertEquals(0x00FF4040, TrailRecency.withAlpha(0xA0FF4040, 0f));

        assertEquals("just now", TrailRecency.ageLabel(T0, T0 + 59_000));
        assertEquals("12 min ago", TrailRecency.ageLabel(T0, T0 + 12 * 60_000 + 5));
        assertEquals("3 h ago", TrailRecency.ageLabel(T0, T0 + 3 * hour + 10));
        assertEquals("1 day ago", TrailRecency.ageLabel(T0, T0 + 25 * hour));
        assertEquals("5 days ago", TrailRecency.ageLabel(T0, T0 + 5 * 24 * hour));
        assertEquals("long ago", TrailRecency.ageLabel(T0, T0 + 400 * 24 * hour));
        assertSame(TrailRecency.ageLabel(T0, T0 + 12 * 60_000), TrailRecency.ageLabel(T0, T0 + 12 * 60_000 + 30_000),
                "labels come from tables: no strings built while drawing");
    }

    @Test
    void speedIsTheSlopeOfPositionOverTime() {
        double[] along = new double[10];
        long[] ticks = new long[10];
        for (int i = 0; i < 10; i++) {
            along[i] = i * 16.0;
            ticks[i] = 1000 + i * 10L; // a chunk every half second: 32 b/s
        }
        assertEquals(32.0, TrailRecency.speed(along, ticks, 10, 20.0, 40), 1e-9);
        long[] ms = new long[10];
        for (int i = 0; i < 10; i++) {
            ms[i] = T0 + i * 4000L; // a chunk every 4 s: 4 b/s
        }
        assertEquals(4.0, TrailRecency.speed(along, ms, 10, 1000.0, 3000), 1e-9);
        ticks[3] = 0;
        ticks[7] = 0; // unknown stamps are skipped
        assertEquals(32.0, TrailRecency.speed(along, ticks, 10, 20.0, 40), 1e-9);
        assertTrue(Double.isNaN(TrailRecency.speed(along, new long[10], 10, 20.0, 40)), "no stamps");
        long[] burst = new long[10];
        java.util.Arrays.fill(burst, 1000L);
        assertTrue(Double.isNaN(TrailRecency.speed(along, burst, 10, 20.0, 40)), "all at once: no speed");
    }

    // ---- classification with real speeds -------------------------------------------------------------------------

    private final TrailClassifier c = new TrailClassifier();

    @Test
    void sparseTrailAtWalkingSpeedIsTravelNotElytra() {
        int n = 30;
        int[] xs = new int[n];
        int[] zs = new int[n];
        long[] ticks = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i * 2; // sparse, the shape alone reads as an elytra highway
            zs[i] = i % 2;
            ticks[i] = 100 + i * 160L; // 32 blocks per 8 s = 4 b/s
        }
        assertEquals(TrailClassifier.Kind.ELYTRA_HIGHWAY, c.classify(xs, zs, n, null, null, false, -10, 0).kind());
        TrailClassifier.Segment s = c.classify(xs, zs, n, null, ticks, null, false, -10, 0);
        assertEquals(TrailClassifier.Kind.TRAVEL, s.kind());
        assertEquals(4.0, s.speedBps(), 0.2);
        assertTrue(s.dirX() > 0.95, "points toward the newest cells");
    }

    @Test
    void fastDryTrailIsFlightAndWallTimeOrientsAndStampsTheSpine() {
        int n = 24;
        int[] xs = new int[n];
        int[] zs = new int[n];
        long[] wall = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = 3;
            zs[i] = -i; // solid line toward -Z
            wall[i] = T0 + i * 500L; // 16 blocks per 0.5 s = 32 b/s, newest at the far -Z end
        }
        TrailClassifier.Segment s = c.classify(xs, zs, n, null, null, wall, false, 3, 50);
        assertEquals(TrailClassifier.Kind.ELYTRA_HIGHWAY, s.kind());
        assertEquals(32.0, s.speedBps(), 0.5);
        assertTrue(s.dirZ() < -0.95);
        assertEquals(T0 + (n - 1) * 500L, s.latestWallMs());
        assertEquals(T0, s.oldestWallMs());
        long[] sw = s.spineWallMs();
        assertEquals(s.spineX().length, sw.length);
        assertTrue(sw[sw.length - 1] > sw[0], "the spine end (newest) has the newest stamp");
    }

    @Test
    void wetTrailKeepsBoatUnlessFasterThanAnyBoat() {
        int n = 30;
        int[] xs = new int[n];
        int[] zs = new int[n];
        boolean[] wet = new boolean[n];
        long[] ticks = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i;
            wet[i] = true;
            ticks[i] = 100 + i * 40L; // 8 b/s: a boat on water
        }
        assertEquals(TrailClassifier.Kind.BOAT_RUN, c.classify(xs, zs, n, wet, ticks, null, false, 0, 0).kind());
        for (int i = 0; i < n; i++) {
            ticks[i] = 100 + i * 2L; // 160 b/s
        }
        assertEquals(TrailClassifier.Kind.ELYTRA_HIGHWAY, c.classify(xs, zs, n, wet, ticks, null, false, 0, 0).kind());
    }
}
