package com.utilitysuite.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.utilitysuite.core.actions.SuiteActionRegistry;
import com.utilitysuite.core.api.SuiteAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBundleTest {

    private static JsonObject profile(double reach, boolean minimap) {
        JsonObject root = new JsonObject();
        JsonObject combat = new JsonObject();
        combat.addProperty("reach", reach);
        root.add("combat_assistant", combat);
        JsonObject map = new JsonObject();
        map.addProperty("enabled", minimap);
        root.add("minimap", map);
        JsonObject rules = new JsonObject();
        JsonArray list = new JsonArray();
        list.add("if hunger<6 then eat");
        rules.add("rules", list);
        root.add("local_rules", rules);
        return root;
    }

    @Test
    void diffListsChangedAddedAndRemovedKeys() {
        JsonObject a = profile(3.0, true);
        JsonObject b = profile(3.5, true);
        b.getAsJsonObject("minimap").addProperty("zoom", 2);
        a.getAsJsonObject("local_rules").getAsJsonArray("rules").add("x");
        List<ConfigDiff.Change> d = ConfigDiff.diff(a, b, true);
        assertEquals(3, d.size());
        assertEquals("combat_assistant.reach: 3.0 -> 3.5", ConfigDiff.describe(d.get(0)));
        assertEquals("local_rules.rules", d.get(1).key());
        assertTrue(d.get(2).added());
        assertTrue(ConfigDiff.diff(a, a, true).isEmpty());
    }

    @Test
    void roundTripsThroughAFileAndPreviewsOnlyRealChanges(@TempDir Path dir) throws Exception {
        JsonObject src = profile(4.0, false);
        src.add("core", new JsonObject());
        src.getAsJsonObject("core").addProperty("secret", 1);
        ProfileBundle b = new ProfileBundle("sp_World", src,
                Map.of("key.utilitysuite.stop_all", "key.keyboard.backspace"));
        Path f = dir.resolve("utilitysuite/bundles").resolve(ProfileBundle.fileName("my pvp/kit"));
        b.write(f);
        assertEquals("my_pvp_kit.json", f.getFileName().toString());
        ProfileBundle r = ProfileBundle.read(f);
        assertEquals("sp_World", r.source());
        assertEquals(src, r.settings());

        JsonObject current = profile(3.0, false);
        List<ConfigDiff.Change> p = r.previewOver(current,
                Map.of("key.utilitysuite.stop_all", "key.keyboard.delete"), Set.of("core"));
        assertEquals(2, p.size());
        assertEquals("combat_assistant.reach", p.get(0).key());
        assertEquals("keybind.key.utilitysuite.stop_all", p.get(1).key());
        assertEquals("key.keyboard.delete", p.get(1).oldValue());
    }

    @Test
    void rejectsNonBundles() {
        assertThrows(IllegalArgumentException.class, () -> ProfileBundle.fromJson(new JsonPrimitive(3)));
        assertThrows(IllegalArgumentException.class, () -> ProfileBundle.fromJson(new JsonObject()));
    }

    @Test
    void undoStackKeepsTenStepsAndRestoresOldValues() {
        ConfigUndoStack s = new ConfigUndoStack(10);
        assertFalse(s.record("p", "m", "k", new JsonPrimitive(1), new JsonPrimitive(1)));
        for (int i = 0; i < 12; i++) {
            assertTrue(s.record("p", "m", "k", i == 0 ? null : new JsonPrimitive(i), new JsonPrimitive(i + 1)));
        }
        assertEquals(10, s.size());
        JsonObject root = new JsonObject();
        ConfigUndoStack.Entry e = s.pop();
        assertEquals(new JsonPrimitive(11), e.before());
        ConfigUndoStack.restore(e, root);
        assertEquals(11, root.getAsJsonObject("m").get("k").getAsInt());
        int popped = 1;
        while (s.pop() != null) {
            popped++;
        }
        assertEquals(10, popped); // the two oldest dropped off the ring
        assertNull(s.peek());
        s.record("p", "m", "k", null, new JsonPrimitive(true));
        ConfigUndoStack.restore(s.pop(), root);
        assertFalse(root.getAsJsonObject("m").has("k"));
    }

    @Test
    void actionRegistryRunsByIdAndKeepsOrder() {
        SuiteActionRegistry r = new SuiteActionRegistry();
        int[] n = {0};
        r.register(new SuiteAction("a", "A", "", () -> n[0]++));
        r.register(new SuiteAction("b", "B", "", () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(r.run("a"));
        assertFalse(r.run("b"));
        assertFalse(r.run("missing"));
        assertEquals(1, n[0]);
        assertEquals(List.of("a", "b"), r.all().stream().map(SuiteAction::id).toList());
    }
}
