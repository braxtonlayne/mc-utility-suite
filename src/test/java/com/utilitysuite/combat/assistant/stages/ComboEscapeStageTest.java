package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.FightStageSeamTest;
import com.utilitysuite.combat.tactics.ComboEscape;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ComboEscapeStage} against the real Combat Assistant. */
class ComboEscapeStageTest {

    static final int LOCAL = 1;
    static final int OPP = 10;

    FightStageSeamTest.Rig rig;
    ComboEscapeStage stage;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        rig = new FightStageSeamTest.Rig();
        stage = new ComboEscapeStage();
        rig.assistant.addStage(stage);
        rig.assistant.config().tacticUtility = false; // keep the fight on the melee path while health drops
        rig.world.self.attackStrength = 0.2f; // charging: no swing of ours lands by itself
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    void engagePlayer() {
        rig.world.addPlayer(OPP, "Duelist", 3.0, 64, 0);
        rig.assistant.onDamageEvent(LOCAL, "minecraft:player_attack", OPP, OPP);
        rig.tick(3);
    }

    /** The opponent's hit: its damage event, then the health drop on the next tick. */
    void takeHit() {
        rig.assistant.onDamageEvent(LOCAL, "minecraft:player_attack", OPP, OPP);
        rig.world.self.health -= 2f;
        rig.tick(1);
    }

    boolean strafing() {
        return rig.down(SimKey.LEFT) || rig.down(SimKey.RIGHT);
    }

    @Test
    void twoUnansweredHitsStartAStrafeOutOfTheLine() {
        engagePlayer();
        takeHit();
        rig.tick(3);
        assertFalse(stage.escaping(), "one hit is a trade");
        takeHit();
        assertTrue(stage.escaping(), "two without answer: escape");
        assertTrue(strafing(), "perpendicular to the knockback line");
        assertFalse(rig.down(SimKey.BACK), "never a straight backpedal");
        assertFalse(rig.down(SimKey.SPRINT));
        for (int i = 0; i < 5; i++) {
            rig.tick(1);
            assertTrue(stage.escaping());
            assertFalse(rig.down(SimKey.BACK));
        }
    }

    @Test
    void handsBackWhenOurHitLands() {
        engagePlayer();
        takeHit();
        takeHit();
        assertTrue(stage.escaping());
        rig.world.entities.get(OPP).health -= 4f; // our hit lands
        rig.tick(1);
        assertFalse(stage.escaping(), "the combo is broken: built-in movement again");
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
    }

    @Test
    void handsBackWhenTheWindowEnds() {
        engagePlayer();
        takeHit();
        takeHit();
        rig.tick(ComboEscape.WINDOW_TICKS + 2);
        assertFalse(stage.escaping());
        assertEquals(ComboEscape.WINDOW_TICKS, stage.drivenTicks());
    }

    @Test
    void pinnedAgainstAWallSlidesToTheOpenSide() {
        rig.world.self.hazardBack = true;
        rig.world.self.hazardLeft = true;
        engagePlayer();
        takeHit();
        takeHit();
        assertTrue(stage.escaping());
        assertTrue(stage.escape().pinned());
        assertTrue(rig.down(SimKey.RIGHT), "slide along the wall to the open side");
        assertFalse(rig.down(SimKey.LEFT) || rig.down(SimKey.BACK));
    }

    @Test
    void mobsAndTheSettingOffNeverEscape() {
        rig.world.add(OPP, "Zombie", 3.0, 64, 0);
        rig.assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", OPP, OPP);
        rig.tick(3);
        for (int i = 0; i < 3; i++) {
            rig.assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", OPP, OPP);
            rig.world.self.health -= 2f;
            rig.tick(1);
        }
        assertFalse(stage.escaping(), "players only");

        Services.resetForTests();
        rig = new FightStageSeamTest.Rig();
        stage = new ComboEscapeStage();
        stage.setEnabled(false);
        rig.assistant.addStage(stage);
        rig.assistant.config().tacticUtility = false;
        rig.world.self.attackStrength = 0.2f;
        engagePlayer();
        takeHit();
        takeHit();
        assertFalse(stage.escaping(), "setting off");
    }
}
