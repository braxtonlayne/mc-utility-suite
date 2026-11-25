package com.utilitysuite.tools.enchant;

import com.utilitysuite.tools.enchant.AnvilPlanner.Merge;
import com.utilitysuite.tools.enchant.AnvilPlanner.Piece;
import com.utilitysuite.tools.enchant.AnvilPlanner.Plan;
import com.utilitysuite.tools.enchant.AnvilPlanner.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilPlannerTest {

    private static final Set<String> SWORD = Set.of("minecraft:sharpness", "minecraft:smite",
            "minecraft:bane_of_arthropods", "minecraft:knockback", "minecraft:fire_aspect", "minecraft:looting",
            "minecraft:sweeping_edge", "minecraft:unbreaking", "minecraft:mending", "minecraft:vanishing_curse");
    private static final Set<String> BOW = Set.of("minecraft:power", "minecraft:punch", "minecraft:flame",
            "minecraft:infinity", "minecraft:unbreaking", "minecraft:mending");

    private static Piece book(String id, int lvl) {
        return new Piece(id.substring(id.indexOf(':') + 1), true, 0, Map.of(id, lvl));
    }

    private static Piece item(String label, int rc) {
        return new Piece(label, false, rc, Map.of());
    }

    @Test
    void singleMendingBookCostsTwo() {
        Plan p = AnvilPlanner.plan(item("sword", 0), List.of(book("minecraft:mending", 1)), SWORD, false);
        assertEquals(2, p.totalLevels());
        assertTrue(p.complete());
        assertEquals(1, p.result().repairCost());
        assertEquals(1, p.result().enchants().get("minecraft:mending"));
    }

    @Test
    void sharpnessAndMendingKnownOptimum() {
        // sword+Sharp V = 5 (penalty 1), then + Mending = 1 + 2 = 3: 8 total, worst step 5
        Plan p = AnvilPlanner.plan(item("sword", 0),
                List.of(book("minecraft:sharpness", 5), book("minecraft:mending", 1)), SWORD, false);
        assertEquals(8, p.totalLevels());
        assertEquals(5, p.maxStep());
        assertEquals(2, p.steps().size());
    }

    @Test
    void equalLevelsCombineUpward() {
        Plan p = AnvilPlanner.plan(item("sword", 0),
                List.of(book("minecraft:sharpness", 4), book("minecraft:sharpness", 4)), SWORD, false);
        assertEquals(5, p.result().enchants().get("minecraft:sharpness"));
        assertEquals(10, p.totalLevels());
    }

    @Test
    void penaltyDoublesPlusOne() {
        Merge m = AnvilPlanner.merge(item("sword", 3), book("minecraft:unbreaking", 3), SWORD);
        assertNotNull(m);
        assertEquals(3 + 3, m.cost());
        assertEquals(7, m.result().repairCost());
    }

    @Test
    void tooExpensiveIsRefusedInSurvivalOnly() {
        Piece worn = item("sword", 63);
        Plan p = AnvilPlanner.plan(worn, List.of(book("minecraft:mending", 1)), SWORD, false);
        assertTrue(p.empty());
        assertFalse(p.complete());
        assertEquals(List.of(0), p.skipped());
        Plan c = AnvilPlanner.plan(worn, List.of(book("minecraft:mending", 1)), SWORD, true);
        assertEquals(65, c.totalLevels());
    }

    @Test
    void incompatibleBookIsSkipped() {
        Piece sharp = new Piece("sword", false, 0, Map.of("minecraft:sharpness", 5));
        Plan p = AnvilPlanner.plan(sharp, List.of(book("minecraft:smite", 5), book("minecraft:unbreaking", 3)),
                SWORD, false);
        assertEquals(List.of(0), p.skipped());
        assertEquals(List.of(1), p.used());
        assertNull(AnvilPlanner.merge(sharp, book("minecraft:smite", 5), SWORD));
    }

    @Test
    void infinityAndMendingConflictKeepsCheaper() {
        Plan p = AnvilPlanner.plan(item("bow", 0),
                List.of(book("minecraft:infinity", 1), book("minecraft:mending", 1)), BOW, false);
        assertFalse(p.complete());
        assertEquals(List.of(1), p.used());
        assertEquals(2, p.totalLevels());
    }

    @Test
    void exclusiveSets() {
        assertFalse(AnvilPlanner.compatible("minecraft:density", "minecraft:breach"));
        assertFalse(AnvilPlanner.compatible("minecraft:protection", "minecraft:blast_protection"));
        assertFalse(AnvilPlanner.compatible("minecraft:riptide", "minecraft:channeling"));
        assertFalse(AnvilPlanner.compatible("minecraft:multishot", "minecraft:piercing"));
        assertFalse(AnvilPlanner.compatible("minecraft:silk_touch", "minecraft:fortune"));
        assertTrue(AnvilPlanner.compatible("minecraft:loyalty", "minecraft:channeling"));
        assertTrue(AnvilPlanner.compatible("minecraft:sharpness", "minecraft:looting"));
    }

    @Test
    void godSwordBeatsEveryLinearOrderAndReplays() {
        List<Piece> books = List.of(book("minecraft:sharpness", 5), book("minecraft:unbreaking", 3),
                book("minecraft:mending", 1), book("minecraft:looting", 3), book("minecraft:fire_aspect", 2),
                book("minecraft:sweeping_edge", 3), book("minecraft:knockback", 2));
        Piece sword = item("sword", 0);
        Plan p = AnvilPlanner.plan(sword, books, SWORD, false);
        assertTrue(p.complete());
        assertEquals(7, p.result().enchants().size());
        assertTrue(p.maxStep() < AnvilPlanner.TOO_EXPENSIVE);
        // replay the steps through the vanilla merge and check the reported costs
        Map<Integer, Piece> nodes = new HashMap<>();
        for (int i = 0; i < books.size(); i++) {
            nodes.put(i, books.get(i));
        }
        nodes.put(books.size(), sword);
        int total = 0;
        for (Step s : p.steps()) {
            Piece l = nodes.get(s.left());
            Merge m = AnvilPlanner.merge(l, nodes.get(s.right()), l.book() ? null : SWORD);
            assertNotNull(m);
            assertEquals(s.cost(), m.cost());
            total += m.cost();
            nodes.put(s.result(), m.result());
        }
        assertEquals(p.totalLevels(), total);
        // every one-at-a-time order onto the sword is at least as expensive (or too expensive)
        int bestLinear = bestLinear(sword, new ArrayList<>(books), 0);
        assertTrue(p.totalLevels() <= bestLinear, p.totalLevels() + " vs " + bestLinear);
        assertTrue(p.totalLevels() < bestLinear, "tree should beat one-at-a-time for 7 books");
    }

    private static int bestLinear(Piece cur, List<Piece> rest, int spent) {
        if (rest.isEmpty()) {
            return spent;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < rest.size(); i++) {
            Merge m = AnvilPlanner.merge(cur, rest.get(i), SWORD);
            if (m == null || m.cost() >= AnvilPlanner.TOO_EXPENSIVE) {
                continue;
            }
            List<Piece> r = new ArrayList<>(rest);
            r.remove(i);
            best = Math.min(best, bestLinear(m.result(), r, spent + m.cost()));
        }
        return best;
    }

    @Test
    void linesNameSteps() {
        List<Piece> books = List.of(book("minecraft:sharpness", 5), book("minecraft:mending", 1));
        Piece sword = item("sword", 0);
        Plan p = AnvilPlanner.plan(sword, books, SWORD, false);
        List<String> lines = AnvilPlanner.lines(p, sword, books);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("1. sword + "));
        assertTrue(lines.get(2).startsWith("Total 8"));
    }
}
