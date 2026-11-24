package com.utilitysuite.ui;

import com.utilitysuite.core.api.PlanPreview;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The console preview's estimate and tools lines, resolved through the real lang file. */
class PreviewSummaryTest {

    private static JsonObject lang() throws Exception {
        try (InputStream in = PreviewSummaryTest.class.getResourceAsStream("/assets/utilitysuite/lang/en_us.json")) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void estimateAndToolsLinesUseLangKeys() throws Exception {
        JsonObject l = lang();
        java.util.function.BiFunction<String, Object[], String> fmt =
                (k, a) -> String.format(l.get(k).getAsString(), a);
        PlanPreview pv = new PlanPreview(null, "t", List.of("a"), Map.of(), List.of(), 750,
                List.of("minecraft:iron_pickaxe"));
        List<String> lines = GoalConsole.summaryLines(pv, fmt);
        assertEquals(2, lines.size());
        assertEquals("Estimated time: about 13 min", lines.get(0));
        assertTrue(lines.get(1).startsWith("Tools needed: "), lines.get(1));
    }

    @Test
    void durationFormats() throws Exception {
        JsonObject l = lang();
        java.util.function.BiFunction<String, Object[], String> fmt =
                (k, a) -> String.format(l.get(k).getAsString(), a);
        assertEquals("45 s", GoalConsole.duration(45, fmt));
        assertEquals("2 min", GoalConsole.duration(120, fmt));
        assertEquals("1 h 5 min", GoalConsole.duration(3900, fmt));
    }

    @Test
    void nothingToShowWithoutAnEstimate() {
        PlanPreview pv = new PlanPreview(null, "t", List.of(), Map.of(), List.of());
        assertTrue(GoalConsole.summaryLines(pv, (k, a) -> k).isEmpty());
    }
}
