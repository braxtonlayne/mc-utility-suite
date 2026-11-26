package com.utilitysuite.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A profile bundle: one JSON file holding a profile's module settings (Local Rules, pinned slots and every other module
 * object live in it), the suite key bindings ({@code translation key → key name}) and the source profile id. Engine
 * free (Gson + NIO). Files go to {@code <gameDir>/utilitysuite/bundles/}.
 * <pre>{"format":2,"source":"sp_World","settings":{...},"keybinds":{"key.utilitysuite.stop_all":"key.keyboard.backspace"}}</pre>
 * Format 1 bundles were written by the 1.21.1 builds (GLFW-era key names); their key names are migrated on read
 * ({@link com.utilitysuite.core.input.LegacyKeyCodes#migrateSaveString}).
 */
public final class ProfileBundle {

    public static final int FORMAT = 2;
    /** Last format written with GLFW-era (1.21.1) key names. */
    public static final int FORMAT_GLFW = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String source;
    private final JsonObject settings;
    private final Map<String, String> keybinds;

    public ProfileBundle(String source, JsonObject settings, Map<String, String> keybinds) {
        this.source = source == null ? "" : source;
        this.settings = settings == null ? new JsonObject() : settings.deepCopy();
        this.keybinds = new TreeMap<>(keybinds == null ? Map.of() : keybinds);
    }

    public String source() {
        return source;
    }

    /** Deep copy of the settings tree. */
    public JsonObject settings() {
        return settings.deepCopy();
    }

    public Map<String, String> keybinds() {
        return new TreeMap<>(keybinds);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("source", source);
        o.add("settings", settings.deepCopy());
        JsonObject k = new JsonObject();
        keybinds.forEach(k::addProperty);
        o.add("keybinds", k);
        return o;
    }

    /** Parses a bundle; throws IllegalArgumentException for anything that is not one. */
    public static ProfileBundle fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject o = el.getAsJsonObject();
        if (!o.has("format") || o.get("format").getAsInt() > FORMAT) {
            throw new IllegalArgumentException("unknown bundle format");
        }
        JsonObject s = o.has("settings") && o.get("settings").isJsonObject() ? o.getAsJsonObject("settings")
                : new JsonObject();
        boolean legacyKeys = o.get("format").getAsInt() <= FORMAT_GLFW;
        Map<String, String> keys = new TreeMap<>();
        if (o.has("keybinds") && o.get("keybinds").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("keybinds").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    String v = e.getValue().getAsString();
                    keys.put(e.getKey(), legacyKeys ? com.utilitysuite.core.input.LegacyKeyCodes.migrateSaveString(v) : v);
                }
            }
        }
        return new ProfileBundle(o.has("source") ? o.get("source").getAsString() : "", s, keys);
    }

    /**
     * Diff preview of importing this bundle over {@code current} settings and key bindings: settings keys the bundle
     * would change (keys the bundle lacks are kept, so they are not listed), then {@code keybind.<name>} lines.
     * Modules in {@link com.utilitysuite.ui.ProfileCopier#EXCLUDED}-style {@code excluded} ids are skipped.
     */
    public List<ConfigDiff.Change> previewOver(JsonObject current, Map<String, String> currentKeys,
                                               java.util.Set<String> excluded) {
        JsonObject filtered = new JsonObject();
        for (Map.Entry<String, JsonElement> e : settings.entrySet()) {
            if (!excluded.contains(e.getKey())) {
                filtered.add(e.getKey(), e.getValue());
            }
        }
        JsonObject cur = new JsonObject();
        for (String k : filtered.keySet()) {
            if (current.has(k)) {
                cur.add(k, current.get(k));
            }
        }
        List<ConfigDiff.Change> out = new ArrayList<>(ConfigDiff.diff(cur, filtered, false));
        for (Map.Entry<String, String> e : keybinds.entrySet()) {
            String old = currentKeys.get(e.getKey());
            if (!e.getValue().equals(old)) {
                out.add(new ConfigDiff.Change("keybind." + e.getKey(), old, e.getValue()));
            }
        }
        return out;
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static ProfileBundle read(Path file) throws IOException {
        try {
            return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IOException("Not a profile bundle: " + file.getFileName() + " (" + e.getMessage() + ")", e);
        }
    }

    /** Safe file name for a bundle: letters, digits, '-', '_' and '.' kept, the rest '_'; ".json" appended. */
    public static String fileName(String name) {
        String s = name == null || name.isBlank() ? "bundle" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.endsWith(".json") ? s : s + ".json";
    }
}
