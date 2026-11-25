package com.utilitysuite.core.api;

import java.util.List;

/** Read-only view of indexed containers for the current world. Snapshots; safe from any thread. */
public interface IStorageIndex {

    List<StorageEntry> entries();

    /** Total known count of an item across containers (stale ones only when {@code includeStale}). */
    int count(String itemId, boolean includeStale);

    /** Containers holding the item, nearest to {@code fromPackedPos} first. */
    List<StorageEntry> containersWith(String itemId, String dimension, long fromPackedPos);

    /**
     * The ender chest the player carries, as a source to place, use and mine back with Silk Touch, or null when that is
     * not possible (no chest or no Silk Touch pickaxe carried, mirror unknown, or the ender chest source is off).
     * Its position is a sentinel, never a real block. Callers use it only when no placed ender chest is in range.
     */
    default StorageEntry carriedEnderChest(String dimension) {
        return null;
    }
}
