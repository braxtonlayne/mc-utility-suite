package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.ui.holo.AutopilotHud;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopilotStatusLineTest {

    @Test
    void reflexLineIsTheConstantAndUnchangedUpdatesDoNotRebuild() {
        HudStatusLine line = new HudStatusLine();
        assertTrue(line.updateAutopilot(AutopilotHud.stemLine(Reflex.FALL_CLUTCH)));
        assertSame(AutopilotHud.stemLine(Reflex.FALL_CLUTCH), line.text());
        assertTrue(line.isAutopilot());
        assertEquals("STEM - FALL CLUTCH", line.text());
        int r = line.rebuilds();
        for (int i = 0; i < 100; i++) {
            assertFalse(line.updateAutopilot(AutopilotHud.stemLine(Reflex.FALL_CLUTCH)));
        }
        assertEquals(r, line.rebuilds());
        assertEquals("", line.progressText());
    }

    @Test
    void jobAndClearReplaceTheReflexLine() {
        HudStatusLine line = new HudStatusLine();
        line.update("gather 4 minecraft:coal", JobStatus.RUNNING, 1, 4, "Mining");
        String job = line.text();
        assertTrue(line.updateAutopilot(AutopilotHud.stemLine(Reflex.TOTEM)));
        assertEquals("STEM - TOTEM", line.text());
        // the same job snapshot again must restore the job line, not be skipped as unchanged
        assertTrue(line.update("gather 4 minecraft:coal", JobStatus.RUNNING, 1, 4, "Mining"));
        assertEquals(job, line.text());
        assertFalse(line.isAutopilot());
        line.updateAutopilot(AutopilotHud.stemLine(Reflex.SHIELD));
        assertTrue(line.updateAutopilot(null));
        assertTrue(line.isEmpty());
        assertFalse(line.isAutopilot());
    }
}
