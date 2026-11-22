package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.tactics.TacticUtility;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.RotationMode;
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

/** Pursuit and flight through a fake {@link IRouteRunner} sharing the assistant's lease; hand-over in both directions. */
class CombatRouteHandoverTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;

    /** Holds W + sprint and turns along its route through the same lease, like the real runner. */
    static final class FakeRunner implements IRouteRunner {
        final IInputSmoother input;
        boolean accept = true;
        State state = State.IDLE;
        int pursues;
        int flees;
        int stops;
        ControlLease runLease;
        double lastStopRange;
        double lastMinDistance;

        FakeRunner(IInputSmoother input) {
            this.input = input;
        }

        @Override
        public boolean isAvailable() {
            return accept;
        }

        private boolean run(ControlLease lease) {
            if (!accept) {
                state = State.FAILED;
                return false;
            }
            if (runLease == null) {
                input.setKeyHeld(lease, SimKey.FORWARD, true);
                input.setKeyHeld(lease, SimKey.SPRINT, true);
                input.setRotationTarget(lease, new RotationTarget(180f, 0f, RotationMode.VISIBLE, 1f));
            }
            runLease = lease;
            if (state != State.FAILED && state != State.ARRIVED) {
                state = State.RUNNING;
            }
            return true;
        }

        @Override
        public boolean pursue(ControlLease lease, double x, double y, double z, double stopRange,
                              boolean lookAlongRoute) {
            assertTrue(lookAlongRoute);
            pursues++;
            lastStopRange = stopRange;
            return run(lease);
        }

        @Override
        public boolean flee(ControlLease lease, double fromX, double fromY, double fromZ, double minDistance,
                            boolean lookAlongRoute) {
            assertTrue(lookAlongRoute);
            flees++;
            lastMinDistance = minDistance;
            return run(lease);
        }

        @Override
        public void stop(ControlLease lease) {
            stops++;
            if (runLease != null && runLease == lease) {
                input.setKeyHeld(lease, SimKey.FORWARD, false);
                input.setKeyHeld(lease, SimKey.SPRINT, false);
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
            return runLease == lease && (state == State.RUNNING || state == State.PLANNING);
        }
    }

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    FakeRunner runner;
    long[] now = {50_000L};

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
        runner = new FakeRunner(smoother);
        Services.registerRouteRunner(runner);
        world.self.attackStrength = 0.2f; // charging: no swings in the way
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
            smoother.onFrame(0.025); // two frames per tick turn the camera
            smoother.onFrame(0.025);
            now[0] += 50;
        }
    }

    static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        assertNotNull(assistant.lease());
    }

    @Test
    void pursuesAlongARouteBeyondRangeAndHandsBackInsideIt() {
        world.entities.get(TARGET).x = 12; // beyond spacing max + 6
        engage();
        assertTrue(runner.pursues > 0, "the runner chases");
        assertTrue(assistant.routeActive());
        assertEquals(assistant.config().spacingMax, runner.lastStopRange);
        assertTrue(backend.isDown(SimKey.FORWARD), "runner's W");
        int pursues = runner.pursues;
        tick(3);
        assertTrue(runner.pursues >= pursues + 3, "retargeted every tick");

        world.entities.get(TARGET).x = 8.5; // between exit and enter range: still running (hysteresis)
        tick(1);
        assertTrue(assistant.routeActive());

        world.entities.get(TARGET).x = 5; // inside spacing max + 5: melee code again
        tick(1);
        assertFalse(assistant.routeActive());
        assertEquals(1, runner.stops);
        // the runner released W on stop; the band must press it again (the key cache was cleared on take-back)
        assertTrue(backend.isDown(SimKey.FORWARD), "band closes in after the hand-back");
    }

    @Test
    void ourKeysAreReleasedAndTheAimIsLeftAloneWhileTheRunnerRuns() {
        world.entities.get(TARGET).x = 2.9;
        engage();
        tick(3);
        assertTrue(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "strafing in the band");
        world.entities.get(TARGET).x = 12;
        tick(1);
        assertTrue(assistant.routeActive());
        assertFalse(backend.isDown(SimKey.LEFT) || backend.isDown(SimKey.RIGHT), "our strafe key went up");
        assertFalse(backend.isDown(SimKey.BACK));
        tick(30);
        // only the runner's camera target is active (yaw 180), never the target's bearing (yaw -90)
        assertTrue(Math.abs(wrap(backend.yaw - 180f)) < Math.abs(wrap(backend.yaw + 90f)),
                "turned along the route, not toward the target (yaw " + backend.yaw + ")");
    }

    @Test
    void pursuesWhenLineOfSightIsLostForAWhile() {
        world.entities.get(TARGET).x = 4;
        engage();
        assertFalse(assistant.routeActive());
        world.lineOfSight = false;
        tick(CombatAssistant.LOS_PURSUIT_TICKS - 1);
        assertFalse(assistant.routeActive(), "a short flicker is ignored");
        tick(3);
        assertTrue(assistant.routeActive());
        world.lineOfSight = true;
        tick(1);
        assertFalse(assistant.routeActive(), "in range and in sight again: melee");
    }

    @Test
    void noRunnerOrSettingOffKeepsTheOldChase() {
        assistant.config().routePursuit = false;
        world.entities.get(TARGET).x = 12;
        engage();
        assertEquals(0, runner.pursues);
        assertTrue(backend.isDown(SimKey.FORWARD));
        assertTrue(backend.isDown(SimKey.SPRINT));

        Services.resetForTests(); // no runner registered
        assistant.config().routePursuit = true;
        tick(2);
        assertFalse(assistant.routeActive());
        assertTrue(backend.isDown(SimKey.FORWARD), "band chase");
    }

    @Test
    void fleesAlongARouteOnFlee() {
        assistant.config().sustain = false;
        engage();
        world.self.health = 3f; // took 17 in one tick: losing badly with nothing to heal
        tick(2);
        assertEquals(TacticUtility.Tactic.FLEE, assistant.broadTactic());
        assertTrue(runner.flees > 0);
        assertEquals(CombatAssistant.FLEE_DISTANCE, runner.lastMinDistance);
        assertTrue(assistant.routeActive());
        assertFalse(backend.isDown(SimKey.BACK), "turns and runs instead of backpedalling");
        assertTrue(backend.isDown(SimKey.FORWARD));
    }

    @Test
    void fallsBackToTheBackpedalWhenTheRunnerRefuses() {
        runner.accept = false;
        assistant.config().sustain = false;
        engage();
        world.self.health = 3f;
        tick(2);
        assertEquals(TacticUtility.Tactic.FLEE, assistant.broadTactic());
        assertEquals(1, runner.flees, "tried once");
        assertFalse(assistant.routeActive());
        assertTrue(backend.isDown(SimKey.BACK), "backpedals");
        tick(CombatAssistant.ROUTE_RETRY_TICKS - 3);
        assertEquals(1, runner.flees, "no retry every tick");
        tick(4);
        assertEquals(2, runner.flees, "retried after the wait");
    }

    @Test
    void fallsBackWhenTheRunReportsFailed() {
        assistant.config().sustain = false;
        engage();
        world.self.health = 3f;
        tick(2);
        assertTrue(assistant.routeActive());
        runner.state = IRouteRunner.State.FAILED;
        tick(1);
        assertFalse(assistant.routeActive());
        assertTrue(backend.isDown(SimKey.BACK), "backpedals after the failed run");
        assertFalse(backend.isDown(SimKey.FORWARD), "runner keys gone");
    }

    @Test
    void sustainRetreatRunsOnlyWhileTheOpponentKeepsClosing() {
        world.self.health = 6f; // low: sustain retreat (no gapple, no totem)
        engage();
        tick(2);
        assertFalse(assistant.routeActive(), "the opponent stands still: backpedal facing it");
        assertTrue(backend.isDown(SimKey.BACK));
        world.entities.get(TARGET).vx = -0.25; // charging at us
        tick(2);
        assertTrue(assistant.routeActive(), "it keeps closing: turn and run");
        assertTrue(runner.flees > 0);
    }

    @Test
    void theRunnerStopsOnDisengageAndStopAll() {
        world.entities.get(TARGET).x = 12;
        engage();
        assertTrue(assistant.routeActive());
        world.entities.get(TARGET).alive = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(1, runner.stops);
        assertFalse(assistant.routeActive());

        world.entities.get(TARGET).alive = true;
        engage();
        assertTrue(assistant.routeActive());
        arbiter.stopAll("test");
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertFalse(assistant.routeActive());
        assertTrue(runner.stops >= 2, "stopped with the lease");
        assertFalse(backend.isDown(SimKey.FORWARD));
    }

    @Test
    void theRunnerStopsWhenDisarmed() {
        world.entities.get(TARGET).x = 12;
        engage();
        assertTrue(assistant.routeActive());
        assistant.setEnabled(false);
        assertEquals(1, runner.stops);
        assertFalse(assistant.routeActive());
    }
}
