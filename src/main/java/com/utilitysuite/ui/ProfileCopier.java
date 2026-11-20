package com.utilitysuite.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

/**
 * Copies module settings between profiles (Tools tab → Profiles). Every module object of the source replaces the one
 * in the target; objects the source does not have are kept. Suite-wide core settings ({@code core}) are never copied,
 * they belong to the global profile only. Pure Java (Gson).
 */
public final class ProfileCopier {

    /** Module objects never copied between profiles. */
    public static final Set<String> EXCLUDED = Set.of("core");

    private ProfileCopier() {
    }

    /** Returns the number of module objects copied. */
    public static int copy(JsonObject source, JsonObject target) {
        int n = 0;
        for (Map.Entry<String, JsonElement> e : source.entrySet()) {
            if (EXCLUDED.contains(e.getKey()) || !e.getValue().isJsonObject()) {
                continue;
            }
            target.add(e.getKey(), e.getValue().deepCopy());
            n++;
        }
        return n;
    }
}
