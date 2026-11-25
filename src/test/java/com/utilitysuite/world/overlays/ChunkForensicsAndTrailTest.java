package com.utilitysuite.world.overlays;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkForensicsAndTrailTest {

    @Test
    void paletteForensicsUpdatesStateAndFlags() {
        ChunkAgeTracker tracker = new ChunkAgeTracker();
        tracker.onChunkLoad(3, -4, 100L, false);
        assertEquals(ChunkAgeTracker.UNKNOWN, tracker.stateOf(3, -4));

        // Clean palette without modification signals records PALETTE_NEW and leaves state UNKNOWN.
        tracker.onPaletteCheck(3, -4, false, false);
        assertEquals(ChunkAgeTracker.UNKNOWN, tracker.stateOf(3, -4));
        assertEquals(ChunkAgeTracker.PALETTE_NEW, tracker.flagsOf(3, -4));

        // Palette looks old -> transitions UNKNOWN to OLD and records PALETTE_OLD.
        tracker.onPaletteCheck(3, -4, true, false);
        assertEquals(ChunkAgeTracker.OLD, tracker.stateOf(3, -4));
        assertTrue((tracker.flagsOf(3, -4) & ChunkAgeTracker.PALETTE_OLD) != 0);

        // Explicit modification signals (oxidized copper + active spawner) force OLD even if fluid marked NEW.
        tracker.onChunkLoad(10, 20, 100L, true);
        tracker.onFluidUpdate(10, 20, 105L);
        tracker.onFluidUpdate(10, 20, 110L);
        assertEquals(ChunkAgeTracker.NEW, tracker.stateOf(10, 20));

        byte extra = (byte) (ChunkAgeTracker.OXIDIZED_COPPER | ChunkAgeTracker.SPAWNER_ACTIVE);
        tracker.onPaletteCheck(10, 20, true, true, extra);
        assertEquals(ChunkAgeTracker.OLD, tracker.stateOf(10, 20));
        byte flags = tracker.flagsOf(10, 20);
        assertTrue((flags & ChunkAgeTracker.PALETTE_OLD) != 0);
        assertTrue((flags & ChunkAgeTracker.OXIDIZED_COPPER) != 0);
        assertTrue((flags & ChunkAgeTracker.SPAWNER_ACTIVE) != 0);

        tracker.clear();
        assertEquals(0, tracker.size());
        assertEquals(ChunkAgeTracker.UNKNOWN, tracker.stateOf(10, 20));
        assertEquals(ChunkAgeTracker.FLAG_NONE, tracker.flagsOf(10, 20));
    }

    @Test
    void binaryPersistenceRoundTrip() throws IOException {
        ChunkAgeTracker src = new ChunkAgeTracker();
        src.configure(60, 2);

        // NEW chunk
        src.onChunkLoad(-12, 34, 10L, true);
        src.onFluidUpdate(-12, 34, 15L);
        src.onFluidUpdate(-12, 34, 20L);
        src.onPaletteCheck(-12, 34, false, false);

        // OLD chunk via settle
        src.onChunkLoad(5, 6, 10L, true);
        src.settle(100L);

        // OLD chunk via palette/block-entity forensics
        src.onChunkLoad(-100, -200, 10L, false);
        src.onPaletteCheck(-100, -200, true, true,
                (byte) (ChunkAgeTracker.OXIDIZED_COPPER | ChunkAgeTracker.SPAWNER_ACTIVE));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            src.write(out);
        }

        ChunkAgeTracker dst = new ChunkAgeTracker();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            dst.read(in);
        }

        assertEquals(src.size(), dst.size());
        assertEquals(ChunkAgeTracker.NEW, dst.stateOf(-12, 34));
        assertEquals(ChunkAgeTracker.PALETTE_NEW, dst.flagsOf(-12, 34));
        assertEquals(ChunkAgeTracker.OLD, dst.stateOf(5, 6));
        assertEquals(ChunkAgeTracker.OLD, dst.stateOf(-100, -200));
        assertEquals(src.flagsOf(-100, -200), dst.flagsOf(-100, -200));

        // Corrupt magic must throw IOException.
        byte[] bad = baos.toByteArray();
        bad[0] ^= 0xFF;
        assertThrows(IOException.class, () -> {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bad))) {
                dst.read(in);
            }
        });
    }

    @Test
    void trailVectorEstimatesCorridorHeadingAndLookahead() {
        ChunkAgeTracker tracker = new ChunkAgeTracker();
        // Diagonal NE/SE corridor from (0, 0) to (10, 10)
        for (int i = 0; i <= 10; i++) {
            tracker.onChunkLoad(i, i, 0L, false);
            tracker.onPaletteCheck(i, i, true, true);
        }

        TrailVector tv = new TrailVector();
        assertTrue(tv.estimate(tracker, 5, 5, 12, 4, 1.0, 1.0));
        assertTrue(tv.valid());
        assertEquals(11, tv.sampleCount());
        assertTrue(tv.confidence() > 0.9, "expected high confidence for linear corridor, got " + tv.confidence());
        double invSqrt2 = Math.sqrt(0.5);
        assertEquals(invSqrt2, tv.dx(), 1e-3);
        assertEquals(invSqrt2, tv.dz(), 1e-3);
        assertTrue(tv.targetChunkX() > 5);
        assertTrue(tv.targetChunkZ() > 5);

        // Preferred opposite heading flips the corridor direction.
        assertTrue(tv.estimate(tracker, 5, 5, 12, 4, -1.0, -1.0));
        assertEquals(-invSqrt2, tv.dx(), 1e-3);
        assertEquals(-invSqrt2, tv.dz(), 1e-3);
        assertTrue(tv.targetChunkX() < 5);
        assertTrue(tv.targetChunkZ() < 5);
    }

    @Test
    void trailVectorHandlesIsotropicBlobAndInsufficientSamples() {
        ChunkAgeTracker tracker = new ChunkAgeTracker();
        // Single OLD chunk -> invalid
        tracker.onChunkLoad(0, 0, 0L, false);
        tracker.onPaletteCheck(0, 0, true, true);

        TrailVector tv = new TrailVector();
        assertFalse(tv.estimate(tracker, 0, 0, 8, 4));
        assertFalse(tv.valid());
        assertEquals(0.0, tv.confidence());

        // Symmetric 3x3 square -> isotropic, confidence == 0
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                tracker.onChunkLoad(x, z, 0L, false);
                tracker.onPaletteCheck(x, z, true, true);
            }
        }
        assertTrue(tv.estimate(tracker, 0, 0, 8, 4));
        assertTrue(tv.confidence() < 0.05, "expected near-zero confidence for isotropic blob, got " + tv.confidence());
    }
}
