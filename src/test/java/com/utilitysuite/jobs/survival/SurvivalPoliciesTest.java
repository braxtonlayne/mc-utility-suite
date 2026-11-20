package com.utilitysuite.jobs.survival;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalPoliciesTest {

    private static ToolSelector.Candidate[] hotbar() {
        ToolSelector.Candidate[] c = new ToolSelector.Candidate[9];
        for (int i = 0; i < 9; i++) {
            c[i] = new ToolSelector.Candidate().set(i, 1f, false, 0, 0, 1f, false);
        }
        return c;
    }

    @Test
    void autoToolPicksFastestCorrectTool() {
        ToolSelector.Candidate[] c = hotbar();
        c[2].set(2, 6f, true, 0, 0, 1f, false); // iron pick
        c[5].set(5, 8f, true, 0, 0, 1f, true);  // diamond pick
        assertEquals(5, ToolSelector.choose(c, 9, true, ToolSelector.DropPreference.ANY, 0.1f, 0));
        // valuable tool under the protection threshold is skipped
        c[5].set(5, 8f, true, 0, 0, 0.05f, true);
        assertEquals(2, ToolSelector.choose(c, 9, true, ToolSelector.DropPreference.ANY, 0.1f, 0));
        assertTrue(ToolSelector.needsProtection(c[5], 0.1f));
    }

    @Test
    void autoToolRespectsDropPreference() {
        ToolSelector.Candidate[] c = hotbar();
        c[1].set(1, 8f, true, 1, 0, 1f, true);  // silk touch
        c[3].set(3, 8f, true, 0, 3, 1f, true);  // fortune III
        c[4].set(4, 9f, true, 0, 0, 1f, false); // plain, faster
        assertEquals(3, ToolSelector.choose(c, 9, true, ToolSelector.DropPreference.FORTUNE, 0.1f, 0));
        assertEquals(1, ToolSelector.choose(c, 9, true, ToolSelector.DropPreference.SILK, 0.1f, 0));
        assertEquals(4, ToolSelector.choose(c, 9, true, ToolSelector.DropPreference.ANY, 0.1f, 0));
        // nothing strictly better than the current slot: keep it
        assertEquals(4, ToolSelector.choose(c, 9, true, ToolSelector.DropPreference.ANY, 0.1f, 4));
    }

    @Test
    void autoEatThresholdsAndFoodChoice() {
        assertTrue(FoodSelector.shouldEat(14, 20, 20, 14, 0.5f));
        assertFalse(FoodSelector.shouldEat(16, 20, 20, 14, 0.5f));
        assertTrue(FoodSelector.shouldEat(16, 6, 20, 14, 0.5f), "hurt: top up for regeneration");
        assertFalse(FoodSelector.shouldEat(20, 2, 20, 14, 0.5f));

        String[] ids = {"minecraft:rotten_flesh", null, "minecraft:bread", "minecraft:cooked_beef", null, null, null,
                null, null};
        int[] nut = {4, 0, 5, 8, 0, 0, 0, 0, 0};
        float[] sat = {0.1f, 0, 0.6f, 0.8f, 0, 0, 0, 0, 0};
        assertEquals(3, FoodSelector.choose(ids, nut, sat, 10), "very hungry: steak");
        assertEquals(2, FoodSelector.choose(ids, nut, sat, 15), "steak would overfill: bread");
        String[] onlyUnsafe = {"minecraft:rotten_flesh", null, null, null, null, null, null, null, null};
        assertEquals(-1, FoodSelector.choose(onlyUnsafe, nut, sat, 5));
    }

    @Test
    void inventoryPressureDropsJunkKeepsValuablesAndCompacts() {
        List<InventoryPressurePolicy.Stack> stacks = List.of(
                new InventoryPressurePolicy.Stack(10, "minecraft:diamond", 5, false, false),
                new InventoryPressurePolicy.Stack(11, "minecraft:rotten_flesh", 12, false, false),
                new InventoryPressurePolicy.Stack(12, "minecraft:iron_ingot", 20, false, false),
                new InventoryPressurePolicy.Stack(13, "minecraft:poisonous_potato", 2, false, false));
        Set<String> junk = Set.of("minecraft:rotten_flesh", "minecraft:poisonous_potato", "minecraft:diamond");
        List<InventoryPressurePolicy.Action> a = InventoryPressurePolicy.decide(stacks, 0, 2,
                EnumSet.allOf(InventoryPressurePolicy.Rule.class), junk, Set.of("minecraft:poisonous_potato"));
        assertEquals(2, a.size(), a.toString());
        InventoryPressurePolicy.Action.Drop d = assertInstanceOf(InventoryPressurePolicy.Action.Drop.class, a.get(0));
        assertEquals(11, d.slot());
        InventoryPressurePolicy.Action.Compact c = assertInstanceOf(InventoryPressurePolicy.Action.Compact.class,
                a.get(1));
        assertEquals("minecraft:iron_block", c.blockId());
        assertEquals(2, c.blocks());
        assertTrue(InventoryPressurePolicy.decide(stacks, 3, 2,
                EnumSet.allOf(InventoryPressurePolicy.Rule.class), junk, Set.of()).isEmpty());
    }

    @Test
    void protectedAreasParseAndQuery() {
        List<String> errors = new ArrayList<>();
        List<ProtectedAreas.Box> boxes = ProtectedAreas.parse(
                "10 60 10 0 70 0; -5,0,-5,5,10,5 the_nether; bad entry; 1 2 3 x 5 6", errors);
        assertEquals(2, boxes.size());
        assertEquals(2, errors.size());
        ProtectedAreas areas = new ProtectedAreas();
        areas.set(boxes);
        assertTrue(areas.isProtected("minecraft:overworld", 5, 65, 5));
        assertFalse(areas.isProtected("minecraft:overworld", 5, 75, 5));
        assertFalse(areas.isProtected("minecraft:overworld", 0, 5, 0));
        assertTrue(areas.isProtected("minecraft:the_nether", 0, 5, 0));
        assertEquals(boxes, ProtectedAreas.parse(ProtectedAreas.format(boxes), new ArrayList<>()));
    }

    @Test
    void settingTextParsers() {
        assertEquals(Set.of("minecraft:dirt", "mymod:thing"), SurvivalAutopilot.parseIds("dirt, mymod:thing"));
        assertEquals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:bread", 8),
                SurvivalAutopilot.parseKit("stone_pickaxe=1, minecraft:bread=8, junk=x"));
    }
}
