package com.utilitysuite.ui;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The word wrap behind the goal console's preview card and inline error (6 px per character). */
class GoalConsoleWrapTest {

    private static int w(String s) {
        return s.length() * 6;
    }

    @Test
    void wrapsAtWordBoundaries() {
        List<String> lines = GoalConsole.wrap("Could not read that goal", 12 * 6, GoalConsoleWrapTest::w);
        assertEquals(List.of("Could not", "read that", "goal"), lines);
    }

    @Test
    void shortTextIsOneLineAndEmptyIsNone() {
        assertEquals(List.of("gather 32"), GoalConsole.wrap("gather 32", 200, GoalConsoleWrapTest::w));
        assertTrue(GoalConsole.wrap("", 200, GoalConsoleWrapTest::w).isEmpty());
        assertTrue(GoalConsole.wrap(null, 200, GoalConsoleWrapTest::w).isEmpty());
    }

    @Test
    void aWordWiderThanTheLineIsCutAndNothingIsLost() {
        List<String> lines = GoalConsole.wrap("minecraft:netherite_upgrade_smithing_template", 10 * 6,
                GoalConsoleWrapTest::w);
        assertTrue(lines.size() > 1);
        StringBuilder all = new StringBuilder();
        for (String l : lines) {
            assertTrue(w(l) <= 60, l);
            all.append(l);
        }
        assertEquals("minecraft:netherite_upgrade_smithing_template", all.toString());
    }
}
