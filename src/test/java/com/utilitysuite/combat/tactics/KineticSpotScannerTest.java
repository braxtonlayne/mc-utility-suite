package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KineticSpotScannerTest {

    /** Flat floor at y <= 63, plus a wall column at x = 2 (y 64..66). */
    private static boolean world(int x, int y, int z) {
        return y <= 63 || (x == 2 && y >= 64 && y <= 66);
    }

    @Test
    void floorUnderFeetIsTheBestSpot() {
        KineticSpotScanner s = new KineticSpotScanner(64);
        s.scan(0.5, 64, 0.5, 0, 0, 0, KineticSpotScannerTest::world);
        assertTrue(s.count > 0);
        int under = s.find(0, 63, 0, 0);
        assertTrue(under >= 0, "floor under the feet is a spot");
        assertEquals(under, s.best);
        assertTrue(s.apex[under] > 0);
    }

    @Test
    void wallFacesAreFoundAndCoveredFacesSkipped() {
        KineticSpotScanner s = new KineticSpotScanner(64);
        s.scan(1.2, 64, 0.5, 0, 0, 0, KineticSpotScannerTest::world);
        assertTrue(s.find(2, 64, 0, 2) >= 0, "west face of the wall");
        assertEquals(-1, s.find(0, 62, 0, 0), "buried floor face");
        for (int i = 0; i < s.count; i++) {
            assertTrue(s.apex[i] <= s.apex[s.best]);
        }
    }

    @Test
    void capacityIsRespectedAndNothingInMidAir() {
        KineticSpotScanner small = new KineticSpotScanner(3);
        small.scan(0.5, 64, 0.5, 0, 0, 0, KineticSpotScannerTest::world);
        assertEquals(3, small.count);
        KineticSpotScanner s = new KineticSpotScanner(8);
        s.scan(0.5, 100, 0.5, 0, 0, 0, KineticSpotScannerTest::world);
        assertEquals(0, s.count);
        assertEquals(-1, s.best);
    }

    @Test
    void faceLookup() {
        assertEquals(0, KineticSpotScanner.faceOf(0, 1, 0));
        assertEquals(-1, KineticSpotScanner.faceOf(0, -1, 0));
        assertEquals(4, KineticSpotScanner.faceOf(0, 0, -1));
    }
}
