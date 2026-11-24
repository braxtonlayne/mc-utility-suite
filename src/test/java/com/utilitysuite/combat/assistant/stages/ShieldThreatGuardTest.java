package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.TradeDriver;
import com.utilitysuite.combat.reflex.ShieldDecision;
import com.utilitysuite.combat.tactics.ShieldDuel;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a fight the shield duel stage is the one owner of USE and also blocks what the shield reflex reports through
 * {@link ShieldThreatFeed}: arrows, blasts and a second attacker. Real {@link CombatAssistant}, real stage, fake
 * world and input backend; the test plays the reflex's part by publishing to the feed once per tick.
 */
class ShieldThreatGuardTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;
    /** World yaw from us toward the target (we face +X). */
    static final float AHEAD = -90f;
    static final float BEHIND = 90f;

    FakeCombatWorld world;
    ActionArbiter arbiter;
    FakeInputBackend backend;
    InputSmoother smoother;
    ModuleRegistry registry;
    CombatAssistant assistant;
    ShieldDuelStageTest.FakeFacts facts;
    ShieldDuelStage stage;
    ShieldThreatFeed feed;
    long[] now = {50_000L};

    // the reflex's report, repeated every tick while reporting
    boolean reporting;
    int threatTicks = Integer.MAX_VALUE;
    float threatDamage;
    float threatBearing = Float.NaN;
    boolean threatStanding;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        TradeDriver.shieldFeed().clear();
        world = new FakeCombatWorld();
        FakeCombatWorld.E t = world.add(TARGET, "Vindicator", 2.5, 64, 0);
        t.yaw = 90f;
        world.self.yaw = AHEAD;
        arbiter = new ActionArbiter();
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(9));
        registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        assistant = new CombatAssistant(world, new AllyRoster(), arbiter, smoother, smoother, () -> now[0]);
        facts = new ShieldDuelStageTest.FakeFacts();
        facts.headYaw = 90f;
        facts.ownReady = true;
        stage = new ShieldDuelStage(facts);
        feed = stage.threatFeed();
        assistant.addStage(stage);
        assistant.setEnabled(true);
        registry.register(assistant);
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            if (reporting) {
                feed.begin(world.self.yaw, ShieldDecision.DEFAULT_FRONT_CONE_DEG);
                feed.offer(threatTicks, threatDamage, threatBearing, threatStanding);
                feed.publish();
            }
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            now[0] += 50;
        }
    }

    void report(int ticks, float damage, float bearing) {
        reporting = true;
        threatTicks = ticks;
        threatDamage = damage;
        threatBearing = bearing;
        threatStanding = false;
    }

    /** A second attacker in reach: the reflex's standing estimate, the same every tick. */
    void reportAttacker(int ticks, float damage, float bearing) {
        report(ticks, damage, bearing);
        threatStanding = true;
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        assertNotNull(assistant.lease());
    }

    /** The target holds its own shield up: it cannot swing, so the block-and-answer loop keeps ours down. */
    void targetTurtles() {
        world.entities.get(TARGET).blocking = true;
        facts.targetUse = 10;
    }

    int attacks() {
        return backend.clicks[SimKey.ATTACK.ordinal()] + backend.presses[SimKey.ATTACK.ordinal()];
    }

    @Test
    void anArrowFromTheFrontIsBlockedInAFight() {
        targetTurtles();
        engage();
        tick(3);
        assertFalse(backend.isDown(SimKey.USE), "nothing to block yet");
        report(6, 5f, AHEAD);
        tick(1);
        assertTrue(backend.isDown(SimKey.USE), "raised inside the raise lead");
        assertTrue(stage.holdingUse());
        assertTrue(stage.guarding());
        report(Integer.MAX_VALUE, 0f, Float.NaN); // it hit the shield or flew past
        tick(1);
        assertFalse(backend.isDown(SimKey.USE), "lowered when the threat is gone");
        assertFalse(stage.guarding());
    }

    @Test
    void aBlastIsBlockedTheSameWay() {
        targetTurtles();
        engage();
        report(ShieldDecision.RAISE_TICKS + ShieldDecision.MARGIN_TICKS, 20f, AHEAD - 40f);
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
    }

    @Test
    void aFarThreatWaitsForTheRaiseLead() {
        targetTurtles();
        engage();
        report(30, 5f, AHEAD);
        tick(5);
        assertFalse(backend.isDown(SimKey.USE), "no shield half a second early: a player raises it as it arrives");
        report(ShieldDecision.RAISE_TICKS + ShieldDecision.MARGIN_TICKS, 5f, AHEAD);
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
    }

    @Test
    void aThreatOutsideTheFrontConeIsNotBlockedAndNeverTurnsUs() {
        targetTurtles();
        engage();
        report(4, 5f, BEHIND);
        tick(6);
        assertFalse(backend.isDown(SimKey.USE), "legit: only what is in front; the fight keeps the camera");
        report(4, 5f, AHEAD + 80f);
        tick(2);
        assertFalse(backend.isDown(SimKey.USE), "80 degrees off is outside the 70 degree cone");
    }

    @Test
    void aSilentReflexMeansNoThreat() {
        targetTurtles();
        engage();
        report(5, 5f, AHEAD);
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
        reporting = false; // module off, setting off: it stops publishing
        tick(ShieldThreatFeed.FRESH_STEPS + 1);
        assertFalse(backend.isDown(SimKey.USE), "a stale report does not hold the shield up");
    }

    @Test
    void neverRaisedIntoAnAxeInReachButStillAgainstAnArrowWhenItIsFar() {
        facts.axe = true;
        engage();
        report(5, 5f, AHEAD);
        tick(5);
        assertFalse(backend.isDown(SimKey.USE), "an axe in reach would disable it for 5 seconds");
        world.entities.get(TARGET).x = 9.0;
        tick(2);
        assertTrue(backend.isDown(SimKey.USE), "out of its reach the arrow is the only threat");
    }

    @Test
    void notRaisedWithoutAUsableShieldOrWithTheBlockSettingOff() {
        targetTurtles();
        facts.ownReady = false;
        engage();
        report(5, 5f, AHEAD);
        tick(3);
        assertFalse(backend.isDown(SimKey.USE));
        facts.ownReady = true;
        tick(1);
        assertTrue(backend.isDown(SimKey.USE));
        stage.configure(true, false); // the reflex owns the shield again: the stage must not press USE
        tick(1);
        assertFalse(backend.isDown(SimKey.USE));
        assertFalse(stage.guarding());
    }

    @Test
    void noSwingWhileGuardingThenTheSwingGoesInAfterTheShieldIsDown() {
        // target in reach, weapon charged, target's own hit far off (it just swung a slow weapon)
        FakeCombatWorld.E t = world.entities.get(TARGET);
        engage();
        t.swingStarted = true;
        t.attackSpeed = 0.5;
        tick(1);
        // an arrow about to land (inside the swing window) keeps the shield up and the swing waiting
        report(ShieldDecision.SWING_WINDOW_TICKS, 5f, AHEAD);
        tick(1);
        int before = attacks();
        tick(4);
        assertTrue(backend.isDown(SimKey.USE));
        assertEquals(before, attacks(), "attack clicks are eaten while the shield is up");
        report(Integer.MAX_VALUE, 0f, Float.NaN);
        tick(1);
        assertFalse(backend.isDown(SimKey.USE));
        tick(ShieldDuel.LOWER_GAP + 3);
        assertTrue(attacks() > before, "the charged hit goes in once the shield is down");
    }

    @Test
    void aSecondAttackerDoesNotStopOurOffence() {
        // a second mob stands in front, always "about to hit": block between swings, lower to swing when charged
        FakeCombatWorld.E t = world.entities.get(TARGET);
        engage();
        t.swingStarted = true;
        t.attackSpeed = 0.5; // the target's own hit is 40 ticks off: the block-and-answer loop stays down
        reportAttacker(4, 4f, AHEAD + 30f);
        int before = attacks();
        int up = 0;
        int down = 0;
        for (int i = 0; i < 30; i++) {
            world.self.attackStrength = i % 12 < 4 ? 0.3f : 1f; // our cooldown after each swing
            tick(1);
            if (backend.isDown(SimKey.USE)) {
                up++;
            } else {
                down++;
            }
        }
        assertTrue(attacks() > before, "we still swing at the target");
        assertTrue(up >= 6, "blocked while our weapon recharges (" + up + " ticks up)");
        assertTrue(down >= 6, "lowered to swing (" + down + " ticks down)");
    }

    @Test
    void aGuardThatNothingHitsRestsLikeTheDuelShield() {
        targetTurtles();
        engage();
        reportAttacker(4, 4f, AHEAD);
        tick(ShieldDuel.MAX_HOLD);
        assertTrue(backend.isDown(SimKey.USE));
        tick(2);
        assertFalse(backend.isDown(SimKey.USE), "no turtling: down for a rest after the longest hold");
        tick(ShieldDuel.REST_TICKS + 2);
        assertTrue(backend.isDown(SimKey.USE), "and up again while the threat stays");
    }

    @Test
    void theSolverHearsAGuardRaise() {
        targetTurtles();
        engage();
        report(5, 5f, AHEAD);
        tick(1);
        assertTrue(TradeDriver.shieldFeed().selfBlocking(assistant.stageContext().clock()),
                "our raised shield is fed to the trade solver whatever raised it");
    }

    @Test
    void guardStateEndsWithTheFight() {
        targetTurtles();
        engage();
        report(5, 5f, AHEAD);
        tick(1);
        assertTrue(stage.guarding());
        reporting = false;
        arbiter.stopAll("test");
        tick(1);
        assertFalse(stage.guarding());
        assertFalse(stage.holdingUse());
        assertFalse(backend.isDown(SimKey.USE));
    }

    // ---- the feed itself --------------------------------------------------------------------------------------------

    @Test
    void theFeedKeepsTheMostUrgentThreatWorthABlock() {
        ShieldThreatFeed f = new ShieldThreatFeed();
        assertFalse(f.take(), "nothing published");
        f.begin(0f, 70.0);
        f.offer(12, 5f, 10f, false);
        f.offer(2, 0.5f, 20f, false); // a snowball: not worth a shield, and it must not hide the arrow
        f.offer(6, 20f, -170f, true);
        f.offer(Integer.MAX_VALUE, 9f, 0f, false);
        f.offer(1, 9f, Float.NaN, false); // no direction: cannot be faced
        f.publish();
        assertTrue(f.take());
        assertEquals(6, f.ticksToImpact());
        assertEquals(20f, f.damage());
        assertEquals(-170f, f.relYawDeg(), 1.0e-4);
        assertEquals(70.0, f.frontConeDeg());
        assertTrue(f.standing());
    }

    @Test
    void theFeedReportsDirectionRelativeToTheView() {
        ShieldThreatFeed f = new ShieldThreatFeed();
        f.begin(170f, 60.0);
        f.offer(3, 5f, -170f, false);
        f.publish();
        assertTrue(f.take());
        assertEquals(20f, f.relYawDeg(), 1.0e-4, "across the +-180 seam");
        assertEquals(60.0, f.frontConeDeg());
    }

    @Test
    void aPublicationGoesStaleAndAnEmptyOneClears() {
        ShieldThreatFeed f = new ShieldThreatFeed();
        f.begin(0f, 70.0);
        f.offer(3, 5f, 0f, false);
        f.publish();
        for (int i = 0; i < ShieldThreatFeed.FRESH_STEPS; i++) {
            assertTrue(f.take());
        }
        assertFalse(f.take(), "stale");
        assertEquals(Integer.MAX_VALUE, f.ticksToImpact());
        f.begin(0f, 70.0);
        f.offer(3, 5f, 0f, false);
        f.publish();
        f.begin(0f, 70.0);
        f.publish();
        assertFalse(f.take(), "an empty publication replaces the threat");
        f.begin(0f, 70.0);
        f.offer(3, 5f, 0f, false);
        f.publish();
        f.clear();
        assertFalse(f.take());
    }

    @Test
    void theFeedAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        int[] i = {0};
        long bytes = AllocationProbe.measure(20_000, () -> {
            feed.begin(AHEAD, 70.0);
            feed.offer(4 + (i[0]++ & 7), 5f, AHEAD, false);
            feed.offer(9, 20f, BEHIND, true);
            feed.publish();
            feed.take();
        });
        assertTrue(bytes < 1_024, "the feed allocated " + bytes + " bytes");
    }
}
