package com.utilitysuite.combat.assistant;

import com.google.gson.JsonObject;
import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.tactics.FeintPlanner;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link FightStage} seam of the {@link CombatAssistant}: no behaviour change without stages, slot short-cuts,
 * every one-tick request, the target request, stage settings, reset, and zero allocation with idle stages.
 */
public class FightStageSeamTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;
    static final int OTHER = 20;

    /**
     * A real Combat Assistant over fakes (the DuelModeTest set-up), with a world view that also reports the
     * target's health (FakeCombatWorld leaves it at 20) so landed hits can be scripted. Shared with the stage tests.
     */
    public static final class Rig {
        public final FakeCombatWorld world = new FakeCombatWorld();
        public final HealthView view = new HealthView(world);
        public final ActionArbiter arbiter = new ActionArbiter();
        public final FakeInputBackend backend = new FakeInputBackend();
        public final InputSmoother smoother = new InputSmoother(backend, new Random(9));
        public final ModuleRegistry registry = new ModuleRegistry();
        public final AllyRoster roster = new AllyRoster();
        public final CombatAssistant assistant;
        public final long[] now = {50_000L};

        public Rig() {
            arbiter.bind(smoother, registry, null);
            assistant = new CombatAssistant(view, roster, arbiter, smoother, smoother, () -> now[0]);
            assistant.setEnabled(true);
            registry.register(assistant);
        }

        public void tick(int n) {
            for (int i = 0; i < n; i++) {
                smoother.preTick();
                arbiter.tick();
                registry.tick();
                smoother.postTick();
                now[0] += 50;
            }
        }

        /** Engages {@code id} through a defensive trigger and settles for three ticks. */
        public void engage(int id) {
            assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", id, id);
            tick(3);
        }

        public boolean down(SimKey k) {
            return backend.isDown(k);
        }
    }

    /** {@link CombatWorld} over a {@link FakeCombatWorld} that also fills the target's health. */
    public static final class HealthView implements CombatWorld {
        final FakeCombatWorld w;

        HealthView(FakeCombatWorld w) {
            this.w = w;
        }

        @Override
        public int localPlayerId() {
            return w.localPlayerId();
        }

        @Override
        public boolean isLivingAlive(int id) {
            return w.isLivingAlive(id);
        }

        @Override
        public boolean isPlayer(int id) {
            return w.isPlayer(id);
        }

        @Override
        public UUID uuidOf(int id) {
            return w.uuidOf(id);
        }

        @Override
        public String describe(int id) {
            return w.describe(id);
        }

        @Override
        public double distanceSqToLocal(int id) {
            return w.distanceSqToLocal(id);
        }

        @Override
        public int crosshairEntityId() {
            return w.crosshairEntityId();
        }

        @Override
        public boolean sample(int targetId, CombatSnapshot out) {
            if (!w.sample(targetId, out)) {
                return false;
            }
            out.targetHealth = w.entities.get(targetId).health;
            return true;
        }

        @Override
        public int bestMeleeSlot(int fallback) {
            return w.bestMeleeSlot(fallback);
        }

        @Override
        public int totemRefillSlot() {
            return w.totemRefillSlot();
        }

        @Override
        public boolean extrasAllowedHere() {
            return w.extrasAllowedHere();
        }

        @Override
        public void scanTargets(double radius, boolean computeLos, TargetCandidates out) {
            w.scanTargets(radius, computeLos, out);
        }

        @Override
        public int inventoryFood() {
            return w.inventoryFood();
        }

        @Override
        public int storedFoodNearby(double radius) {
            return w.storedFoodNearby(radius);
        }

        @Override
        public boolean nearestFoodDrop(double radius, double[] outXyz) {
            return w.nearestFoodDrop(radius, outXyz);
        }
    }

    /** A scripted stage: counts calls and resets, returns {@link #result}, sends the configured requests. */
    static final class Probe implements FightStage {
        final String id;
        final Slot slot;
        boolean result;
        int steps, engages, resets, reads;
        boolean vetoSwing, vetoJump, press;
        HitPlanner.Policy policy;
        int side = Integer.MIN_VALUE;
        double widen;
        int request = -1;
        int targetSeenInStep = -1;
        boolean settingValue = true;
        String settingKey;

        Probe(String id, Slot slot) {
            this.id = id;
            this.slot = slot;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Slot slot() {
            return slot;
        }

        @Override
        public boolean step(CombatSnapshot s, FightContext ctx) {
            steps++;
            targetSeenInStep = ctx.targetId();
            if (vetoSwing) {
                ctx.vetoSwing();
            }
            if (vetoJump) {
                ctx.vetoJump();
            }
            if (policy != null) {
                ctx.hitPolicy(policy);
            }
            if (side != Integer.MIN_VALUE) {
                ctx.forceSide(side);
            }
            if (widen > 0) {
                ctx.widenSpacing(widen);
            }
            if (press) {
                ctx.pressAdvantage();
            }
            if (request >= 0) {
                ctx.requestTarget(request);
            }
            return result;
        }

        @Override
        public void onEngage(FightContext ctx) {
            engages++;
        }

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public void specs(List<SettingSpec> out) {
            if (settingKey != null) {
                out.add(SettingSpec.bool(settingKey, "Probe", true, "Tactics", "probe"));
            }
        }

        @Override
        public void read(ProfileConfig profile, String moduleId) {
            reads++;
            if (settingKey != null) {
                settingValue = profile.getBool(moduleId, settingKey, true);
            }
        }
    }

    Rig rig;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        rig = new Rig();
        rig.world.add(TARGET, "Vindicator", 3, 64, 0);
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    // ---- 1. no behaviour change without stages ----------------------------------------------------------------------

    /** Key, aim and swing trace of a scripted fight: the target circles and steps in and out. */
    static List<String> scriptedTrace(Rig r) {
        r.world.add(TARGET, "Vindicator", 3, 64, 0);
        r.world.self.attackStrength = 0.4f;
        r.engage(TARGET);
        List<String> trace = new ArrayList<>();
        for (int t = 0; t < 120; t++) {
            FakeCombatWorld.E e = r.world.entities.get(TARGET);
            e.x = 3.0 + Math.sin(t * 0.15) * 1.5;
            e.z = Math.cos(t * 0.11) * 2.0;
            r.world.self.attackStrength = Math.min(1f, 0.1f + (t % 13) * 0.08f);
            r.world.self.hazardBack = t > 60 && t < 70;
            r.tick(1);
            StringBuilder sb = new StringBuilder();
            for (SimKey k : SimKey.all()) {
                sb.append(r.backend.isDown(k) ? '1' : '0');
            }
            sb.append(' ').append(Math.round(r.backend.yaw * 100)).append(' ').append(Math.round(r.backend.pitch * 100))
                    .append(' ').append(r.assistant.swings()).append(' ').append(r.backend.presses[SimKey.JUMP.ordinal()]);
            trace.add(sb.toString());
        }
        return trace;
    }

    @Test
    void idleStagesLeaveTheFightUnchanged() {
        List<String> plain = scriptedTrace(rig);
        Services.resetForTests();
        Rig again = new Rig();
        assertEquals(plain, scriptedTrace(again), "the scripted fight is deterministic");
        Services.resetForTests();
        Rig staged = new Rig();
        staged.assistant.addStage(new Probe("a", FightStage.Slot.PRE_AIM));
        staged.assistant.addStage(new Probe("b", FightStage.Slot.MOVEMENT));
        assertEquals(plain, scriptedTrace(staged), "two idle stages change no key, aim or swing");
        assertTrue(rig.assistant.swings() > 3, "the trace includes swings");
    }

    // ---- 2. a stage that takes the tick stops the rest of the loop --------------------------------------------------

    @Test
    void aStageReturningTrueStopsTheRestOfTheLoopInEverySlot() {
        for (FightStage.Slot slot : FightStage.Slot.values()) {
            Services.resetForTests();
            Rig r = new Rig();
            r.world.add(TARGET, "Vindicator", 3.0, 64, 0); // in reach: the built-in loop would swing and strafe
            r.world.self.attackStrength = 1f;
            r.assistant.config().critSync = false;
            Probe taker = new Probe("taker", slot);
            taker.result = true;
            Probe sameSlot = new Probe("same", slot);
            r.assistant.addStage(taker);
            r.assistant.addStage(sameSlot);
            List<Probe> later = new ArrayList<>();
            for (FightStage.Slot other : FightStage.Slot.values()) {
                if (other.ordinal() > slot.ordinal()) {
                    Probe p = new Probe("later-" + other, other);
                    later.add(p);
                    r.assistant.addStage(p);
                }
            }
            r.engage(TARGET);
            r.tick(10);
            assertTrue(taker.steps > 5, slot + ": the stage ran");
            assertEquals(0, sameSlot.steps, slot + ": later stages of the slot are skipped");
            for (Probe p : later) {
                assertEquals(0, p.steps, slot + ": later slots are skipped");
            }
            assertFalse(r.down(SimKey.FORWARD) || r.down(SimKey.BACK) || r.down(SimKey.LEFT) || r.down(SimKey.RIGHT),
                    slot + ": no built-in movement");
            if (slot != FightStage.Slot.MOVEMENT) {
                assertEquals(0, r.assistant.swings(), slot + ": no built-in swing");
            } else {
                assertTrue(r.assistant.swings() > 0, "MOVEMENT runs after the swing");
            }
        }
    }

    // ---- 3. one-tick requests ---------------------------------------------------------------------------------------

    @Test
    void vetoSwingSuppressesTheAttackTap() {
        rig.world.self.attackStrength = 1f;
        rig.assistant.config().critSync = false;
        Probe veto = new Probe("veto", FightStage.Slot.PRE_SWING);
        veto.vetoSwing = true;
        rig.assistant.addStage(veto);
        rig.engage(TARGET);
        rig.tick(20);
        assertEquals(0, rig.assistant.swings());
        assertEquals(0, rig.backend.clicks[SimKey.ATTACK.ordinal()] + rig.backend.presses[SimKey.ATTACK.ordinal()]);
        veto.vetoSwing = false;
        rig.tick(5);
        assertTrue(rig.assistant.swings() > 0, "the veto lasts only while it is renewed");
    }

    @Test
    void vetoJumpSuppressesTheCritHop() {
        rig.assistant.config().hitChoice = HitPlanner.Policy.CRITS;
        rig.assistant.config().critSync = true;
        rig.world.self.attackStrength = 1f;
        rig.engage(TARGET);
        int jumps = 0;
        for (int i = 0; i < 30; i++) {
            rig.tick(1);
            jumps += rig.down(SimKey.JUMP) ? 1 : 0;
        }
        assertTrue(jumps > 0, "control: crit sync hops");

        Services.resetForTests();
        Rig r = new Rig();
        r.world.add(TARGET, "Vindicator", 3, 64, 0);
        r.assistant.config().hitChoice = HitPlanner.Policy.CRITS;
        r.assistant.config().critSync = true;
        r.world.self.attackStrength = 1f;
        Probe veto = new Probe("nohop", FightStage.Slot.PRE_SWING);
        veto.vetoJump = true;
        r.assistant.addStage(veto);
        r.engage(TARGET);
        jumps = 0;
        for (int i = 0; i < 30; i++) {
            r.tick(1);
            jumps += r.down(SimKey.JUMP) ? 1 : 0;
        }
        assertEquals(0, jumps, "vetoJump: no crit hop");
        assertEquals(0, r.backend.presses[SimKey.JUMP.ordinal()]);
    }

    @Test
    void hitPolicyOverridesTheSettingForOneTickOnly() {
        rig.assistant.config().hitChoice = HitPlanner.Policy.KNOCKBACK;
        Probe p = new Probe("policy", FightStage.Slot.PRE_AIM);
        rig.assistant.addStage(p);
        rig.engage(TARGET);
        assertEquals(HitPlanner.Policy.KNOCKBACK, rig.assistant.lastHitPolicy());
        p.policy = HitPlanner.Policy.PLAIN;
        rig.tick(1);
        assertEquals(HitPlanner.Policy.PLAIN, rig.assistant.lastHitPolicy());
        p.policy = null;
        rig.tick(1);
        assertEquals(HitPlanner.Policy.KNOCKBACK, rig.assistant.lastHitPolicy(), "back to the setting next tick");
    }

    @Test
    void forceSideSetsTheStrafeKeyAndSuppressesFeints() {
        Services.resetForTests();
        Rig r = new Rig();
        r.world.addPlayer(TARGET, "Duelist", 3, 64, 0); // feints are for players
        r.assistant.config().feints = true;
        ProFeelLayerTest.ScriptedFeints feints = new ProFeelLayerTest.ScriptedFeints();
        feints.move = FeintPlanner.Move.BREAK_PATTERN;
        feints.strafeOverride = StrafePlanner.RIGHT;
        r.assistant.proFeel().useFeints(feints);
        Probe p = new Probe("side", FightStage.Slot.PRE_AIM);
        r.assistant.addStage(p);
        r.assistant.onDamageEvent(LOCAL, "minecraft:player_attack", TARGET, TARGET);
        r.tick(5);
        assertTrue(feints.plans > 0, "control: feints are planned without the request");
        p.side = StrafePlanner.LEFT;
        r.tick(1);
        int plans = feints.plans;
        r.tick(10);
        assertEquals(plans, feints.plans, "no feints while a side is forced");
        assertTrue(r.down(SimKey.LEFT));
        assertFalse(r.down(SimKey.RIGHT));
        r.world.self.hazardLeft = true;
        r.tick(2);
        assertFalse(r.down(SimKey.LEFT), "the hazard veto still wins over a forced side");
    }

    @Test
    void widenSpacingMovesTheBandAndBypassesTheSolver() {
        TradeDriverTest.ScriptedStep step = new TradeDriverTest.ScriptedStep();
        step.answer(1, 0, 1.0, 0.0); // the solver would step in
        rig.assistant.useTradeDriver(new TradeDriver(step));
        Probe p = new Probe("widen", FightStage.Slot.PRE_AIM);
        rig.assistant.addStage(p);
        rig.engage(TARGET);
        rig.tick(3);
        assertTrue(rig.assistant.tradeTicks() > 0, "control: the solver drives inside the band");
        int trades = rig.assistant.tradeTicks();
        p.widen = 3.0;
        rig.tick(4);
        assertEquals(trades, rig.assistant.tradeTicks(), "a widened band takes the step from the solver");
        assertTrue(rig.down(SimKey.BACK), "3 blocks is inside the widened band: back off");
        assertFalse(rig.down(SimKey.FORWARD));
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
    }

    // ---- 4. requestTarget -------------------------------------------------------------------------------------------

    @Test
    void requestTargetSwitchesAfterTheTickOnlyToALivingNonAlly() {
        rig.world.add(OTHER, "Zombie", -2, 64, 0);
        Probe p = new Probe("req", FightStage.Slot.PRE_AIM);
        rig.assistant.addStage(p);
        rig.engage(TARGET);
        int engages = p.engages;
        p.request = OTHER;
        rig.tick(1);
        assertEquals(TARGET, p.targetSeenInStep, "the step still saw the old target");
        assertEquals(OTHER, rig.assistant.targetId(), "switched after the tick");
        assertEquals(engages + 1, p.engages, "onEngage on the switch");

        p.request = TARGET;
        rig.world.entities.get(TARGET).alive = false;
        rig.tick(1);
        assertEquals(OTHER, rig.assistant.targetId(), "never to a dead entity");

        FakeCombatWorld.E ally = rig.world.add(30, "Friend", 1, 64, 1);
        ally.player = true;
        rig.roster.add(ally.uuid, "Friend", false);
        rig.roster.setNeverTarget(ally.uuid, true);
        p.request = 30;
        rig.tick(2);
        assertEquals(OTHER, rig.assistant.targetId(), "never to a roster ally (seam fix)");
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
    }

    // ---- 5. settings and reset ------------------------------------------------------------------------------------

    @Test
    void stageSettingsAreListedAndReadOnLoadAndChange() {
        Probe p = new Probe("set", FightStage.Slot.PRE_AIM);
        p.settingKey = "probe_setting";
        rig.assistant.addStage(p);
        assertTrue(rig.assistant.settings().stream().anyMatch(s -> s.key().equals("probe_setting")));
        assertTrue(rig.assistant.settings().size() > CombatSettings.specs().size());

        JsonObject root = new JsonObject();
        ProfileConfig profile = new ProfileConfig("test", root);
        profile.set(CombatAssistant.ID, "probe_setting", false);
        rig.assistant.onProfileLoaded(profile);
        assertFalse(p.settingValue, "read on profile load");

        profile.set(CombatAssistant.ID, "probe_setting", true);
        Services.registerModState(new IModState() {
            @Override
            public boolean isCleanScreen() {
                return false;
            }

            @Override
            public void setCleanScreen(boolean active) {
            }

            @Override
            public boolean isAutomationStopped() {
                return false;
            }

            @Override
            public ProfileConfig profile() {
                return profile;
            }

            @Override
            public ProfileConfig global() {
                return profile;
            }

            @Override
            public void markDirty() {
            }
        });
        rig.assistant.onSettingChanged("probe_setting");
        assertTrue(p.settingValue, "read on a setting change");
    }

    @Test
    void resetRunsOnDisengageStopAllAndWorldUnload() {
        Probe p = new Probe("reset", FightStage.Slot.PRE_AIM);
        rig.assistant.addStage(p);
        rig.engage(TARGET);
        rig.assistant.onCancelKey();
        assertEquals(1, p.resets, "disengage");
        rig.now[0] += 20_000;
        rig.engage(TARGET);
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
        rig.assistant.onStopAll();
        assertEquals(2, p.resets, "Stop-All");
        rig.tick(200);
        rig.engage(TARGET);
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
        rig.assistant.onWorldUnload();
        assertEquals(3, p.resets, "world unload");
    }

    // ---- 6. allocation ----------------------------------------------------------------------------------------------

    @Test
    void twoIdleStagesAllocateNothingPerTick() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Probe a = new Probe("a", FightStage.Slot.PRE_AIM);
        Probe b = new Probe("b", FightStage.Slot.MOVEMENT);
        rig.assistant.addStage(a);
        rig.assistant.addStage(b);
        rig.world.self.attackStrength = 0.3f;
        rig.engage(TARGET);
        Runnable body = () -> {
            rig.smoother.preTick();
            rig.assistant.onTick();
            rig.smoother.postTick();
        };
        AllocationProbe.measure(50_000, body);
        long bytes = AllocationProbe.measure(50_000, body);
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
        assertTrue(a.steps > 100_000 && b.steps > 100_000);
        assertTrue(bytes < 1_024, "engaged tick with two idle stages allocated " + bytes + " bytes over 50k ticks");
    }
}
