package com.utilitysuite.core;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.utilitysuite.core.api.ControlChannel.CAMERA;
import static com.utilitysuite.core.api.ControlChannel.HOTBAR;
import static com.utilitysuite.core.api.ControlChannel.MOVEMENT;
import static com.utilitysuite.core.api.ControlChannel.WORLD_INTERACT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance Scenario 3: a GOAL mining lease (MOVEMENT+CAMERA+WORLD_INTERACT) is preempted by SURVIVAL eating
 * (HOTBAR+WORLD_INTERACT), then a COMBAT trigger arrives, then Stop-All. Runs the real arbiter, smoother, registry and
 * event bus against a fake key/camera backend.
 */
class AcceptanceScenario3Test {

    /** A module that requests a lease, drives keys, and records every callback. */
    static final class ScriptedModule implements IModule, LeaseListener {
        final String id;
        final ControlPriority priority;
        final Set<ControlChannel> channels;
        final List<String> log = new ArrayList<>();
        ActionArbiter arbiter;
        InputSmoother input;
        ControlLease lease;
        boolean enabled = true;
        int stopAllCalls;
        /** Mining progress, kept across preemptions so the job resumes from its checkpoint. */
        int checkpoint;
        boolean resumeOnResumable;

        ScriptedModule(String id, ControlPriority priority, ControlChannel first, ControlChannel... rest) {
            this.id = id;
            this.priority = priority;
            this.channels = EnumSet.of(first, rest);
        }

        boolean start() {
            lease = arbiter.request(new LeaseRequest(id, priority, channels, 0, this)).orElse(null);
            log.add(lease != null ? "granted" : "refused");
            return lease != null;
        }

        @Override
        public void onPreempted(ControlLease l, LeaseEndReason reason, String by) {
            log.add("preempted:" + reason + ":" + by);
        }

        @Override
        public void onResumable(ControlLease former) {
            log.add("resumable");
            if (resumeOnResumable) {
                start();
            }
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ModuleCategory category() {
            return ModuleCategory.JOBS;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean e) {
            enabled = e;
        }

        @Override
        public void onTick() {
        }

        @Override
        public void onStopAll() {
            stopAllCalls++;
            lease = null;
        }
    }

    @Test
    void goalSurvivalCombatStopAll() {
        FakeInputBackend keys = new FakeInputBackend();
        InputSmoother input = new InputSmoother(keys, new Random(3));
        ModuleRegistry registry = new ModuleRegistry();
        SuiteEventBus bus = new SuiteEventBus();
        List<SuiteEvent> events = new ArrayList<>();
        bus.subscribe(SuiteEvent.class, events::add);
        ActionArbiter arbiter = new ActionArbiter();
        arbiter.bind(input, registry, bus);

        ScriptedModule goal = new ScriptedModule("goal", ControlPriority.GOAL, MOVEMENT, CAMERA, WORLD_INTERACT);
        ScriptedModule survival = new ScriptedModule("survival", ControlPriority.SURVIVAL, HOTBAR, WORLD_INTERACT);
        ScriptedModule combat = new ScriptedModule("combat", ControlPriority.COMBAT, MOVEMENT, CAMERA,
                WORLD_INTERACT);
        for (ScriptedModule m : List.of(goal, survival, combat)) {
            m.arbiter = arbiter;
            m.input = input;
            registry.register(m);
        }
        survival.resumeOnResumable = true; // survival re-grabs its channels as soon as they are free

        // 1. GOAL starts mining.
        assertTrue(goal.start());
        assertTrue(input.setKeyHeld(goal.lease, SimKey.FORWARD, true));
        assertTrue(input.setKeyHeld(goal.lease, SimKey.ATTACK, true));
        assertTrue(input.setRotationTarget(goal.lease, RotationTarget.visible(45f, 30f)));
        goal.checkpoint = 17;
        input.preTick();
        assertTrue(keys.isDown(SimKey.FORWARD) && keys.isDown(SimKey.ATTACK));

        // 2. SURVIVAL eats: preempts GOAL (shares WORLD_INTERACT). The whole GOAL lease ends.
        ControlLease goalLease1 = goal.lease;
        assertTrue(survival.start());
        assertFalse(goalLease1.isActive());
        assertEquals("preempted:PREEMPTED:survival", last(goal.log));
        assertFalse(keys.isDown(SimKey.FORWARD), "GOAL's keys released on preemption");
        assertFalse(keys.isDown(SimKey.ATTACK));
        assertFalse(input.setKeyHeld(goalLease1, SimKey.FORWARD, true), "stale lease cannot drive keys");
        assertTrue(arbiter.isHeldBy(HOTBAR, "survival"));
        assertTrue(arbiter.isHeldBy(WORLD_INTERACT, "survival"));
        assertTrue(arbiter.holder(MOVEMENT).isEmpty());
        assertTrue(input.selectHotbarSlot(survival.lease, 3));
        assertTrue(input.setKeyHeld(survival.lease, SimKey.USE, true));
        assertTrue(keys.isDown(SimKey.USE));

        // 3. COMBAT trigger: preempts SURVIVAL mid-meal.
        ControlLease survivalLease1 = survival.lease;
        assertTrue(combat.start());
        assertFalse(survivalLease1.isActive());
        assertEquals("preempted:PREEMPTED:combat", last(survival.log));
        assertFalse(keys.isDown(SimKey.USE), "eating stopped");
        assertTrue(input.scheduleKeyTap(combat.lease, SimKey.ATTACK, 1, 2));
        assertTrue(input.setKeyHeld(combat.lease, SimKey.BACK, true));
        // Lower modules cannot take anything back while COMBAT holds it.
        assertTrue(arbiter.request(LeaseRequest.of("goal", ControlPriority.GOAL, goal, MOVEMENT)).isEmpty());
        assertFalse(goal.log.contains("resumable"));
        assertFalse(survival.log.contains("resumable"));

        // 4. The fight ends: SURVIVAL (most recent) resumes first and re-takes WORLD_INTERACT, so GOAL keeps waiting.
        combat.lease.close();
        assertFalse(keys.isDown(SimKey.BACK));
        assertEquals(List.of("granted", "preempted:PREEMPTED:combat", "resumable", "granted"), survival.log);
        assertTrue(arbiter.isHeldBy(WORLD_INTERACT, "survival"));
        assertFalse(goal.log.contains("resumable"), "GOAL still blocked on WORLD_INTERACT");

        // 5. SURVIVAL finishes eating: GOAL may resume from its checkpoint.
        survival.lease.close();
        assertEquals("resumable", last(goal.log));
        assertTrue(goal.start(), "GOAL re-acquires its channels");
        assertEquals(17, goal.checkpoint, "checkpoint survived both preemptions");
        assertTrue(input.setKeyHeld(goal.lease, SimKey.FORWARD, true));
        assertTrue(input.setKeyHeld(goal.lease, SimKey.ATTACK, true));
        assertEquals(0, arbiter.suspendedCount());

        // 6. Hunger again, then another COMBAT trigger, then the user hits Stop-All mid-fight.
        assertTrue(survival.start());
        assertTrue(combat.start());
        input.setKeyHeld(combat.lease, SimKey.FORWARD, true);
        input.setKeyHeld(combat.lease, SimKey.SPRINT, true);
        input.setRotationTarget(combat.lease, RotationTarget.silent(180f, 0f));
        input.scheduleKeyTap(combat.lease, SimKey.ATTACK, 5, 5);
        assertTrue(keys.anyDown());
        assertEquals(2, arbiter.suspendedCount(), "GOAL and SURVIVAL are both waiting to resume");

        ControlLease combatLease = combat.lease;
        arbiter.stopAll("user pressed Stop-All");

        assertFalse(combatLease.isActive());
        assertEquals("preempted:STOP_ALL:stop_all", last(combat.log));
        assertTrue(arbiter.isAutomationStopped());
        for (ControlChannel c : ControlChannel.all()) {
            assertTrue(arbiter.holder(c).isEmpty(), c + " revoked");
        }
        assertFalse(keys.anyDown(), "every automation key state is zero");
        for (SimKey k : SimKey.all()) {
            assertFalse(input.isKeyHeldByAutomation(k), k + " still managed");
        }
        assertTrue(input.isRotationSettled(0f), "rotation target cleared");
        assertEquals(1, goal.stopAllCalls);
        assertEquals(1, survival.stopAllCalls);
        assertEquals(1, combat.stopAllCalls);
        assertTrue(events.stream().anyMatch(e -> e instanceof SuiteEvent.StopAll));
        assertEquals(0, arbiter.suspendedCount());

        // 7. While stopped every request is refused, including EMERGENCY; nothing resumes on its own.
        assertFalse(goal.start());
        assertTrue(arbiter.request(LeaseRequest.of("panic", ControlPriority.EMERGENCY, null, CAMERA)).isEmpty());
        int goalResumables = count(goal.log, "resumable");
        for (int i = 0; i < 100; i++) {
            arbiter.tick();
            input.preTick();
            input.postTick();
        }
        assertEquals(goalResumables, count(goal.log, "resumable"), "no onResumable after Stop-All");
        assertFalse(keys.anyDown());

        // 8. Explicit resume.
        arbiter.resumeAutomation();
        assertTrue(goal.start());
    }

    private static String last(List<String> log) {
        return log.get(log.size() - 1);
    }

    private static int count(List<String> log, String s) {
        int n = 0;
        for (String x : log) {
            if (x.equals(s)) {
                n++;
            }
        }
        return n;
    }
}
