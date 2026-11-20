package com.utilitysuite.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.combat.allies.AllyEntry;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.allies.AllyRosterModule;
import com.utilitysuite.combat.allies.AllyRosterStore;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AllyRosterTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void defaultsAndToggles() {
        AllyRoster r = new AllyRoster();
        AtomicInteger changes = new AtomicInteger();
        r.setChangeListener(changes::incrementAndGet);
        assertTrue(r.toggle(A, "Alice"));
        assertTrue(r.isNeverTarget(A), "neverTarget defaults to true");
        assertFalse(r.isDefended(A), "defend is opt-in");
        assertTrue(r.setDefend(A, true));
        assertTrue(r.isDefended(A));
        assertFalse(r.toggle(A, "Alice"), "second toggle removes");
        assertFalse(r.contains(A));
        assertFalse(r.isNeverTarget(null));
        assertFalse(r.setDefend(B, true), "unknown player");
        assertEquals(3, changes.get(), "add, defend, remove");
    }

    @Test
    void worldEntryOverridesGlobal() {
        AllyRoster r = new AllyRoster();
        r.add(A, "Alice", true);
        r.setDefend(A, true);
        assertTrue(r.isDefended(A));
        r.add(A, "Alice", false); // world entry with defaults
        assertFalse(r.isDefended(A));
        assertEquals(1, r.entries().size());
        r.remove(A);
        assertFalse(r.contains(A), "remove clears both lists");
    }

    @Test
    void persistenceRoundTrip(@TempDir Path dir) throws Exception {
        AllyRosterStore store = new AllyRosterStore(dir);
        AllyRosterModule module = new AllyRosterModule(store);
        module.onProfileLoaded(new ProfileConfig("mp_play.example.net_25565", null));
        AllyRoster r = module.roster();
        r.add(A, "Alice", false);
        r.add(B, "Bob", true);
        r.setDefend(B, true);
        r.add(C, "Carol", false);
        r.setNeverTarget(C, false);
        store.flush();

        Path worldFile = store.fileFor("mp_play.example.net_25565");
        assertTrue(Files.isRegularFile(worldFile), "world list written: " + worldFile);
        assertTrue(Files.isRegularFile(store.globalFile()));
        assertFalse(worldFile.getFileName().toString().contains(":"));

        AllyRosterModule reloaded = new AllyRosterModule(new AllyRosterStore(dir));
        reloaded.onProfileLoaded(new ProfileConfig("mp_play.example.net_25565", null));
        AllyRoster r2 = reloaded.roster();
        assertEquals(new AllyEntry(A, "Alice", true, false, false), r2.get(A));
        assertEquals(new AllyEntry(B, "Bob", true, true, true), r2.get(B));
        assertEquals(new AllyEntry(C, "Carol", false, false, false), r2.get(C));

        // Another world sees only the global list.
        reloaded.onProfileLoaded(new ProfileConfig("sp_Other World", null));
        assertNull(r2.get(A));
        assertTrue(r2.isDefended(B));
        store.close();
    }

    @Test
    void malformedEntriesAreSkipped() {
        JsonObject root = JsonParser.parseString("""
                {"allies":[{"uuid":"not-a-uuid"},{"name":"no uuid"},
                 {"uuid":"00000000-0000-0000-0000-00000000000a","name":"Alice"}]}""").getAsJsonObject();
        List<AllyEntry> list = AllyRoster.fromJson(root, false);
        assertEquals(1, list.size());
        assertTrue(list.get(0).neverTarget(), "missing neverTarget means true (safe default)");
        assertFalse(list.get(0).defend());
        assertTrue(AllyRoster.fromJson(null, false).isEmpty());
    }

    @Test
    void reservedGlobalNameDoesNotCollide(@TempDir Path dir) {
        AllyRosterStore store = new AllyRosterStore(dir);
        assertNotEquals(store.globalFile(), store.fileFor("global"));
        store.close();
    }
}
