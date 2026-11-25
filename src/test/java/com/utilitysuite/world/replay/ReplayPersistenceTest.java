package com.utilitysuite.world.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F2 persistence: saved parkour ghosts / personal bests and kill-cam clips. */
class ReplayPersistenceTest {

    private static final String OW = "minecraft:overworld";

    private static GhostLibrary.Course course(String name, double sx, int best, long saved, int samples) {
        double[] xs = new double[samples];
        double[] ys = new double[samples];
        double[] zs = new double[samples];
        for (int i = 0; i < samples; i++) {
            xs[i] = sx + i * 0.25;
            ys[i] = 64 + Math.sin(i * 0.3);
            zs[i] = -20.5 + i * 0.1;
        }
        return GhostLibrary.course(name, OW, sx, 64, -20.5, sx + 30, 66, -10.5, best, saved, xs, ys, zs, samples);
    }

    @Test
    void ghostLibraryRoundTripsCoursesAndTracks() throws IOException {
        GhostLibrary lib = new GhostLibrary();
        lib.put(course("Spiral", 100.5, 240, 1000, 241), false);
        lib.put(course("Tower", 500.5, 180, 2000, 181), false);
        byte[] bytes = lib.toBytes();

        GhostLibrary back = new GhostLibrary();
        back.fromBytes(bytes);
        assertEquals(2, back.size());
        List<GhostLibrary.Course> list = back.list();
        assertEquals("Tower", list.get(0).name(), "newest save first");
        GhostLibrary.Course spiral = back.match(OW, 100.7, 64.2, -20.4, 130.4, 66, -10.6);
        assertNotNull(spiral, "markers within the tolerance find the course");
        assertEquals("Spiral", spiral.name());
        assertEquals(240, spiral.bestTicks());
        double[] xs = new double[ParkourSplits.MAX_TICKS];
        double[] ys = new double[ParkourSplits.MAX_TICKS];
        double[] zs = new double[ParkourSplits.MAX_TICKS];
        assertEquals(241, spiral.copyTrack(xs, ys, zs));
        assertEquals(100.5 + 100 * 0.25, xs[100], 1e-4);
        assertEquals(64 + Math.sin(100 * 0.3), ys[100], 1e-4);
        assertNull(back.match("minecraft:the_nether", 100.5, 64, -20.5, 130.5, 66, -10.5), "other dimension");
        assertNull(back.match(OW, 104, 64, -20.5, 130.5, 66, -10.5), "start too far away");
        assertTrue(bytes.length < 8 * 1024, "float offsets keep two courses under 8 KB: " + bytes.length);
    }

    @Test
    void slowerRunDoesNotReplaceBestUnlessForced() {
        GhostLibrary lib = new GhostLibrary();
        lib.put(course("A", 0.5, 200, 1, 201), false);
        GhostLibrary.Course kept = lib.put(course("", 0.5, 260, 2, 261), false);
        assertEquals(200, kept.bestTicks());
        GhostLibrary.Course faster = lib.put(course("", 0.5, 150, 3, 151), false);
        assertEquals(150, faster.bestTicks());
        assertEquals("A", faster.name(), "an unnamed replacement keeps the name");
        assertEquals(300, lib.put(course("Manual", 0.5, 300, 4, 301), true).bestTicks(), "explicit save wins");
        assertEquals(1, lib.size());
        assertTrue(lib.consumeDirty());
        assertFalse(lib.consumeDirty());
    }

    @Test
    void libraryIsSizeCappedByOldestSave() {
        GhostLibrary lib = new GhostLibrary();
        for (int i = 0; i < GhostLibrary.MAX_COURSES + 5; i++) {
            lib.put(course("C" + i, i * 100 + 0.5, 100, 1000 + i, 20), false);
        }
        assertEquals(GhostLibrary.MAX_COURSES, lib.size());
        assertNull(lib.match(OW, 0.5, 64, -20.5, 30.5, 66, -10.5), "the oldest went first");
        assertNotNull(lib.match(OW, (GhostLibrary.MAX_COURSES + 4) * 100 + 0.5, 64, -20.5,
                (GhostLibrary.MAX_COURSES + 4) * 100 + 30.5, 66, -10.5));
        assertTrue(lib.remove(lib.list().get(0).id()));
        assertEquals(GhostLibrary.MAX_COURSES - 1, lib.size());
    }

    @Test
    void ghostFileRejectsForeignBytes() {
        GhostLibrary lib = new GhostLibrary();
        assertThrows(IOException.class, () -> lib.fromBytes(new byte[] {1, 2, 3, 4}));
        assertEquals(0, lib.size());
    }

    @Test
    void splitsExportGhostAndFlagNewBestOnce() {
        ParkourSplits s = new ParkourSplits();
        s.setStart(0, 64, 0);
        s.setFinish(10, 64, 0);
        s.tick(0, 64, 0); // armed
        for (int i = 1; i <= 10; i++) {
            s.tick(i * 1.0 + 0.6, 64, 0);
        }
        assertEquals(ParkourSplits.State.FINISHED, s.state());
        assertTrue(s.consumeNewBest());
        assertFalse(s.consumeNewBest());
        double[] xs = new double[ParkourSplits.MAX_TICKS];
        double[] ys = new double[ParkourSplits.MAX_TICKS];
        double[] zs = new double[ParkourSplits.MAX_TICKS];
        int n = s.copyGhost(xs, ys, zs);
        assertEquals(s.ghostLength(), n);
        assertTrue(n > 5);
        s.clearGhost();
        assertEquals(0, s.ghostLength());
        assertEquals(-1, s.bestTicks());
    }

    // ---- kill-cam clips ------------------------------------------------------------------------------------------

    private static ReplayRing recorded(int frames) {
        ReplayRing r = new ReplayRing();
        for (int t = 0; t < frames; t++) {
            r.beginFrame(5000 + t);
            r.addEntity(7, 1000.25 + t * 0.2, 70, -3000.75, t * 3f, -10f, 0.2f, 0f, 0f, 0.6f, 1.8f,
                    (byte) (ReplayRing.F_SELF | ReplayRing.F_PLAYER));
            if (t % 2 == 0) {
                r.addEntity(42, 1003 + t * 0.1, 70, -3001, 180f, 0f, 0.1f, 0f, 0f, 0.6f, 1.95f,
                        (byte) (ReplayRing.F_HOSTILE | (t == 10 ? ReplayRing.F_HURT : 0)));
            }
        }
        r.addEvent(5010, ReplayRing.EV_HIT_DEALT, 12f, 42);
        r.addEvent(5012, ReplayRing.EV_HIT_TAKEN, 18f, 7);
        return r;
    }

    @Test
    void clipRoundTripKeepsPosesEventsAndSelf() throws IOException {
        ReplayRing src = recorded(120);
        byte[] file = ReplayClipCodec.compress(ReplayClipCodec.encode(src, 7, 123456789L));
        ReplayClipCodec.Info info = ReplayClipCodec.readInfo(file);
        assertEquals(123456789L, info.savedMs());
        assertEquals(7, info.selfId());
        assertEquals(120, info.frames());
        assertEquals(2, info.events());
        assertEquals(119 / 20.0, info.durationSeconds(), 1e-9);

        ReplayClipCodec.Clip clip = ReplayClipCodec.decode(file);
        ReplayRing back = clip.ring();
        assertTrue(back.frozen(), "a loaded clip is read-only");
        assertEquals(src.frames(), back.frames());
        for (int f = 0; f < src.frames(); f++) {
            assertEquals(src.tickOf(f), back.tickOf(f));
            assertEquals(src.count(f), back.count(f));
            for (int s = 0; s < src.count(f); s++) {
                int a = src.at(f, s);
                int b = back.at(f, s);
                assertEquals(src.id(a), back.id(b));
                assertEquals(src.x(a), back.x(b), 1e-3);
                assertEquals(src.y(a), back.y(b), 1e-3);
                assertEquals(src.z(a), back.z(b), 1e-3);
                assertEquals(src.yaw(a), back.yaw(b), 1e-6);
                assertEquals(src.height(a), back.height(b), 1e-6);
                assertEquals(src.flags(a), back.flags(b));
            }
        }
        assertEquals(src.tradeGap(5015), back.tradeGap(5015));
        assertEquals(ReplayRing.EV_HIT_TAKEN, back.eventType(1));
        assertThrows(IOException.class, () -> ReplayClipCodec.decode(new byte[] {0x1f, (byte) 0x8b, 0, 0}));
    }

    @Test
    void shelfSavesListsCapsLoadsAndDeletes(@TempDir Path dir) throws IOException {
        ClipShelf shelf = new ClipShelf(Runnable::run); // synchronous for the test
        assertFalse(shelf.isOpen());
        shelf.open(dir.resolve("world_a"));
        byte[] raw = ReplayClipCodec.encode(recorded(40), 7, 1L);
        AtomicReference<String> saved = new AtomicReference<>();
        for (int i = 0; i < ClipShelf.MAX_CLIPS + 3; i++) {
            shelf.save(raw, 1000L + i, saved::set);
        }
        assertEquals(ClipShelf.MAX_CLIPS, shelf.entries().size(), "oldest clips are deleted beyond the cap");
        assertEquals("clip-" + (1000 + ClipShelf.MAX_CLIPS + 2) + ".urc.gz", saved.get());
        assertEquals(saved.get(), shelf.entries().get(0).id(), "newest first");
        try (var files = Files.list(dir.resolve("world_a"))) {
            assertEquals(ClipShelf.MAX_CLIPS, files.count());
        }
        AtomicReference<ReplayClipCodec.Clip> loaded = new AtomicReference<>();
        shelf.load(saved.get(), loaded::set);
        assertNotNull(loaded.get());
        assertEquals(40, loaded.get().ring().frames());
        shelf.delete(saved.get());
        assertEquals(ClipShelf.MAX_CLIPS - 1, shelf.entries().size());
        shelf.load("../escape.urc.gz", loaded::set);
        assertNull(loaded.get(), "ids are plain file names written by the shelf");
        shelf.open(null);
        assertTrue(shelf.entries().isEmpty());
        assertFalse(shelf.isOpen());
    }

    @Test
    void encodedClipIsCompact() throws IOException {
        byte[] raw = ReplayClipCodec.encode(recorded(ReplayRing.FRAMES), 7, 1L);
        byte[] gz = ReplayClipCodec.compress(raw);
        assertTrue(raw.length < ReplayRing.FRAMES * 2 * 50, "raw " + raw.length);
        assertTrue(gz.length < raw.length, "compressed " + gz.length + " vs " + raw.length);
        assertArrayEquals(new byte[] {0x1f, (byte) 0x8b}, java.util.Arrays.copyOf(gz, 2));
    }
}
