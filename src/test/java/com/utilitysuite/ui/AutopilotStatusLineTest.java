package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.JobStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopilotStatusLineTest {

    @Test
    void reflexLineIsTheConstantAndUnchangedUpdatesDoNotRebuild() {
        HudStatusLine line = new HudStatusLine();
        assertTrue(line.updateAutopilot(ReflexChips.statusLine(Reflex.FALL_CLUTCH)));
        assertSame(ReflexChips.statusLine(Reflex.FALL_CLUTCH), line.text());
        assertTrue(line.isAutopilot());
        int r = line.rebuilds();
        for (int i = 0; i < 100; i++) {
            assertFalse(line.updateAutopilot(ReflexChips.statusLine(Reflex.FALL_CLUTCH)));
        }
        assertEquals(r, line.rebuilds());
        assertEquals("", line.progressText());
    }

    @Test
    void jobAndClearReplaceTheReflexLine() {
        HudStatusLine line = new HudStatusLine();
        line.update("gather 4 minecraft:coal", JobStatus.RUNNING, 1, 4, "Mining");
        String job = line.text();
        assertTrue(line.updateAutopilot(ReflexChips.statusLine(Reflex.TOTEM)));
        assertEquals("Autopilot: totem", line.text());
        // the same job snapshot again must restore the job line, not be skipped as unchanged
        assertTrue(line.update("gather 4 minecraft:coal", JobStatus.RUNNING, 1, 4, "Mining"));
        assertEquals(job, line.text());
        assertFalse(line.isAutopilot());
        line.updateAutopilot(ReflexChips.statusLine(Reflex.SHIELD));
        assertTrue(line.updateAutopilot(null));
        assertTrue(line.isEmpty());
        assertFalse(line.isAutopilot());
    }
}
