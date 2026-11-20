package com.utilitysuite.core.api;

import java.util.Map;

/**
 * Last known contents of a container.
 *
 * @param packedPos {@code BlockPos.asLong()}
 * @param contents  item id -> count
 * @param stale     contents may no longer be accurate
 */
public record StorageEntry(long packedPos, String dimension, String containerType, Map<String, Integer> contents,
                           long timestampMillis, boolean stale) {

    public StorageEntry {
        contents = Map.copyOf(contents);
    }
}
