package com.utilitysuite.combat.assistant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import com.utilitysuite.core.input.ManualFactsTracker;
import com.utilitysuite.core.input.SharedInputOverlay;
import com.utilitysuite.core.input.YawCoSteer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Combat Assistant's co-pilot end to end on the real arbiter, smoother and shared-control overlay: the user keeps
 * the keys and the camera, the assistant writes only through the shared grant and a short swing lease.
 */
class CombatCoPilotTest {

    static final int LOCAL = 1;
    static final int TARGET = 20;

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    ManualFactsTracker facts;
    YawCoSteer steer;
    SharedInputOverlay overlay;
    CombatAssistant assistant;
    final boolean[] legit = {false};
    long[] now = {50_000L};

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        world = new FakeCombatWorld();
        world.addPlayer(TARGET, "Duelist", 2.9, 64, 0); // straight along +x: aim yaw -90
        world.self.yaw = -70f; // 20 degrees off the target
        world.self.attackStrength = 0.4f;
        world.crosshairOnTarget = false;
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        facts = new ManualFactsTracker();
        steer = new YawCoSteer();
        overlay = new SharedInputOverlay(arbiter, facts, steer, () -> false, () -> legit[0]);
        Services.registerSharedControl(overlay);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        assistant.setEnabled(true);
        registry.register(assistant);
        assistant.config().controlMode = CombatSettings.ControlMode.CO_PILOT;
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            facts.beginTick();
            overlay.beginTick(false);
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            now[0] += 50;
        }
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:player_attack", TARGET, TARGET);
        tick(2);
        assertEquals(CombatState.ENGAGED, assistant.state());
    }

    int attacks() {
        return backend.clicks[SimKey.ATTACK.ordinal()] + backend.presses[SimKey.ATTACK.ordinal()];
    }

    @Test
    void takeoverStaysTheDefault() {
        CombatSettings fresh = new CombatSettings();
        assertEquals(CombatSettings.ControlMode.TAKEOVER, fresh.controlMode);
        fresh.read(new ProfileConfig("sp_Test", null), CombatAssistant.ID);
        assertEquals(CombatSettings.ControlMode.TAKEOVER, fresh.controlMode);
        assertTrue(fresh.copilotSteer && fresh.copilotSpacing && fresh.copilotSwing);
        SettingSpec mode = CombatSettings.specs().stream()
                .filter(s -> s.key().equals(CombatSettings.CONTROL_MODE)).findFirst().orElseThrow();
        assertEquals(CombatSettings.ControlMode.TAKEOVER.name(), String.valueOf(mode.defaultValue()));
    }

    @Test
    void theNewSettingsHaveLangKeys() throws Exception {
        String text;
        try (InputStream in = getClass().getResourceAsStream("/assets/utilitysuite/lang/en_us.json")) {
            assertNotNull(in);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        for (String k : new String[]{CombatSettings.CONTROL_MODE, CombatSettings.COPILOT_STEER,
                CombatSettings.COPILOT_SPACING, CombatSettings.COPILOT_SWING}) {
            for (String key : new String[]{"setting.utilitysuite.combat_assistant." + k,
                    "setting.utilitysuite.combat_assistant." + k + ".tooltip"}) {
                assertTrue(o.has(key) && !o.get(key).getAsString().isBlank(), key);
                assertEquals(text.indexOf("\"" + key + "\""), text.lastIndexOf("\"" + key + "\""), key);
            }
        }
    }

    @Test
    void theUserKeepsKeysAndCameraAndTheAssistantOnlyCoSteers() {
        engage();
        assertTrue(assistant.coPilotActive());
        assertNull(assistant.lease(), "no fight lease in co-pilot");
        assertFalse(arbiter.isHeldBy(ControlChannel.CAMERA, CombatAssistant.ID));
        assertFalse(arbiter.isHeldBy(ControlChannel.HOTBAR, CombatAssistant.ID));
        assertEquals(CombatAssistant.ID, overlay.activeModuleId(), "the shared grant carries the writes");
        assertTrue(steer.isLive(), "a yaw co-steer toward the target was asked for");
        assertFalse(assistant.stageOwns(SimKey.USE), "USE stays the user's and the shield reflex's");
        assertEquals(0, backend.rotationWrites, "no rotation target: the camera is never driven");
    }

    @Test
    void theUsersMovementKeysNeverEndACoPilotFight() {
        engage();
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        facts.onPress(SimKey.FORWARD);
        tick(10);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertTrue(assistant.coPilotActive());
    }

    @Test
    void theSameKeysEndATakeoverFight() {
        assistant.config().controlMode = CombatSettings.ControlMode.TAKEOVER;
        engage();
        assertNotNull(assistant.lease());
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        tick(3);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertEquals(DisengageReason.MANUAL_MOVEMENT, assistant.lastDisengageReason());
    }

    @Test
    void noYawOnTheLegitLevelButTheOtherHelpStays() {
        legit[0] = true;
        world.entities.get(TARGET).x = 1.2; // crowded
        world.self.yaw = -90f;
        facts.onPress(SimKey.FORWARD);
        engage();
        tick(1);
        assertFalse(steer.isLive(), "the core refuses the co-steer on Legit");
        SharedInputOverlay.MoveFields f = runningForward();
        overlay.apply(f, 1f);
        assertEquals(0f, f.forwardImpulse, "the spacing nudge still holds W back");
    }

    @Test
    void crowdingWhileRunningAtItHoldsWBackForAFewTicksOnly() {
        world.entities.get(TARGET).x = 1.2;
        world.self.yaw = -90f;
        facts.onPress(SimKey.FORWARD);
        engage();
        int held = 0;
        int free = 0;
        for (int i = 0; i < 20; i++) {
            tick(1);
            SharedInputOverlay.MoveFields f = runningForward();
            overlay.apply(f, 1f);
            if (f.forwardImpulse == 0f) {
                held++;
            } else {
                free++;
            }
        }
        assertTrue(held > 0, "nudged");
        assertTrue(free > 0, "a nudge, not a lock");
        assertTrue(assistant.coPilotPlanner().nudgeTicks() <= 14);
    }

    SharedInputOverlay.MoveFields runningForward() {
        SharedInputOverlay.MoveFields f = new SharedInputOverlay.MoveFields();
        f.forwardImpulse = 1f;
        f.up = true;
        return f;
    }

    @Test
    void swingsThroughAShortWorldInteractLeaseOnlyWithTheUsersCrosshairOnIt() {
        engage();
        world.self.attackStrength = 1f;
        tick(6);
        assertEquals(0, assistant.coPilotSwings(), "crosshair off the target: no swing");
        assertEquals(0, attacks());

        world.crosshairOnTarget = true;
        int guard = 0;
        while (assistant.coPilotSwings() == 0 && guard++ < 10) {
            tick(1);
        }
        assertEquals(1, assistant.coPilotSwings());
        assertTrue(arbiter.isHeldBy(ControlChannel.WORLD_INTERACT, CombatAssistant.ID), "held for the tap");
        assertFalse(arbiter.isHeldBy(ControlChannel.MOVEMENT, CombatAssistant.ID) && assistant.lease() != null);
        world.self.attackStrength = 0.1f; // the swing spent the charge
        tick(CombatAssistant.SWING_LEASE_TICKS + 2);
        assertTrue(attacks() > 0, "the tap went out through the core");
        assertFalse(arbiter.isHeldBy(ControlChannel.WORLD_INTERACT, CombatAssistant.ID), "released right after");
    }

    @Test
    void theUsersOwnClickWins() {
        engage();
        world.crosshairOnTarget = true;
        world.self.attackStrength = 1f;
        for (int i = 0; i < 8; i++) {
            arbiter.onManualInput(ManualInput.ATTACK_OR_USE); // the user clicks away
            tick(1);
        }
        assertEquals(0, assistant.coPilotSwings());
    }

    @Test
    void aWalkingJobMeansThereIsNothingToCoPilot() {
        Optional<ControlLease> job = arbiter.request(LeaseRequest.of("goal_engine", ControlPriority.GOAL,
                new LeaseListener() {
                    @Override
                    public void onPreempted(ControlLease lease, LeaseEndReason reason, String byModuleId) {
                    }
                }, ControlChannel.MOVEMENT));
        assertTrue(job.isPresent()); // a job walks the player when the fight starts
        engage();
        world.crosshairOnTarget = true;
        world.self.attackStrength = 1f;
        int before = assistant.coPilotGrantTicks();
        tick(8);
        assertTrue(job.get().isActive(), "the job's walk was not preempted");
        assertEquals(before, assistant.coPilotGrantTicks());
        assertEquals(0, assistant.coPilotSwings());
    }

    @Test
    void switchingStylesMidFightHandsControlCleanly() {
        engage();
        assertEquals(CombatAssistant.ID, overlay.activeModuleId());
        assistant.config().controlMode = CombatSettings.ControlMode.TAKEOVER;
        tick(2);
        assertNull(overlay.activeModuleId(), "the grant ended");
        assertNotNull(assistant.lease(), "the takeover took its fight lease");
        assertTrue(arbiter.isHeldBy(ControlChannel.CAMERA, CombatAssistant.ID));

        assistant.config().controlMode = CombatSettings.ControlMode.CO_PILOT;
        tick(2);
        assertNull(assistant.lease(), "the fight lease went back");
        assertFalse(arbiter.isHeldBy(ControlChannel.CAMERA, CombatAssistant.ID));
        assertTrue(assistant.coPilotActive());
        assertEquals(CombatState.ENGAGED, assistant.state());
    }

    @Test
    void theCancelKeyEndsTheFightAndTheGrant() {
        engage();
        assertEquals(CombatAssistant.ID, overlay.activeModuleId());
        assistant.onCancelKey();
        assertEquals(CombatState.DORMANT, assistant.state());
        assertNull(overlay.activeModuleId());
        assertFalse(arbiter.isHeldBy(ControlChannel.WORLD_INTERACT, CombatAssistant.ID));
    }

    @Test
    void aSteadyCoPilotTickAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        facts.onPress(SimKey.FORWARD);
        world.entities.get(TARGET).x = 1.2; // the nudge runs and rests
        world.self.yaw = -60f; // and the co-steer is asked for every tick (no frames: the window never fills)
        engage();
        Runnable body = () -> {
            facts.beginTick();
            overlay.beginTick(false);
            smoother.preTick();
            assistant.onTick();
            smoother.postTick();
        };
        AllocationProbe.measure(50_000, body);
        long bytes = AllocationProbe.measure(50_000, body);
        assertEquals(CombatState.ENGAGED, assistant.state());
        assertTrue(assistant.coPilotGrantTicks() > 50_000);
        assertTrue(bytes < 1_024, "co-pilot tick allocated " + bytes + " bytes over 50k ticks");
    }
}
