package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.core.api.IContainerClicker.Click;
import com.utilitysuite.jobs.planner.SlotTransfer;

import java.util.ArrayList;
import java.util.List;

/**
 * Merging partial stacks inside one open container, engine free. Slots are described by a stacking {@code kind}
 * (equal kinds stack together; -1 = empty), a count and a max stack size. Later partial stacks are poured into
 * earlier ones of the same kind, like a player tidying a chest: pick the later stack up, drop it on the earlier one
 * (vanilla puts as many as fit), and put the remainder back where it came from. The clicks for a whole pass are
 * planned on a simulated copy, so they can be queued at once; the caller re-reads the menu and runs another pass if
 * anything is left (a server correction, a slot changed under it).
 */
public final class InChestMerge {

    /**
     * One merge: pour slot {@code from} into slot {@code to}; {@code moved} items go over and {@code rest} stay in
     * {@code from} (then the third click puts them back).
     */
    public record Merge(int from, int to, int moved, int rest) {
    }

    private InChestMerge() {
    }

    /**
     * Stacking kinds from item ids and variant keys (equal id and variant = same kind; null id = empty, -1). The game
     * side compares whole stacks instead (same item and components).
     */
    public static int[] kinds(String[] item, String[] variant, int[] count) {
        int[] k = new int[item.length];
        for (int i = 0; i < item.length; i++) {
            k[i] = -1;
            if (item[i] == null || count[i] <= 0) {
                continue;
            }
            k[i] = i;
            for (int j = 0; j < i; j++) {
                if (k[j] >= 0 && item[i].equals(item[j]) && variant[i].equals(variant[j])) {
                    k[i] = k[j];
                    break;
                }
            }
        }
        return k;
    }

    /** Plans one pass over copies of the arrays (inputs untouched). */
    public static List<Merge> plan(int[] kind, int[] count, int[] max) {
        int n = kind.length;
        int[] k = kind.clone();
        int[] c = count.clone();
        List<Merge> out = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            if (k[s] < 0 || c[s] <= 0 || c[s] >= max[s]) {
                continue;
            }
            for (int t = s + 1; t < n && c[s] < max[s]; t++) {
                if (k[t] != k[s] || c[t] <= 0 || c[t] >= max[t]) {
                    continue; // only partial stacks are poured: moving a full one just shifts the gap
                }
                int moved = Math.min(max[s] - c[s], c[t]);
                c[s] += moved;
                c[t] -= moved;
                out.add(new Merge(t, s, moved, c[t]));
                if (c[t] == 0) {
                    k[t] = -1;
                }
            }
        }
        return out;
    }

    /**
     * The clicks for a pass, as container slot positions (the caller maps them to menu slot indices): left-click the
     * source (pick up), left-click the target (drop what fits), left-click the source again when a rest is carried.
     */
    public static List<SlotTransfer.Step> clicks(List<Merge> merges) {
        List<SlotTransfer.Step> out = new ArrayList<>(merges.size() * 3);
        for (Merge m : merges) {
            out.add(new SlotTransfer.Step(m.from(), 0, Click.PICKUP));
            out.add(new SlotTransfer.Step(m.to(), 0, Click.PICKUP));
            if (m.rest() > 0) {
                out.add(new SlotTransfer.Step(m.from(), 0, Click.PICKUP));
            }
        }
        return out;
    }

    /** Items moved by a pass. */
    public static int moved(List<Merge> merges) {
        int n = 0;
        for (Merge m : merges) {
            n += m.moved();
        }
        return n;
    }
}
