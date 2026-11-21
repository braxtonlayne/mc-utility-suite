package com.utilitysuite.tools.commands;

import com.utilitysuite.tools.commands.DotCommandParser.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DotCommandParserTest {

    @Test
    void ordinaryChatIsNotConsumed() {
        assertNull(DotCommandParser.parse("hello there", "."));
        assertNull(DotCommandParser.parse("", "."));
        assertNull(DotCommandParser.parse("!t fish", "."));
    }

    @Test
    void everyPrefixedLineIsConsumedEvenTypos() {
        for (String s : new String[]{".", ". ", ".tgl x", ".unknown stuff", ".GOAL"}) {
            assertNotNull(DotCommandParser.parse(s, "."), s);
        }
        assertEquals(Kind.EMPTY, DotCommandParser.parse(".", ".").kind());
        DotCommandParser.Command c = DotCommandParser.parse(".frobnicate now", ".");
        assertEquals(Kind.UNKNOWN, c.kind());
        assertEquals("frobnicate", c.name());
    }

    @Test
    void commandsParse() {
        DotCommandParser.Command t = DotCommandParser.parse(".t auto_fish", ".");
        assertEquals(Kind.TOGGLE, t.kind());
        assertEquals("auto_fish", t.arg());
        assertEquals(Kind.STOP, DotCommandParser.parse(".stop", ".").kind());
        assertEquals(Kind.HELP, DotCommandParser.parse(".help", ".").kind());
        assertEquals(Kind.LIST, DotCommandParser.parse(".list", ".").kind());
        DotCommandParser.Command g = DotCommandParser.parse(".goal gather 32 minecraft:iron_ingot", ".");
        assertEquals(Kind.GOAL, g.kind());
        assertEquals("gather 32 minecraft:iron_ingot", g.arg());
        assertEquals(Kind.GOAL, DotCommandParser.parse(".GOAL x", ".").kind());
    }

    @Test
    void quotedToggleArgument() {
        assertEquals("Auto Fish", DotCommandParser.parse(".t \"Auto Fish\"", ".").arg());
        assertEquals("Auto Fish", DotCommandParser.parse(".toggle 'Auto Fish'", ".").arg());
    }

    @Test
    void customAndBlankPrefix() {
        assertEquals(Kind.STOP, DotCommandParser.parse("#stop", "#").kind());
        assertNull(DotCommandParser.parse(".stop", "#"));
        assertEquals(Kind.STOP, DotCommandParser.parse(".stop", "   ").kind(), "blank prefix falls back to '.'");
        assertNull(DotCommandParser.parse("stop", ""));
    }

    @Test
    void moduleMatching() {
        List<String> ids = List.of("auto_fish", "auto_respawn", "offhand_manager");
        List<String> names = List.of("Auto-Fish", "Auto-Respawn", "Offhand Manager");
        assertEquals(0, DotCommandParser.matchModule("auto_fish", ids, names));
        assertEquals(0, DotCommandParser.matchModule("Auto Fish", ids, names));
        assertEquals(2, DotCommandParser.matchModule("offhand", ids, names));
        assertEquals(-1, DotCommandParser.matchModule("auto", ids, names), "ambiguous prefix");
        assertEquals(-1, DotCommandParser.matchModule("nothing", ids, names));
        assertEquals(-1, DotCommandParser.matchModule("", ids, names));
    }
}
