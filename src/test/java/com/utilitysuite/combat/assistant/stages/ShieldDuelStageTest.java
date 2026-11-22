package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.TradeDriver;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.ShieldDuel;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shield duel stage driven by the real {@link CombatAssistant} with the fake world and input backend. */
class ShieldDuelStageTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;

    /** Plain fake facts. */
    static final class FakeFacts implements ShieldFacts {
        boolean known = true;
        boolean ownReady;
        int ownUse;
        float headYaw = Float.NaN;
        int targetUse;
        boolean axe;
        int disabled = -1;
        int refreshes;

        @Override
        public void refresh(int targetId) {
            refreshes++;
        }

        @Override
        public boolean targetKnown() {
            return known;
        }

        @Override
        public boolean ownShieldReady() {
            return ownReady;
        }

        @Override
        public int ownShieldUseTicks() {
            return ownUse;
        }

        @Override
        public float targetHeadYaw() {
            return headYaw;
        }

        @Override
        public int targetShieldUseTicks() {
            return targetUse;
        }

        @Override
        public boolean targetHoldsAxe() {
            return axe;
        }

        @Override
        public int targetShieldDisabledTicks() {
            return disabled;
        }
    }

    /** Forwards to the assistant's context and records this tick's one-tick requests. */
    static final class Recorder implements FightContext {
        FightContext d;
        boolean vetoSwing;
        boolean vetoJump;
        HitPlanner.Policy policy;
        int side = Integer.MIN_VALUE;
        boolean press;
        int taps;

        void clearTick() {
            vetoSwing = false;
            vetoJump = false;
            policy = null;
            side = Integer.MIN_VALUE;
            press = false;
        }

        @Override
        public CombatSettings settings() {
            return d.settings();
        }

        @Override
        public CombatWorld world() {
            return d.world();
        }

        @Override
        public OpponentModel opponents() {
            return d.opponents();
        }

        @Override
        public int targetId() {
            return d.targetId();
        }

        @Override
        public boolean targetIsPlayer() {
            return d.targetIsPlayer();
        }

        @Override
        public long clock() {
            return d.clock();
        }

        @Override
        public boolean eating() {
            return d.eating();
        }

        @Override
        public int weaponSlot() {
            return d.weaponSlot();
        }

        @Override
        public float lastHitDamage() {
            return d.lastHitDamage();
        }

        @Override
        public void aimAtTarget(CombatSnapshot s) {
            d.aimAtTarget(s);
        }

        @Override
        public void aimAt(float yaw, float pitch, float speed) {
            d.aimAt(yaw, pitch, speed);
        }

        @Override
        public void move(int forward, int side, boolean sprint) {
            d.move(forward, side, sprint);
        }

        @Override
        public void retreatMove(CombatSnapshot s) {
            d.retreatMove(s);
        }

        @Override
        public boolean retreatRoute(CombatSnapshot s) {
            return d.retreatRoute(s);
        }

        @Override
        public void tapAttack() {
            taps++;
            d.tapAttack();
        }

        @Override
        public void hold(SimKey key, boolean down) {
            d.hold(key, down);
        }

        @Override
        public void tap(SimKey key, int holdTicks, int delayTicks) {
            d.tap(key, holdTicks, delayTicks);
        }

        @Override
        public void selectHotbar(int slot) {
            d.selectHotbar(slot);
        }

        @Override
        public void selectWeapon() {
            d.selectWeapon();
        }

        @Override
        public void startEating(CombatSnapshot s, int hotbarSlot) {
            d.startEating(s, hotbarSlot);
        }

        @Override
        public void vetoSwing() {
            vetoSwing = true;
            d.vetoSwing();
        }

        @Override
        public void vetoJump() {
            vetoJump = true;
            d.vetoJump();
        }

        @Override
        public void hitPolicy(HitPlanner.Policy policy) {
            this.policy = policy;
            d.hitPolicy(policy);
        }

        @Override
        public void forceSide(int side) {
            this.side = side;
            d.forceSide(side);
        }

        @Override
        public void widenSpacing(double blocks) {
            d.widenSpacing(blocks);
        }

        @Override
        public void pressAdvantage() {
            press = true;
            d.pressAdvantage();
        }

        @Override
        public void requestTarget(int entityId) {
            d.requestTarget(entityId);
        }
    }

    /** Registered with the assistant: runs the real stage through the recorder. */
    static final class Wrapped implements FightStage {
        final ShieldDuelStage stage;
        final Recorder rec = new Recorder();

        Wrapped(ShieldDuelStage stage) {
            this.stage = stage;
        }

        @Override
        public String id() {
            return stage.id();
        }

        @Override
        public Slot slot() {
            return stage.slot();
        }

        @Override
        public boolean step(CombatSnapshot s, FightContext ctx) {
            rec.d = ctx;
            rec.clearTick();
            return stage.step(s, rec);
        }

        @Override
        public void onEngage(FightContext ctx) {
            stage.onEngage(ctx);
        }

        @Override
        public void reset() {
            stage.reset();
        }

        @Override
        public void specs(List<SettingSpec> out) {
            stage.specs(out);
        }

        @Override
        public void read(ProfileConfig profile, String moduleId) {
            stage.read(profile, moduleId);
        }
    }

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    FakeFacts facts;
    ShieldDuelStage stage;
    Wrapped wrapped;
    long[] now = {50_000L};

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        TradeDriver.shieldFeed().clear();
        world = new FakeCombatWorld();
        FakeCombatWorld.E t = world.add(TARGET, "Vindicator", 2.5, 64, 0);
        t.yaw = 90f; // facing -X: toward us
        world.self.yaw = -90f; // we face +X: toward it
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        facts = new FakeFacts();
        facts.headYaw = 90f;
        stage = new ShieldDuelStage(facts);
        wrapped = new Wrapped(stage);
        assistant.addStage(wrapped);
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

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        assertNotNull(assistant.lease());
    }

    int attacks() {
        return backend.clicks[SimKey.ATTACK.ordinal()] + backend.presses[SimKey.ATTACK.ordinal()];
    }

    void raiseTheirShield() {
        world.entities.get(TARGET).blocking = true;
        facts.targetUse = 10;
    }

    void lowerTheirShield() {
        world.entities.get(TARGET).blocking = false;
        facts.targetUse = 0;
    }

    @Test
    void settingsAreAppendedWithUniqueKeysInTheShieldGroup() {
        List<SettingSpec> specs = assistant.settings();
        Set<String> keys = new HashSet<>();
        List<SettingSpec> ours = new ArrayList<>();
        for (SettingSpec sp : specs) {
            assertTrue(keys.add(sp.key()), "unique key " + sp.key());
            if (sp.key().startsWith("shield_duel")) {
                ours.add(sp);
            }
        }
        assertEquals(2, ours.size());
        for (SettingSpec sp : ours) {
            assertEquals("Shield & weapons", sp.group());
            assertEquals(Boolean.TRUE, sp.defaultValue());
        }
    }

    @Test
    void noAttackWhileTheTargetBlocksFrontallyThenATapOnTheTickItLowers() {
        raiseTheirShield();
        engage();
        int before = attacks();
        tick(20);
        assertEquals(before, attacks(), "no swing into a frontal raised shield");
        assertTrue(stage.vetoes() >= 20);
        assertTrue(wrapped.rec.vetoSwing);
        assertTrue(wrapped.rec.vetoJump, "no crit hop into a shield either");
        assertTrue(wrapped.rec.side == ShieldDuel.LEFT || wrapped.rec.side == ShieldDuel.RIGHT,
                "circles toward the open side");

        lowerTheirShield();
        tick(1);
        assertEquals(1, stage.drops(), "the tap goes in on the tick it comes down");
        assertEquals(1, wrapped.rec.taps);
        tick(1);
        assertTrue(attacks() > before, "and reaches the game on the next tick");
    }

    @Test
    void swingsNormallyOncePastNinetyDegrees() {
        raiseTheirShield();
        facts.headYaw = -90f; // it looks away from us (+X): we stand behind the shield
        world.entities.get(TARGET).yaw = -90f;
        engage();
        int before = attacks();
        tick(10);
        assertTrue(attacks() > before, "behind the shield the hit lands: the planner swings");
        assertEquals(0, stage.vetoes());
    }

    @Test
    void earlyRaiseIsNotVetoed() {
        engage();
        facts.targetUse = 2; // raising: not blocking for another 3 ticks (isBlocking needs 5)
        tick(1);
        assertFalse(wrapped.rec.side == ShieldDuel.LEFT || wrapped.rec.side == ShieldDuel.RIGHT);
        assertEquals(0, stage.vetoes(), "a hit inside the raise delay lands");
        facts.targetUse = 4; // our tap arrives on tick 5
        tick(1);
        assertEquals(1, stage.vetoes());
    }

    @Test
    void circlingHonoursHazards() {
        raiseTheirShield();
        world.self.hazardLeft = true;
        world.self.hazardRight = true;
        engage();
        tick(2);
        assertEquals(ShieldDuel.NONE, wrapped.rec.side, "hazards both sides: no circling");
    }

    @Test
    void ourShieldGoesUpAgainstAChargedSwordAndIsReleasedOnDisengage() {
        facts.ownReady = true;
        engage();
        tick(1);
        assertTrue(stage.holdingUse(), "an unread opponent in reach counts as charged");
        assertTrue(backend.isDown(SimKey.USE), "USE held through the lease");
        world.entities.get(TARGET).alive = false;
        tick(1);
        assertEquals(CombatState.DORMANT, assistant.state());
        assertFalse(backend.isDown(SimKey.USE), "released on disengage");
        assertFalse(stage.holdingUse());
    }

    @Test
    void ourShieldIsReleasedOnStopAll() {
        facts.ownReady = true;
        engage();
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
        assistant.onStopAll();
        tick(1);
        assertFalse(backend.isDown(SimKey.USE), "Stop-All releases USE");
        assertFalse(stage.holdingUse());
        assertNull(assistant.lease());
    }

    @Test
    void ourShieldComesDownWhenTheSettingGoesOff() {
        facts.ownReady = true;
        engage();
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
        stage.configure(true, false);
        tick(1);
        assertFalse(backend.isDown(SimKey.USE), "released when the stage stops blocking");
        stage.configure(true, true);
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
        stage.configure(false, true);
        tick(1);
        assertFalse(backend.isDown(SimKey.USE), "released when the whole stage is off");
    }

    @Test
    void neverRaisedAgainstAnAxe() {
        facts.ownReady = true;
        facts.axe = true;
        engage();
        tick(20);
        assertFalse(backend.isDown(SimKey.USE));
        assertFalse(stage.holdingUse());
    }

    @Test
    void blockAndAnswerThroughTheAssistant() {
        facts.ownReady = true;
        world.self.attackStrength = 1f;
        engage();
        tick(1);
        assertTrue(backend.isDown(SimKey.USE), "up before their charged hit");
        int before = attacks();
        tick(5);
        assertEquals(before, attacks(), "no swings while our shield is up (attack clicks would be eaten)");
        // their hit lands on our shield: a sword swing (12.5-tick cooldown) restarts their charge
        FakeCombatWorld.E t = world.entities.get(TARGET);
        t.swingStarted = true;
        t.attackSpeed = 1.6;
        tick(1);
        assertFalse(backend.isDown(SimKey.USE), "lowered inside their cooldown");
        tick(4);
        assertTrue(attacks() > before, "answered inside their cooldown");
        tick(6);
        assertTrue(backend.isDown(SimKey.USE), "up again before their next charged hit");
    }

    @Test
    void axeDisableWindowPressesWithCrits() {
        raiseTheirShield();
        engage();
        facts.disabled = 100; // the shield-break sound of our axe hit was heard
        lowerTheirShield();
        tick(1);
        long until = assistant.opponents().shieldDisabledUntil(TARGET);
        assertTrue(until > 0, "window recorded");
        facts.disabled = -1;
        for (int i = 0; i < 90; i++) {
            tick(1);
            assertEquals(HitPlanner.Policy.CRITS, wrapped.rec.policy, "crits for the window, tick " + i);
            assertTrue(wrapped.rec.press, "no feints for the window");
        }
        tick(15);
        assertNull(wrapped.rec.policy, "window over");
        assertFalse(wrapped.rec.press);
    }

    @Test
    void feedsTheSolverEachTick() {
        raiseTheirShield();
        engage();
        tick(1);
        TradeDriver.ShieldFeed f = TradeDriver.shieldFeed();
        long clock = wrapped.rec.clock();
        assertTrue(f.oppShieldUp(clock));
        assertFalse(f.selfBlocking(clock));
        assertFalse(f.oppShieldUp(clock + 1), "stale on the next tick");
    }
}
