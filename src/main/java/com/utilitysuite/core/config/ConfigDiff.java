package com.utilitysuite.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Key-level diff of two config JSON trees, engine free (Gson). Objects are flattened to dotted paths
 * ({@code "combat_assistant.reach"}); arrays and primitives are leaves compared by their compact JSON text, so a
 * Local Rules list shows as one changed key. Used by the profile bundle import preview.
 */
public final class ConfigDiff {

    /** One changed key; {@code oldValue} null = added, {@code newValue} null = removed (compact JSON text). */
    public record Change(String key, String oldValue, String newValue) {
        public boolean added() {
            return oldValue == null;
        }

        public boolean removed() {
            return newValue == null;
        }
    }

    private ConfigDiff() {
    }

    /** Changes from {@code before} to {@code after}, sorted by key. Keys only in {@code before} are listed when
     * {@code includeRemoved}. */
    public static List<Change> diff(JsonObject before, JsonObject after, boolean includeRemoved) {
        Map<String, String> a = flatten(before);
        Map<String, String> b = flatten(after);
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, String> e : b.entrySet()) {
            String old = a.get(e.getKey());
            if (!e.getValue().equals(old)) {
                out.add(new Change(e.getKey(), old, e.getValue()));
            }
        }
        if (includeRemoved) {
            for (Map.Entry<String, String> e : a.entrySet()) {
                if (!b.containsKey(e.getKey())) {
                    out.add(new Change(e.getKey(), e.getValue(), null));
                }
            }
        }
        out.sort((x, y) -> x.key().compareTo(y.key()));
        return out;
    }

    /** Dotted path → compact JSON text of every leaf. */
    public static Map<String, String> flatten(JsonObject root) {
        Map<String, String> out = new TreeMap<>();
        if (root != null) {
            walk("", root, out);
        }
        return out;
    }

    private static void walk(String prefix, JsonObject o, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonElement v = e.getValue();
            if (v != null && v.isJsonObject() && !v.getAsJsonObject().isEmpty()) {
                walk(k, v.getAsJsonObject(), out);
            } else {
                out.put(k, v == null ? "null" : v.toString());
            }
        }
    }

    /** "key: old -> new" lines for the preview (removed / added spelled out). */
    public static String describe(Change c) {
        if (c.added()) {
            return c.key() + ": (unset) -> " + c.newValue();
        }
        if (c.removed()) {
            return c.key() + ": " + c.oldValue() + " -> (unset)";
        }
        return c.key() + ": " + c.oldValue() + " -> " + c.newValue();
    }
}
