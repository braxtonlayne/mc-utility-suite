package com.utilitysuite.tools.inventory.loadout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Named loadouts (case-insensitive names, insertion order kept), stored as one compact JSON array string. */
public final class LoadoutBook {

    public static final int MAX = 32;

    private final Map<String, Loadout> byName = new LinkedHashMap<>();

    public void put(Loadout l) {
        String k = l.name().toLowerCase(Locale.ROOT);
        if (!byName.containsKey(k) && byName.size() >= MAX) {
            return;
        }
        byName.put(k, l);
    }

    public Loadout get(String name) {
        return name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public boolean remove(String name) {
        return name != null && byName.remove(name.trim().toLowerCase(Locale.ROOT)) != null;
    }

    public List<Loadout> all() {
        return Collections.unmodifiableList(new ArrayList<>(byName.values()));
    }

    public String encode() {
        JsonArray a = new JsonArray();
        for (Loadout l : byName.values()) {
            a.add(l.toJson());
        }
        return a.toString();
    }

    public static LoadoutBook decode(String text) {
        LoadoutBook b = new LoadoutBook();
        if (text == null || text.isBlank()) {
            return b;
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    Loadout l = Loadout.fromJson(x);
                    if (l != null) {
                        b.put(l);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // garbled: start empty
        }
        return b;
    }
}
