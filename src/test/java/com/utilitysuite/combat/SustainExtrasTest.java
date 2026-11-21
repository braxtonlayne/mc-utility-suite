package com.utilitysuite.combat;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.SustainExtras;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.ServerAllowList;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.utilitysuite.combat.assistant.SustainExtras.*;
import static org.junit.jupiter.api.Assertions.*;

/** Whole-inventory consumables, offhand totem keeper, jump reset and the shared allow list. */
class SustainExtrasTest {

    // ---- pure decisions -------------------------------------------------------------------------------------------

    @Test
    void consumableRanking() {
        assertTrue(better(KIND_ENCHANTED_GOLDEN_APPLE, KIND_GOLDEN_APPLE));
        assertTrue(better(KIND_GOLDEN_APPLE, KIND_HEALING_POTION));
        assertTrue(better(KIND_HEALING_POTION, KIND_REGEN_POTION));
        assertTrue(better(KIND_REGEN_POTION, KIND_NONE));
        assertFalse(better(KIND_NONE, KIND_NONE));
        assertFalse(better(KIND_GOLDEN_APPLE, KIND_GOLDEN_APPLE), "ties keep the first found");
        assertEquals(0, rank(99));
    }

    @Test
    void pullTargetsEmptyHotbarSlotAndNeverTheWeapon() {
        assertEquals(4, hotbarSlotForPull(4, 0));
        assertEquals(8, hotbarSlotForPull(-1, 0));
        assertEquals(7, hotbarSlotForPull(-1, 8));
        assertEquals(8, hotbarSlotForPull(2, 2), "an 'empty' weapon slot is not used");
    }

    @Test
    void pullOnlyWhenHotbarLacksAnItemAndInventoryHasOne() {
        assertTrue(shouldPull(true, true, -1, 20, false, false));
        assertFalse(shouldPull(false, true, -1, 20, false, false), "setting off");
        assertFalse(shouldPull(true, false, -1, 20, false, false), "not allowed on this server");
        assertFalse(shouldPull(true, true, 3, 20, false, false), "hotbar already has one");
        assertFalse(shouldPull(true, true, -1, -1, false, false), "nothing in the inventory");
        assertFalse(shouldPull(true, true, -1, 5, false, false), "hotbar indices are not pulled");
        assertFalse(shouldPull(true, true, -1, 20, true, false), "a pull is in flight");
        assertFalse(shouldPull(true, true, -1, 20, false, true), "a screen is open");
    }

    @Test
    void totemReplenishConditions() {
        assertTrue(shouldReplenishTotem(true, true, true, false, 14, false, false, 0));
        assertTrue(shouldReplenishTotem(true, true, true, false, 3, false, false, 0), "hotbar totem too");
        assertFalse(shouldReplenishTotem(false, true, true, false, 14, false, false, 0), "disarmed");
        assertFalse(shouldReplenishTotem(true, false, true, false, 14, false, false, 0), "setting off");
        assertFalse(shouldReplenishTotem(true, true, false, false, 14, false, false, 0), "not allowed");
        assertFalse(shouldReplenishTotem(true, true, true, true, 14, false, false, 0), "offhand has one");
        assertFalse(shouldReplenishTotem(true, true, true, false, -1, false, false, 0), "no totem left");
        assertFalse(shouldReplenishTotem(true, true, true, false, 14, true, false, 0), "screen open");
        assertFalse(shouldReplenishTotem(true, true, true, false, 14, false, true, 0), "user on the hotbar");
        assertFalse(shouldReplenishTotem(true, true, true, false, 14, false, false, 3), "cooling down");
    }

    @Test
    void jumpResetTrigger() {
        assertTrue(shouldJumpReset(true, true, true, true, true, false));
        assertFalse(shouldJumpReset(false, true, true, true, true, false), "default off");
        assertFalse(shouldJumpReset(true, false, true, true, true, false), "not allowed");
        assertFalse(shouldJumpReset(true, true, false, true, true, false), "not engaged");
        assertFalse(shouldJumpReset(true, true, true, false, true, false), "not a knockback hit");
        assertFalse(shouldJumpReset(true, true, true, true, false, false), "airborne");
        assertFalse(shouldJumpReset(true, true, true, true, true, true), "eating");
    }

    @Test
    void sharedAllowList() {
        assertTrue(ServerAllowList.isAllowed(true, "", ""));
        assertTrue(ServerAllowList.isAllowed(false, "LocalHost:25565", ServerAllowList.DEFAULT_LIST));
        assertTrue(ServerAllowList.isAllowed(false, "my.server:25570", "my.server:25570"));
        assertFalse(ServerAllowList.isAllowed(false, "my.server:25566", "my.server:25570"));
        assertFalse(ServerAllowList.isAllowed(false, "play.example.net", ServerAllowList.DEFAULT_LIST));
        assertFalse(ServerAllowList.isAllowed(false, null, ServerAllowList.DEFAULT_LIST));
    }

    // ---- through the real arbiter and smoother --------------------------------------------------------------------

    static final int LOCAL = 1;
    static final int TARGET = 10;

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
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
    void keeperRefillsOffhandWhileDormant() {
        world.self.totemInvSlot = 20;
        tick(30);
        assertFalse(backend.containerClicks.isEmpty(), "totem moved without an engagement");
        int[] c = backend.containerClicks.get(0);
        assertEquals(20, c[0]);
        assertEquals(40, c[1], "offhand SWAP button");
        assertEquals(IContainerClicker.Click.SWAP.ordinal(), c[2]);
        // the fake's offhand never fills, so the keeper retries after its cooldown; every click is the same refill
        assertTrue(assistant.totemRefills() >= 1);
        for (int[] k : backend.containerClicks) {
            assertEquals(20, k[0]);
        }
    }

    @Test
    void autopilotKeepsATotemWhileTheModuleIsOff() {
        world.self.totemInvSlot = 20;
        assistant.setEnabled(false);
        com.utilitysuite.core.autopilot.Autopilot ap = new com.utilitysuite.core.autopilot.Autopilot();
        ap.onProfileLoaded(new com.utilitysuite.core.api.ProfileConfig("t", new com.google.gson.JsonObject()));
        Services.registerAutopilot(ap);
        for (int i = 0; i < 30; i++) {
            tick(1);
            assistant.autopilotTick();
        }
        assertTrue(backend.containerClicks.isEmpty(), "autopilot off: nothing moves");
        ap.setEnabled(true);
        for (int i = 0; i < 30; i++) {
            tick(1);
            assistant.autopilotTick();
        }
        assertFalse(backend.containerClicks.isEmpty(), "autopilot on: the totem goes to the offhand");
        int[] c = backend.containerClicks.get(0);
        assertEquals(20, c[0]);
        assertEquals(40, c[1], "offhand SWAP button");
    }

    @Test
    void keeperIdleWhenNotAllowedOrOffhandFull() {
        world.self.totemInvSlot = 20;
        world.allowed = false;
        tick(30);
        assertTrue(backend.containerClicks.isEmpty());
        world.allowed = true;
        world.self.offhandTotem = true;
        tick(30);
        assertTrue(backend.containerClicks.isEmpty());
    }

    @Test
    void pullsGoldenAppleFromMainInventoryAtLowHealth() {
        world.self.health = 6f;
        world.self.offhandTotem = true;
        world.self.sustainInvSlot = 22;
        world.self.emptyHotbarSlot = 5;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(20);
        assertFalse(backend.containerClicks.isEmpty(), "a SWAP into the hotbar was performed");
        int[] c = backend.containerClicks.get(0);
        assertEquals(22, c[0]);
        assertEquals(5, c[1], "into the empty hotbar slot");
    }

    @Test
    void jumpResetTapsJumpOnAHitWhileEngagedOnGround() {
        assistant.config().jumpReset = true;
        world.self.offhandTotem = true;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(2);
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        assertEquals(1, assistant.jumpResets());
        assertTrue(smoother.isKeyHeldByAutomation(SimKey.JUMP), "jump tap scheduled under the combat lease");
        tick(1);
        assertTrue(backend.down[SimKey.JUMP.ordinal()], "jump is down on the hit tick");
    }

    @Test
    void jumpResetOffByDefaultAndWhenNotAllowed() {
        world.self.offhandTotem = true;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(2);
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        assistant.config().jumpReset = true;
        world.allowed = false;
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        assertEquals(0, assistant.jumpResets());
    }
}
