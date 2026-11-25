package com.utilitysuite.world.seed;

import com.google.gson.JsonObject;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.world.knowledge.KnowledgeLedger;
import com.utilitysuite.world.knowledge.WorldKnowledgeService;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.overlays.WorldScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedScannerModuleTest {

    private ProfileConfig profile;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        profile = new ProfileConfig("scanner-test", new JsonObject());
        ProfileConfig global = new ProfileConfig("global", new JsonObject());
        Services.registerModState(new IModState() {
            @Override public boolean isCleanScreen() { return false; }
            @Override public void setCleanScreen(boolean active) { }
            @Override public boolean isAutomationStopped() { return false; }
            @Override public ProfileConfig profile() { return profile; }
            @Override public ProfileConfig global() { return global; }
            @Override public void markDirty() { }
        });
        Services.registerModules(new ModuleRegistry());
    }

    @Test
    void tracksManualCluesAndResetsCleanly() {
        SeedScannerModule scanner = new SeedScannerModule(GeneratorProfile::none, s -> { });
        scanner.onProfileLoaded(profile);
        assertEquals(0, scanner.clueCount());

        scanner.addManualStructure("desert_pyramid", 1, 2);
        scanner.addManualStructure("desert_pyramid", 1, 2); // deduplicated
        scanner.addManualStructure("unknown_struct", 3, 4); // ignored
        assertEquals(1, scanner.clueCount());
        assertTrue(scanner.clueSummary().startsWith("1 struct"));

        scanner.resetClues();
        assertEquals(0, scanner.clueCount());
        assertFalse(scanner.isSolving());
    }

    @Test
    void scannedSeedIsStoredAndReadInAutoProfile() {
        WorldKnowledgeService knowledge = new WorldKnowledgeService(new KnowledgeLedger(), e -> { }, () -> 0L);
        OreSimModule oreSim = new OreSimModule(knowledge, new WorldScanner());
        oreSim.onProfileLoaded(profile);

        long expectedSeed = 9876543210123L;
        oreSim.onScannedSeed(expectedSeed);
        assertEquals(Long.toString(expectedSeed), profile.getString(OreSimModule.ID, "scanned_seed", ""));

        OreSimModule reloaded = new OreSimModule(knowledge, new WorldScanner());
        reloaded.onProfileLoaded(profile);
        assertEquals(Long.toString(expectedSeed), profile.getString(OreSimModule.ID, "scanned_seed", ""));
    }

    @Test
    void handlesDotCommandsForStatusAddRescanAndReset() {
        SeedScannerModule scanner = new SeedScannerModule(GeneratorProfile::none, s -> { });
        scanner.onProfileLoaded(profile);

        String statusReply = scanner.handleDotCommand(new String[] {"status"});
        assertTrue(statusReply.contains("0 struct"));

        String addReply = scanner.handleDotCommand(new String[] {"add", "desert_pyramid", "5", "-3"});
        assertTrue(addReply.contains("Added desert_pyramid"));
        assertEquals(1, scanner.clueCount());

        String badAdd = scanner.handleDotCommand(new String[] {"add", "desert_pyramid", "abc", "0"});
        assertTrue(badAdd.startsWith("Usage:"));

        String rescanReply = scanner.handleDotCommand(new String[] {"rescan"});
        assertTrue(rescanReply.contains("Rescanning"));

        String resetReply = scanner.handleDotCommand(new String[] {"reset"});
        assertTrue(resetReply.contains("cleared"));
        assertEquals(0, scanner.clueCount());
    }
}
