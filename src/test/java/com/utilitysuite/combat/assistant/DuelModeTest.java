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

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.add(TARGET, "Vindicator", 3, 64, 0);
        arbiter = new ActionArbiter();
        smoother = new InputSmoother(new FakeInputBackend(), new Random(9));
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
