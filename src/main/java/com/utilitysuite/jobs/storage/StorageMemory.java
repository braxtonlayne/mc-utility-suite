package com.utilitysuite.jobs.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.StationJobPlanner;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
public final class StorageMemory implements IStorageIndex, StationJobPlanner.StationSource {

    private static final class Record {
        final long pos;
        final String dimension;
        String type;
        Map<String, Integer> contents;
        List<NestedContainer> nested = List.of();
        /** Per-slot contents when the capture had them (null for old saves / item-count-only records). */
        ChestSlots slots;
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
        record(dimension, pos, containerType, contents, List.of());
    }

    /**
     * Records the contents plus the shulker boxes seen inside the container (their items count toward {@link #count}
     * and {@link #containersWith}, tagged as nested in {@link StorageEntry#nested()}).
     */
    public void record(String dimension, long pos, String containerType, Map<String, Integer> contents,
                       List<NestedContainer> nested) {
        record(dimension, pos, containerType, contents, nested, null);
    }

    /** As above, plus the per-slot contents (hall sorter input; null when not captured). */
    public void record(String dimension, long pos, String containerType, Map<String, Integer> contents,
                       List<NestedContainer> nested, ChestSlots slots) {
        Record r = byDim.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>()).get(pos);
        if (r == null) {
            r = new Record(pos, dimension);
            byDim.get(dimension).put(pos, r);
        }
        r.type = containerType;
        r.timestamp = clock.getAsLong();
        r.marked = false;
        if (ENDER_CHEST.equals(containerType)) {
            // One shared inventory: the mirror holds the contents, the record only remembers where a chest stands.
            ender.record(contents, nested, r.timestamp);
            r.contents = Map.of();
            r.nested = List.of();
            r.slots = null;
            for (Long2ObjectOpenHashMap<Record> m : byDim.values()) {
                for (Record o : m.values()) {
                    if (ENDER_CHEST.equals(o.type)) {
                        o.marked = false;
                    }
                }
            }
        } else {
            r.contents = Map.copyOf(contents);
            r.nested = List.copyOf(nested);
            r.slots = slots;
        }
        changed();
    }

    // ---- hall sorter: per-slot contents and category tags --------------------------------------------------------------

    /** dimension -> packed pos -> manual category tag (StorageCategories id). */
    private final Map<String, Long2ObjectOpenHashMap<String>> tags = new HashMap<>();

    /** The manual category tag of a container, or "" (client thread). */
    public String tag(String dimension, long pos) {
        Long2ObjectOpenHashMap<String> m = tags.get(dimension);
        String t = m == null ? null : m.get(pos);
        return t == null ? "" : t;
    }

    /** Sets (or with null/"" clears) a container's category tag. */
    public void setTag(String dimension, long pos, String tag) {
        Long2ObjectOpenHashMap<String> m = tags.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
        String old = tag == null || tag.isEmpty() ? m.remove(pos) : m.put(pos, tag);
        if (!java.util.Objects.equals(old, tag == null || tag.isEmpty() ? null : tag)) {
            dirty = true;
        }
    }

    /** Advances the tag through none, then every category in order, then none again. Returns the new tag. */
    public String cycleTag(String dimension, long pos) {
        String cur = tag(dimension, pos);
        String[] all = com.utilitysuite.jobs.storage.hall.StorageCategories.ALL;
        String next = all[0];
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(cur)) {
                next = i + 1 < all.length ? all[i + 1] : "";
                break;
            }
        }
        setTag(dimension, pos, next);
        return next;
    }

    /**
     * Fills {@code out} with up to {@code out.length} positions of captured containers within {@code radius} whose
     * used-slot share is at least {@code ratio}; returns how many. No per-entry allocation (client thread; call on a
     * budget, not every tick).
     */
    public int nearlyFull(String dimension, long center, int radius, double ratio, long[] out) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        if (m == null) {
            return 0;
        }
        long r2 = (long) radius * radius;
        int n = 0;
        for (Record r : m.values()) {
            if (n >= out.length) {
                break;
            }
            ChestSlots s = r.slots;
            if (s != null && s.size() > 0 && !ENDER_CHEST.equals(r.type) && PackedPos.distSq(r.pos, center) <= r2
                    && s.usedSlots() >= s.size() * ratio) {
                out[n++] = r.pos;
            }
        }
        return n;
    }

    /** Per-slot contents last captured for a container, or null. */
    public ChestSlots slots(String dimension, long pos) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        Record r = m == null ? null : m.get(pos);
        return r == null ? null : r.slots;
    }

    /**
     * Hall sorter input: every non-ender container in the dimension within {@code radius} of {@code center} with
     * captured slots, nearest first, as planner chests (tag = manual tag or "" for inference). Client thread.
     */
    public List<com.utilitysuite.jobs.storage.hall.HallSortPlanner.Chest> hallChests(String dimension, long center,
                                                                                   int radius) {
        List<Record> picked = new ArrayList<>();
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        long r2 = (long) radius * radius;
        if (m != null) {
            for (Record r : m.values()) {
                if (r.slots != null && !ENDER_CHEST.equals(r.type) && PackedPos.distSq(r.pos, center) <= r2) {
                    picked.add(r);
                }
            }
        }
        picked.sort((a, b) -> Long.compare(PackedPos.distSq(a.pos, center), PackedPos.distSq(b.pos, center)));
        List<com.utilitysuite.jobs.storage.hall.HallSortPlanner.Chest> out = new ArrayList<>(picked.size());
        for (Record r : picked) {
            ChestSlots s = r.slots;
            var c = new com.utilitysuite.jobs.storage.hall.HallSortPlanner.Chest(r.pos, tag(dimension, r.pos),
                    s.size());
            for (int i = 0; i < s.size(); i++) {
                if (s.item(i) != null && s.count(i) > 0) {
                    c.put(i, s.item(i), s.variant(i), s.count(i), s.max(i));
                }
            }
            out.add(c);
        }
        return out;
    }

    // ---- ender chest mirror and remembered stations -------------------------------------------------------------------

    public static final String ENDER_CHEST = "minecraft:ender_chest";

    private final EnderChestMirror ender = new EnderChestMirror();
    private volatile boolean enderAsSource;
    /** dimension -> packed pos -> station block id (furnaces, brewing stands, crafters) remembered when opened. */
    private final Map<String, Long2ObjectOpenHashMap<String>> stations = new HashMap<>();
    private volatile List<StationJobPlanner.KnownStation> stationSnapshot = List.of();
    private volatile List<String> stationDims = List.of();

    public EnderChestMirror enderMirror() {
        return ender;
    }

    /**
     * When on, every remembered ender chest shows the mirrored contents in the snapshot (so the planner can FETCH from
     * the nearest one, including nested shulker boxes); {@link #count} counts the mirror once.
     */
    public void setEnderAsSource(boolean on) {
        if (enderAsSource != on) {
            enderAsSource = on;
            publish();
        }
    }

    public boolean enderAsSource() {
        return enderAsSource;
    }

    public void rememberStation(String dimension, long pos, String type) {
        String old = stations.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>()).put(pos, type);
        if (!type.equals(old)) {
            dirty = true;
            publishStations();
        }
    }

    public void forgetStation(String dimension, long pos) {
        Long2ObjectOpenHashMap<String> m = stations.get(dimension);
        if (m != null && m.remove(pos) != null) {
            dirty = true;
            publishStations();
        }
    }

    private void publishStations() {
        List<StationJobPlanner.KnownStation> out = new ArrayList<>();
        List<String> dims = new ArrayList<>();
        for (Map.Entry<String, Long2ObjectOpenHashMap<String>> e : stations.entrySet()) {
            for (Long2ObjectMap.Entry<String> s : e.getValue().long2ObjectEntrySet()) {
                out.add(new StationJobPlanner.KnownStation(s.getLongKey(), s.getValue()));
                dims.add(e.getKey());
            }
        }
        stationSnapshot = List.copyOf(out);
        stationDims = List.copyOf(dims);
    }

    /** Remembered stations of the types within {@code range} in the dimension, nearest first (any thread). */
    @Override
    public List<StationJobPlanner.KnownStation> stations(String dimension, long from, int range,
                                                        java.util.Set<String> types) {
        List<StationJobPlanner.KnownStation> all = stationSnapshot;
        List<String> dims = stationDims;
        List<StationJobPlanner.KnownStation> out = new ArrayList<>();
        long r2 = (long) range * range;
        for (int i = 0; i < all.size(); i++) {
            StationJobPlanner.KnownStation s = all.get(i);
            if (dims.get(i).equals(dimension) && types.contains(s.type()) && PackedPos.distSq(s.pos(), from) <= r2) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Long.compare(PackedPos.distSq(a.pos(), from), PackedPos.distSq(b.pos(), from)));
        return out;
    }

    /** Contents may no longer be accurate (block changed, fetch came up short). */
    public void markStale(String dimension, long pos) {
        Long2ObjectOpenHashMap<Record> m = byDim.get(dimension);
        Record r = m == null ? null : m.get(pos);
        if (r != null && !r.marked) {
            r.marked = true;
            if (ENDER_CHEST.equals(r.type)) {
                // the mirror was wrong: every ender chest shows the same contents
                for (Long2ObjectOpenHashMap<Record> mm : byDim.values()) {
                    for (Record o : mm.values()) {
                        if (ENDER_CHEST.equals(o.type)) {
                            o.marked = true;
                        }
                    }
                }
            }
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
        stations.clear();
        tags.clear();
        publishStations();
        ender.clear();
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
                if (ENDER_CHEST.equals(r.type)) {
                    EnderChestMirror.Snapshot s = ender.snapshot();
                    if (enderAsSource && s.known()) {
                        boolean stale = r.marked || now - s.timestampMillis() > maxAgeMillis;
                        out.add(new StorageEntry(r.pos, r.dimension, r.type, s.contents(), s.timestampMillis(), stale,
                                s.nested()));
                    } else {
                        out.add(new StorageEntry(r.pos, r.dimension, r.type, Map.of(), r.timestamp, true, List.of()));
                    }
                    continue;
                }
                boolean stale = r.marked || now - r.timestamp > maxAgeMillis;
                out.add(new StorageEntry(r.pos, r.dimension, r.type, r.contents, r.timestamp, stale, r.nested));
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
        boolean enderCounted = false;
        for (StorageEntry e : snapshot) {
            if (includeStale || !e.stale()) {
                if (ENDER_CHEST.equals(e.containerType())) {
                    if (enderCounted) {
                        continue; // every ender chest shows the same shared inventory
                    }
                    enderCounted = true;
                }
                sum += e.totalCount(itemId);
            }
        }
        return sum;
    }

    @Override
    public List<StorageEntry> containersWith(String itemId, String dimension, long fromPackedPos) {
        List<StorageEntry> out = new ArrayList<>();
        for (StorageEntry e : snapshot) {
            if (!e.stale() && e.dimension().equals(dimension) && e.totalCount(itemId) > 0) {
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
                if (!r.nested.isEmpty()) {
                    JsonArray n = new JsonArray();
                    for (NestedContainer nc : r.nested) {
                        JsonObject b = new JsonObject();
                        b.addProperty("i", nc.containerItemId());
                        JsonObject bc = new JsonObject();
                        nc.contents().forEach(bc::addProperty);
                        b.add("c", bc);
                        n.add(b);
                    }
                    o.add("n", n);
                }
                if (r.slots != null) {
                    o.addProperty("sl", r.slots.size());
                    JsonArray si = new JsonArray();
                    for (String s : r.slots.encode()) {
                        si.add(s);
                    }
                    o.add("si", si);
                }
                arr.add(o);
            }
        }
        root.add("entries", arr);
        EnderChestMirror.Snapshot es = ender.snapshot();
        if (es.known()) {
            JsonObject e = new JsonObject();
            e.addProperty("ts", es.timestampMillis());
            JsonObject c = new JsonObject();
            es.contents().forEach(c::addProperty);
            e.add("c", c);
            e.add("n", nestedJson(es.nested()));
            root.add("ender", e);
        }
        if (!stations.isEmpty()) {
            JsonArray st = new JsonArray();
            for (Map.Entry<String, Long2ObjectOpenHashMap<String>> d : stations.entrySet()) {
                for (Long2ObjectMap.Entry<String> s : d.getValue().long2ObjectEntrySet()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("p", s.getLongKey());
                    o.addProperty("d", d.getKey());
                    o.addProperty("t", s.getValue());
                    st.add(o);
                }
            }
            root.add("stations", st);
        }
        JsonArray tg = new JsonArray();
        for (Map.Entry<String, Long2ObjectOpenHashMap<String>> d : tags.entrySet()) {
            for (Long2ObjectMap.Entry<String> t : d.getValue().long2ObjectEntrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("p", t.getLongKey());
                o.addProperty("d", d.getKey());
                o.addProperty("g", t.getValue());
                tg.add(o);
            }
        }
        if (!tg.isEmpty()) {
            root.add("tags", tg);
        }
        return root.toString();
    }

    private static JsonArray nestedJson(List<NestedContainer> nested) {
        JsonArray n = new JsonArray();
        for (NestedContainer nc : nested) {
            JsonObject b = new JsonObject();
            b.addProperty("i", nc.containerItemId());
            JsonObject bc = new JsonObject();
            nc.contents().forEach(bc::addProperty);
            b.add("c", bc);
            n.add(b);
        }
        return n;
    }

    private static List<NestedContainer> readNested(JsonArray arr) {
        List<NestedContainer> nested = new ArrayList<>();
        if (arr == null) {
            return nested;
        }
        for (JsonElement be : arr) {
            JsonObject b = be.getAsJsonObject();
            Map<String, Integer> bc = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> x : b.getAsJsonObject("c").entrySet()) {
                bc.put(x.getKey(), x.getValue().getAsInt());
            }
            nested.add(new NestedContainer(b.get("i").getAsString(), bc));
        }
        return nested;
    }

    /** Replaces the index with the JSON contents. Malformed entries are skipped. */
    public void loadJson(String json) {
        byDim.clear();
        stations.clear();
        tags.clear();
        ender.clear();
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
                if (o.has("n")) {
                    List<NestedContainer> nested = new ArrayList<>();
                    for (JsonElement be : o.getAsJsonArray("n")) {
                        JsonObject b = be.getAsJsonObject();
                        Map<String, Integer> bc = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> x : b.getAsJsonObject("c").entrySet()) {
                            bc.put(x.getKey(), x.getValue().getAsInt());
                        }
                        nested.add(new NestedContainer(b.get("i").getAsString(), bc));
                    }
                    r.nested = List.copyOf(nested);
                }
                if (o.has("sl") && o.has("si")) {
                    List<String> si = new ArrayList<>();
                    for (JsonElement s : o.getAsJsonArray("si")) {
                        si.add(s.getAsString());
                    }
                    r.slots = ChestSlots.decode(o.get("sl").getAsInt(), si);
                }
                byDim.computeIfAbsent(r.dimension, d -> new Long2ObjectOpenHashMap<>()).put(r.pos, r);
            }
            if (root.has("ender")) {
                JsonObject e = root.getAsJsonObject("ender");
                Map<String, Integer> c = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> x : e.getAsJsonObject("c").entrySet()) {
                    c.put(x.getKey(), x.getValue().getAsInt());
                }
                ender.record(c, readNested(e.getAsJsonArray("n")), e.get("ts").getAsLong());
            }
            if (root.has("stations")) {
                for (JsonElement el : root.getAsJsonArray("stations")) {
                    JsonObject o = el.getAsJsonObject();
                    stations.computeIfAbsent(o.get("d").getAsString(), d -> new Long2ObjectOpenHashMap<>())
                            .put(o.get("p").getAsLong(), o.get("t").getAsString());
                }
            }
            if (root.has("tags")) {
                for (JsonElement el : root.getAsJsonArray("tags")) {
                    JsonObject o = el.getAsJsonObject();
                    tags.computeIfAbsent(o.get("d").getAsString(), d -> new Long2ObjectOpenHashMap<>())
                            .put(o.get("p").getAsLong(), o.get("g").getAsString());
                }
            }
        } catch (RuntimeException e) {
            byDim.clear();
        }
        publishStations();
        dirty = false;
        publish();
    }
}
