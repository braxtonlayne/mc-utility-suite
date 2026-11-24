package com.utilitysuite.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utilitysuite.core.LegitPresets.Level;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.config.ProfileIds;

import java.util.Locale;
import java.util.Map;

/**
 * Per-server legit level: the user's own setups, e.g. RELAXED for single-player and LEGIT for their whitelisted
 * server. Read from the global profile's {@code core} section:
 * <pre>
 *   "server_levels": { "singleplayer": "RELAXED", "play.example.net": "LEGIT", "other.host:25570": "BALANCED" },
 *   "default_server_level": "BALANCED"   (optional; absent or empty: unlisted servers keep their stored profile)
 * </pre>
 * Server keys are matched the way profile ids are ({@link ProfileIds#multiplayer}): case-insensitive, and
 * {@code host} equals {@code host:25565}. Pure Java (Gson only). The level is written into the profile with
 * {@link LegitPresets#apply} right after the profile loads and before any module reads it.
 */
public final class ServerLevels {

    public static final String KEY_MAP = "server_levels";
    public static final String KEY_DEFAULT = "default_server_level";
    public static final String SINGLEPLAYER = "singleplayer";

    private ServerLevels() {
    }

    /**
     * The level for this login, or null to leave the profile as stored.
     *
     * @param serverAddress the server-list address, ignored in single player
     */
    public static Level resolve(ProfileConfig global, boolean singleplayer, String serverAddress) {
        if (global == null) {
            return null;
        }
        JsonObject core = global.root().has("core") && global.root().get("core").isJsonObject()
                ? global.root().getAsJsonObject("core") : null;
        if (core == null) {
            return null;
        }
        JsonElement map = core.get(KEY_MAP);
        if (map != null && map.isJsonObject()) {
            String wanted = singleplayer ? SINGLEPLAYER : ProfileIds.multiplayer(serverAddress);
            for (Map.Entry<String, JsonElement> e : map.getAsJsonObject().entrySet()) {
                String k = e.getKey();
                boolean isSp = SINGLEPLAYER.equalsIgnoreCase(k.trim());
                boolean hit = singleplayer ? isSp : !isSp && ProfileIds.multiplayer(k).equals(wanted);
                if (hit) {
                    Level l = parse(e.getValue());
                    if (l != null) {
                        return l;
                    }
                }
            }
        }
        return parse(core.get(KEY_DEFAULT));
    }

    static Level parse(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return Level.valueOf(e.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
