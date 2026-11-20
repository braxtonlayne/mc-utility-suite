package com.utilitysuite.world.waypoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.Waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Saved waypoints of the current world plus the breadcrumb trail; implements {@link IWaypointService}. Pure logic
 * (JSON via Gson). Writes on the client thread; {@link #all()} returns copies. Tags are kept beside the immutable
 * {@link Waypoint} records.
 */
public final class WaypointStore implements IWaypointService {

    public static final String GROUP_BASES = "Bases";
    public static final String GROUP_MINES = "Mines";
    public static final String GROUP_PORTALS = "Portals";
    public static final String GROUP_DEATHS = "Death Sites";
    public static final List<String> DEFAULT_GROUPS = List.of(GROUP_BASES, GROUP_MINES, GROUP_PORTALS, GROUP_DEATHS);

    private final Map<String, Waypoint> byId = new LinkedHashMap<>();
    private final Map<String, Set<String>> tags = new LinkedHashMap<>();
    private final Supplier<BreadcrumbTrail> trail;
    private long nextId = 1;
    private boolean dirty;
    private volatile int version;
    private long lastDeathMillis = Long.MIN_VALUE;
    private long lastDeathPos;
    private String lastDeathDim;

    public WaypointStore(Supplier<BreadcrumbTrail> trail) {
        this.trail = trail;
    }

    public static int defaultColor(String group) {
        return switch (group) {
            case GROUP_BASES -> 0xFF55FF55;
            case GROUP_MINES -> 0xFFFFAA00;
            case GROUP_PORTALS -> 0xFFAA55FF;
            case GROUP_DEATHS -> 0xFFFF5555;
            default -> 0xFF55FFFF;
        };
    }

    @Override
    public synchronized List<Waypoint> all() {
        return List.copyOf(byId.values());
    }

    public synchronized List<Waypoint> inGroup(String group) {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : byId.values()) {
            if (w.group().equals(group)) {
                out.add(w);
            }
        }
        return out;
    }

    @Override
    public synchronized Optional<Waypoint> byName(String name) {
        for (Waypoint w : byId.values()) {
            if (w.name().equalsIgnoreCase(name)) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Waypoint add(String name, String group, int x, int y, int z, String dimension) {
        String g = group == null || group.isBlank() ? GROUP_BASES : group;
        Waypoint w = new Waypoint("wp" + (nextId++), name, g, x, y, z, dimension, defaultColor(g));
        byId.put(w.id(), w);
        dirty = true;
        version++;
        return w;
    }

    /** Adds a "Death Site" waypoint, ignoring duplicates of the same death (several sources may report it). */
    public synchronized Waypoint addDeathSite(int x, int y, int z, String dimension, long timeMillis) {
        long pos = com.utilitysuite.world.BlockPosPacking.pack(x, y, z);
        if (dimension.equals(lastDeathDim) && timeMillis - lastDeathMillis < 10_000L
                && com.utilitysuite.world.BlockPosPacking.distSq(pos, lastDeathPos) <= 16) {
            return null;
        }
        lastDeathMillis = timeMillis;
        lastDeathPos = pos;
        lastDeathDim = dimension;
        int n = inGroup(GROUP_DEATHS).size() + 1;
        return add("Death " + n, GROUP_DEATHS, x, y, z, dimension);
    }

    @Override
    public synchronized void remove(String id) {
        if (byId.remove(id) != null) {
            tags.remove(id);
            dirty = true;
            version++;
        }
    }

    public synchronized void rename(String id, String name) {
        Waypoint w = byId.get(id);
        if (w != null) {
            byId.put(id, new Waypoint(id, name, w.group(), w.x(), w.y(), w.z(), w.dimension(), w.argbColor()));
            dirty = true;
            version++;
        }
    }

    public synchronized void setGroup(String id, String group) {
        Waypoint w = byId.get(id);
        if (w != null) {
            byId.put(id, new Waypoint(id, w.name(), group, w.x(), w.y(), w.z(), w.dimension(), defaultColor(group)));
            dirty = true;
            version++;
        }
    }

    public synchronized void addTag(String id, String tag) {
        if (byId.containsKey(id) && tags.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(tag)) {
            dirty = true;
            version++;
        }
    }

    public synchronized void removeTag(String id, String tag) {
        Set<String> t = tags.get(id);
        if (t != null && t.remove(tag)) {
            dirty = true;
            version++;
        }
    }

    public synchronized Set<String> tags(String id) {
        Set<String> t = tags.get(id);
        return t == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(t));
    }

    public synchronized List<String> groups() {
        Set<String> g = new LinkedHashSet<>(DEFAULT_GROUPS);
        for (Waypoint w : byId.values()) {
            g.add(w.group());
        }
        return List.copyOf(g);
    }

    @Override
    public long positionIn(Waypoint waypoint, String dimension) {
        return DimensionScale.convert(waypoint.x(), waypoint.y(), waypoint.z(), waypoint.dimension(), dimension);
    }

    @Override
    public long[] breadcrumbs() {
        BreadcrumbTrail t = trail.get();
        return t == null ? new long[0] : t.toArray();
    }

    /** Bumped on every change (render lists rebuild when it moves). */
    public int version() {
        return version;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void clearDirty() {
        dirty = false;
    }

    public synchronized void clear() {
        byId.clear();
        tags.clear();
        nextId = 1;
        dirty = false;
        version++;
    }

    // ---- JSON -------------------------------------------------------------------------------------------------------

    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("nextId", nextId);
        JsonArray arr = new JsonArray();
        for (Waypoint w : byId.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", w.id());
            o.addProperty("name", w.name());
            o.addProperty("group", w.group());
            o.addProperty("x", w.x());
            o.addProperty("y", w.y());
            o.addProperty("z", w.z());
            o.addProperty("dim", w.dimension());
            o.addProperty("color", w.argbColor());
            Set<String> t = tags.get(w.id());
            if (t != null && !t.isEmpty()) {
                JsonArray ta = new JsonArray();
                t.forEach(ta::add);
                o.add("tags", ta);
            }
            arr.add(o);
        }
        root.add("waypoints", arr);
        return root.toString();
    }

    public synchronized void fromJson(String json) {
        clear();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        nextId = root.has("nextId") ? root.get("nextId").getAsLong() : 1;
        for (JsonElement e : root.getAsJsonArray("waypoints")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            String group = o.get("group").getAsString();
            Waypoint w = new Waypoint(id, o.get("name").getAsString(), group, o.get("x").getAsInt(),
                    o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("dim").getAsString(),
                    o.has("color") ? o.get("color").getAsInt() : defaultColor(group));
            byId.put(id, w);
            if (o.has("tags")) {
                Set<String> t = new LinkedHashSet<>();
                for (JsonElement te : o.getAsJsonArray("tags")) {
                    t.add(te.getAsString());
                }
                tags.put(id, t);
            }
        }
        dirty = false;
        version++;
    }
}
