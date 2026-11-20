package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalParserTest {

    private static final ItemNameMatcher MATCHER = new ItemNameMatcher(List.of(
            "minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:diamond_block",
            "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:iron_ingot", "minecraft:iron_ore",
            "minecraft:oak_log", "minecraft:oak_planks", "minecraft:hopper", "minecraft:torch", "minecraft:cobblestone",
            "minecraft:obsidian", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe"));

    private final GoalParser parser = new GoalParser(() -> MATCHER,
            name -> name.equalsIgnoreCase("base")
                    ? Optional.of(new GoalParser.WaypointTarget(100, 70, -20, "minecraft:overworld"))
                    : Optional.empty(),
            () -> DataSourceMode.OBSERVED_ONLY, () -> PackedPos.pack(10, 64, 20), () -> "minecraft:overworld");

    private GoalRequest ok(String cmd) {
        ParseResult r = parser.parse(cmd);
        assertTrue(r.isOk(), () -> cmd + " -> " + r.error() + " " + r.suggestions());
        return r.request();
    }

    @Test
    void gatherWithPluralAndQuantityForms() {
        for (String cmd : List.of("mine 32 diamonds", "gather x32 diamond", "collect 32x diamonds",
                "get 32 of diamonds")) {
            GoalRequest.Gather g = assertInstanceOf(GoalRequest.Gather.class, ok(cmd));
            assertEquals("minecraft:diamond", g.itemId(), cmd);
            assertEquals(32, g.count(), cmd);
        }
    }

    @Test
    void craftMultiWordAndFuzzyNames() {
        GoalRequest.Craft c = assertInstanceOf(GoalRequest.Craft.class, ok("craft iron pickaxe"));
        assertEquals("minecraft:iron_pickaxe", c.itemId());
        assertEquals(1, c.count());
        assertEquals("minecraft:hopper", ((GoalRequest.Craft) ok("craft 16 hoppers")).itemId());
        assertEquals("minecraft:obsidian", ((GoalRequest.Gather) ok("mine 10 obsidain")).itemId());
        assertEquals("minecraft:iron_ingot", ((GoalRequest.Gather) ok("gather 5 iron ingots")).itemId());
    }

    @Test
    void modifiers() {
        GoalRequest.Gather g = (GoalRequest.Gather) ok("mine 4 diamonds cost=safest source=hybrid");
        assertEquals(CostProfile.SAFEST, g.cost());
        assertEquals(DataSourceMode.HYBRID, g.dataSource());
        GoalRequest.Gather d = (GoalRequest.Gather) ok("mine 4 diamonds");
        assertEquals(CostProfile.FASTEST, d.cost());
        assertEquals(DataSourceMode.OBSERVED_ONLY, d.dataSource());
        ParseResult bad = parser.parse("mine 4 diamonds cost=quickest");
        assertFalse(bad.isOk());
        assertFalse(bad.suggestions().isEmpty());
    }

    @Test
    void travelCoordsRelativeAndWaypoint() {
        GoalRequest.Travel t = (GoalRequest.Travel) ok("goto 100 ~ ~-5");
        assertEquals(100, t.x());
        assertEquals(64, t.y());
        assertEquals(15, t.z());
        GoalRequest.Travel w = (GoalRequest.Travel) ok("travel to Base");
        assertEquals(100, w.x());
        assertEquals(-20, w.z());
        assertFalse(parser.parse("travel to Nowhere").isOk());
    }

    @Test
    void buildWithGatherOnly() {
        GoalRequest.Build b = (GoalRequest.Build) ok("build schematic house.litematic gather-only");
        assertEquals("house.litematic", b.schematicPath());
        assertTrue(b.gatherOnly());
    }

    @Test
    void errorsCarrySuggestions() {
        ParseResult verb = parser.parse("mien 3 diamonds");
        assertFalse(verb.isOk());
        assertTrue(verb.suggestions().contains("mine"), verb.suggestions().toString());
        ParseResult ambiguous = parser.parse("craft 1 pick");
        assertFalse(ambiguous.isOk());
        assertTrue(ambiguous.suggestions().contains("minecraft:iron_pickaxe"), ambiguous.suggestions().toString());
        assertFalse(parser.parse("mine 0 diamonds").isOk());
        assertFalse(parser.parse("").isOk());
        assertFalse(parser.parse("mine 3 zzqqxx").isOk());
    }
}
