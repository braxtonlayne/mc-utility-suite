package com.utilitysuite.ui.palette;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteLogicTest {

    @Test
    void fuzzyMatchesSubsequencesAndRanksPrefixAndWordStartsFirst() {
        assertEquals(FuzzyScorer.NO_MATCH, FuzzyScorer.score("xyz", "Stop-All"));
        assertTrue(FuzzyScorer.score("sa", "Stop-All") != FuzzyScorer.NO_MATCH);
        List<String> items = List.of("Storage stop list", "Stop-All", "Toggle Clean-Screen", "Sonar pulse");
        List<FuzzyScorer.Ranked<String>> r = FuzzyScorer.rank("stop", items, Function.identity(), 10);
        assertEquals("Stop-All", r.get(0).item());
        assertEquals(2, r.size());
        assertEquals("Toggle Clean-Screen", FuzzyScorer.rank("clean", items, Function.identity(), 10).get(0).item());
        assertEquals(1, FuzzyScorer.rank("s", items, Function.identity(), 1).size());
        assertEquals(4, FuzzyScorer.rank("", items, Function.identity(), 10).size());
    }

    @Test
    void routeIntentWithVia() {
        IntentParser.Intent i = IntentParser.parse("Route to nearest stronghold via nether");
        assertEquals(IntentParser.Kind.ROUTE, i.kind());
        assertEquals("nearest stronghold", i.target());
        assertEquals("nether", i.extra());
        IntentParser.Intent j = IntentParser.parse("route to base");
        assertEquals("base", j.target());
        assertEquals("", j.extra());
    }

    @Test
    void whereAreMyItemsBecomesAStorageLookup() {
        IntentParser.Intent i = IntentParser.parse("where are my diamonds?");
        assertEquals(IntentParser.Kind.FIND_ITEM, i.kind());
        assertEquals("minecraft:diamond", i.target());
        assertEquals("minecraft:iron_ingot", IntentParser.parse("find my iron ingots").target());
        assertEquals("minecraft:torch", IntentParser.parse("where is my torches").target());
        assertEquals("minecraft:glass", IntentParser.parse("where is my glass").target());
    }

    @Test
    void actionsTogglesAndWaypoints() {
        assertEquals(IntentParser.Kind.ACTION, IntentParser.parse("pulse sonar").kind());
        assertEquals("sonar_pulse", IntentParser.parse("sonar").target());
        IntentParser.Intent t = IntentParser.parse("enable world overlays");
        assertEquals(IntentParser.Kind.TOGGLE, t.kind());
        assertEquals(Boolean.TRUE, t.flag());
        assertNull(IntentParser.parse("toggle freecam").flag());
        assertEquals(Boolean.FALSE, IntentParser.parse("turn off minimap").flag());
        IntentParser.Intent g = IntentParser.parse("go to Home Base");
        assertEquals(IntentParser.Kind.GO_TO, g.kind());
        assertEquals("home base", g.target());
        assertEquals(IntentParser.Kind.NONE, IntentParser.parse("stop").kind());
        assertEquals(IntentParser.Kind.NONE, IntentParser.parse("   ").kind());
    }
}
