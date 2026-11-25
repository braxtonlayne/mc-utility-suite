package com.utilitysuite.jobs.storage;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-slot contents of one container as last seen (item id, count, max stack size, and a variant key: the sorted
 * stored enchantments for enchanted books, else ""). Filled once while capturing, then treated as immutable; engine
 * free. Persisted compactly as one {@code "slot|item|count|max|variant"} string per non-empty slot.
 */
public final class ChestSlots {

    private final String[] item;
    private final String[] variant;
    private final int[] count;
    private final int[] max;

    public ChestSlots(int slots) {
        item = new String[slots];
        variant = new String[slots];
        Arrays.fill(variant, "");
        count = new int[slots];
        max = new int[slots];
    }

    /** Builder-style fill (used while capturing, before the instance is shared). */
    public ChestSlots put(int slot, String itemId, int n, int maxStack, String variantKey) {
        item[slot] = itemId;
        count[slot] = n;
        max[slot] = maxStack;
        variant[slot] = variantKey == null ? "" : variantKey;
        return this;
    }

    public int size() {
        return item.length;
    }

    public String item(int slot) {
        return item[slot];
    }

    public String variant(int slot) {
        return variant[slot];
    }

    public int count(int slot) {
        return count[slot];
    }

    public int max(int slot) {
        return max[slot];
    }

    public int usedSlots() {
        int n = 0;
        for (int i = 0; i < item.length; i++) {
            if (item[i] != null && count[i] > 0) {
                n++;
            }
        }
        return n;
    }

    /** Compact encoding: non-empty slots only. */
    public String[] encode() {
        String[] out = new String[usedSlots()];
        int k = 0;
        for (int i = 0; i < item.length; i++) {
            if (item[i] != null && count[i] > 0) {
                out[k++] = i + "|" + item[i] + "|" + count[i] + "|" + max[i] + "|" + variant[i];
            }
        }
        return out;
    }

    /** Decodes {@link #encode()} output; malformed entries are skipped. */
    public static ChestSlots decode(int size, Iterable<String> entries) {
        ChestSlots s = new ChestSlots(Math.max(0, Math.min(size, 256)));
        for (String e : entries) {
            String[] f = e.split("\\|", -1);
            if (f.length < 4) {
                continue;
            }
            try {
                int slot = Integer.parseInt(f[0]);
                if (slot >= 0 && slot < s.size()) {
                    s.put(slot, f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), f.length > 4 ? f[4] : "");
                }
            } catch (NumberFormatException ignored) {
                // skip the malformed slot
            }
        }
        return s;
    }

    /** Variant key for stored enchantments: sorted {@code id=level} pairs joined by commas ("" when none). */
    public static String enchantKey(Map<String, Integer> enchantments) {
        if (enchantments == null || enchantments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : new TreeMap<>(enchantments).entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
