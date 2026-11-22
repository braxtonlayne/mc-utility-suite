package com.utilitysuite.combat.assistant.stages;

import com.google.gson.JsonObject;
import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.tactics.HealWindow;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The healing stage against the real assistant, arbiter and smoother. */
class HealWindowStageTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;
    static final int GAPPLE = 3;

    /** Test double for {@link HealFacts}. */
    static final class FakeHealFacts implements HealFacts {
        int hunger = 20;
        int foodSlot = -1;
        int nutrition;
        int refreshes;

        @Override
        public void refresh() {
            refreshes++;
        }

        @Override
        public int hunger() {
            return hunger;
        }

        @Override
        public float saturation() {
            return 0f;
        }

        @Override
        public int bestFoodHotbarSlot() {
            return foodSlot;
        }

        @Override
        public int bestFoodNutrition() {
            return nutrition;
        }

        @Override
        public float bestFoodSaturation() {
            return 0.6f;
        }

        @Override
        public int bestFoodEatTicks() {
            return 32;
        }

        @Override
        public int splashHealingSlot() {
            return -1;
        }

        @Override
        public int goldenAppleCount() {
            return 1;
        }
    }

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    FakeHealFacts facts;
    HealWindowStage stage;
    long[] now = {50_000L};

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.add(TARGET, "Vindicator", 3, 64, 0);
        world.self.offhandTotem = true;
        world.self.gappleSlot = GAPPLE;
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        facts = new FakeHealFacts();
        stage = new HealWindowStage(facts);
        assistant.addStage(stage);
        assistant.setEnabled(true);
        registry.register(assistant);
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            now[0] += 50;
        }
    }

    /** Engages the vindicator and lands two 5-point hits on us: 10 health left, 2 hits left (k 2.5 wants a heal). */
    void engageAndTakeTwoHits() {
        assertTrue(assistant.config().sustain, "the stage hands the eat to Emergency sustain");
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(2);
        world.self.health = 15f;
        tick(2);
        world.self.health = 10f;
        tick(1);
        if (stage.enabled()) {
            assertEquals(5.0, stage.damagePerHit(), 0.6, "measured damage per hit");
        }
    }

    @Test
    void noEatWhileTheOpponentIsInReachWithACharge() {
        engageAndTakeTwoHits();
        tick(5);
        assertEquals(HealWindow.Plan.FIGHT_FOR_KNOCKBACK, stage.lastPlan(),
                "in reach with our attack charged: fight for a knockback hit first");
        assertEquals(0, stage.eatsStarted());
        assertFalse(smoother.isKeyHeldByAutomation(SimKey.USE), "no eat in reach of a charged opponent");
    }

    @Test
    void eatStartsInTheFirstWindowAndTheWeaponComesBack() {
        engageAndTakeTwoHits();
        tick(3);
        assertEquals(0, stage.eatsStarted());
        // our sprint hit lands: the vindicator flies off
        FakeCombatWorld.E t = world.entities.get(TARGET);
        t.hurtTime = 9;
        t.vx = 0.9;
        t.onGround = false;
        world.self.usingItem = true;
        tick(1);
        assertEquals(1, stage.eatsStarted(), "the eat starts on the first window tick");
        assertTrue(smoother.isKeyHeldByAutomation(SimKey.USE), "USE held by the fight's lease");
        tick(1);
        assertEquals(GAPPLE, backend.selectedSlot, "the golden apple is in hand");
        t.hurtTime = 0;
        t.vx = 0.0;
        t.onGround = true;
        tick(20);
        assertTrue(smoother.isKeyHeldByAutomation(SimKey.USE), "the built-in layer finishes the eat");
        world.self.usingItem = false; // eaten
        world.self.health = 14f;
        world.self.absorption = 4f;
        tick(3);
        assertFalse(smoother.isKeyHeldByAutomation(SimKey.USE), "USE released after eating");
        assertEquals(0, backend.selectedSlot, "the weapon is back");
        assertEquals(1, stage.eatsStarted(), "one eat");
    }

    @Test
    void nothingHappensWithTheSettingOff() {
        ProfileConfig p = new ProfileConfig("t", new JsonObject());
        p.set(CombatAssistant.ID, HealWindowStage.HEAL_WINDOWS, false);
        stage.read(p, CombatAssistant.ID);
        engageAndTakeTwoHits();
        FakeCombatWorld.E t = world.entities.get(TARGET);
        t.hurtTime = 9;
        t.vx = 0.9;
        t.onGround = false;
        tick(1);
        assertEquals(0, stage.eatsStarted());
        assertNull(stage.lastPlan());
        assertFalse(smoother.isKeyHeldByAutomation(SimKey.USE), "10 health is above the emergency threshold");
    }

    @Test
    void theEmergencyStillEatsExactlyAsBefore() {
        engageAndTakeTwoHits();
        world.self.health = 6f; // below the low-health threshold, in reach: the emergency eats now
        world.self.usingItem = true;
        tick(2);
        assertEquals(0, stage.eatsStarted(), "the stage steps aside for the emergency");
        assertTrue(smoother.isKeyHeldByAutomation(SimKey.USE), "the built-in emergency eats as it always did");
    }

    @Test
    void topUpWhenTheOpponentIsAway() {
        assistant.config().duelMode = true; // keep the far target engaged
        facts.hunger = 12;
        facts.foodSlot = 5;
        facts.nutrition = 6;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        assertEquals(0, stage.topUps(), "in reach: no top-up");
        world.entities.get(TARGET).x = 14.0;
        world.self.usingItem = true;
        tick(1);
        assertEquals(1, stage.topUps(), "away and hungry: eat food that wastes nothing");
        assertTrue(smoother.isKeyHeldByAutomation(SimKey.USE));
    }
}
