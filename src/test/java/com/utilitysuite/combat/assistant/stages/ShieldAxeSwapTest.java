package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSnapshot;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The duel target draws an axe while our shield is up: an axe hit on a raised shield disables it for 5 seconds. The
 * stage lowers it a reaction margin after the swap, keeps it down against that target's melee while the axe is held,
 * and spends it with the axe in reach only on a single impact that is the bigger hit. Real {@link CombatAssistant},
 * real stage, fake world and input backend; the test plays the shield reflex by publishing to the feed.
 */
class ShieldAxeSwapTest {

    static final int LOCAL = 1;
    static final int TARGET = 10;
    /** World yaw from us toward the target (we face +X). */
    static final float AHEAD = -90f;

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

    boolean reporting;
    int threatTicks = Integer.MAX_VALUE;
    float threatDamage;
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
                feed.offer(threatTicks, threatDamage, AHEAD, threatStanding);
                feed.publish();
            }
            smoother.preTick();
            arbiter.tick();
            registry.tick();
            smoother.postTick();
            now[0] += 50;
        }
    }

    /** A single impact from the front (an arrow, a blast), the same report every tick. */
    void report(int ticks, float damage) {
        reporting = true;
        threatTicks = ticks;
        threatDamage = damage;
        threatStanding = false;
    }

    void reportNothing() {
        report(Integer.MAX_VALUE, 0f);
    }

    void engage() {
        assistant.onDamageEvent(LOCAL, "minecraft:mob_attack", TARGET, TARGET);
        tick(1);
        assertNotNull(assistant.lease());
    }

    boolean up() {
        return backend.isDown(SimKey.USE);
    }

    /** Engaged with our shield up against the target's sword (an unread opponent in reach counts as charged). */
    void blockingItsSword() {
        engage();
        tick(1);
        assertTrue(up(), "up against the sword");
        assertFalse(stage.disablerSeen());
    }

    /** Ticks {@code n} times and requires the shield to keep one state throughout. */
    void tickAll(int n, boolean wantUp, String why) {
        for (int i = 0; i < n; i++) {
            tick(1);
            assertTrue(up() == wantUp, why + " (tick " + i + ")");
        }
    }

    // ---- swap while blocking ----------------------------------------------------------------------------------------

    @Test
    void theSwapToAnAxeLowersARaisedShieldAfterTheReactionMarginAndKeepsItDown() {
        blockingItsSword();
        facts.axe = true;
        tickAll(ShieldDuel.REACTION_MARGIN, true, "not on the tick of the swap: a person needs a moment to see it");
        tick(1);
        assertFalse(up(), "lowered once the swap is seen");
        assertFalse(stage.holdingUse());
        assertTrue(stage.disablerSeen());
        tickAll(60, false, "never raised against its melee while the axe is held");
    }

    @Test
    void anAxeThatWasAlreadyOutNeedsNoReaction() {
        facts.axe = true;
        engage();
        assertTrue(stage.disablerSeen(), "the shield is down: nothing to react with, it just stays down");
        tickAll(20, false, "never raised into an axe");
    }

    // ---- the swap back ----------------------------------------------------------------------------------------------

    @Test
    void theSwapBackToASwordBringsTheShieldBackAfterTheSameMargin() {
        blockingItsSword();
        facts.axe = true;
        tick(ShieldDuel.REACTION_MARGIN + 1);
        assertFalse(up());
        facts.axe = false;
        tickAll(ShieldDuel.REACTION_MARGIN, false, "not on the tick the axe is put away");
        tick(1);
        assertTrue(up(), "block-and-answer again against the sword");
        assertFalse(stage.disablerSeen());
    }

    @Test
    void aFlickBackToTheAxeDoesNotBaitTheShieldUp() {
        blockingItsSword();
        facts.axe = true;
        tick(ShieldDuel.REACTION_MARGIN + 1);
        assertFalse(up());
        for (int i = 0; i < 6; i++) {
            facts.axe = false; // sword shown for one tick, then the axe again
            tick(1);
            assertFalse(up(), "sword tick " + i);
            facts.axe = true;
            tick(1);
            assertFalse(up(), "axe tick " + i);
        }
        assertTrue(stage.disablerSeen());
    }

    @Test
    void aNewFightStartsWithoutTheOldAxe() {
        blockingItsSword();
        facts.axe = true;
        tick(ShieldDuel.REACTION_MARGIN + 1);
        assertTrue(stage.disablerSeen());
        arbiter.stopAll("test");
        tick(1);
        assertFalse(stage.disablerSeen(), "forgotten with the fight");
        assertFalse(up());
    }

    // ---- an axe out of reach ----------------------------------------------------------------------------------------

    @Test
    void anAxeOutOfReachDoesNotLowerTheShieldForAnArrow() {
        engage();
        world.entities.get(TARGET).x = 9.0; // well past its reach of us
        tick(2);
        assertFalse(up(), "no melee to block from there");
        report(5, 5f);
        tick(1);
        assertTrue(up(), "up for the arrow");
        assertTrue(stage.guarding());
        facts.axe = true;
        tickAll(ShieldDuel.REACTION_MARGIN + 6, true, "the axe cannot reach the shield: the arrow is the only threat");
        assertTrue(stage.disablerSeen());
        // it walks in with the axe: an ordinary arrow is not worth the shield any more
        world.entities.get(TARGET).x = 2.5;
        tick(1);
        assertFalse(up(), "lowered as the axe comes into reach");
        reportNothing();
        tickAll(20, false, "and no shield against the axe's melee");
    }

    // ---- an axe and an impact together ------------------------------------------------------------------------------

    @Test
    void anOrdinaryArrowIsTakenRatherThanLosingTheShieldToTheAxe() {
        blockingItsSword();
        report(5, 5f);
        tick(1);
        assertTrue(up());
        facts.axe = true;
        tickAll(ShieldDuel.REACTION_MARGIN, true, "reaction margin");
        tick(1);
        assertFalse(up(), "5 damage costs less than 5 seconds without a shield");
        assertFalse(stage.guarding());
        tickAll(10, false, "stays down with the arrow still reported");
    }

    @Test
    void aBlastIsBlockedThroughTheAxeThenTheShieldComesDown() {
        blockingItsSword();
        report(6, 20f);
        tick(1);
        assertTrue(up());
        facts.axe = true;
        tickAll(ShieldDuel.REACTION_MARGIN + 4, true, "the blast is the bigger hit: held until it has landed");
        assertTrue(stage.disablerSeen());
        assertTrue(stage.guarding());
        reportNothing(); // it went off against the shield
        tick(1);
        assertFalse(up(), "down as soon as the blast is over");
        tickAll(20, false, "and not up again against the axe");
    }

    @Test
    void anArrowWorthHalfOurHealthIsBlockedThroughTheAxe() {
        world.self.health = 16f;
        blockingItsSword();
        report(5, 8f); // below an axe's own hit, but half of what we have left
        tick(1);
        facts.axe = true;
        tickAll(ShieldDuel.REACTION_MARGIN + 4, true, "8 damage at 8 hearts is the bigger hit");
        world.self.health = 20f;
        tick(1);
        assertFalse(up(), "the same arrow at full health is not");
    }

    @Test
    void withTheAxeAlreadyOutTheShieldGoesUpOnlyForABigImpactItCanStillStop() {
        facts.axe = true;
        engage();
        report(ShieldDuel.RAISE_DELAY - 2, 20f);
        tickAll(6, false, "too late to block: a raise would only hand the axe a free disable");
        report(ShieldDuel.RAISE_DELAY + 1, 20f);
        tick(1);
        assertTrue(up(), "raised for a blast it can still stop");
        assertTrue(stage.guarding());
        reportNothing();
        tick(1);
        assertFalse(up());
    }

    @Test
    void aSecondAttackerNeverHoldsTheShieldUpForTheAxe() {
        facts.axe = true;
        engage();
        report(4, 20f);
        threatStanding = true; // it stays in reach: blocking it would pin the shield up in front of the axe
        tickAll(20, false, "no standing block with an axe in reach");
    }

    // ---- allocation -------------------------------------------------------------------------------------------------

    @Test
    void theSwapRuleAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ShieldDuelStageTest.FakeFacts ff = new ShieldDuelStageTest.FakeFacts();
        ff.ownReady = true;
        ff.headYaw = 90f;
        ShieldDuelStage st = new ShieldDuelStage(ff);
        ShieldThreatFeed fd = st.threatFeed();
        ShieldDuelAllocationTest.CountingContext ctx = new ShieldDuelAllocationTest.CountingContext();
        CombatSnapshot s = new CombatSnapshot();
        s.py = 64;
        s.eyeY = 65.62;
        s.yaw = AHEAD;
        s.attackStrength = 1f;
        s.crosshairOnTarget = true;
        s.health = 20f;
        s.ty = 64;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = -0.3;
        s.maxZ = 0.3;
        int[] k = {0};
        Runnable body = () -> {
            int i = k[0]++;
            ctx.clock = i;
            double x = (i & 64) != 0 ? 9.0 : 2.5; // in and out of the axe's reach
            s.tx = x;
            s.minX = x - 0.3;
            s.maxX = x + 0.3;
            ff.axe = (i & 12) != 0 && (i & 256) == 0; // swaps every few ticks, flicks included
            s.health = (i & 512) != 0 ? 6f : 20f;
            fd.begin(AHEAD, ShieldDecision.DEFAULT_FRONT_CONE_DEG);
            if ((i & 16) != 0) {
                fd.offer(3 + (i & 3), (i & 32) != 0 ? 20f : 5f, AHEAD, (i & 128) != 0);
            }
            fd.publish();
            st.step(s, ctx);
            if ((i & 2047) == 0) {
                st.onEngage(ctx);
            }
        };
        for (int w = 0; w < 20_000; w++) {
            body.run(); // warm-up: JIT and every branch
        }
        long bytes = AllocationProbe.measure(100_000, body);
        assertTrue(bytes < 1_024, "step allocated " + bytes + " bytes over 100k ticks");
        assertTrue(ctx.calls != 0);
    }
}
