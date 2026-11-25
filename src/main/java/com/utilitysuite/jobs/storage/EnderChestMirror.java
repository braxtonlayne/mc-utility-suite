package com.utilitysuite.jobs.storage;

import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.jobs.planner.ShulkerContents;

import java.util.List;
import java.util.Map;

/**
 * Last seen contents of the player's ender chest: one portable source shared by every placed ender chest, so it is
 * kept apart from the position-keyed {@link StorageMemory}. Record it whenever an ender chest menu is open (nested
 * shulker boxes via {@link ShulkerItems}). Immutable snapshot published on each record; safe to read from any thread.
 */
public final class EnderChestMirror {

    public record Snapshot(Map<String, Integer> contents, List<NestedContainer> nested, long timestampMillis) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), 0L);

        public Snapshot {
            contents = Map.copyOf(contents);
            nested = List.copyOf(nested);
        }

        public boolean known() {
            return timestampMillis > 0;
        }

        public int count(String itemId) {
            return contents.getOrDefault(itemId, 0) + ShulkerContents.count(nested, itemId);
        }
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public void record(Map<String, Integer> contents, List<NestedContainer> nested, long nowMillis) {
        snapshot = new Snapshot(contents, nested, Math.max(1L, nowMillis));
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void clear() {
        snapshot = Snapshot.EMPTY;
    }
}
