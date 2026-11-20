package com.utilitysuite.core.api;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * Per-world (save folder name) or per-server (address) settings. Each module owns one JSON object keyed by its id.
 * Not thread-safe: client thread only. Persisted by the config store.
 */
public final class ProfileConfig {

    private final String profileId;
    private final JsonObject root;

    public ProfileConfig(String profileId, JsonObject root) {
        this.profileId = Objects.requireNonNull(profileId);
        this.root = root == null ? new JsonObject() : root;
    }

    /** e.g. "sp_New World" or "mp_play.example.net_25565"; "global" for the global profile. */
    public String profileId() {
        return profileId;
    }

    /** The module's settings object, created on first use. Mutations are saved by the config store. */
    public JsonObject module(String moduleId) {
        JsonObject o = root.getAsJsonObject(moduleId);
        if (o == null) {
            o = new JsonObject();
            root.add(moduleId, o);
        }
        return o;
    }

    public boolean getBool(String moduleId, String key, boolean def) {
        JsonObject o = root.getAsJsonObject(moduleId);
        return o != null && o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    public int getInt(String moduleId, String key, int def) {
        JsonObject o = root.getAsJsonObject(moduleId);
        return o != null && o.has(key) ? o.get(key).getAsInt() : def;
    }

    public double getDouble(String moduleId, String key, double def) {
        JsonObject o = root.getAsJsonObject(moduleId);
        return o != null && o.has(key) ? o.get(key).getAsDouble() : def;
    }

    public String getString(String moduleId, String key, String def) {
        JsonObject o = root.getAsJsonObject(moduleId);
        return o != null && o.has(key) ? o.get(key).getAsString() : def;
    }

    public void set(String moduleId, String key, boolean value) {
        module(moduleId).addProperty(key, value);
    }

    public void set(String moduleId, String key, Number value) {
        module(moduleId).addProperty(key, value);
    }

    public void set(String moduleId, String key, String value) {
        module(moduleId).addProperty(key, value);
    }

    /** The backing JSON (for the config store). */
    public JsonObject root() {
        return root;
    }
}
