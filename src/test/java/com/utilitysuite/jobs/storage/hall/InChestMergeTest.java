package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.core.api.IContainerClicker.Click;
import com.utilitysuite.jobs.planner.SlotTransfer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In-chest merge planning: which partial stacks pour into which, and the pick-up / drop / put-back clicks. */
class InChestMergeTest {

    /** Replays the clicks like vanilla left clicks: pick up a stack, drop as much as fits, put the rest down. */
    private static int[] replay(int[] kind, int[] count, int[] max, List<SlotTransfer.Step> clicks) {
        int[] k = kind.clone();
        int[] c = count.clone();
        int heldKind = -1;
        int held = 0;
        for (SlotTransfer.Step s : clicks) {
            assertEquals(Click.PICKUP, s.click());
            assertEquals(0, s.button());
            int i = s.slot();
            if (held == 0) {
                heldKind = k[i];
                held = c[i];
                c[i] = 0;
                k[i] = -1;
            } else if (k[i] < 0 || c[i] == 0) {
                k[i] = heldKind;
                c[i] = held;
                held = 0;
            } else {
                assertEquals(heldKind, k[i], "dropped onto a different item");
                int n = Math.min(max[i] - c[i], held);
                c[i] += n;
                held -= n;
            }
        }
        assertEquals(0, held, "an item was left on the cursor");
        return c;
    }

    @Test
    void laterPartialsPourIntoEarlierOnesAndTheRestGoesBack() {
        int[] kind = {7, 3, 7, 7, -1};
        int[] count = {30, 64, 40, 10, 0};
        int[] max = {64, 64, 64, 64, 64};
        List<InChestMerge.Merge> m = InChestMerge.plan(kind, count, max);
        assertEquals(List.of(new InChestMerge.Merge(2, 0, 34, 6), new InChestMerge.Merge(3, 2, 10, 0)), m);
        List<SlotTransfer.Step> clicks = InChestMerge.clicks(m);
        assertEquals(5, clicks.size()); // pick up, drop, put back the rest; then pick up, drop
        assertArrayEquals(new int[]{64, 64, 16, 0, 0}, replay(kind, count, max, clicks));
        assertEquals(44, InChestMerge.moved(m));
        assertArrayEquals(new int[]{30, 64, 40, 10, 0}, count, "inputs are not changed");
    }

    @Test
    void anEmptiedSourceNeedsNoPutBackClick() {
        int[] kind = {1, 1};
        int[] count = {20, 20};
        int[] max = {64, 64};
        List<InChestMerge.Merge> m = InChestMerge.plan(kind, count, max);
        assertEquals(List.of(new InChestMerge.Merge(1, 0, 20, 0)), m);
        assertEquals(2, InChestMerge.clicks(m).size());
        assertArrayEquals(new int[]{40, 0}, replay(kind, count, max, InChestMerge.clicks(m)));
    }

    @Test
    void fullStacksUnstackablesAndDifferentKindsAreLeftAlone() {
        int[] kind = {1, 2, 1, 4, 4};
        int[] count = {64, 10, 64, 1, 1};
        int[] max = {64, 64, 64, 1, 1};
        assertTrue(InChestMerge.plan(kind, count, max).isEmpty());
        // 16-stacks (ender pearls) use their own max
        assertEquals(List.of(new InChestMerge.Merge(1, 0, 6, 4)),
                InChestMerge.plan(new int[]{5, 5}, new int[]{10, 10}, new int[]{16, 16}));
    }

    @Test
    void kindsGroupByItemAndVariant() {
        String[] item = {"minecraft:enchanted_book", "minecraft:dirt", "minecraft:enchanted_book", null,
                "minecraft:dirt"};
        String[] variant = {"minecraft:mending=1", "", "minecraft:sharpness=5", "", ""};
        int[] count = {1, 5, 1, 0, 3};
        assertArrayEquals(new int[]{0, 1, 2, -1, 1}, InChestMerge.kinds(item, variant, count));
    }
}
