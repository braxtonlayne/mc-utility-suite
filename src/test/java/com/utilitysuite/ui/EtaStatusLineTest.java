package com.utilitysuite.ui;

import com.utilitysuite.core.api.JobStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtaStatusLineTest {

    @Test
    void etaKeyRoundsToTheShownValue() {
        assertEquals(-1, HudStatusLine.etaKey(-1));
        assertEquals(5, HudStatusLine.etaKey(0));
        assertEquals(5, HudStatusLine.etaKey(5));
        assertEquals(10, HudStatusLine.etaKey(6));
        assertEquals(60, HudStatusLine.etaKey(59) );
        assertEquals(1001, HudStatusLine.etaKey(60));
        assertEquals(1002, HudStatusLine.etaKey(61));
        assertEquals("~4m", HudStatusLine.etaText(HudStatusLine.etaKey(200)));
        assertEquals("~45s", HudStatusLine.etaText(HudStatusLine.etaKey(43)));
    }

    @Test
    void etaIsAppendedAndRebuiltOnlyWhenTheShownValueChanges() {
        HudStatusLine line = new HudStatusLine();
        line.update("gather 32 minecraft:diamond", JobStatus.RUNNING, 18, 32, "Mining at Y=-54");
        String base = line.text();
        assertTrue(line.updateEta(200));
        assertEquals(base + " · ETA ~4m", line.text());
        assertEquals("18/32 · ~4m", line.progressText());
        int r = line.rebuilds();
        String text = line.text();
        for (int s = 200; s > 180; s--) { // still "~4m"
            assertFalse(line.updateEta(s));
        }
        assertEquals(r, line.rebuilds());
        assertSame(text, line.text());
        assertTrue(line.updateEta(170)); // ~3m
        assertEquals(base + " · ETA ~3m", line.text());
        // a new snapshot keeps the ETA suffix
        line.update("gather 32 minecraft:diamond", JobStatus.RUNNING, 19, 32, "Mining at Y=-54");
        assertTrue(line.text().endsWith(" · ETA ~3m"));
        assertEquals("19/32 · ~3m", line.progressText());
        // unknown again: the suffix goes away
        assertTrue(line.updateEta(-1));
        assertEquals("19/32", line.progressText());
        assertFalse(line.text().contains("ETA"));
    }

    @Test
    void noEtaOnNonJobLines() {
        HudStatusLine line = new HudStatusLine();
        line.updateSchematic("Placing 12/400");
        assertFalse(line.updateEta(120));
        assertEquals("[Build] Placing 12/400", line.text());
        line.clear();
        assertFalse(line.updateEta(120));
        assertTrue(line.isEmpty());
    }
}
