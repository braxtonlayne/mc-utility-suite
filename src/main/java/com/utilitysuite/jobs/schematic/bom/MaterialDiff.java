package com.utilitysuite.jobs.schematic.bom;

import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Subtracts what the player has (inventory first, then indexed storage) from a {@link BillOfMaterials}. Exact lines
 * are served before substitution groups so a group never takes an item an exact line needs. Within a group the
 * preferred item is used first, then the other members in id order.
 *
 * @param lines one per bill line, same order
 */
public record MaterialDiff(List<Line> lines) {

    /**
     * @param fromInventory satisfied from the player's inventory
     * @param fromStorage   satisfied from indexed containers
     * @param missing       still to gather
     */
    public record Line(String key, String preferredItem, boolean group, int required, int fromInventory,
                       int fromStorage, int missing) {
    }

    public MaterialDiff {
        lines = List.copyOf(lines);
    }

    /** Preferred item to missing count (lines with nothing missing are left out). Input for a GatherMany goal. */
    public Map<String, Integer> missing() {
        Map<String, Integer> m = new TreeMap<>();
        for (Line l : lines) {
            if (l.missing > 0) {
                m.merge(l.preferredItem, l.missing, Integer::sum);
            }
        }
        return m;
    }

    public int totalMissing() {
        int t = 0;
        for (Line l : lines) {
            t += l.missing;
        }
        return t;
    }

    public Line line(String key) {
        for (Line l : lines) {
            if (l.key.equals(key)) {
                return l;
            }
        }
        return null;
    }

    public static MaterialDiff compute(BillOfMaterials bom, Map<String, Integer> inventory,
                                       Map<String, Integer> storage) {
        Map<String, Integer> inv = new HashMap<>(inventory == null ? Map.of() : inventory);
        Map<String, Integer> sto = new HashMap<>(storage == null ? Map.of() : storage);
        Map<String, Line> out = new LinkedHashMap<>();
        for (MaterialLine l : bom.lines()) {
            out.put(l.key(), null);
        }
        for (MaterialLine l : bom.lines()) {
            if (!l.isGroup()) {
                int fromInv = take(inv, l.key(), l.required());
                int fromSto = take(sto, l.key(), l.required() - fromInv);
                out.put(l.key(), new Line(l.key(), l.preferredItem(), false, l.required(), fromInv, fromSto,
                        l.required() - fromInv - fromSto));
            }
        }
        for (MaterialLine l : bom.lines()) {
            if (l.isGroup()) {
                int need = l.required();
                int fromInv = takeGroup(inv, l, need);
                int fromSto = takeGroup(sto, l, need - fromInv);
                out.put(l.key(), new Line(l.key(), l.preferredItem(), true, need, fromInv, fromSto,
                        need - fromInv - fromSto));
            }
        }
        return new MaterialDiff(new ArrayList<>(out.values()));
    }

    /** Convenience over the core views ({@code storage} may be {@code null} when that module is absent). */
    public static MaterialDiff compute(BillOfMaterials bom, IInventoryView inventory, IStorageIndex storage) {
        return compute(bom, inventory == null ? Map.of() : inventory.counts(), storageCounts(storage));
    }

    /** Sums the item counts of every non-stale indexed container. */
    public static Map<String, Integer> storageCounts(IStorageIndex storage) {
        if (storage == null) {
            return Map.of();
        }
        Map<String, Integer> m = new HashMap<>();
        for (StorageEntry e : storage.entries()) {
            if (e.stale()) {
                continue;
            }
            for (Map.Entry<String, Integer> c : e.contents().entrySet()) {
                m.merge(c.getKey(), c.getValue(), Integer::sum);
            }
        }
        return m;
    }

    private static int take(Map<String, Integer> supply, String item, int want) {
        if (want <= 0) {
            return 0;
        }
        int have = supply.getOrDefault(item, 0);
        int used = Math.min(have, want);
        if (used > 0) {
            supply.put(item, have - used);
        }
        return used;
    }

    private static int takeGroup(Map<String, Integer> supply, MaterialLine line, int want) {
        if (want <= 0) {
            return 0;
        }
        int used = take(supply, line.preferredItem(), want);
        if (used < want) {
            List<String> members = new ArrayList<>();
            for (Map.Entry<String, Integer> e : supply.entrySet()) {
                if (e.getValue() > 0 && line.accepts(e.getKey()) && !e.getKey().equals(line.preferredItem())) {
                    members.add(e.getKey());
                }
            }
            members.sort(null);
            for (String m : members) {
                used += take(supply, m, want - used);
                if (used >= want) {
                    break;
                }
            }
        }
        return used;
    }
}
