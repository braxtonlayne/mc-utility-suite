package com.utilitysuite.core.config;

import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {

    @TempDir
    Path dir;

    static final class Toggle implements IModule {
        final String id;
        boolean enabled;
        ProfileConfig loaded;

        Toggle(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ModuleCategory category() {
            return ModuleCategory.WORLD;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean e) {
            enabled = e;
        }

        @Override
        public void onTick() {
        }

        @Override
        public void onProfileLoaded(ProfileConfig profile) {
            loaded = profile;
        }
    }

    @Test
    void profileIds() {
        assertEquals("sp_New World", ProfileIds.singleplayer("New World"));
        assertEquals("mp_play.example.net_25565", ProfileIds.multiplayer("Play.Example.net"));
        assertEquals("mp_play.example.net_25570", ProfileIds.multiplayer("play.example.net:25570"));
        assertEquals("mp_::1_25566", ProfileIds.multiplayer("[::1]:25566"));
        assertEquals("mp_unknown", ProfileIds.multiplayer(null));
        assertEquals("sp_a_b_c", ProfileIds.fileName("sp_a/b\\c"));
        assertEquals("unnamed", ProfileIds.fileName("..."));
        assertEquals("_CON", ProfileIds.fileName("CON"));
        assertEquals("mp_1.2.3.4_25565", ProfileIds.fileName("mp_1.2.3.4_25565"));
        assertFalse(ProfileIds.fileName("../../etc").contains("/"));
    }

    @Test
    void roundTrip() {
        ConfigStore store = new ConfigStore(dir);
        ProfileConfig p = store.load("sp_World");
        p.set("overlays", "range", 48);
        p.set("overlays", "mode", "ores");
        store.writeAsync(p);
        store.flush();
        assertTrue(Files.isRegularFile(dir.resolve("profiles/sp_World.json")));
        ProfileConfig back = new ConfigStore(dir).load("sp_World");
        assertEquals(48, back.getInt("overlays", "range", 0));
        assertEquals("ores", back.getString("overlays", "mode", ""));
        store.close();
    }

    @Test
    void corruptFileIsBackedUpAndReplaced() throws IOException {
        Path file = dir.resolve("global.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(dir);
        ProfileConfig g = assertDoesNotThrow(() -> store.load(ProfileIds.GLOBAL));
        assertEquals("global", g.profileId());
        assertEquals(0, g.root().size());
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("global.corrupt-")));
        }
        assertEquals("{}", Files.readString(file).trim(), "defaults written in place");

        Files.writeString(file, "[1,2,3]", StandardCharsets.UTF_8); // valid JSON, wrong shape
        assertEquals(0, store.load(ProfileIds.GLOBAL).root().size());
        store.close();
    }

    @Test
    void modStateRestoresModuleStatesPerProfileAndDebouncesSaves() {
        ConfigStore store = new ConfigStore(dir);
        ModuleRegistry registry = new ModuleRegistry();
        Toggle overlays = new Toggle("overlays", false);
        Toggle scout = new Toggle("scout", true);
        registry.register(overlays);
        registry.register(scout);
        ModState state = new ModState(store, new ActionArbiter(), registry);
        state.loadGlobal();

        state.loadProfile("sp_A");
        assertSame(state.profile(), overlays.loaded);
        overlays.setEnabled(true);
        scout.setEnabled(false);
        state.setCleanScreen(true);
        state.unloadProfile();

        // A different world gets each module's registration default, not world A's state.
        state.loadProfile("sp_B");
        assertFalse(overlays.isEnabled());
        assertTrue(scout.isEnabled());
        state.unloadProfile();

        state.loadProfile("sp_A");
        assertTrue(overlays.isEnabled());
        assertFalse(scout.isEnabled());

        // Debounce: nothing written until SAVE_DELAY_TICKS after the last markDirty.
        state.profile().set("overlays", "range", 99);
        state.markDirty();
        for (int i = 0; i < ModState.SAVE_DELAY_TICKS - 1; i++) {
            state.tick();
        }
        store.flush();
        assertEquals(0, new ConfigStore(dir).load("sp_A").getInt("overlays", "range", 0));
        state.tick();
        store.flush();
        assertEquals(99, new ConfigStore(dir).load("sp_A").getInt("overlays", "range", 0));

        ModState fresh = new ModState(new ConfigStore(dir), new ActionArbiter(), new ModuleRegistry());
        fresh.loadGlobal();
        assertTrue(fresh.isCleanScreen(), "clean screen persisted in global.json");
        store.close();
    }
}
