package com.utilitysuite.jobs.schematic.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * Helpers for block states kept as strings ({@code minecraft:oak_slab[type=double,waterlogged=false]}) in the pure
 * schematic layer, so parsing, material counting and planning run without a Minecraft registry bootstrap.
 * <p>
 * The canonical form has a namespace and properties sorted by name. Every palette entry of a
 * {@link SchematicVolume} is canonical, so two states compare equal exactly when their strings are equal.
 */
public final class BlockStateString {

    private BlockStateString() {
    }

    /** Canonical form of a state string: namespace added, whitespace removed, properties sorted by name. */
    public static String canonical(String raw) {
        String s = raw.trim();
        int bracket = s.indexOf('[');
        String id = normalizeId(bracket < 0 ? s : s.substring(0, bracket));
        if (bracket < 0) {
            return id;
        }
        int end = s.lastIndexOf(']');
        String body = s.substring(bracket + 1, end < bracket ? s.length() : end);
        TreeMap<String, String> props = new TreeMap<>();
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return of(id, props);
    }

    /** Builds a canonical state string from an id and a property map (any order). */
    public static String of(String id, Map<String, String> properties) {
        String nid = normalizeId(id);
        if (properties == null || properties.isEmpty()) {
            return nid;
        }
        Map<String, String> sorted = properties instanceof TreeMap ? properties : new TreeMap<>(properties);
        StringBuilder sb = new StringBuilder(nid.length() + 16 * sorted.size()).append(nid).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    /** {@code "stone"} becomes {@code "minecraft:stone"}; lower-cased. */
    public static String normalizeId(String id) {
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    /** The block id part of a state string. */
    public static String blockId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /** Value of a property, or {@code null} when absent. */
    public static String property(String state, String name) {
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            return null;
        }
        int i = bracket + 1;
        int n = state.length();
        while (i < n) {
            int eq = state.indexOf('=', i);
            if (eq < 0) {
                return null;
            }
            int end = state.indexOf(',', eq);
            if (end < 0) {
                end = state.indexOf(']', eq);
                if (end < 0) {
                    end = n;
                }
            }
            if (eq - i == name.length() && state.regionMatches(i, name, 0, name.length())) {
                return state.substring(eq + 1, end);
            }
            i = end + 1;
        }
        return null;
    }

    /** Integer property value, or {@code def} when absent or not a number. */
    public static int intProperty(String state, String name, int def) {
        String v = property(state, name);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Path part of an id: {@code minecraft:oak_slab} becomes {@code oak_slab}. */
    public static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** Namespace part of an id ({@code minecraft} when none). */
    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
