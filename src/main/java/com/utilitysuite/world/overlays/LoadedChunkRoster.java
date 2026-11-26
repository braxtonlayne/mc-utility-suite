package com.utilitysuite.world.overlays;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * The chunks the scanner believes are loaded (packed chunk keys), with O(1) add/remove/contains and a round-robin
 * cursor for verifying them against the client chunk cache.
 * <p>
 * Why the verification: the client chunk cache drops chunks without a {@code ChunkEvent.Unload} in three cases
 * (vanilla 1.21.1 and 26.3 alike): a forget-chunk packet for a chunk that is already outside the cache's range
 * (the server sends the new chunk-cache centre before the forget packets, so after a teleport or a fast move every old
 * chunk is dropped silently), a ring-buffer slot reused by a newer chunk, and a view-radius change. Their highlights
 * then stayed in the index forever; the 7-world playtest's X-Ray check saw them as {@code void_air} (the empty chunk the
 * client returns for an unloaded position). {@link #sweep} finds them within a bounded budget per tick.
 * <p>
 * Engine-free and allocation-free after warm-up (the backing list and map grow to the render-distance chunk count).
 */
final class LoadedChunkRoster {

    private final LongArrayList keys = new LongArrayList();
    private final Long2IntOpenHashMap slot = new Long2IntOpenHashMap();
    private int cursor;

    LoadedChunkRoster() {
        slot.defaultReturnValue(-1);
    }

    /** Adds a chunk; false if it was already present. */
    boolean add(long chunk) {
        if (slot.containsKey(chunk)) {
            return false;
        }
        slot.put(chunk, keys.size());
        keys.add(chunk);
        return true;
    }

    /** Removes a chunk (swap-with-last); false if it was not present. */
    boolean remove(long chunk) {
        int i = slot.remove(chunk);
        if (i < 0) {
            return false;
        }
        int last = keys.size() - 1;
        if (i != last) {
            long moved = keys.getLong(last);
            keys.set(i, moved);
            slot.put(moved, i);
        }
        keys.removeLong(last);
        return true;
    }

    boolean contains(long chunk) {
        return slot.containsKey(chunk);
    }

    int size() {
        return keys.size();
    }

    void clear() {
        keys.clear();
        slot.clear();
        cursor = 0;
    }

    long[] toLongArray() {
        return keys.toLongArray();
    }

    /**
     * Checks up to {@code budget} chunks round-robin; every chunk {@code present} rejects is removed from the roster
     * and then passed to {@code onStale}. Returns the number of stale chunks found.
     */
    int sweep(int budget, LongPredicate present, LongConsumer onStale) {
        int stale = 0;
        for (int n = 0; n < budget && !keys.isEmpty(); n++) {
            if (cursor >= keys.size()) {
                cursor = 0;
            }
            long chunk = keys.getLong(cursor);
            if (present.test(chunk)) {
                cursor++;
            } else {
                remove(chunk); // the last entry moves into the cursor slot: check it next without advancing
                onStale.accept(chunk);
                stale++;
            }
        }
        return stale;
    }
}
