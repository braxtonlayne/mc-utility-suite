package com.utilitysuite.jobs.storage.hall;

import java.util.ArrayList;
import java.util.List;

/**
 * Slot-precise take, engine free: matches {@link HallSortSteps.Take} entries against what the open container holds
 * right now ({@code item}, {@code variant} = stored-enchantment key, {@code count} per container slot). A slot is only
 * taken when its item id and variant both match, so a Mending book is never taken in place of a Sharpness one. The
 * planned slot is used first; when it changed since it was remembered, other slots holding the same item+variant
 * make up the rest. Each slot is used once.
 */
public final class HallSlotPick {

    /** Take {@code count} items from container slot {@code slot}. */
    public record Pick(int slot, int count) {
    }

    private HallSlotPick() {
    }

    public static List<Pick> pick(List<HallSortSteps.Take> wanted, String[] item, String[] variant, int[] count) {
        int n = item.length;
        boolean[] used = new boolean[n];
        int[] left = new int[wanted.size()];
        List<Pick> out = new ArrayList<>();
        for (int w = 0; w < wanted.size(); w++) {
            HallSortSteps.Take t = wanted.get(w);
            left[w] = t.count();
            int s = t.slot();
            if (s >= 0 && s < n && !used[s] && matches(t, item[s], variant[s], count[s])) {
                int k = Math.min(left[w], count[s]);
                used[s] = true;
                left[w] -= k;
                out.add(new Pick(s, k));
            }
        }
        for (int w = 0; w < wanted.size(); w++) {
            HallSortSteps.Take t = wanted.get(w);
            for (int s = 0; s < n && left[w] > 0; s++) {
                if (!used[s] && matches(t, item[s], variant[s], count[s])) {
                    int k = Math.min(left[w], count[s]);
                    used[s] = true;
                    left[w] -= k;
                    out.add(new Pick(s, k));
                }
            }
        }
        return out;
    }

    static boolean matches(HallSortSteps.Take t, String item, String variant, int count) {
        return item != null && count > 0 && t.item().equals(item)
                && t.variant().equals(variant == null ? "" : variant);
    }
}
