package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.GearRead;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.TradeInput;
import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TradeDriver#useReads}: learned reach, post-hit stun and the retreat read reach the solver input. */
class TradeDriverReadsTest {

    static final int ID = 10;
    static final long NOW = 500;

    TradeDriver driver;
    OpponentModel opponents;
    CombatSnapshot s;

    @BeforeEach
    void setUp() {
        driver = new TradeDriver(new TradeDriverTest.ScriptedStep());
        opponents = new OpponentModel();
        s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.tx = 3;
        s.ty = 64;
        s.onGround = true;
        s.attackStrength = 1f;
    }

    void fill() {
        driver.fill(s, opponents, ID, NOW, true, true, 7, 2.5, true);
    }

    void learnShortReach() {
        for (int i = 0; i < 4; i++) {
            opponents.noteHitDistance(ID, i, 2.8);
        }
        opponents.noteOwnHit(ID, 100);
        opponents.noteSwing(ID, 106, 1.6);
        opponents.noteOwnHit(ID, 200);
        opponents.noteSwing(ID, 207, 1.6);
    }

    @Test
    void readsOffKeepThePlainModel() {
        learnShortReach();
        fill();
        TradeInput in = driver.input();
        assertEquals(3.0, in.oReach, 1e-9);
        assertFalse(in.hoverRelative);
        assertEquals(0, in.oHitStun);
    }

    @Test
    void learnedReachAndStunFeedTheSolver() {
        driver.useReads(true, false);
        fill();
        assertEquals(3.0, driver.input().oReach, 1e-9, "nothing learned yet");
        assertFalse(driver.input().hoverRelative);
        learnShortReach();
        fill();
        TradeInput in = driver.input();
        assertEquals(2.8, in.oReach, 1e-6);
        assertTrue(in.hoverRelative);
        assertEquals(5, in.oHitStun, "min(6, 7) - 1");
        driver.fill(s, opponents, ID, NOW, false, true, 7, 2.5, true);
        assertEquals(3.0, driver.input().oReach, 1e-9, "mobs keep the plain model");
    }

    @Test
    void aRetreatingOpponentIsNoImmediateThreat() {
        driver.useReads(false, true);
        for (int t = 0; t < 6; t++) {
            // it stands at x=3 facing +x (away from us) and runs off
            opponents.observeRetreat(ID, t, -90f, 3, 0, 0.26, 0, 0, 0);
        }
        assertTrue(opponents.retreating(ID));
        fill();
        TradeInput in = driver.input();
        assertEquals(in.horizon + 1, in.oReadyIn, "its hit is never due inside the horizon");
        assertEquals(GearRead.MIN_WEIGHT, in.takenWeight, 1e-9, "a hit taken weighs the least");
        driver.useReads(false, false);
        fill();
        assertEquals(2.5, driver.input().takenWeight, 1e-9, "setting off: read like anyone else");
    }

    @Test
    void fillWithReadsAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        driver.useReads(true, true);
        learnShortReach();
        long[] tick = {0};
        long bytes = AllocationProbe.measure(50_000, () -> {
            long t = tick[0]++;
            s.tvx = (t & 8) == 0 ? 0.26 : -0.2;
            driver.fill(s, opponents, ID, NOW + t, true, true, 7, 2.5, true);
        });
        assertTrue(bytes < 1_024, "TradeDriver.fill with reads allocated " + bytes + " bytes");
    }
}
