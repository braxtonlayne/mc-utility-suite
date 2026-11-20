package com.utilitysuite.jobs.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Persistent per-world container index. Writes happen on the client thread; readers get an immutable snapshot that is
 * republished after every change (safe from any thread, as {@link IStorageIndex} requires). Entries are keyed by
 * dimension and {@code BlockPos.asLong()} in fastutil maps. An entry is stale when it was marked (block changed or a
 * fetch found it short) or when it is older than {@link #maxAgeMillis()}.
 */
public final class StorageMemory implements IStorageIndex {

    private static final class Record {
        final long pos;
        final String dimension;
        String type;
        Map<String, Integer> contents;
        long timestamp;
        boolean marked;

        Record(long pos, String dimension) {
            this.pos = pos;
            this.dimension = dimension;
        }
    }

    private final Map<String, Long2ObjectOpenHashMap<Record>> byDim = new HashMap<>();
    private final LongSupplier clock;
    private volatile List<StorageEntry> snapshot = List.of();
    private volatile long maxAgeMillis;
    private long lastAgeCheck;
    private boolean dirty;

    public StorageMemory(LongSupplier clock, long maxAgeMillis) {
        this.clock = clock;
        this.maxAgeMillis = maxAgeMillis;
    }

    public long maxAgeMillis() {
        return maxAgeMillis;
    }

    public void setMaxAgeMillis(long ms) {
        this.maxAgeMillis = ms;
        publish();
    }

    // ---- writes (client thread) -------------------------------------------------------------------------------------

    /** Records the full contents seen when a container was open. Empty contents are recorded too (known empty). */
    public void record(String dimension, long pos, String containerType, Map<String, Integer> contents) {
        Record r = byDim.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>()).get(pos);
        if (r == null) {
            r = new Record(pos, dimension);
            byDim.get(dimension).put(pos, r);
        }
        r.type = containerType;
        r.contents = Map.copyOf(contents);
        r.timestamp = clock.getAsLong();
        r.marked = false;
        changed();
    }

    /** Contents may no longer be accurate (block changed, fetch came up short). */
    public void markStale(String dimension, long pos) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        Record r = m == null ? null : m.get(pos);
        if (r != null && !r.marked) {
            r.marked = true;
            changed();
        }
    }

    /** The container block is gone. */
    public void remove(String dimension, long pos) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        if (m != null && m.remove(pos) != null) {
            changed();
        }
    }

    /** True when the position is indexed in the dimension (cheap, no allocation). */
    public boolean contains(String dimension, long pos) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        return m != null && m.containsKey(pos);
    }

    /** Marks every entry within {@code radius} of the position stale (nearby blocks changed). */
    public void markStaleNear(String dimension, long center, int radius) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        if (m == null) {
            return;
        }
        long r2 = (long) radius * radius;
        boolean any = false;
        for (Record r : m.values()) {
            if (!r.marked && PackedPos.distSq(r.pos, center) <= r2) {
                r.marked = true;
                any = true;
            }
        }
        if (any) {
            changed();
        }
    }

    public void clear() {
        byDim.clear();
        dirty = false;
        snapshot = List.of();
    }

    /** Republishes the snapshot when entries crossed the age threshold. Call occasionally (e.g. every few seconds). */
    public void refreshAges() {
        long now = clock.getAsLong();
        if (now - lastAgeCheck < 1000) {
            return;
        }
        lastAgeCheck = now;
        for (StorageEntry e : snapshot) {
            if (!e.stale() && now - e.timestampMillis() > maxAgeMillis) {
                publish();
                return;
            }
        }
    }

    /** True once since the last {@link #consumeDirty()} when something changed (drives debounced saving). */
    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    private void changed() {
        dirty = true;
        publish();
    }

    private void publish() {
        long now = clock.getAsLong();
        List<StorageEntry> out = new ArrayList<>();
        for (Long2ObjectOpenHashMap<Record> m : byDim.values()) {
            for (Record r : m.values()) {
                boolean stale = r.marked || now - r.timestamp > maxAgeMillis;
                out.add(new StorageEntry(r.pos, r.dimension, r.type, r.contents, r.timestamp, stale));
            }
        }
        snapshot = List.copyOf(out);
    }

    // ---- reads (any thread) -----------------------------------------------------------------------------------------

    @Override
    public List<StorageEntry> entries() {
        return snapshot;
    }

    @Override
    public int count(String itemId, boolean includeStale) {
        int sum = 0;
        for (StorageEntry e : snapshot) {
            if (includeStale || !e.stale()) {
                sum += e.contents().getOrDefault(itemId, 0);
            }
        }
        return sum;
    }

    @Override
    public List<StorageEntry> containersWith(String itemId, String dimension, long fromPackedPos) {
        List<StorageEntry> out = new ArrayList<>();
        for (StorageEntry e : snapshot) {
            if (!e.stale() && e.dimension().equals(dimension) && e.contents().getOrDefault(itemId, 0) > 0) {
                out.add(e);
            }
        }
        out.sort((a, b) -> Long.compare(PackedPos.distSq(a.packedPos(), fromPackedPos),
                PackedPos.distSq(b.packedPos(), fromPackedPos)));
        return out;
    }

    /** Nearest non-stale container in the dimension that already holds the item (deposit target), or null. */
    public StorageEntry depositTarget(String itemId, String dimension, long from, int maxDistance) {
        List<StorageEntry> c = containersWith(itemId, dimension, from);
        if (c.isEmpty() || PackedPos.distSq(c.get(0).packedPos(), from) > (long) maxDistance * maxDistance) {
            return null;
        }
        return c.get(0);
    }

    // ---- persistence ------------------------------------------------------------------------------------------------

    /** Compact JSON of the current snapshot (call on the client thread, write the string off-thread). */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonArray arr = new JsonArray();
        for (Long2ObjectOpenHashMap<Record> m : byDim.values()) {
            for (Record r : m.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("p", r.pos);
                o.addProperty("d", r.dimension);
                o.addProperty("t", r.type);
                o.addProperty("ts", r.timestamp);
                if (r.marked) {
                    o.addProperty("s", true);
                }
                JsonObject c = new JsonObject();
                r.contents.forEach(c::addProperty);
                o.add("c", c);
                arr.add(o);
            }
        }
        root.add("entries", arr);
        return root.toString();
    }

    /** Replaces the index with the JSON contents. Malformed entries are skipped. */
    public void loadJson(String json) {
        byDim.clear();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                Record r = new Record(o.get("p").getAsLong(), o.get("d").getAsString());
                r.type = o.get("t").getAsString();
                r.timestamp = o.get("ts").getAsLong();
                r.marked = o.has("s") && o.get("s").getAsBoolean();
                Map<String, Integer> c = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("c").entrySet()) {
                    c.put(e.getKey(), e.getValue().getAsInt());
                }
                r.contents = Map.copyOf(c);
                byDim.computeIfAbsent(r.dimension, d -> new Long2ObjectOpenHashMap<>()).put(r.pos, r);
            }
        } catch (RuntimeException e) {
            byDim.clear();
        }
        dirty = false;
        publish();
    }
}
