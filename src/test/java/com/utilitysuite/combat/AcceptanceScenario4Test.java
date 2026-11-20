package com.utilitysuite.combat;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.DisengageReason;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance Scenario 4: combat attribution and ally safety. Real {@link ActionArbiter}, {@link InputSmoother} and
 * {@link ModuleRegistry} against a fake world and a fake key/camera backend.
 */
class AcceptanceScenario4Test {

    static final int LOCAL = 1;
    static final int ZOMBIE = 10;
    static final int ALLY = 20;
    static final int DEFENDED = 30;
    static final int STRANGER = 40;

    FakeCombatWorld world;
    AllyRoster roster;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    long[] now;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.add(ZOMBIE, "Zombie", 3, 64, 0);
        world.addPlayer(ALLY, "AllyPlayer", -3, 64, 0);
        world.addPlayer(DEFENDED, "DefendedPlayer", 0, 64, 5);
        world.addPlayer(STRANGER, "Stranger", 4, 64, 4);
        roster = new AllyRoster();
        roster.add(world.uuidOf(ALLY), "AllyPlayer", false); // neverTarget, not defended
        roster.add(world.uuidOf(DEFENDED), "DefendedPlayer", false);
        roster.setDefend(world.uuidOf(DEFENDED), true);

        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(4));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        now = new long[]{100_000L};
        assistant = new CombatAssistant(world, roster, arbiter, smoother, smoother, () -> now[0]);
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

    void assertDormantNoLease() {
        assertEquals(CombatState.DORMANT, assistant.state());
        for (ControlChannel c : ControlChannel.all()) {
            assertFalse(arbiter.isHeldBy(c, CombatAssistant.ID), "channel " + c + " still held");
        }
    }

    void assertEngagedOn(int target) {
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertEquals(target, assistant.targetId());
    }

    // ---- environmental & ambiguous damage ------------------------------------------------------------------------

    @Test
    void environmentalDamageNeverEngages() {
        for (String type : new String[]{"minecraft:fall", "minecraft:lava", "minecraft:in_fire", "minecraft:on_fire",
                "minecraft:drown", "minecraft:hot_floor", "minecraft:in_wall", "minecraft:cactus"}) {
            assistant.onDamageEvent(LOCAL, type, -1, -1);
            // Even with an entity attached (e.g. knocked into lava), environmental types never engage.
            assistant.onDamageEvent(LOCAL, type, ZOMBIE, ZOMBIE);
            tick(2);
            assertDormantNoLease();
        }
        assertEquals(0, assistant.engagements());
    }

    @Test
    void ambiguousAttributionStaysDormantAndLogs() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", -1, -1);          // no cause entity
        assistant.onDamageEvent(LOCAL, "minecraft:arrow", -1, 77);               // dispenser arrow, no shooter
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", 999, 999);        // cause not loaded on the client
        assistant.onDamageEvent(LOCAL, "othermod:laser", ZOMBIE, ZOMBIE);       // unknown (modded) damage type
        world.entities.get(ZOMBIE).alive = false;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", ZOMBIE, ZOMBIE); // cause already dead
        tick(3);
        assertDormantNoLease();
        assertEquals(5, assistant.ignoredAttributions());
        assertEquals(0, assistant.engagements());
    }

    @Test
    void disarmedNeverEngages() {
        assistant.setEnabled(false);
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(3);
        assertDormantNoLease();
        assertEquals(ZOMBIE, assistant.recentOpponentId(), "attribution is still tracked for the coach");
    }

    // ---- confirmed engagement & lease ----------------------------------------------------------------------------

    @Test
    void confirmedAttackerEngagesWithCombatLeasePreemptingGoalWhichResumes() {
        List<String> goalLog = new ArrayList<>();
        LeaseListener goalListener = new LeaseListener() {
            @Override
            public void onPreempted(ControlLease lease, LeaseEndReason reason, String by) {
                goalLog.add("preempted:" + reason + ":" + by);
            }

            @Override
            public void onResumable(ControlLease former) {
                goalLog.add("resumable");
            }
        };
        ControlLease goal = arbiter.request(LeaseRequest.of("goal_engine", ControlPriority.GOAL, goalListener,
                ControlChannel.MOVEMENT, ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT)).orElseThrow();

        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        assertEngagedOn(ZOMBIE);
        tick(1);
        ControlLease lease = assistant.lease();
        assertNotNull(lease);
        assertEquals(ControlPriority.COMBAT, lease.priority());
        for (ControlChannel c : ControlChannel.all()) {
            assertTrue(arbiter.isHeldBy(c, CombatAssistant.ID), "combat should hold " + c);
        }
        assertFalse(goal.isActive());
        assertEquals(List.of("preempted:PREEMPTED:" + CombatAssistant.ID), goalLog);

        assertFalse(smoother.isRotationSettled(1f), "turning toward the zombie (yaw -90) through the smoother");
        // Crit sync hops first; this fake player never leaves the ground, so the swing falls back after the wait.
        tick(15);
        assertTrue(backend.clicks[SimKey.JUMP.ordinal()] + backend.presses[SimKey.JUMP.ordinal()] > 0, "hop");
        assertTrue(backend.clicks[SimKey.ATTACK.ordinal()] > 0, "a full-charge swing at a target in reach");
        assertTrue(assistant.swings() >= 1);

        world.entities.get(ZOMBIE).alive = false;
        tick(1);
        assertDormantNoLease();
        assertEquals(DisengageReason.TARGET_LOST, assistant.lastDisengageReason());
        assertEquals(List.of("preempted:PREEMPTED:" + CombatAssistant.ID, "resumable"), goalLog,
                "the goal lease is told it can resume");
        assertFalse(backend.anyDown(), "every simulated key released");
    }

    @Test
    void keepsLockOnFirstAttackerWhenAnotherHits() {
        world.add(11, "Skeleton", 0, 64, 6);
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        assistant.onDamageEvent(LOCAL, "minecraft:arrow", 11, 55);
        assertEngagedOn(ZOMBIE);
    }

    @Test
    void offensiveEngagementNeedsConfirmedHit() {
        // A damage event caused by us without a manual attack does not engage.
        assistant.onDamageEvent(ZOMBIE, "minecraft:player_attack", LOCAL, LOCAL);
        assertEquals(CombatState.DORMANT, assistant.state());

        // A manual swing that the server never confirms in time does not engage.
        assistant.onLocalAttack(ZOMBIE);
        now[0] += 2_000;
        assistant.onDamageEvent(ZOMBIE, "minecraft:player_attack", LOCAL, LOCAL);
        assertEquals(CombatState.DORMANT, assistant.state());

        // Manual swing + confirmation engages.
        assistant.onLocalAttack(ZOMBIE);
        now[0] += 100;
        assistant.onDamageEvent(ZOMBIE, "minecraft:player_attack", LOCAL, LOCAL);
        assertEngagedOn(ZOMBIE);
        tick(1);
        assertNotNull(assistant.lease());
    }

    // ---- ally safety ---------------------------------------------------------------------------------------------

    @Test
    void neverTargetsAnAllyEvenWhenTheAllyHitsUs() {
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", ALLY, ALLY);
        tick(3);
        assertDormantNoLease();
        assertEquals(1, assistant.ignoredAttributions());
        assertTrue(assistant.lastNote().contains("AllyPlayer"), assistant.lastNote());

        // The user hitting an ally by hand does not engage either.
        assistant.onLocalAttack(ALLY);
        assistant.onDamageEvent(ALLY, "minecraft:player_attack", LOCAL, LOCAL);
        tick(2);
        assertDormantNoLease();
    }

    @Test
    void allyAddedMidFightEndsTheEngagement() {
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", STRANGER, STRANGER);
        tick(2);
        assertEngagedOn(STRANGER);
        roster.add(world.uuidOf(STRANGER), "Stranger", false);
        tick(1);
        assertDormantNoLease();
        assertEquals(DisengageReason.TARGET_IS_ALLY, assistant.lastDisengageReason());
    }

    @Test
    void defendsOptedInAllyOnlyOnConfirmedAttribution() {
        // Not opted in: an attack on a neverTarget-only ally is ignored.
        assistant.onDamageEvent(ALLY, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(2);
        assertDormantNoLease();

        // Opted in, but ambiguous: logged, no bystander guessed.
        assistant.onDamageEvent(DEFENDED, "minecraft:arrow", -1, 66);
        assistant.onDamageEvent(DEFENDED, "minecraft:fall", -1, -1);
        tick(2);
        assertDormantNoLease();
        assertEquals(1, assistant.ignoredAttributions(), "only the ambiguous attack is logged, not the fall");

        // Opted in, attacked by another neverTarget ally: stays dormant.
        assistant.onDamageEvent(DEFENDED, "minecraft:player_attack", ALLY, ALLY);
        tick(2);
        assertDormantNoLease();

        // Opted in, confirmed attacker: engage the attacker.
        assistant.onDamageEvent(DEFENDED, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        assertEngagedOn(ZOMBIE);
        tick(1);
        assertNotNull(assistant.lease());
    }

    @Test
    void doesNotDefendAllyOutOfRange() {
        world.entities.get(DEFENDED).z = 40;
        assistant.onDamageEvent(DEFENDED, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(2);
        assertDormantNoLease();
    }

    // ---- disengage conditions ------------------------------------------------------------------------------------

    private void engageZombie() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(2);
        assertEngagedOn(ZOMBIE);
        assertNotNull(assistant.lease());
    }

    @Test
    void disengagesBeyondRadius() {
        engageZombie();
        world.entities.get(ZOMBIE).x = 17;
        tick(1);
        assertDormantNoLease();
        assertEquals(DisengageReason.OUT_OF_RANGE, assistant.lastDisengageReason());
    }

    @Test
    void disengagesAfterLineOfSightLostFiveSeconds() {
        engageZombie();
        world.lineOfSight = false;
        tick(100);
        assertEngagedOn(ZOMBIE);
        tick(1);
        assertDormantNoLease();
        assertEquals(DisengageReason.LINE_OF_SIGHT_LOST, assistant.lastDisengageReason());
    }

    @Test
    void lineOfSightTimerResetsWhenSeenAgain() {
        engageZombie();
        world.lineOfSight = false;
        tick(90);
        world.lineOfSight = true;
        tick(1);
        world.lineOfSight = false;
        tick(90);
        assertEngagedOn(ZOMBIE);
    }

    @Test
    void manualMovementKeyDisengagesAndReleases() {
        engageZombie();
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY); // what the core's hardware hooks report
        assertDormantNoLease();
        assertEquals(DisengageReason.MANUAL_MOVEMENT, assistant.lastDisengageReason());
        assertFalse(backend.anyDown());
        tick(40);
        assertDormantNoLease();
    }

    @Test
    void movementWhileYieldingToMouseLookAlsoDisengages() {
        engageZombie();
        arbiter.onManualInput(ManualInput.MOUSE_LOOK);
        tick(3);
        assertEquals(CombatState.ENGAGED, assistant.state());
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        tick(1);
        assertDormantNoLease();
        assertEquals(DisengageReason.MANUAL_MOVEMENT, assistant.lastDisengageReason());
    }

    @Test
    void walkingWhenHitStillEngagesAfterTheOverridePause() {
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY); // the user was walking when the mob hit
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", ZOMBIE, ZOMBIE);
        tick(5);
        assertEngagedOn(ZOMBIE);
        assertNull(assistant.lease(), "yields while the user's own movement is fresh");
        tick(arbiter.manualOverridePauseTicks());
        assertEngagedOn(ZOMBIE);
        assertNotNull(assistant.lease());
    }

    @Test
    void explicitMovementSignalDisengages() {
        engageZombie();
        assistant.onManualMovementKey();
        assertDormantNoLease();
        assertEquals(DisengageReason.MANUAL_MOVEMENT, assistant.lastDisengageReason());
    }

    @Test
    void cancelKeyDisengagesAndReleases() {
        engageZombie();
        assistant.onCancelKey();
        assertDormantNoLease();
        assertEquals(DisengageReason.CANCEL_KEY, assistant.lastDisengageReason());
        assertFalse(backend.anyDown());
    }

    @Test
    void mouseLookYieldsThenTakesOverAgain() {
        engageZombie();
        arbiter.onManualInput(ManualInput.MOUSE_LOOK);
        assertNull(assistant.lease(), "manual mouse look ends the lease");
        assertEquals(CombatState.ENGAGED, assistant.state(), "but not the engagement");
        tick(5);
        assertNull(assistant.lease(), "waits out the override pause");
        tick(arbiter.manualOverridePauseTicks());
        assertNotNull(assistant.lease(), "takes over again");
    }

    @Test
    void stopAllDisengages() {
        engageZombie();
        arbiter.stopAll("test");
        assertDormantNoLease();
        assertEquals(DisengageReason.STOP_ALL, assistant.lastDisengageReason());
        arbiter.resumeAutomation();
    }

    @Test
    void disarmingMidFightReleasesImmediately() {
        engageZombie();
        assistant.setEnabled(false);
        assertDormantNoLease();
        assertEquals(DisengageReason.DISARMED, assistant.lastDisengageReason());
    }

    @Test
    void higherPriorityPreemptionPausesThenResumes() {
        engageZombie();
        ControlLease emergency = arbiter.request(LeaseRequest.of("survival_emergency", ControlPriority.EMERGENCY,
                LeaseListener.NONE, ControlChannel.HOTBAR)).orElseThrow();
        assertNull(assistant.lease());
        assertEquals(CombatState.ENGAGED, assistant.state());
        tick(3);
        assertNull(assistant.lease(), "does not fight the higher-priority holder");
        emergency.close();
        tick(1);
        assertNotNull(assistant.lease(), "re-requests after onResumable");
    }
}
