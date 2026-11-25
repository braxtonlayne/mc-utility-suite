package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.memory.HabitRecorder;
import com.utilitysuite.combat.tactics.memory.OpponentMemory;
import com.utilitysuite.combat.tactics.memory.OpponentMemoryStore;
import com.utilitysuite.combat.tactics.memory.RecalledHabits;
import com.utilitysuite.core.api.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C6 habits through the real assistant: the target's own projectiles (by owner, else by spawn at its eye) feed the
 * fight's record, the fight end persists them, the next engage recalls them (hint, pearl pre-warning, stage view),
 * and nothing happens with "Opponent memory" off.
 */
class OpponentHabitsAssistantTest {

    static final int TARGET = 10;
    static final int OTHER = 20;

    @TempDir
    Path dir;

    final List<String> hints = new ArrayList<>();
    OpponentMemoryStore store;
    FightStageSeamTest.Rig rig;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        rig = newRig();
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    FightStageSeamTest.Rig newRig() {
        FightStageSeamTest.Rig r = new FightStageSeamTest.Rig();
        r.assistant.config().opponentMemory = true;
        store = new OpponentMemoryStore(dir.resolve("opponents.json"));
        r.assistant.useOpponentMemory(store, hints::add);
        r.world.addPlayer(TARGET, "Rival", 3, 64, 0);
        r.world.addPlayer(OTHER, "Bystander", 8, 64, 4);
        return r;
    }

    /** An escape pearl (flying +x, away from us at the origin) thrown by {@code owner}, spawned at (sx, sy, sz). */
    static void pearl(FightStageSeamTest.Rig r, int owner, double sx, double sy, double sz) {
        r.assistant.onProjectileSpawn(HabitRecorder.PROJ_PEARL, owner, sx, sy, sz, 1.2, 0.4, 0.0);
    }

    @Test
    void targetProjectilesAreRecordedAndRememberedForTheNextFight() {
        rig.engage(TARGET);
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
        rig.world.entities.get(TARGET).health = 6f;
        rig.tick(2);
        pearl(rig, TARGET, 3, 65.6, 0);      // owner synced: the target
        pearl(rig, OTHER, 8, 65.6, 4);       // someone else's pearl
        pearl(rig, -1, 3.1, 65.7, 0.1);      // owner unsynced, spawned at the target's eye
        pearl(rig, -1, 8, 65.6, 4);          // owner unsynced, spawned elsewhere
        assertEquals(2, rig.assistant.habitRecord().pearls());
        assertEquals(6.0, rig.assistant.habitRecord().pearlOutHp(), 1e-6);

        rig.world.entities.get(TARGET).alive = false; // we won: the fight ends and is remembered
        rig.tick(2);
        assertNotEquals(CombatState.ENGAGED, rig.assistant.state());
        assertEquals(0, rig.assistant.habitRecord().pearls(), "the record is reset after the fight");
        store.flush();

        // a new session reads the file
        hints.clear();
        Services.resetForTests();
        FightStageSeamTest.Rig later = newRig();
        later.world.entities.get(TARGET).health = 20f;
        later.engage(TARGET);
        assertEquals(1, hints.size(), hints.toString());
        assertTrue(hints.get(0).contains("pearls out near 6 HP"), hints.get(0));
        RecalledHabits r = later.assistant.stageContext().recalledHabits();
        assertTrue(r.known());
        assertTrue(r.pearlEscaper());
        assertFalse(later.assistant.opponentPearlLikely()); // full health: no pre-warning yet
        later.world.entities.get(TARGET).health = 7f;
        later.tick(1);
        assertTrue(later.assistant.opponentPearlLikely()); // near its usual pearl-out health
        later.assistant.config().opponentMemory = false;
        later.tick(1);
        assertFalse(later.assistant.opponentPearlLikely());
        assertSame(RecalledHabits.NONE, later.assistant.stageContext().recalledHabits());

        OpponentMemory.Profile p = new OpponentMemoryStore(dir.resolve("opponents.json")).load()
                .lookup("", later.world.uuidOf(TARGET).toString());
        assertNotNull(p);
        assertEquals(1.0, p.pearlEscapeShare, 1e-9);
        assertEquals(1.0, p.pearlLowHpShare, 1e-9);
    }

    @Test
    void offRecordsNothing() {
        rig.assistant.config().opponentMemory = false;
        rig.engage(TARGET);
        pearl(rig, TARGET, 3, 65.6, 0);
        assertEquals(0, rig.assistant.habitRecord().pearls());
        assertFalse(rig.assistant.opponentPearlLikely());
        assertSame(RecalledHabits.NONE, rig.assistant.stageContext().recalledHabits());
    }
}
