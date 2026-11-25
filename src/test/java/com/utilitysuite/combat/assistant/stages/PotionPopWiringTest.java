package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.FightStageSeamTest;
import com.utilitysuite.core.api.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The totem-pop detection (entity event 35, forwarded by the target HUD to {@code CombatAssistant.onTotemPop})
 * reaches {@link PotionStage#onOpponentPop}, so the post-pop pressure rule opens its window.
 */
class PotionPopWiringTest {

    static final int TARGET = 10;
    static final int OTHER = 20;

    FightStageSeamTest.Rig rig;
    PotionStage stage;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        rig = new FightStageSeamTest.Rig();
        stage = new PotionStage(new PlannerStagesTest.Potions());
        rig.assistant.addStage(stage);
        rig.world.add(TARGET, "Vindicator", 3, 64, 0);
        rig.world.add(OTHER, "Zombie", 8, 64, 0);
        stage.planner().pressureTicks = 60;
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    @Test
    void targetPopOpensThePressureWindow() {
        rig.engage(TARGET);
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
        long clock = rig.assistant.stageContext().clock();
        assertFalse(stage.planner().pressureActive(clock));
        rig.assistant.onTotemPop(TARGET);
        assertTrue(stage.planner().pressureActive(rig.assistant.stageContext().clock()));
    }

    @Test
    void otherEntityPopOrIdlePopIsIgnored() {
        rig.assistant.onTotemPop(TARGET); // not engaged yet
        assertFalse(stage.planner().pressureActive(rig.assistant.stageContext().clock()));
        rig.engage(TARGET);
        rig.assistant.onTotemPop(OTHER); // a bystander's totem
        assertFalse(stage.planner().pressureActive(rig.assistant.stageContext().clock()));
    }
}
