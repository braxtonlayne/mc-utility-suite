package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.jobs.fieldwork.DebrisBlastCycle.Abort;
import com.utilitysuite.jobs.fieldwork.DebrisBlastCycle.Charge;
import com.utilitysuite.jobs.fieldwork.DebrisBlastCycle.Observation;
import com.utilitysuite.jobs.fieldwork.DebrisBlastCycle.Phase;
import com.utilitysuite.world.blast.BlastMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebrisBlastCycleTest {

    private static Observation ready() {
        Observation o = new Observation();
        o.nether = true;
        o.feetY = 15;
        o.health = 20f;
        o.hasBed = true;
        o.hasTnt = true;
        o.hasIgniter = true;
        o.hasShieldBlock = true;
        o.distanceToCharge = 4.0;
        o.exposure = 0.0;
        return o;
    }

    private static Phase done(DebrisBlastCycle c, Observation o) {
        o.stepDone = true;
        return c.step(o);
    }

    @Test
    void bedCycleRunsPocketChargeRetreatShieldTriggerSettleThenMines() {
        DebrisBlastCycle.Config cfg = new DebrisBlastCycle.Config();
        cfg.settleTicks = 3;
        DebrisBlastCycle c = new DebrisBlastCycle(cfg);
        Observation o = ready();
        c.start(o);
        assertEquals(Phase.TUNNEL, c.phase());
        o.blocksSincePocket = 2;
        assertEquals(Phase.TUNNEL, c.step(o));
        o.blocksSincePocket = cfg.pocketEvery;
        assertEquals(Phase.POCKET, c.step(o));
        assertEquals(Charge.BED, c.charge());
        assertEquals(Phase.PLACE_CHARGE, done(c, o));
        assertEquals(Phase.RETREAT, done(c, o));
        assertEquals(Phase.PLACE_SHIELD, done(c, o));
        assertEquals(Phase.TRIGGER, done(c, o));
        assertEquals(Phase.SETTLE, done(c, o));
        o.exposedLava = 2;
        o.debrisVisible = 1;
        c.step(o);
        c.step(o);
        assertEquals(Phase.PLUG_LAVA, c.step(o));
        assertEquals(1, c.cycles());
        o.exposedLava = 0;
        assertEquals(Phase.MINE_DEBRIS, c.step(o));
        o.debrisVisible = 0;
        assertEquals(Phase.TUNNEL, c.step(o));
    }

    @Test
    void tntIsLitBeforeTheRetreat() {
        DebrisBlastCycle.Config cfg = new DebrisBlastCycle.Config();
        cfg.preferBed = false;
        cfg.settleTicks = 1;
        DebrisBlastCycle c = new DebrisBlastCycle(cfg);
        Observation o = ready();
        c.start(o);
        o.blocksSincePocket = cfg.pocketEvery;
        c.step(o);
        assertEquals(Charge.TNT, c.charge());
        assertEquals(Phase.PLACE_CHARGE, done(c, o));
        assertEquals(Phase.TRIGGER, done(c, o));
        assertEquals(Phase.RETREAT, done(c, o));
        assertEquals(Phase.PLACE_SHIELD, done(c, o));
        o.distanceToCharge = 7.0;
        assertEquals(Phase.SETTLE, done(c, o));
        assertEquals(Phase.TUNNEL, c.step(o));
    }

    @Test
    void refusesOutsideTheNetherOrOffLevel() {
        DebrisBlastCycle c = new DebrisBlastCycle(new DebrisBlastCycle.Config());
        Observation o = ready();
        o.nether = false;
        c.start(o);
        assertEquals(Abort.NOT_NETHER, c.abortReason());
        o.nether = true;
        o.feetY = 40;
        c.start(o);
        assertEquals(Abort.WRONG_LEVEL, c.abortReason());
    }

    @Test
    void abortsOnLowHealthLavaAndMissingShield() {
        DebrisBlastCycle.Config cfg = new DebrisBlastCycle.Config();
        DebrisBlastCycle c = new DebrisBlastCycle(cfg);
        Observation o = ready();
        c.start(o);
        o.health = 6f;
        assertEquals(Phase.ABORTED, c.step(o));
        assertEquals(Abort.LOW_HEALTH, c.abortReason());

        o = ready();
        c.start(o);
        o.lavaNearFeet = true;
        c.step(o);
        assertEquals(Abort.LAVA_AT_FEET, c.abortReason());

        o = ready();
        c.start(o);
        o.hasShieldBlock = false;
        o.blocksSincePocket = cfg.pocketEvery;
        c.step(o);
        assertEquals(Abort.NO_SHIELD_BLOCK, c.abortReason());

        o = ready();
        c.start(o);
        o.hasBed = false;
        o.hasTnt = false;
        o.blocksSincePocket = cfg.pocketEvery;
        c.step(o);
        assertEquals(Abort.NO_CHARGE, c.abortReason());
    }

    @Test
    void exposedStandSpotAbortsBeforeTheBedIsUsed() {
        DebrisBlastCycle c = new DebrisBlastCycle(new DebrisBlastCycle.Config());
        Observation o = ready();
        c.start(o);
        o.blocksSincePocket = 99;
        c.step(o);
        done(c, o);
        done(c, o);
        done(c, o);
        assertEquals(Phase.TRIGGER, done(c, o));
        o.exposure = 1.0; // the shield does not cover the player
        assertEquals(Phase.ABORTED, c.step(o));
        assertEquals(Abort.UNSAFE_DISTANCE, c.abortReason());
    }

    @Test
    void serverStallHoldsEveryPhaseAndResumesAfterRecovery() {
        DebrisBlastCycle.Config cfg = new DebrisBlastCycle.Config();
        cfg.settleTicks = 3;
        DebrisBlastCycle c = new DebrisBlastCycle(cfg);
        Observation o = ready();
        c.start(o);
        o.blocksSincePocket = cfg.pocketEvery;
        o.serverStalled = true;
        assertEquals(Phase.TUNNEL, c.step(o)); // no pocket (no charge plan) during the stall
        assertTrue(c.stallHeld());
        o.serverStalled = false;
        assertEquals(Phase.POCKET, c.step(o));
        assertFalse(c.stallHeld());
        assertEquals(Phase.PLACE_CHARGE, done(c, o));
        // A "done" reported during the stall is dropped: the charge is not counted as placed.
        o.serverStalled = true;
        assertEquals(Phase.PLACE_CHARGE, done(c, o));
        assertFalse(o.stepDone);
        o.serverStalled = false;
        assertEquals(Phase.RETREAT, done(c, o));
        assertEquals(Phase.PLACE_SHIELD, done(c, o));
        assertEquals(Phase.TRIGGER, done(c, o));
        o.serverStalled = true;
        assertEquals(Phase.TRIGGER, done(c, o)); // the bed is not used during a stall
        assertFalse(c.chargeLive());
        o.serverStalled = false;
        assertEquals(Phase.SETTLE, done(c, o));
        assertTrue(c.chargeLive());
        // The settle timer waits while the server is frozen.
        o.serverStalled = true;
        for (int i = 0; i < 10; i++) {
            assertEquals(Phase.SETTLE, c.step(o));
        }
        assertEquals(0, c.cycles());
        o.serverStalled = false;
        c.step(o);
        c.step(o);
        assertEquals(Phase.TUNNEL, c.step(o));
        assertEquals(1, c.cycles());
    }

    @Test
    void safetyAbortsStillApplyDuringAStall() {
        DebrisBlastCycle c = new DebrisBlastCycle(new DebrisBlastCycle.Config());
        Observation o = ready();
        c.start(o);
        o.serverStalled = true;
        o.lavaNearFeet = true;
        assertEquals(Phase.ABORTED, c.step(o));
        assertEquals(Abort.LAVA_AT_FEET, c.abortReason());
    }

    @Test
    void tntChargeIsLiveFromLightingUntilTheBlastSettles() {
        DebrisBlastCycle.Config cfg = new DebrisBlastCycle.Config();
        cfg.preferBed = false;
        DebrisBlastCycle c = new DebrisBlastCycle(cfg);
        Observation o = ready();
        o.hasBed = false;
        o.distanceToCharge = 7.0;
        c.start(o);
        o.blocksSincePocket = cfg.pocketEvery;
        c.step(o);
        assertEquals(Charge.TNT, c.charge());
        assertEquals(Phase.PLACE_CHARGE, done(c, o));
        assertFalse(c.chargeLive());
        assertEquals(Phase.TRIGGER, done(c, o));
        assertFalse(c.chargeLive());
        assertEquals(Phase.RETREAT, done(c, o)); // lit
        assertTrue(c.chargeLive());
        o.serverStalled = true;
        assertEquals(Phase.RETREAT, done(c, o));
        assertTrue(c.chargeLive());
    }

    @Test
    void stallHoldLatchCountsHoldsAndReportsResume() {
        StallHold h = new StallHold();
        assertFalse(h.update(true, false)); // a stall over harmless work does not hold
        assertFalse(h.update(false, true));
        assertTrue(h.update(true, true));
        assertTrue(h.update(true, true));
        assertEquals(1, h.holds());
        assertFalse(h.justResumed());
        assertFalse(h.update(false, true));
        assertTrue(h.justResumed());
        assertFalse(h.update(false, true));
        assertFalse(h.justResumed());
        assertTrue(h.update(true, true));
        assertEquals(2, h.holds());
        h.reset();
        assertFalse(h.held());
    }

    @Test
    void planCheckRejectsAStandSpotTooCloseForTheDamageCap() {
        DebrisBlastCycle.Config cfg = new DebrisBlastCycle.Config();
        cfg.plannedExposure = 1.0;
        DebrisBlastCycle c = new DebrisBlastCycle(cfg);
        assertFalse(c.planIsSafe(Charge.BED));
        cfg.plannedExposure = 0.0;
        assertTrue(c.planIsSafe(Charge.BED));
        assertTrue(c.planIsSafe(Charge.TNT));
    }

    @Test
    void blastDamageMatchesVanillaFormula() {
        // Point blank, fully exposed, normal: (1+1)/2*7*10+1 = 71.
        assertEquals(71f, BlastMath.entityDamage(BlastMath.POWER_BED, 0, 1, 2), 1e-4);
        // Fully shielded inside the radius: the vanilla floor of 1, x1.5 on hard.
        assertEquals(1.5f, BlastMath.entityDamage(BlastMath.POWER_BED, 3, 0, 3), 1e-4);
        assertEquals(0f, BlastMath.entityDamage(BlastMath.POWER_TNT, 8.1, 1, 2), 1e-4);
        double d = BlastMath.safeDistance(BlastMath.POWER_TNT, 6, 1, 2);
        assertTrue(BlastMath.entityDamage(BlastMath.POWER_TNT, d, 1, 2) < 6);
        assertTrue(BlastMath.entityDamage(BlastMath.POWER_TNT, d - 0.1, 1, 2) >= 6);
    }
}
