package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.jobs.goal.ManualCraftPlanner.Click;
import com.utilitysuite.jobs.goal.ManualCraftPlanner.Menu;
import com.utilitysuite.jobs.goal.ManualCraftPlanner.Plan;
import com.utilitysuite.jobs.goal.ManualCraftPlanner.Shape;
import com.utilitysuite.jobs.goal.ManualCraftPlanner.Status;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hand-filling a crafting grid when the recipe book cannot place the recipe (roadmap 4.4). */
class ManualCraftPlannerTest {

    private static final Set<String> NONE = Set.of();
    private static final Set<String> PLANKS = Set.of("minecraft:oak_planks", "minecraft:birch_planks");
    private static final Set<String> STICK = Set.of("minecraft:stick");
    private static final Set<String> COBBLE = Set.of("minecraft:cobblestone");
    private static final Set<String> IRON = Set.of("minecraft:iron_ingot");
    private static final Set<String> FLINT = Set.of("minecraft:flint");

    /** Crafting-table menu layout: result 0, grid 1..9, inventory 10..45 (main 10..36, hotbar 37..45). */
    private static final int TABLE_INV_FIRST = 10;
    /** Inventory menu layout: result 0, grid 1..4, armor 5..8, inventory 9..44. */
    private static final int INV_INV_FIRST = 9;

    /** Minimal vanilla click semantics for PICKUP (left/right) and QUICK_MOVE, enough to check the plans. */
    static final class Sim {
        final int gridSize;
        final int gridFirst;
        final int invFirst;
        final String[] grid;
        final String[] inv = new String[36];
        final int[] invCount = new int[36];
        final int[] gridCount;
        String carried;
        int carriedCount;

        Sim(int gridSize, int gridFirst, int invFirst) {
            this.gridSize = gridSize;
            this.gridFirst = gridFirst;
            this.invFirst = invFirst;
            this.grid = new String[gridSize * gridSize];
            this.gridCount = new int[gridSize * gridSize];
        }

        static Sim table() {
            return new Sim(3, 1, TABLE_INV_FIRST);
        }

        static Sim inventory() {
            return new Sim(2, 1, INV_INV_FIRST);
        }

        Sim put(int invIndex, String id, int count) {
            inv[invIndex] = id;
            invCount[invIndex] = count;
            return this;
        }

        Menu menu() {
            int[] slots = new int[36];
            for (int i = 0; i < 36; i++) {
                slots[i] = invFirst + i;
            }
            return new Menu(gridSize, gridFirst, grid.clone(), slots, inv.clone(), invCount.clone(), carried,
                    carriedCount);
        }

        void run(List<Click> clicks) {
            for (Click c : clicks) {
                click(c);
            }
        }

        private void click(Click c) {
            boolean isGrid = c.slot() >= gridFirst && c.slot() < gridFirst + grid.length;
            String[] ids = isGrid ? grid : inv;
            int[] counts = isGrid ? gridCount : invCount;
            int i = isGrid ? c.slot() - gridFirst : c.slot() - invFirst;
            if (!isGrid && (i < 0 || i >= 36)) {
                throw new AssertionError("click on unknown slot " + c.slot());
            }
            if (c.type() == IContainerClicker.Click.QUICK_MOVE) {
                if (!isGrid) {
                    throw new AssertionError("quick-move only used on the grid");
                }
                String id = ids[i];
                int n = counts[i];
                ids[i] = null;
                counts[i] = 0;
                for (int k = 0; k < 36 && n > 0; k++) {
                    if (id.equals(inv[k])) {
                        int add = Math.min(64 - invCount[k], n);
                        invCount[k] += add;
                        n -= add;
                    }
                }
                for (int k = 0; k < 36 && n > 0; k++) {
                    if (inv[k] == null) {
                        inv[k] = id;
                        invCount[k] = n;
                        n = 0;
                    }
                }
                assertEquals(0, n, "inventory overflow");
                return;
            }
            assertEquals(IContainerClicker.Click.PICKUP, c.type());
            if (c.button() == 0) {
                if (carried == null) {
                    carried = ids[i];
                    carriedCount = counts[i];
                    ids[i] = null;
                    counts[i] = 0;
                } else if (ids[i] == null) {
                    ids[i] = carried;
                    counts[i] = carriedCount;
                    carried = null;
                    carriedCount = 0;
                } else if (ids[i].equals(carried)) {
                    int add = Math.min(64 - counts[i], carriedCount);
                    counts[i] += add;
                    carriedCount -= add;
                    if (carriedCount == 0) {
                        carried = null;
                    }
                } else {
                    String sid = ids[i];
                    int sc = counts[i];
                    ids[i] = carried;
                    counts[i] = carriedCount;
                    carried = sid;
                    carriedCount = sc;
                }
            } else {
                if (carried == null) {
                    throw new AssertionError("right-click with an empty cursor is not planned");
                }
                if (ids[i] != null && !ids[i].equals(carried)) {
                    throw new AssertionError("right-click onto a different item would swap stacks");
                }
                ids[i] = carried;
                counts[i]++;
                if (--carriedCount == 0) {
                    carried = null;
                }
            }
        }

        Map<String, Integer> totals() {
            Map<String, Integer> m = new HashMap<>();
            for (int k = 0; k < 36; k++) {
                if (inv[k] != null) {
                    m.merge(inv[k], invCount[k], Integer::sum);
                }
            }
            for (int k = 0; k < grid.length; k++) {
                if (grid[k] != null) {
                    m.merge(grid[k], gridCount[k], Integer::sum);
                }
            }
            if (carried != null) {
                m.merge(carried, carriedCount, Integer::sum);
            }
            return m;
        }
    }

    private static Shape pickaxe() {
        return Shape.shaped(3, 3, List.of(
                COBBLE, COBBLE, COBBLE,
                NONE, STICK, NONE,
                NONE, STICK, NONE));
    }

    /** Vanilla axe pattern: asymmetric, so the mirrored layout is a different grid. */
    private static Shape axe() {
        return Shape.shaped(2, 3, List.of(
                PLANKS, PLANKS,
                PLANKS, STICK,
                NONE, STICK));
    }

    private static Plan placeAndRun(Sim sim, Shape shape) {
        Plan p = ManualCraftPlanner.plan(shape, sim.menu());
        assertEquals(Status.PLACE, p.status(), p.detail());
        Map<String, Integer> before = sim.totals();
        sim.run(p.clicks());
        assertNull(sim.carried, "cursor must end empty");
        assertEquals(before, sim.totals(), "no item may be lost or duplicated");
        for (int k = 0; k < sim.gridCount.length; k++) {
            assertTrue(sim.gridCount[k] <= 1, "one item per grid cell");
        }
        assertTrue(ManualCraftPlanner.matches(shape, sim.gridSize, sim.grid), "grid must match the recipe");
        return p;
    }

    @Test
    void shapedPickaxeFillsPatternWithOneItemPerCellAndPutsTheRestBack() {
        Sim sim = Sim.table().put(0, "minecraft:cobblestone", 20).put(5, "minecraft:stick", 7);
        Plan p = placeAndRun(sim, pickaxe());
        assertArrayEquals(new String[]{
                "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone",
                null, "minecraft:stick", null,
                null, "minecraft:stick", null}, sim.grid);
        assertEquals(17, sim.invCount[0], "rest of the cobblestone back in its slot");
        assertEquals(5, sim.invCount[5]);
        // pickup + 3 right clicks + put back, pickup + 2 right clicks + put back
        assertEquals(9, p.clicks().size());
        // the scarcer ingredient (sticks) is assigned and placed first: pick up the stick stack, right-click cell (1,1)
        assertEquals(new Click(TABLE_INV_FIRST + 5, 0, IContainerClicker.Click.PICKUP), p.clicks().get(0));
        assertEquals(new Click(1 + 4, 1, IContainerClicker.Click.PICKUP), p.clicks().get(1));
    }

    @Test
    void exactStackNeedsNoPutBackAndSmallStacksAreCombined() {
        // 2 + 1 cobblestone in separate slots, exactly 2 sticks
        Sim sim = Sim.table().put(3, "minecraft:cobblestone", 2).put(30, "minecraft:cobblestone", 1)
                .put(8, "minecraft:stick", 2);
        placeAndRun(sim, pickaxe());
        assertNull(sim.inv[3]);
        assertNull(sim.inv[30]);
        assertNull(sim.inv[8]);
    }

    @Test
    void tagIngredientMixesWoodTypes() {
        Sim sim = Sim.table().put(0, "minecraft:oak_planks", 2).put(1, "minecraft:birch_planks", 1)
                .put(2, "minecraft:stick", 2);
        placeAndRun(sim, axe());
    }

    @Test
    void twoByTwoRecipeInInventoryGrid() {
        Shape table = Shape.shaped(2, 2, List.of(PLANKS, PLANKS, PLANKS, PLANKS));
        Sim sim = Sim.inventory().put(27, "minecraft:oak_planks", 4); // hotbar slot 0 is menu slot 36
        Plan p = placeAndRun(sim, table);
        assertEquals(new Click(INV_INV_FIRST + 27, 0, IContainerClicker.Click.PICKUP), p.clicks().get(0));
        assertEquals(5, p.clicks().size(), "exact stack: no put-back click");
    }

    @Test
    void shapelessUsesAnyCells() {
        Shape flintAndSteel = Shape.shapeless(List.of(IRON, FLINT));
        Sim sim = Sim.inventory().put(4, "minecraft:flint", 3).put(9, "minecraft:iron_ingot", 1);
        placeAndRun(sim, flintAndSteel);
    }

    @Test
    void mostConstrainedIngredientIsAssignedFirst() {
        // A cell taking either item and a cell taking only stone; one of each: greedy-by-order would starve the stone
        Set<String> either = Set.of("minecraft:stone", "minecraft:andesite");
        Set<String> stone = Set.of("minecraft:stone");
        Shape s = Shape.shapeless(List.of(either, stone));
        Sim sim = Sim.inventory().put(0, "minecraft:stone", 1).put(1, "minecraft:andesite", 1);
        placeAndRun(sim, s);
    }

    @Test
    void missingIngredientIsReported() {
        Sim sim = Sim.table().put(0, "minecraft:cobblestone", 3).put(1, "minecraft:stick", 1);
        Plan p = ManualCraftPlanner.plan(pickaxe(), sim.menu());
        assertEquals(Status.MISSING, p.status());
        assertEquals("minecraft:stick", p.detail());
        assertTrue(p.clicks().isEmpty());
    }

    @Test
    void threeByThreePatternDoesNotFitTheInventoryGrid() {
        Sim sim = Sim.inventory().put(0, "minecraft:cobblestone", 3).put(1, "minecraft:stick", 2);
        assertEquals(Status.TOO_BIG, ManualCraftPlanner.plan(pickaxe(), sim.menu()).status());
    }

    @Test
    void occupiedGridAndCursorAreClearedFirst() {
        Sim sim = Sim.table().put(0, "minecraft:cobblestone", 3).put(1, "minecraft:stick", 2);
        sim.grid[4] = "minecraft:dirt";
        sim.gridCount[4] = 5;
        sim.carried = "minecraft:sand";
        sim.carriedCount = 9;
        Plan p = ManualCraftPlanner.plan(pickaxe(), sim.menu());
        assertEquals(Status.CLEAR_GRID, p.status());
        Map<String, Integer> before = sim.totals();
        sim.run(p.clicks());
        assertEquals(before, sim.totals());
        assertNull(sim.carried);
        for (String g : sim.grid) {
            assertNull(g);
        }
        placeAndRun(sim, pickaxe()); // the next round places normally
    }

    @Test
    void stuckCursorWithFullInventory() {
        Sim sim = Sim.table();
        for (int i = 0; i < 36; i++) {
            sim.put(i, "minecraft:dirt", 64);
        }
        sim.carried = "minecraft:sand";
        sim.carriedCount = 1;
        assertEquals(Status.CURSOR_STUCK, ManualCraftPlanner.plan(pickaxe(), sim.menu()).status());
    }

    @Test
    void matchesAcceptsOffsetsAndTheMirroredPattern() {
        String p = "minecraft:oak_planks";
        String s = "minecraft:stick";
        // axe drawn at the right edge, as written
        assertTrue(ManualCraftPlanner.matches(axe(), 3, new String[]{
                null, p, p,
                null, p, s,
                null, null, s}));
        // mirrored left-to-right
        assertTrue(ManualCraftPlanner.matches(axe(), 3, new String[]{
                p, p, null,
                s, p, null,
                s, null, null}));
        // flipped upside down is not a vanilla variant
        assertFalse(ManualCraftPlanner.matches(axe(), 3, new String[]{
                null, s, null,
                p, s, null,
                p, p, null}));
        // a stray extra item breaks the match
        assertFalse(ManualCraftPlanner.matches(axe(), 3, new String[]{
                p, p, null,
                p, s, null,
                null, s, "minecraft:dirt"}));
        // shapeless in any arrangement
        Shape fs = Shape.shapeless(List.of(IRON, FLINT));
        assertTrue(ManualCraftPlanner.matches(fs, 2, new String[]{null, "minecraft:flint", "minecraft:iron_ingot", null}));
        assertFalse(ManualCraftPlanner.matches(fs, 2, new String[]{null, "minecraft:flint", "minecraft:flint", null}));
    }

    @Test
    void patternWithBlankEdgeIsTrimmedLikeVanilla() {
        // a 3x3-declared pattern whose last column is blank behaves as 2 wide
        Shape s = Shape.shaped(3, 1, List.of(STICK, STICK, NONE));
        assertTrue(ManualCraftPlanner.matches(s, 3, new String[]{
                null, null, null,
                null, "minecraft:stick", "minecraft:stick",
                null, null, null}));
    }
}
