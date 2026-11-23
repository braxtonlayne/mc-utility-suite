package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.tactics.StalemateSearch;
import com.utilitysuite.combat.tactics.StalemateWorlds;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The assistant with the stalemate breaker: the two ravine stalls seen in the bot arena on 2026-11-23. */
class CombatStalemateTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;

    /** A route runner that takes caller routes (like the real one) and counts pursuits. */
    static final class Runner implements IRouteRunner {
        final IInputSmoother input;
        State state = State.IDLE;
        ControlLease runLease;
        int pursues;
        int follows;
        long lastEnd;

        Runner(IInputSmoother input) {
            this.input = input;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean pursue(ControlLease lease, double x, double y, double z, double stopRange, boolean look) {
            pursues++;
            runLease = lease;
            state = State.ARRIVED; // the horizontal stop range: a target straight above or below "arrives" at once
            return true;
        }

        @Override
        public boolean flee(ControlLease lease, double x, double y, double z, double d, boolean look) {
            return false;
        }

        @Override
        public boolean follow(ControlLease lease, long[] cells, int count, boolean look) {
            assertTrue(look, "looks where it walks");
            follows++;
            lastEnd = cells[count - 1];
            if (runLease == null) {
                input.setKeyHeld(lease, SimKey.FORWARD, true);
                input.setRotationTarget(lease, RotationTarget.visible(0f, 0f));
            }
            runLease = lease;
            state = State.RUNNING;
            return true;
        }

        @Override
        public void stop(ControlLease lease) {
            if (runLease != null && runLease == lease) {
                input.setKeyHeld(lease, SimKey.FORWARD, false);
            }
            runLease = null;
            state = State.IDLE;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public boolean isRunning(ControlLease lease) {
            return runLease == lease && state == State.RUNNING;
        }
    }

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    Runner runner;
    long[] now = {50_000L};

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        assistant.setEnabled(true);
        registry.register(assistant);
        runner = new Runner(smoother);
        Services.registerRouteRunner(runner);
        assistant.useArenaVoxels(StalemateWorlds.ravine(true));
        assistant.config().duelMode = true; // the arena: sight and range never end the fight
        world.self.attackStrength = 0.2f;
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            smoother.onFrame(0.025);
            smoother.onFrame(0.025);
            now[0] += 50;
        }
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", TARGET, TARGET);
        tick(1);
        assertNotNull(assistant.lease());
    }

    @Test
    void inTheRavineWithTheOpponentPacingAboveItRoutesOutInsteadOfHovering() {
        world.local().x = 10.5;
        world.local().y = 56;
        world.local().z = 0.5;
        FakeCombatWorld.E t = world.addPlayer(TARGET, "arenabot", 7.5, 64, 0.5);
        engage();
        int stall = (int) Math.round(assistant.config().stallSeconds * 20);
        for (int i = 0; i < stall - 5; i++) {
            t.z = 0.5 + Math.sin(i * 0.15) * 2.0; // pacing back and forth at the rim
            t.vz = Math.cos(i * 0.15) * 0.3;
            tick(1);
        }
        assertEquals(0, runner.follows, "the normal fight until the stall time");
        tick(40);
        assertTrue(runner.follows >= 1, "a route out");
        assertTrue(assistant.routeActive());
        assertEquals(StalemateBreaker.Phase.APPROACH, assistant.stalemate().phase());
        assertTrue(Math.abs(StalemateSearch.unpackY(runner.lastEnd) - 64) <= 1);
        assertTrue(assistant.lastNote().startsWith("Stalemate: routing up to"), assistant.lastNote());
        assertTrue(backend.isDown(SimKey.FORWARD), "the runner's keys");
        assertFalse(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "no strafing in place");
        assertEquals(0, runner.pursues);

        // up and beside it: back to the normal fight
        world.local().x = 6.5;
        world.local().y = 64;
        world.local().z = t.z + 1.5;
        t.vz = 0;
        tick(1);
        assertFalse(assistant.routeActive());
        assertEquals(StalemateBreaker.Phase.IDLE, assistant.stalemate().phase());
    }

    @Test
    void onTopWithTheOpponentStraightBelowItTakesTheStairsDownAndNeverSitsInAPursuitThatCannotMove() {
        // the live finding: 8 blocks straight below, out of sight; the old LOS pursuit "arrived" at once every try
        world.local().x = 8.4;
        world.local().y = 64;
        world.local().z = 0.6;
        world.addPlayer(TARGET, "arenabot", 9.3, 56, 0.3).health = 0.4f;
        world.lineOfSight = false;
        engage();
        tick(130);
        assertEquals(0, runner.pursues, "no LOS pursuit toward a target inside the horizontal stop range");
        assertTrue(runner.follows >= 1, "a route down");
        assertEquals(56, StalemateSearch.unpackY(runner.lastEnd));
        assertTrue(assistant.routeActive());
        assertTrue(assistant.lastNote().startsWith("Stalemate: routing down to"), assistant.lastNote());
        assertTrue(backend.isDown(SimKey.FORWARD));
    }

    @Test
    void offTheSettingKeepsTheOldBehaviour() {
        assistant.config().breakStalemates = false;
        world.local().x = 10.5;
        world.local().y = 56;
        world.local().z = 0.5;
        world.addPlayer(TARGET, "arenabot", 7.5, 64, 0.5);
        engage();
        tick(200);
        assertEquals(0, runner.follows);
        assertFalse(assistant.lastNote().startsWith("Stalemate"));
    }
}
