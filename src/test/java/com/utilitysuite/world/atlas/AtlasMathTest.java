package com.utilitysuite.world.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasMathTest {

    @Test
    void viewRoundTripsAndPansWithTheMouse() {
        AtlasView v = new AtlasView();
        v.setViewport(400, 300);
        v.center(100, -50);
        v.setScale(2);
        assertEquals(200, v.toScreenX(100), 1e-9);
        assertEquals(150, v.toScreenY(-50), 1e-9);
        assertEquals(123.25, v.toWorldX(v.toScreenX(123.25)), 1e-9);
        double before = v.toScreenX(110);
        v.pan(20, 0);
        assertEquals(before + 20, v.toScreenX(110), 1e-9);
    }

    @Test
    void zoomKeepsThePointUnderTheCursorAndClamps() {
        AtlasView v = new AtlasView();
        v.setViewport(400, 300);
        v.center(0, 0);
        double wx = v.toWorldX(50);
        double wz = v.toWorldZ(260);
        v.zoomAt(50, 260, 1.25);
        assertEquals(wx, v.toWorldX(50), 1e-9);
        assertEquals(wz, v.toWorldZ(260), 1e-9);
        for (int i = 0; i < 100; i++) {
            v.zoomAt(10, 10, 2);
        }
        assertEquals(AtlasView.MAX_SCALE, v.scale(), 1e-12);
        for (int i = 0; i < 100; i++) {
            v.zoomAt(10, 10, 0.5);
        }
        assertEquals(AtlasView.MIN_SCALE, v.scale(), 1e-12);
    }

    @Test
    void isoTopDownIsAPlainScaledMapAndTiltRaisesHeights() {
        AtlasIso iso = new AtlasIso();
        iso.setOrigin(100, 100);
        iso.setScale(2);
        iso.set(0, 0);
        iso.project(10, 50, 5);
        assertEquals(120, iso.sx, 1e-9);
        assertEquals(110, iso.sy, 1e-9); // straight down: height is invisible
        iso.set(0, Math.toRadians(60));
        iso.project(0, 10, 0);
        assertTrue(iso.sy < 100, "higher points move up the screen when tilted");
        iso.rotate(0, 10);
        assertEquals(AtlasIso.MAX_PITCH, iso.pitch(), 1e-12);
        iso.rotate(-100, 0);
        assertTrue(iso.yaw() >= 0 && iso.yaw() < Math.PI * 2);
    }

    @Test
    void paintOrderDrawsFarSideFirst() {
        AtlasIso iso = new AtlasIso();
        iso.setScale(1);
        for (int deg = 0; deg < 360; deg += 30) {
            iso.set(Math.toRadians(deg + 1), Math.toRadians(50));
            int order = iso.paintOrder();
            iso.project(-1, 0, 0);
            double lowX = iso.depth;
            iso.project(1, 0, 0);
            double highX = iso.depth;
            // first drawn = farthest = larger depth
            assertEquals((order & 1) != 0, lowX >= highX, "x order at " + deg);
            iso.project(0, 0, -1);
            double lowZ = iso.depth;
            iso.project(0, 0, 1);
            assertEquals((order & 2) != 0, lowZ >= iso.depth, "z order at " + deg);
        }
    }

    @Test
    void pinHitTestPicksNearestVisibleEnabledPin() {
        AtlasPins pins = new AtlasPins(4);
        pins.add(AtlasPins.WAYPOINT, 0, 0, 0, 0, "a");
        pins.add(AtlasPins.STRUCTURE, 0, 0, 0, 0, "b");
        pins.add(AtlasPins.WAYPOINT, 0, 0, 0, 0, "c");
        pins.screenX[0] = 10;
        pins.screenY[0] = 10;
        pins.screenX[1] = 12;
        pins.screenY[1] = 10;
        pins.screenX[2] = 50;
        pins.screenY[2] = 50;
        int all = (1 << AtlasPins.KINDS) - 1;
        assertEquals(1, pins.hitTest(13, 10, 6, all));
        assertEquals(0, pins.hitTest(13, 10, 6, 1 << AtlasPins.WAYPOINT));
        assertEquals(-1, pins.hitTest(30, 30, 6, all));
        pins.visible[2] = false;
        assertEquals(-1, pins.hitTest(50, 50, 6, all));
        assertTrue(pins.add(AtlasPins.ANOMALY, 0, 0, 0, 0, "d"));
        assertFalse(pins.add(AtlasPins.ANOMALY, 0, 0, 0, 0, "e"));
    }

    @Test
    void storeComposesRegionsAndVersionsThem() {
        AtlasTileStore s = new AtlasTileStore(100);
        assertEquals(0, s.regionVersion(0, 0));
        AtlasTileStore.Tile t = s.obtain(1, 2);
        t.argb[3 * 16 + 4] = 0xFF112233;
        t.height[3 * 16 + 4] = 70;
        s.markWritten(t, 5);
        assertEquals(1, s.regionVersion(0, 0));
        assertEquals(0xFF112233, s.colorAt(16 + 4, 32 + 3, 0));
        assertEquals(70, s.heightAt(16 + 4, 32 + 3));
        assertEquals(AtlasTileStore.NO_HEIGHT, s.heightAt(-1, -1));
        int[] out = new int[AtlasTileStore.REGION_BLOCKS * AtlasTileStore.REGION_BLOCKS];
        s.composeRegion(0, 0, out, 7);
        assertEquals(0xFF112233, out[(32 + 3) * AtlasTileStore.REGION_BLOCKS + 16 + 4]);
        assertEquals(7, out[0]);
        // negative chunks land in region -1
        AtlasTileStore.Tile n = s.obtain(-1, -1);
        s.markWritten(n, 5);
        assertEquals(1, s.regionVersion(-1, -1));
    }

    @Test
    void storeEvictsFarthestBeyondCap() {
        AtlasTileStore s = new AtlasTileStore(64);
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                s.markWritten(s.obtain(x, z), 0);
            }
        }
        assertTrue(s.evictBeyondCap(0, 0) > 0);
        assertTrue(s.size() <= 64);
        assertTrue(s.get(0, 0) != null, "the centre is kept");
        assertTrue(s.get(9, 9) == null, "the far corner goes first");
    }

    @Test
    void meshRebuildsOnlyOnChangeAndFillsGaps() {
        AtlasTileStore s = new AtlasTileStore(100);
        AtlasTileStore.Tile t = s.obtain(0, 0);
        java.util.Arrays.fill(t.height, (short) 80);
        s.markWritten(t, 0);
        AtlasMesh m = new AtlasMesh(8);
        assertTrue(m.rebuildIfNeeded(s, 8, 8, 2, 64, 0));
        assertFalse(m.rebuildIfNeeded(s, 8, 8, 2, 64, 0));
        assertFalse(m.rebuildIfNeeded(s, 9, 9, 2, 64, 0), "moves within a step do not rebuild");
        int centre = m.index(4, 4);
        assertTrue(m.known[centre]);
        assertEquals(80, m.height[centre], 1e-6);
        // the far corner (outside chunk 0,0 at x=16) borrowed a known neighbour height
        assertFalse(m.known[m.index(8, 4)]);
        assertEquals(80, m.height[m.index(8, 4)], 1e-6);
        s.markWritten(t, 1);
        assertTrue(m.rebuildIfNeeded(s, 8, 8, 2, 64, 0));
        assertEquals(2, m.rebuilds());
    }

    @Test
    void spiralStartsAtCentreAndCoversSquare() {
        int[] sp = AtlasSpiral.build(3);
        assertEquals(49, sp.length);
        assertEquals(0, AtlasSpiral.dx(sp[0]));
        assertEquals(0, AtlasSpiral.dz(sp[0]));
        assertEquals(3, Math.abs(AtlasSpiral.dx(sp[48])));
        assertEquals(3, Math.abs(AtlasSpiral.dz(sp[48])));
    }
}
