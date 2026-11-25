package com.utilitysuite.world.trial;

import org.junit.jupiter.api.Test;

import static com.utilitysuite.world.trial.TrialBlocks.KIND_NONE;
import static com.utilitysuite.world.trial.TrialBlocks.KIND_SPAWNER;
import static com.utilitysuite.world.trial.TrialBlocks.KIND_VAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialBlocksTest {

    @Test
    void kindsFromIds() {
        assertEquals(KIND_VAULT, TrialBlocks.kindOf("minecraft:vault"));
        assertEquals(KIND_SPAWNER, TrialBlocks.kindOf("minecraft:trial_spawner"));
        assertEquals(KIND_NONE, TrialBlocks.kindOf("minecraft:spawner"));
        assertEquals(0, TrialBlocks.classify(KIND_NONE, "active", true, false));
    }

    @Test
    void vaultStates() {
        int active = TrialBlocks.classify(KIND_VAULT, "active", false, false);
        assertEquals(KIND_VAULT, TrialBlocks.kind(active));
        assertEquals(TrialBlocks.STATUS_ACTIVE, TrialBlocks.status(active));
        assertFalse(TrialBlocks.ominous(active));
        assertEquals(TrialBlocks.STATUS_REWARD,
                TrialBlocks.status(TrialBlocks.classify(KIND_VAULT, "unlocking", false, false)));
        assertEquals(TrialBlocks.STATUS_REWARD,
                TrialBlocks.status(TrialBlocks.classify(KIND_VAULT, "ejecting", true, false)));
        assertEquals(TrialBlocks.STATUS_INACTIVE,
                TrialBlocks.status(TrialBlocks.classify(KIND_VAULT, "inactive", false, false)));
    }

    @Test
    void lootedVaultOverridesIdleAndActiveButNotTheRewardAnimation() {
        assertEquals(TrialBlocks.STATUS_LOOTED,
                TrialBlocks.status(TrialBlocks.classify(KIND_VAULT, "inactive", true, true)));
        assertEquals(TrialBlocks.STATUS_LOOTED,
                TrialBlocks.status(TrialBlocks.classify(KIND_VAULT, "active", false, true)));
        assertEquals(TrialBlocks.STATUS_REWARD,
                TrialBlocks.status(TrialBlocks.classify(KIND_VAULT, "ejecting", false, true)));
        // Spawners are never "looted".
        assertEquals(TrialBlocks.STATUS_INACTIVE,
                TrialBlocks.status(TrialBlocks.classify(KIND_SPAWNER, "inactive", false, true)));
    }

    @Test
    void spawnerStates() {
        assertEquals(TrialBlocks.STATUS_ACTIVE,
                TrialBlocks.status(TrialBlocks.classify(KIND_SPAWNER, "waiting_for_players", false, false)));
        assertEquals(TrialBlocks.STATUS_ACTIVE,
                TrialBlocks.status(TrialBlocks.classify(KIND_SPAWNER, "active", true, false)));
        assertEquals(TrialBlocks.STATUS_REWARD,
                TrialBlocks.status(TrialBlocks.classify(KIND_SPAWNER, "waiting_for_reward_ejection", false, false)));
        assertEquals(TrialBlocks.STATUS_REWARD,
                TrialBlocks.status(TrialBlocks.classify(KIND_SPAWNER, "ejecting_reward", false, false)));
        int cool = TrialBlocks.classify(KIND_SPAWNER, "cooldown", true, false);
        assertEquals(TrialBlocks.STATUS_COOLDOWN, TrialBlocks.status(cool));
        assertTrue(TrialBlocks.ominous(cool));
        assertEquals("Ominous spawner: cooldown", TrialBlocks.label(cool));
    }

    @Test
    void ominousAndNormalLookDifferent() {
        int normal = TrialBlocks.classify(KIND_VAULT, "active", false, false);
        int ominous = TrialBlocks.classify(KIND_VAULT, "active", true, false);
        assertNotEquals(TrialBlocks.color(normal), TrialBlocks.color(ominous));
        assertEquals("Vault: open", TrialBlocks.label(normal));
        assertEquals("Ominous vault: open", TrialBlocks.label(ominous));
        assertEquals("Vault: looted", TrialBlocks.label(TrialBlocks.classify(KIND_VAULT, "inactive", false, true)));
    }

    @Test
    void lootedInference() {
        assertTrue(TrialBlocks.inferLooted("inactive", 2.0 * 2.0, false));
        assertFalse(TrialBlocks.inferLooted("active", 2.0 * 2.0, false)); // still openable
        assertFalse(TrialBlocks.inferLooted("inactive", 2.0 * 2.0, true)); // someone else nearby
        assertFalse(TrialBlocks.inferLooted("inactive", 5.0 * 5.0, false)); // outside activation range
        assertFalse(TrialBlocks.inferLooted("unlocking", 1.0, false));
    }
}
