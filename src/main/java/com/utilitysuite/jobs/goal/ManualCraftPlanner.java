package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.IContainerClicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Plans the container clicks that fill a crafting grid by hand, for when the recipe book cannot place a recipe (it is
 * not unlocked yet). Engine-free: items are registry id strings, ingredients are sets of acceptable ids and slots are
 * menu slot indices, so the logic is unit-tested without the game.
 * <p>
 * The grid is filled for one craft (one item per used cell). Each source stack is picked up whole with a left click,
 * one item is right-clicked into every cell assigned to it, and whatever is left on the cursor is left-clicked back
 * into the (now empty) source slot, so the cursor ends empty. Shaped recipes are laid out at the grid's top-left in
 * their own orientation; {@link #matches} accepts any offset and the mirrored pattern, like vanilla.
 * <p>
 * Not a hot path (runs once per craft round), so it allocates small lists freely.
 */
public final class ManualCraftPlanner {

    private ManualCraftPlanner() {
    }

    public enum Status {
        /** {@link Plan#clicks} fill the grid for one craft. */
        PLACE,
        /** The grid (or cursor) holds items first: {@link Plan#clicks} put them back into the inventory. */
        CLEAR_GRID,
        /** The cursor holds an item and the inventory has no free slot for it. */
        CURSOR_STUCK,
        /** Not enough matching items; {@link Plan#detail} names one ingredient that ran out. */
        MISSING,
        /** The shaped pattern is larger than the grid, or the recipe has more ingredients than cells. */
        TOO_BIG,
        /** The recipe has no ingredients to place. */
        EMPTY_RECIPE
    }

    /**
     * A crafting recipe reduced to ids. Shaped: {@code width * height} cells, row-major, an empty set is a blank cell.
     * Shapeless: one entry per ingredient (width and height unused).
     */
    public record Shape(boolean shaped, int width, int height, List<Set<String>> cells) {

        public static Shape shaped(int width, int height, List<Set<String>> cells) {
            if (width <= 0 || height <= 0 || cells.size() != width * height) {
                throw new IllegalArgumentException("Shaped cells must be width*height");
            }
            return new Shape(true, width, height, List.copyOf(cells));
        }

        public static Shape shapeless(List<Set<String>> ingredients) {
            return new Shape(false, 0, 0, List.copyOf(ingredients));
        }

        /** Non-blank cells (shaped) or ingredients (shapeless). */
        public int ingredientCount() {
            int n = 0;
            for (Set<String> c : cells) {
                if (!c.isEmpty()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** One container click: menu slot, mouse button (0 left, 1 right) and kind. */
    public record Click(int slot, int button, IContainerClicker.Click type) {
    }

    public record Plan(Status status, List<Click> clicks, String detail) {
    }

    /**
     * The menu's crafting grid and the player's inventory as seen through the open menu.
     *
     * @param gridSize      2 (inventory) or 3 (crafting table)
     * @param gridFirstSlot menu slot of the grid's top-left cell; cells are row-major after it
     * @param grid          item id per cell (row-major, {@code gridSize * gridSize}), null when empty
     * @param invSlots      menu slot index of each player inventory slot that may be used as a source
     * @param invItems      item id in each of those slots, null when empty
     * @param invCounts     stack size in each of those slots
     * @param carried       item id on the cursor, null when empty
     * @param carriedCount  stack size on the cursor
     */
    public record Menu(int gridSize, int gridFirstSlot, String[] grid, int[] invSlots, String[] invItems,
                       int[] invCounts, String carried, int carriedCount) {
    }

    /** Plans one round: clears the cursor and grid when needed, otherwise fills the grid for one craft. */
    public static Plan plan(Shape shape, Menu m) {
        List<Click> clicks = new ArrayList<>();
        String[] items = m.invItems().clone();
        int[] counts = m.invCounts().clone();
        int n = items.length;

        // 1. Cursor must be empty: drop the carried stack into the first free inventory slot.
        if (m.carried() != null && m.carriedCount() > 0) {
            int free = -1;
            for (int i = 0; i < n; i++) {
                if (items[i] == null || counts[i] <= 0) {
                    free = i;
                    break;
                }
            }
            if (free < 0) {
                return new Plan(Status.CURSOR_STUCK, List.of(), m.carried());
            }
            clicks.add(new Click(m.invSlots()[free], 0, IContainerClicker.Click.PICKUP));
        }

        // 2. Grid must be empty: shift-click occupied cells back into the inventory, then plan again.
        int cells = m.gridSize() * m.gridSize();
        boolean occupied = false;
        for (int c = 0; c < cells; c++) {
            if (m.grid()[c] != null) {
                occupied = true;
                clicks.add(new Click(m.gridFirstSlot() + c, 0, IContainerClicker.Click.QUICK_MOVE));
            }
        }
        if (occupied || !clicks.isEmpty()) {
            return new Plan(Status.CLEAR_GRID, Collections.unmodifiableList(clicks), "");
        }

        // 3. Target cells for one craft.
        List<Integer> targetCell = new ArrayList<>();
        List<Set<String>> targetIng = new ArrayList<>();
        if (shape.shaped()) {
            if (shape.width() > m.gridSize() || shape.height() > m.gridSize()) {
                return new Plan(Status.TOO_BIG, List.of(), shape.width() + "x" + shape.height());
            }
            for (int r = 0; r < shape.height(); r++) {
                for (int c = 0; c < shape.width(); c++) {
                    Set<String> ing = shape.cells().get(r * shape.width() + c);
                    if (!ing.isEmpty()) {
                        targetCell.add(r * m.gridSize() + c);
                        targetIng.add(ing);
                    }
                }
            }
        } else {
            for (Set<String> ing : shape.cells()) {
                if (!ing.isEmpty()) {
                    targetCell.add(targetCell.size());
                    targetIng.add(ing);
                }
            }
            if (targetCell.size() > cells) {
                return new Plan(Status.TOO_BIG, List.of(), targetCell.size() + " ingredients");
            }
        }
        int t = targetCell.size();
        if (t == 0) {
            return new Plan(Status.EMPTY_RECIPE, List.of(), "");
        }

        // 4. Assign a source slot to every cell, most constrained ingredient first.
        Integer[] order = new Integer[t];
        int[] supply = new int[t];
        for (int k = 0; k < t; k++) {
            order[k] = k;
            for (int i = 0; i < n; i++) {
                if (items[i] != null && counts[i] > 0 && targetIng.get(k).contains(items[i])) {
                    supply[k] += counts[i];
                }
            }
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(supply[a], supply[b]));
        int[] source = new int[t];
        int[] used = new int[n];
        List<Integer> sourceOrder = new ArrayList<>();
        for (int idx = 0; idx < t; idx++) {
            int k = order[idx];
            Set<String> ing = targetIng.get(k);
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (items[i] == null || counts[i] - used[i] <= 0 || !ing.contains(items[i])) {
                    continue;
                }
                if (best < 0) {
                    best = i;
                    continue;
                }
                // prefer a stack already picked for another cell (fewer pickups), then the larger remainder
                boolean iUsed = used[i] > 0;
                boolean bUsed = used[best] > 0;
                if (iUsed != bUsed ? iUsed : counts[i] - used[i] > counts[best] - used[best]) {
                    best = i;
                }
            }
            if (best < 0) {
                return new Plan(Status.MISSING, List.of(), describe(ing));
            }
            if (used[best] == 0) {
                sourceOrder.add(best);
            }
            used[best]++;
            source[k] = best;
        }

        // 5. Clicks, grouped by source: pick up the stack, right-click one per cell, put the rest back.
        for (int s : sourceOrder) {
            clicks.add(new Click(m.invSlots()[s], 0, IContainerClicker.Click.PICKUP));
            for (int k = 0; k < t; k++) {
                if (source[k] == s) {
                    clicks.add(new Click(m.gridFirstSlot() + targetCell.get(k), 1, IContainerClicker.Click.PICKUP));
                }
            }
            if (counts[s] - used[s] > 0) {
                clicks.add(new Click(m.invSlots()[s], 0, IContainerClicker.Click.PICKUP));
            }
        }
        return new Plan(Status.PLACE, Collections.unmodifiableList(clicks), "");
    }

    private static String describe(Set<String> ing) {
        if (ing.size() == 1) {
            return ing.iterator().next();
        }
        String any = ing.stream().sorted().findFirst().orElse("?");
        return any + " (or " + (ing.size() - 1) + " alternatives)";
    }

    /**
     * Whether a grid (item id per cell, row-major, null when empty) matches the recipe the way vanilla checks it: a
     * shaped pattern anywhere in the grid, as drawn or mirrored left-to-right; a shapeless recipe in any arrangement.
     */
    public static boolean matches(Shape shape, int gridSize, String[] grid) {
        int minR = gridSize, minC = gridSize, maxR = -1, maxC = -1, filled = 0;
        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) {
                if (grid[r * gridSize + c] != null) {
                    filled++;
                    minR = Math.min(minR, r);
                    maxR = Math.max(maxR, r);
                    minC = Math.min(minC, c);
                    maxC = Math.max(maxC, c);
                }
            }
        }
        if (filled != shape.ingredientCount() || filled == 0) {
            return false;
        }
        if (!shape.shaped()) {
            List<String> present = new ArrayList<>();
            for (String g : grid) {
                if (g != null) {
                    present.add(g);
                }
            }
            List<Set<String>> ings = new ArrayList<>();
            for (Set<String> s : shape.cells()) {
                if (!s.isEmpty()) {
                    ings.add(s);
                }
            }
            return assign(ings, present, 0, new boolean[present.size()]);
        }
        int w = shape.width(), h = shape.height();
        // vanilla trims to the bounding box, so blank pattern edges do not count; compare against the trimmed pattern
        int pMinR = h, pMinC = w, pMaxR = -1, pMaxC = -1;
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                if (!shape.cells().get(r * w + c).isEmpty()) {
                    pMinR = Math.min(pMinR, r);
                    pMaxR = Math.max(pMaxR, r);
                    pMinC = Math.min(pMinC, c);
                    pMaxC = Math.max(pMaxC, c);
                }
            }
        }
        int pw = pMaxC - pMinC + 1, ph = pMaxR - pMinR + 1;
        if (maxR - minR + 1 != ph || maxC - minC + 1 != pw) {
            return false;
        }
        return fits(shape, grid, gridSize, minR, minC, pMinR, pMinC, pw, ph, false)
                || fits(shape, grid, gridSize, minR, minC, pMinR, pMinC, pw, ph, true);
    }

    private static boolean fits(Shape shape, String[] grid, int gridSize, int gr, int gc, int pr, int pc, int pw,
                                int ph, boolean mirrored) {
        for (int r = 0; r < ph; r++) {
            for (int c = 0; c < pw; c++) {
                int sc = mirrored ? pw - 1 - c : c;
                Set<String> ing = shape.cells().get((pr + r) * shape.width() + pc + sc);
                String item = grid[(gr + r) * gridSize + gc + c];
                if (ing.isEmpty() ? item != null : item == null || !ing.contains(item)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean assign(List<Set<String>> ings, List<String> items, int k, boolean[] taken) {
        if (k == ings.size()) {
            return true;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!taken[i] && ings.get(k).contains(items.get(i))) {
                taken[i] = true;
                if (assign(ings, items, k + 1, taken)) {
                    return true;
                }
                taken[i] = false;
            }
        }
        return false;
    }
}
