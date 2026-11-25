package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatGapJumpTest {

    /** Two floors along z (x in [-3, 3]) with nothing between or below them. */
    private static MovementPhysics.Voxels twoFloors(int nearTo, int farFrom, int farY) {
        return (x, y, z) -> x >= -3 && x <= 3
                && ((y == 19 && z >= -30 && z <= nearTo) || (y == farY && z >= farFrom && z <= 30));
    }

    /** The last tick on the block before a gap, sprinting toward +z (as in a real run-up). */
    private static MovementPhysics atEdge() {
        return new MovementPhysics().set(0.5, 20, 1.31, 0, 0, 0.153, true);
    }

    private static CombatGapJump gate() {
        CombatGapJump g = new CombatGapJump();
        g.enabled = true;
        return g;
    }

    @Test
    void offByDefault() {
        CombatGapJump g = new CombatGapJump();
        assertEquals(CombatGapJump.Verdict.NO,
                g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, 8, false, false, twoFloors(0, 4, 19)));
    }

    @Test
    void takesAValidatedGapJumpTowardTheTarget() {
        CombatGapJump g = gate();
        assertEquals(CombatGapJump.Verdict.JUMP,
                g.decide(atEdge(), 0f, true, 20f, true, false, 0.5, 8, false, false, twoFloors(0, 4, 19)),
                g.reason());
    }

    @Test
    void onlyJumpsTheValidatorApproves() {
        CombatGapJump g = gate();
        // flat ground: no edge, nothing to jump
        MovementPhysics.Voxels flat = (x, y, z) -> y == 19 && x >= -3 && x <= 3 && z >= -30 && z <= 30;
        assertEquals(CombatGapJump.Verdict.NO, g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, 8, false, false,
                flat));
        assertEquals("validator", g.reason());
        // six blocks of air: no flat sprint jump lands, so the validator refuses
        assertEquals(CombatGapJump.Verdict.NO, g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, 10, false, false,
                twoFloors(0, 7, 19)));
    }

    @Test
    void safeWalkMakesDamagingJumpsInvalid() {
        CombatGapJump g = gate();
        MovementPhysics.Voxels drop = twoFloors(0, 4, 16); // far side three blocks lower: walking off misses, the jump lands and hurts
        assertEquals(CombatGapJump.Verdict.NO,
                g.decide(atEdge(), 0f, true, 20f, true, false, 0.5, 8, false, false, drop));
        assertEquals("damage", g.reason());
        assertEquals(CombatGapJump.Verdict.JUMP,
                g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, 8, false, false, drop), g.reason());
        assertTrue(g.search().landDamage() > 0f);
        // without SafeWalk a damaging landing still has to leave a margin of health
        assertEquals(CombatGapJump.Verdict.NO,
                g.decide(atEdge(), 0f, true, g.search().landDamage() + 2f, false, false, 0.5, 8, false, false, drop));
    }

    @Test
    void manualInputAndChargedHitInReachWin() {
        CombatGapJump g = gate();
        MovementPhysics.Voxels w = twoFloors(0, 4, 19);
        assertEquals(CombatGapJump.Verdict.NO, g.decide(atEdge(), 0f, true, 20f, false, true, 0.5, 8, false, false, w));
        assertEquals("manual", g.reason());
        assertEquals(CombatGapJump.Verdict.NO, g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, 3, false, true, w));
        assertEquals("hit first", g.reason());
    }

    @Test
    void doesNotJumpAwayFromTheFightUnlessRetreating() {
        CombatGapJump g = gate();
        MovementPhysics.Voxels w = twoFloors(0, 4, 19);
        // target behind the player: the jump would carry it out of the fight
        assertEquals(CombatGapJump.Verdict.NO, g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, -6, false, false,
                w));
        assertEquals("leaves fight", g.reason());
        assertEquals(CombatGapJump.Verdict.JUMP, g.decide(atEdge(), 0f, true, 20f, false, false, 0.5, -6, true, false,
                w), "a retreat may jump the gap away from the target");
    }

    @Test
    void decideAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        CombatGapJump g = gate();
        MovementPhysics.Voxels w = twoFloors(0, 4, 19);
        MovementPhysics p = atEdge();
        int[] n = {0};
        long bytes = AllocationProbe.measure(2_000, () -> {
            if (g.decide(p, 0f, true, 20f, (n[0] & 1) == 0, false, 0.5, 8, false, false, w)
                    == CombatGapJump.Verdict.JUMP) {
                n[0]++;
            }
        });
        assertTrue(bytes < 1_024, "decide allocated " + bytes + " bytes");
    }
}
