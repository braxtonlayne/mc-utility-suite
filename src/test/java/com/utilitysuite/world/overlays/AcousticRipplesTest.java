package com.utilitysuite.world.overlays;

import com.utilitysuite.tools.packetlab.AcousticRadar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticRipplesTest {

    @Test
    void ringsStaggerExpandAndFadeWithinOneSecond() {
        assertEquals(1000L, AcousticRipples.TOTAL_MS);
        assertEquals(0f, AcousticRipples.ringProgress(0, 0));
        assertEquals(-1f, AcousticRipples.ringProgress(100, 1)); // second ring not started yet
        assertTrue(AcousticRipples.ringProgress(200, 1) > 0f);
        assertEquals(-1f, AcousticRipples.ringProgress(1001, 2));
        float r1 = AcousticRipples.ringRadius(0.25f, 4f);
        float r2 = AcousticRipples.ringRadius(0.75f, 4f);
        assertTrue(r2 > r1 && r2 <= 4f);
        assertEquals(4f, AcousticRipples.ringRadius(1f, 4f), 1e-6);
        assertTrue(AcousticRipples.ringAlpha(0.1f) > AcousticRipples.ringAlpha(0.9f));
        assertEquals(0, AcousticRipples.ringAlpha(1f));
    }

    @Test
    void poolIngestsOnlyNewPositionalPingsAndOverwritesOldest() {
        AcousticRadar radar = new AcousticRadar(16);
        AcousticRipples pool = new AcousticRipples(4);
        radar.recordSound(1, 2, 3, 1000, AcousticRadar.FOOTSTEP);
        radar.recordGlobalBearing(1, 0, 1000, AcousticRadar.PORTAL);
        pool.ingest(radar);
        assertEquals(1, pool.size());
        pool.ingest(radar); // nothing new
        assertEquals(1, pool.size());
        for (int i = 0; i < 6; i++) {
            radar.recordSound(i, 0, 0, 1100 + i, AcousticRadar.CONTAINER);
        }
        pool.ingest(radar);
        assertEquals(4, pool.size());
        int[] colors = new int[1];
        int rings = pool.forEachRing(1400, (x, y, z, r, argb) -> colors[0] = argb);
        assertTrue(rings >= 4 && rings <= 12);
        assertEquals(AcousticRadar.categoryColor(AcousticRadar.CONTAINER) & 0xFFFFFF, colors[0] & 0xFFFFFF);
        assertEquals(0, pool.activeCount(5000));
        assertEquals(0, pool.forEachRing(5000, (x, y, z, r, argb) -> { }));
    }

    @Test
    void radarClearResetsSequence() {
        AcousticRadar radar = new AcousticRadar(8);
        AcousticRipples pool = new AcousticRipples(8);
        radar.recordSound(0, 0, 0, 10, AcousticRadar.OTHER);
        pool.ingest(radar);
        radar.clear();
        radar.recordSound(0, 0, 0, 20, AcousticRadar.OTHER);
        pool.ingest(radar);
        assertEquals(2, pool.size());
    }

    @Test
    void newCategoriesClassify() {
        assertEquals(AcousticRadar.FOOTSTEP, AcousticRadar.classifySound("block.stone.step"));
        assertEquals(AcousticRadar.EATING, AcousticRadar.classifySound("entity.generic.eat"));
        assertEquals(AcousticRadar.EATING, AcousticRadar.classifySound("entity.player.burp"));
        assertEquals(AcousticRadar.SCULK, AcousticRadar.classifySound("entity.warden.heartbeat"));
        assertEquals(AcousticRadar.SCULK, AcousticRadar.classifySound("block.sculk_sensor.clicking"));
        assertEquals(AcousticRadar.PISTON, AcousticRadar.classifySound("block.lever.click"));
        assertEquals(AcousticRadar.CONTAINER, AcousticRadar.classifySound("block.chest.open"));
        assertEquals("Footsteps", AcousticRadar.categoryName(AcousticRadar.FOOTSTEP));
    }

    @Test
    void edgeCuesPointTowardSource() {
        // yaw 0 faces +Z: a source at +Z is straight ahead, at -X is to the right (yaw 90 faces -X).
        assertEquals(0f, AcousticRipples.relativeYaw(0f, 0, 10), 1e-3);
        assertEquals(90f, AcousticRipples.relativeYaw(0f, -10, 0), 1e-3);
        assertEquals(180f, Math.abs(AcousticRipples.relativeYaw(0f, 0, -10)), 1e-3);
        assertEquals(0f, AcousticRipples.relativeYaw(450f, -10, 0), 1e-3);
        float hfov = AcousticRipples.horizontalFov(70f, 16f / 9f);
        assertTrue(hfov > 100f && hfov < 110f);
        assertFalse(AcousticRipples.offScreen(10f, hfov));
        assertTrue(AcousticRipples.offScreen(120f, hfov));
        float[] out = new float[3];
        AcousticRipples.edgePoint(90f, 400, 200, 10, out);
        assertEquals(390f, out[0], 1e-3);
        assertEquals(100f, out[1], 1e-3);
        AcousticRipples.edgePoint(180f, 400, 200, 10, out);
        assertEquals(200f, out[0], 1e-2);
        assertEquals(190f, out[1], 1e-3);
    }
}
