package com.utilitysuite.tools.enchant;

import com.utilitysuite.tools.enchant.EnchantPlanner.Ench;
import com.utilitysuite.tools.enchant.EnchantPlanner.Kind;
import com.utilitysuite.tools.enchant.EnchantPlanner.Offer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantPlannerTest {

    private static final String SHARP = "minecraft:sharpness";
    private static final String UNB = "minecraft:unbreaking";

    private static List<Offer> table() {
        return List.of(
                new Offer(0, 0, 2, List.of(new Ench(UNB, 1))),
                new Offer(0, 1, 5, List.of(new Ench(SHARP, 1))),
                new Offer(0, 2, 0, List.of()),
                new Offer(8, 0, 4, List.of(new Ench(UNB, 1))),
                new Offer(8, 1, 12, List.of(new Ench(SHARP, 2), new Ench(UNB, 2))),
                new Offer(8, 2, 16, List.of(new Ench(SHARP, 3))),
                new Offer(15, 0, 1, List.of(new Ench("minecraft:smite", 1))),
                new Offer(15, 1, 20, List.of(new Ench(SHARP, 3))),
                new Offer(15, 2, 30, List.of(new Ench(SHARP, 4), new Ench(UNB, 3))));
    }

    @Test
    void picksCheapestAffordableMatch() {
        EnchantPlanner.Plan p = EnchantPlanner.plan(table(), SHARP, 3, 40);
        assertEquals(Kind.MATCH, p.kind());
        assertEquals(8, p.offer().bookshelves());
        assertEquals(2, p.offer().slot());
    }

    @Test
    void lowLevelMatchIsCheapest() {
        EnchantPlanner.Plan p = EnchantPlanner.plan(table(), SHARP, 1, 40);
        assertEquals(Kind.MATCH, p.kind());
        assertEquals(0, p.offer().bookshelves());
        assertEquals(5, p.offer().cost());
    }

    @Test
    void notOfferedAdvisesCheapestDummy() {
        EnchantPlanner.Plan p = EnchantPlanner.plan(table(), SHARP, 5, 40);
        assertEquals(Kind.DUMMY, p.kind());
        // slot 1 spends one level; the lowest requirement among slot-1 offers is the 15-shelf cost 1
        assertEquals(0, p.offer().slot());
        assertEquals(1, p.offer().cost());
        assertEquals(15, p.offer().bookshelves());
        assertTrue(EnchantPlanner.advice(p, SHARP, 5).contains("Reroll"));
    }

    @Test
    void matchAboveLevelsReportsNeedsLevelsOnlyWhenNoDummyIsAffordable() {
        // player at 0 levels cannot even reroll: show where the match is
        EnchantPlanner.Plan p = EnchantPlanner.plan(table(), SHARP, 4, 0);
        assertEquals(Kind.MATCH_NEEDS_LEVELS, p.kind());
        assertEquals(30, p.offer().cost());
        // with 10 levels a reroll is affordable but the match is not: still a match target, so reroll is advised
        assertEquals(Kind.DUMMY, EnchantPlanner.plan(table(), SHARP, 4, 10).kind());
    }

    @Test
    void emptyTableIsNothing() {
        EnchantPlanner.Plan p = EnchantPlanner.plan(List.of(new Offer(0, 0, 0, List.of())), SHARP, 1, 30);
        assertEquals(Kind.NOTHING, p.kind());
        assertNull(p.offer());
    }

    @Test
    void namesAndDescriptions() {
        assertEquals("Fire Aspect", EnchantPlanner.prettyName("minecraft:fire_aspect"));
        assertEquals("Sharpness IV, Unbreaking III",
                EnchantPlanner.describe(List.of(new Ench(SHARP, 4), new Ench(UNB, 3))));
    }
}
