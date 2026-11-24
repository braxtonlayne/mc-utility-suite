package com.utilitysuite.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.LegitPresets.Level;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerLevelsTest {

    private static ProfileConfig global(String serversJson, String def) {
        ProfileConfig g = new ProfileConfig("global", new JsonObject());
        if (serversJson != null) {
            g.module("core").add(ServerLevels.KEY_MAP, JsonParser.parseString(serversJson));
        }
        if (def != null) {
            g.set("core", ServerLevels.KEY_DEFAULT, def);
        }
        return g;
    }

    @Test
    void mapMatchesSingleplayerAndServersLikeProfileIds() {
        ProfileConfig g = global("{\"singleplayer\":\"relaxed\",\"Play.Example.NET\":\"LEGIT\","
                + "\"other.host:25570\":\"BALANCED\"}", null);
        assertEquals(Level.RELAXED, ServerLevels.resolve(g, true, null));
        assertEquals(Level.LEGIT, ServerLevels.resolve(g, false, "play.example.net"));
        assertEquals(Level.LEGIT, ServerLevels.resolve(g, false, "PLAY.example.net:25565"));
        assertEquals(Level.BALANCED, ServerLevels.resolve(g, false, "other.host:25570"));
        assertNull(ServerLevels.resolve(g, false, "other.host")); // different port
        assertNull(ServerLevels.resolve(g, false, "unknown.net"));
    }

    @Test
    void unknownServersFallBackToTheDefaultOrKeepTheProfile() {
        assertNull(ServerLevels.resolve(global("{}", null), false, "x.net"));
        assertEquals(Level.BALANCED, ServerLevels.resolve(global("{}", "balanced"), false, "x.net"));
        assertNull(ServerLevels.resolve(global("{}", ""), false, "x.net"));
        assertNull(ServerLevels.resolve(global("{\"x.net\":\"bogus\"}", "nonsense"), false, "x.net"));
        assertNull(ServerLevels.resolve(new ProfileConfig("global", new JsonObject()), true, null));
        assertNull(ServerLevels.resolve(null, true, null));
    }

    @Test
    void aServerNamedSingleplayerNeverMatchesTheSingleplayerKey() {
        ProfileConfig g = global("{\"singleplayer\":\"LEGIT\"}", null);
        assertNull(ServerLevels.resolve(g, false, "singleplayer"));
    }

    @Test
    void appliedLevelWritesTheKnobsIntoTheLoadedProfile() {
        ProfileConfig g = global("{\"play.net\":\"LEGIT\"}", null);
        ProfileConfig profile = new ProfileConfig("mp_play.net_25565", new JsonObject());
        LegitPresets.apply(ServerLevels.resolve(g, false, "play.net"), profile, g);
        assertEquals(Level.LEGIT, LegitPresets.current(profile, g));
    }
}
