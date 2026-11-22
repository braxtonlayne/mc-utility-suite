package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.tactics.TacticUtility;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Never disengage (duel mode)": FLEE becomes a reset, range and sight never end the fight. */
class DuelModeTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;

    @Test
    void duelModeNeverReturnsFlee() {
        TacticUtility u = new TacticUtility();
        u.duelMode = true;
        int resets = 0;
        for (double own = 1; own <= 20; own += 1) {
            for (double oppDps = 1; oppDps <= 20; oppDps += 3) {
                for (int heal = 0; heal <= 1; heal++) {
                    u.reset();
                    u.ownHealth = own;
                    u.oppDps = oppDps;
                    u.ownDps = 2;
                    u.healingItems = heal;
                    for (int t = 0; t < 30; t++) {
                        TacticUtility.Tactic c = u.choose();
                        assertNotEquals(TacticUtility.Tactic.FLEE, c);
                        if (c == TacticUtility.Tactic.RETREAT_HEAL) {
                            resets++;
                        }
                    }
                }
            }
        }
        assertTrue(resets > 0, "losing badly with nothing to heal becomes a reset, not a flight");

        TacticUtility plain = new TacticUtility();
        plain.ownHealth = 2;
        plain.oppDps = 15;
        plain.ownDps = 1;
        plain.healingItems = 0;
        TacticUtility.Tactic c = null;
        for (int t = 0; t < 30; t++) {
            c = plain.choose();
        }
        assertEquals(TacticUtility.Tactic.FLEE, c, "without duel mode the same fight flees");
    }

    @Test
    void duelModeWithNoWayToHealFightsOnInsteadOfResetting() {
        // arena finding 2026-11-22: out-geared, 4 HP, no food to regenerate and no healing items. A reset can never
        // heal, so FIGHT never won again and the client kited for the rest of the round.
        TacticUtility u = new TacticUtility();
        u.duelMode = true;
        u.canRegenerate = false;
        u.healingItems = 0;
        u.ownHealth = 4;
        u.oppDps = 15;
        u.ownDps = 1;
        for (int t = 0; t < 60; t++) {
            TacticUtility.Tactic c = u.choose();
            assertNotEquals(TacticUtility.Tactic.FLEE, c);
            assertNotEquals(TacticUtility.Tactic.RETREAT_HEAL, c, "a reset that cannot heal is not a reset");
        }
    }

    @Test
    void aResetEndsWhenHealingStopsBeingPossible() {
        TacticUtility u = new TacticUtility();
        u.duelMode = true;
        u.ownHealth = 4;
        u.oppDps = 15;
        u.ownDps = 1;
        for (int t = 0; t < 30; t++) {
            u.choose();
        }
        assertEquals(TacticUtility.Tactic.RETREAT_HEAL, u.current(), "food to regenerate: a reset");
        u.canRegenerate = false; // hunger dropped below 18 mid-reset
        TacticUtility.Tactic c = null;
        for (int t = 0; t < 30; t++) {
            c = u.choose();
        }
        assertNotEquals(TacticUtility.Tactic.RETREAT_HEAL, c);
    }

    @Test
    void aRegenerationResetOnlyPaysWhileWeMissClearlyMoreHealthThanTheOpponent() {
        assertTrue(TacticUtility.regenResetPays(14, 0), "they are full, we are not: all of it is ours");
        assertTrue(TacticUtility.regenResetPays(14, 4));
        assertFalse(TacticUtility.regenResetPays(14, 13), "both hurt alike: the reset gains nothing");
        assertFalse(TacticUtility.regenResetPays(12, 16), "a nearly dead opponent is finished, not let go");
        assertFalse(TacticUtility.regenResetPays(0, 0));
    }

    @Test
    void duelModeSwitchedOnMidFlightTurnsTheFlightIntoAReset() {
        TacticUtility u = new TacticUtility();
        u.ownHealth = 2;
        u.oppDps = 15;
        u.ownDps = 1;
        for (int t = 0; t < 30; t++) {
            u.choose();
        }
        assertEquals(TacticUtility.Tactic.FLEE, u.current());
        u.duelMode = true;
        assertEquals(TacticUtility.Tactic.RETREAT_HEAL, u.choose());
    }

    @Test
    void gearRatioMovesTheAdvantageBeforeAnyDamageIsSeen() {
        TacticUtility u = new TacticUtility();
        double even = u.advantage();
        u.gearRatio = 1.0;
        assertEquals(even, u.advantage(), 1e-9, "an even gear read leaves the even fight even");
        u.gearRatio = 3.0;
        assertTrue(u.advantage() < even - 0.1, "a stronger kit reads as losing from the first tick");
    }

    // ---- end to end: range and sight ------------------------------------------------------------------------------

    FakeCombatWorld world;
    ActionArbiter arbiter;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    long[] now = {50_000L};
    FakeInputBackend backend;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.add(TARGET, "Vindicator", 3, 64, 0);
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
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

    @Test
    void duelModeKeepsAFarOrHiddenTargetWhileItIsLoaded() {
        assistant.config().duelMode = true;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        world.entities.get(TARGET).x = 40.0; // far past the 16-block disengage radius
        world.lineOfSight = false;
        tick(assistant.config().losTimeoutTicks + 20);
        assertEquals(CombatState.ENGAGED, assistant.state(), "duel mode: range and sight never end the fight");

        world.entities.get(TARGET).alive = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state(), "a dead or unloaded target still ends it");
    }

    // ---- end to end: low health ------------------------------------------------------------------------------------

    /** A duel against the target at 2.6 blocks with our health at 6 (under the 8 HP sustain threshold). */
    void lowHealthDuel(boolean duel, int food, float saturation, float targetHealth) {
        world.entities.get(TARGET).x = 2.6;
        world.entities.get(TARGET).player = true;
        world.entities.get(TARGET).health = targetHealth;
        assistant.config().duelMode = duel;
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", TARGET, TARGET);
        tick(1);
        world.self.health = 6f;
        world.self.foodLevel = food;
        world.self.saturation = saturation;
    }

    @Test
    void lowHealthInADuelWithNoWayToHealKeepsSwinging() {
        // arena finding 2026-11-22: at 8 HP and food 17 the emergency retreat held S in a corner at full charge and
        // never swung again; the round timed out
        lowHealthDuel(true, 16, 0f, 20f);
        int before = assistant.swings();
        tick(40);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertNotEquals(TacticUtility.Tactic.RETREAT_HEAL, assistant.broadTactic());
        assertTrue(assistant.swings() > before, "the fight goes on: swings at low health");
    }

    @Test
    void lowHealthOutsideDuelModeStillBacksOff() {
        lowHealthDuel(false, 16, 0f, 20f);
        assistant.config().tacticUtility = false; // the emergency retreat alone
        int before = assistant.swings();
        tick(40);
        assertEquals(before, assistant.swings(), "the emergency retreat does not trade");
        assertTrue(backend.isDown(com.utilitysuite.core.api.SimKey.BACK), "backs off");
    }

    @Test
    void slowRegenerationIsNoReasonForADuelReset() {
        lowHealthDuel(true, 19, 0f, 20f); // food 18-19: 1 HP per 4 seconds, the opponent is back sooner
        int before = assistant.swings();
        tick(40);
        assertNotEquals(TacticUtility.Tactic.RETREAT_HEAL, assistant.broadTactic());
        assertTrue(assistant.swings() > before);
    }

    @Test
    void fastRegenerationAgainstAHealthyOpponentIsADuelReset() {
        lowHealthDuel(true, 20, 4f, 20f);
        tick(TacticUtility.MIN_DWELL_TICKS + 5); // the tactic dwell: the reset takes over within a few ticks
        int before = assistant.swings();
        tick(40);
        assertEquals(TacticUtility.Tactic.RETREAT_HEAL, assistant.broadTactic(), "we heal, they have nothing to heal");
        assertEquals(before, assistant.swings());
    }

    @Test
    void noDuelResetWhileTheOpponentIsHurtAsBadly() {
        lowHealthDuel(true, 20, 4f, 5f); // they would regenerate as much as we would
        int before = assistant.swings();
        tick(40);
        assertNotEquals(TacticUtility.Tactic.RETREAT_HEAL, assistant.broadTactic());
        assertTrue(assistant.swings() > before, "finish it");
    }

    @Test
    void withoutDuelModeTheSameTargetIsDropped() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        world.entities.get(TARGET).x = 40.0;
        tick(3);
        assertNotEquals(CombatState.ENGAGED, assistant.state());
        assertEquals(DisengageReason.OUT_OF_RANGE, assistant.lastDisengageReason());
    }
}
