package com.utilitysuite.combat.allies;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Players the combat code must treat as friends: a per-world list (the active profile) plus an optional global list.
 * A player in both uses the world entry. Lookups ({@link #isNeverTarget}, {@link #isDefended}) are map reads with no
 * allocation. Pure Java; client thread only. Persistence is {@link AllyRosterStore}; changes fire the listener.
 */
public final class AllyRoster {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/allies");

    private final Map<UUID, AllyEntry> world = new LinkedHashMap<>();
    private final Map<UUID, AllyEntry> global = new LinkedHashMap<>();
    private Runnable changeListener = () -> { };

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener == null ? () -> { } : listener;
    }

    // ---- queries (hot path) ---------------------------------------------------------------------------------------

    public AllyEntry get(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        AllyEntry e = world.get(uuid);
        return e != null ? e : global.get(uuid);
    }

    public boolean contains(UUID uuid) {
        return get(uuid) != null;
    }

    public boolean isNeverTarget(UUID uuid) {
        AllyEntry e = get(uuid);
        return e != null && e.neverTarget();
    }

    public boolean isDefended(UUID uuid) {
        AllyEntry e = get(uuid);
        return e != null && e.defend();
    }

    // ---- edits ----------------------------------------------------------------------------------------------------

    /** Adds (or replaces) a player with the defaults {@code neverTarget = true}, {@code defend = false}. */
    public AllyEntry add(UUID uuid, String name, boolean toGlobal) {
        AllyEntry e = AllyEntry.added(uuid, name, toGlobal);
        (toGlobal ? global : world).put(uuid, e);
        changed();
        return e;
    }

    /** Removes the player from both lists. */
    public boolean remove(UUID uuid) {
        boolean removed = world.remove(uuid) != null;
        removed |= global.remove(uuid) != null;
        if (removed) {
            changed();
        }
        return removed;
    }

    /** Middle-click behaviour: adds to the world list when absent, removes when present. True when now on the roster. */
    public boolean toggle(UUID uuid, String name) {
        if (contains(uuid)) {
            remove(uuid);
            return false;
        }
        add(uuid, name, false);
        return true;
    }

    public boolean setNeverTarget(UUID uuid, boolean value) {
        return update(uuid, value, true);
    }

    public boolean setDefend(UUID uuid, boolean value) {
        return update(uuid, value, false);
    }

    private boolean update(UUID uuid, boolean value, boolean neverTargetField) {
        Map<UUID, AllyEntry> map = world.containsKey(uuid) ? world : global.containsKey(uuid) ? global : null;
        if (map == null) {
            return false;
        }
        AllyEntry e = map.get(uuid);
        map.put(uuid, neverTargetField ? e.withNeverTarget(value) : e.withDefend(value));
        changed();
        return true;
    }

    /** Keeps the display name current (players can rename). */
    public void refreshName(UUID uuid, String name) {
        Map<UUID, AllyEntry> map = world.containsKey(uuid) ? world : global.containsKey(uuid) ? global : null;
        if (map != null && name != null && !name.equals(map.get(uuid).name())) {
            map.put(uuid, map.get(uuid).withName(name));
            changed();
        }
    }

    /** World entries first, then global entries not overridden by the world list. Snapshot copy. */
    public List<AllyEntry> entries() {
        List<AllyEntry> out = new ArrayList<>(world.values());
        for (AllyEntry e : global.values()) {
            if (!world.containsKey(e.uuid())) {
                out.add(e);
            }
        }
        return out;
    }

    public List<AllyEntry> worldEntries() {
        return new ArrayList<>(world.values());
    }

    public List<AllyEntry> globalEntries() {
        return new ArrayList<>(global.values());
    }

    // ---- loading --------------------------------------------------------------------------------------------------

    /** Replaces the world list (profile load). Does not fire the change listener. */
    public void loadWorld(Collection<AllyEntry> entries) {
        world.clear();
        for (AllyEntry e : entries) {
            world.put(e.uuid(), new AllyEntry(e.uuid(), e.name(), e.neverTarget(), e.defend(), false));
        }
    }

    /** Replaces the global list. Does not fire the change listener. */
    public void loadGlobal(Collection<AllyEntry> entries) {
        global.clear();
        for (AllyEntry e : entries) {
            global.put(e.uuid(), new AllyEntry(e.uuid(), e.name(), e.neverTarget(), e.defend(), true));
        }
    }

    private void changed() {
        try {
            changeListener.run();
        } catch (RuntimeException e) {
            LOG.error("Ally roster change listener failed", e);
        }
    }

    // ---- JSON -----------------------------------------------------------------------------------------------------

    /** {@code {"allies":[{"uuid":..,"name":..,"neverTarget":..,"defend":..}]}} */
    public static JsonObject toJson(Collection<AllyEntry> entries) {
        JsonArray arr = new JsonArray();
        for (AllyEntry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.uuid().toString());
            o.addProperty("name", e.name());
            o.addProperty("neverTarget", e.neverTarget());
            o.addProperty("defend", e.defend());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("allies", arr);
        return root;
    }

    /** Lenient parse: malformed entries are skipped and logged. */
    public static List<AllyEntry> fromJson(JsonObject root, boolean global) {
        List<AllyEntry> out = new ArrayList<>();
        if (root == null || !root.has("allies") || !root.get("allies").isJsonArray()) {
            return out;
        }
        for (JsonElement el : root.getAsJsonArray("allies")) {
            try {
                JsonObject o = el.getAsJsonObject();
                UUID uuid = UUID.fromString(o.get("uuid").getAsString());
                String name = o.has("name") ? o.get("name").getAsString() : "";
                boolean never = !o.has("neverTarget") || o.get("neverTarget").getAsBoolean();
                boolean defend = o.has("defend") && o.get("defend").getAsBoolean();
                out.add(new AllyEntry(uuid, name, never, defend, global));
            } catch (RuntimeException ex) {
                LOG.warn("Skipping malformed ally entry {}", el);
            }
        }
        return out;
    }
}
