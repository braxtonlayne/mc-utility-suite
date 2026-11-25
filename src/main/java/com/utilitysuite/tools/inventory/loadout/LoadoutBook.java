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

    /**
     * Renames a loadout in place (order kept). False when {@code from} is missing, {@code to} is blank, or another
     * loadout already has the new name.
     */
    public boolean rename(String from, String to) {
        Loadout l = get(from);
        if (l == null || to == null || to.isBlank()) {
            return false;
        }
        String nk = to.trim().toLowerCase(Locale.ROOT);
        String ok = l.name().toLowerCase(Locale.ROOT);
        if (!nk.equals(ok) && byName.containsKey(nk)) {
            return false;
        }
        GearItem[] s = new GearItem[Loadout.TARGETS];
        for (int i = 0; i < s.length; i++) {
            s[i] = l.slot(i);
        }
        Loadout renamed = new Loadout(to.trim(), s);
        Map<String, Loadout> copy = new LinkedHashMap<>(byName);
        byName.clear();
        for (Map.Entry<String, Loadout> e : copy.entrySet()) {
            if (e.getKey().equals(ok)) {
                byName.put(nk, renamed);
            } else {
                byName.put(e.getKey(), e.getValue());
            }
        }
        return true;
    }

    /** True when {@code name} is one of the suggested presets (case-insensitive). */
    public static boolean isPreset(String name) {
        for (String p : Loadout.PRESET_NAMES) {
            if (p.equalsIgnoreCase(name == null ? "" : name.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Suite-action id suffix for a loadout name: lower case, runs of non-alphanumerics collapsed to "_". */
    public static String actionKey(String name) {
        String k = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return k.replaceAll("^_+|_+$", "");
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
