package com.utilitysuite.core.api;

import java.util.List;
import java.util.Map;

/**
 * Last known contents of a container.
 *
 * @param packedPos {@code BlockPos.asLong()}
 * @param contents  item id -> count of the top-level slots (a shulker box item counts as itself)
 * @param stale     contents may no longer be accurate
 * @param nested    shulker boxes inside this container, with their contents (reachable only by taking the box out and
 *                  opening it)
 */
public record StorageEntry(long packedPos, String dimension, String containerType, Map<String, Integer> contents,
                           long timestampMillis, boolean stale, List<NestedContainer> nested) {

    public StorageEntry {
        contents = Map.copyOf(contents);
        nested = nested == null ? List.of() : List.copyOf(nested);
    }

    public StorageEntry(long packedPos, String dimension, String containerType, Map<String, Integer> contents,
                        long timestampMillis, boolean stale) {
        this(packedPos, dimension, containerType, contents, timestampMillis, stale, List.of());
    }

    /** Count of the item inside nested shulker boxes only. */
    public int nestedCount(String itemId) {
        int sum = 0;
        for (int i = 0, n = nested.size(); i < n; i++) {
            sum += nested.get(i).count(itemId);
        }
        return sum;
    }

    /** Top-level plus nested count. */
    public int totalCount(String itemId) {
        return contents.getOrDefault(itemId, 0) + nestedCount(itemId);
    }
}
