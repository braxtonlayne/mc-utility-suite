package com.utilitysuite.core.api;

import java.util.List;

/** Read-only view of indexed containers for the current world. Snapshots; safe from any thread. */
public interface IStorageIndex {

    List<StorageEntry> entries();

    /** Total known count of an item across containers (stale ones only when {@code includeStale}). */
    int count(String itemId, boolean includeStale);

    /** Containers holding the item, nearest to {@code fromPackedPos} first. */
    List<StorageEntry> containersWith(String itemId, String dimension, long fromPackedPos);
}
