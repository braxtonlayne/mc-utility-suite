package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.memory.OpponentMemory;
import com.utilitysuite.combat.tactics.memory.OpponentMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpponentRecallTest {

    @TempDir
    Path dir;

    @Test
    void fightEndIsRememberedAndTheNextEngageGetsAHintAndPrior() {
        FakeCombatWorld world = new FakeCombatWorld();
        world.addPlayer(7, "Rival", 3, 0, 0);
        OpponentModel model = new OpponentModel();
        for (int t = 0; t < 30; t++) {
            // strafing to one side while closing in
            model.observe(7, t, 3, 0, 0, 0.2, 0, 0, true, t % 2 == 0);
        }
        Path file = dir.resolve("opponents.json");
        OpponentMemoryStore store = new OpponentMemoryStore(file);
        List<String> hints = new ArrayList<>();
        OpponentRecall recall = new OpponentRecall(world);
        recall.useStore(store, hints::add);

        assertTrue(Double.isNaN(recall.onEngage(7, true)), "unknown the first time");
        world.entities.get(7).alive = false; // we won
        recall.onFightEnd(7, true, model, 0.8, 20, 1000L);
        store.flush();
        assertTrue(Files.isRegularFile(file));

        // a new session: fresh store from the same file
        OpponentRecall later = new OpponentRecall(world);
        later.useStore(new OpponentMemoryStore(file), hints::add);
        double prior = later.onEngage(7, true);
        assertEquals(OpponentMemory.priorSkill(fakeProfile(0.8), 0.5), prior, 1e-6);
        assertEquals(1, hints.size());
        assertTrue(hints.get(0).startsWith("Rival: 1-0"), hints.get(0));
    }

    @Test
    void offOrMobsDoNothing() {
        FakeCombatWorld world = new FakeCombatWorld();
        world.add(3, "Zombie", 1, 0, 0);
        world.addPlayer(4, "P", 1, 0, 0);
        OpponentRecall recall = new OpponentRecall(world);
        recall.useStore(new OpponentMemoryStore(dir.resolve("x.json")), s -> fail("no hint"));
        assertTrue(Double.isNaN(recall.onEngage(3, true)));
        assertTrue(Double.isNaN(recall.onEngage(4, false)));
    }

    private static OpponentMemory.Profile fakeProfile(double skill) {
        OpponentMemory.Profile p = new OpponentMemory.Profile();
        p.skill = skill;
        p.fights = 1;
        return p;
    }
}
