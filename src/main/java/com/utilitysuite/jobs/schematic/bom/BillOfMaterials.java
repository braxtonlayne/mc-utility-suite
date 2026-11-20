package com.utilitysuite.jobs.schematic.bom;

import com.utilitysuite.jobs.schematic.model.BlockCategory;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Items needed for the blocks of a schematic that are not yet placed, plus unobtainable blocks and flagged notes.
 * Computed off the client thread from a volume and (optionally) a placement-status snapshot.
 *
 * @param lines          sorted by key
 * @param unobtainable   block id to cell count (not obtainable in survival)
 * @param flagged        note to cell count (fluids, fire, crops, farmland...)
 * @param totalBlocks    cells that require a block
 * @param placedBlocks   of those, already placed in the world (status MATCH / SAME_BLOCK)
 */
public record BillOfMaterials(List<MaterialLine> lines, Map<String, Integer> unobtainable, Map<String, Integer> flagged,
                              int totalBlocks, int placedBlocks) {

    public BillOfMaterials {
        lines = List.copyOf(lines);
        unobtainable = Map.copyOf(unobtainable);
        flagged = Map.copyOf(flagged);
    }

    public int remainingBlocks() {
        return totalBlocks - placedBlocks;
    }

    /** Required count of a line by key (item id for exact lines), 0 when absent. */
    public int required(String key) {
        for (MaterialLine l : lines) {
            if (l.key().equals(key)) {
                return l.required();
            }
        }
        return 0;
    }

    /** Line key to required count, sorted. */
    public Map<String, Integer> asMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (MaterialLine l : lines) {
            m.put(l.key(), l.required());
        }
        return m;
    }

    /** Full bill (nothing placed yet). */
    public static BillOfMaterials compute(SchematicVolume volume, SubstitutionRules rules) {
        return compute(volume, null, rules);
    }

    /**
     * @param status per-cell {@link PlacementStatus} snapshot, or {@code null} to count every block as unplaced
     */
    public static BillOfMaterials compute(SchematicVolume volume, byte[] status, SubstitutionRules rules) {
        ItemRequirement[] reqs = ItemEquivalents.forPalette(volume);
        boolean[] fullOpaque = new boolean[volume.paletteSize()];
        for (int i = 0; i < fullOpaque.length; i++) {
            fullOpaque[i] = volume.isBlock(i) && BlockCategory.isFullOpaque(volume.paletteEntry(i));
        }
        Map<String, Acc> acc = new TreeMap<>();
        Object2IntLinkedOpenHashMap<String> unobtainable = new Object2IntLinkedOpenHashMap<>();
        Object2IntLinkedOpenHashMap<String> flagged = new Object2IntLinkedOpenHashMap<>();
        int total = 0;
        int placed = 0;
        int n = volume.volume();
        for (int i = 0; i < n; i++) {
            int p = volume.paletteIndex(i);
            if (!volume.isBlock(p)) {
                continue;
            }
            total++;
            if (status != null && PlacementStatus.isPlaced(status[i])) {
                placed++;
                continue;
            }
            ItemRequirement r = reqs[p];
            switch (r.kind()) {
                case UNOBTAINABLE -> unobtainable.addTo(BlockStateString.blockId(volume.paletteEntry(p)), 1);
                case NONE -> {
                    if (r.note() != null) {
                        flagged.addTo(r.note(), 1);
                    }
                }
                case ITEM -> {
                    if (r.note() != null) {
                        flagged.addTo(r.note(), 1);
                    }
                    add(acc, rules, volume, fullOpaque, i, r.item(), r.count());
                    if (r.item2() != null) {
                        add(acc, rules, volume, fullOpaque, i, r.item2(), r.count2());
                    }
                }
            }
        }
        List<MaterialLine> lines = new ArrayList<>(acc.size());
        for (Acc a : acc.values()) {
            lines.add(new MaterialLine(a.key, a.preferred, a.group, a.count, toMap(a.requested)));
        }
        return new BillOfMaterials(lines, toMap(unobtainable), toMap(flagged), total, placed);
    }

    private static void add(Map<String, Acc> acc, SubstitutionRules rules, SchematicVolume v, boolean[] fullOpaque,
                            int index, String item, int count) {
        SubstitutionRules.Group group = null;
        if (!rules.isEmpty()) {
            group = rules.groupFor(item, false);
            if (group == null && !rules.isStrict(item)) {
                group = rules.groupFor(item, isHidden(v, fullOpaque, index));
            }
        }
        String key = group == null ? item : group.key();
        Acc a = acc.get(key);
        if (a == null) {
            a = new Acc(key, group == null ? item : group.preferredItem(), group);
            acc.put(key, a);
        }
        a.count += count;
        a.requested.addTo(item, count);
    }

    /** True when all six neighbors are inside the volume and are full opaque blocks. */
    static boolean isHidden(SchematicVolume v, boolean[] fullOpaque, int index) {
        int x = v.xOf(index), y = v.yOf(index), z = v.zOf(index);
        if (x == 0 || y == 0 || z == 0 || x == v.sizeX() - 1 || y == v.sizeY() - 1 || z == v.sizeZ() - 1) {
            return false;
        }
        return fullOpaque[v.paletteIndex(x - 1, y, z)] && fullOpaque[v.paletteIndex(x + 1, y, z)]
                && fullOpaque[v.paletteIndex(x, y - 1, z)] && fullOpaque[v.paletteIndex(x, y + 1, z)]
                && fullOpaque[v.paletteIndex(x, y, z - 1)] && fullOpaque[v.paletteIndex(x, y, z + 1)];
    }

    private static Map<String, Integer> toMap(Object2IntMap<String> m) {
        Map<String, Integer> out = new TreeMap<>();
        for (Object2IntMap.Entry<String> e : m.object2IntEntrySet()) {
            out.put(e.getKey(), e.getIntValue());
        }
        return out;
    }

    private static final class Acc {
        final String key;
        final String preferred;
        final SubstitutionRules.Group group;
        int count;
        final Object2IntLinkedOpenHashMap<String> requested = new Object2IntLinkedOpenHashMap<>();

        Acc(String key, String preferred, SubstitutionRules.Group group) {
            this.key = key;
            this.preferred = preferred;
            this.group = group;
        }
    }
}
