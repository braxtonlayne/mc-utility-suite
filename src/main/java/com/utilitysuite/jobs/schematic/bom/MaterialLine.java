package com.utilitysuite.jobs.schematic.bom;

import java.util.Map;

/**
 * One line of a bill of materials: either an exact item ({@code group == null}) or a substitution group whose members
 * are interchangeable.
 *
 * @param key            the item id, or the group key
 * @param preferredItem  what to gather when short
 * @param required       items still needed for unplaced blocks
 * @param requestedItems the exact items the schematic asks for within this line (item id to count)
 */
public record MaterialLine(String key, String preferredItem, SubstitutionRules.Group group, int required,
                           Map<String, Integer> requestedItems) {

    public MaterialLine {
        requestedItems = Map.copyOf(requestedItems);
    }

    public boolean isGroup() {
        return group != null;
    }

    public boolean accepts(String itemId) {
        return group == null ? key.equals(itemId) : group.accepts(itemId);
    }
}
