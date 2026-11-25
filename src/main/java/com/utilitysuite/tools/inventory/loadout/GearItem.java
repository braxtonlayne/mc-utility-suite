package com.utilitysuite.tools.inventory.loadout;

import java.util.Map;
import java.util.TreeMap;

/**
 * Engine-free description of one stack for loadout matching: registry id plus the components that identify "the same
 * piece of gear" (enchantments and their levels, custom name). Counts are irrelevant for gear and are not kept.
 *
 * @param itemId       registry id, e.g. {@code minecraft:diamond_pickaxe}; never empty for a real item
 * @param enchantments enchantment id to level (sorted, unmodifiable)
 * @param customName   custom name, or "" when none
 */
public record GearItem(String itemId, Map<String, Integer> enchantments, String customName) {

    public GearItem {
        itemId = itemId == null ? "" : itemId;
        enchantments = enchantments == null || enchantments.isEmpty() ? Map.of()
                : java.util.Collections.unmodifiableMap(new TreeMap<>(enchantments));
        customName = customName == null ? "" : customName;
    }

    public static GearItem of(String itemId) {
        return new GearItem(itemId, Map.of(), "");
    }

    public GearItem withEnchant(String id, int level) {
        TreeMap<String, Integer> m = new TreeMap<>(enchantments);
        m.put(id, level);
        return new GearItem(itemId, m, customName);
    }

    public GearItem named(String name) {
        return new GearItem(itemId, enchantments, name);
    }

    /**
     * How well {@code candidate} fills a slot that wants {@code this}: -1 when the item id differs, otherwise
     * {@code 100 + 10} per wanted enchantment at or above the wanted level ({@code +4} when present but lower, minus
     * the missing levels) {@code + 3} for an equal custom name. Higher is better; ties prefer the stack already in place.
     */
    public int score(GearItem candidate) {
        if (candidate == null || !itemId.equals(candidate.itemId)) {
            return -1;
        }
        int s = 100;
        for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            Integer have = candidate.enchantments.get(e.getKey());
            if (have == null) {
                continue;
            }
            s += have >= e.getValue() ? 10 : 4 - (e.getValue() - have);
        }
        if (!customName.isEmpty() && customName.equals(candidate.customName)) {
            s += 3;
        }
        return s;
    }

    /** Compact text form used in the profile JSON: {@code id|ench=lvl,ench=lvl|name}. */
    public String encode() {
        StringBuilder sb = new StringBuilder(itemId).append('|');
        boolean first = true;
        for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('|').append(customName.replace('|', '/'));
        return sb.toString();
    }

    /** Parses {@link #encode()} output; null for empty/garbled input. */
    public static GearItem decode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] parts = text.split("\\|", 3);
        if (parts[0].isEmpty()) {
            return null;
        }
        TreeMap<String, Integer> ench = new TreeMap<>();
        if (parts.length > 1 && !parts[1].isEmpty()) {
            for (String pair : parts[1].split(",")) {
                int eq = pair.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    ench.put(pair.substring(0, eq), Integer.parseInt(pair.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    // skip a garbled pair
                }
            }
        }
        return new GearItem(parts[0], ench, parts.length > 2 ? parts[2] : "");
    }
}
