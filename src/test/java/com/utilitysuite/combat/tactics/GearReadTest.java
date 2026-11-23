package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GearReadTest {

    @Test
    void evenGearKeepsTheFormerWeight() {
        GearRead g = new GearRead();
        double hit = GearRead.afterArmor(7.0, 20.0, 8.0); // diamond sword into full diamond, both ways
        g.update(20.0, 20.0, hit, hit);
        assertEquals(1.0, g.ratio(), 1e-9);
        assertEquals(1.2, g.takenWeight(), 1e-9);
        assertEquals(1.8, g.takenWeight(true), 1e-9, "kiting: the former 1.8");
        for (int i = 0; i < 5; i++) {
            g.noteTaken(4.0);
            g.noteDealt(4.0);
        }
        g.update(20.0, 20.0, hit, hit);
        assertEquals(1.2, g.takenWeight(), 1e-9, "observed even trades");
    }

    @Test
    void threeToOneDisadvantageClampsAtThree() {
        GearRead g = new GearRead();
        for (int i = 0; i < 4; i++) {
            g.noteTaken(9.0);
            g.noteDealt(3.0);
        }
        g.update(20.0, 20.0, 3.0, 9.0);
        assertEquals(3.0, g.ratio(), 1e-9);
        assertEquals(GearRead.MAX_WEIGHT, g.takenWeight(), 1e-9, "1.2 x 3 = 3.6, clamped to 3.0");
    }

    @Test
    void healthLeftCountsBothWays() {
        GearRead g = new GearRead();
        g.update(10.0, 20.0, 5.0, 5.0); // even hits, but we have half the health left
        assertEquals(2.0, g.ratio(), 1e-9);
        g.update(20.0, 5.0, 5.0, 5.0); // they are nearly dead
        assertEquals(GearRead.MIN_RATIO, g.ratio(), 1e-9);
        assertEquals(GearRead.MIN_WEIGHT, g.takenWeight(), 1e-9);
    }

    @Test
    void beforeTheFirstHitTheVisibleGearDecides() {
        GearRead g = new GearRead();
        // they: netherite sword (8) into our iron armour (15, no toughness); we: an iron sword (6) into full diamond
        double theirs = GearRead.afterArmor(8.0, 15.0, 0.0);
        double ours = GearRead.afterArmor(6.0, 20.0, 8.0);
        g.update(20.0, 20.0, ours, theirs);
        assertEquals(0, g.takenHits());
        assertEquals(theirs / ours, g.ratio(), 1e-9);
        assertTrue(g.ratio() > 1.5, "a visibly stronger kit reads as out-damaging us before any hit");
        assertTrue(g.takenWeight() > 1.8);
    }

    @Test
    void observedHitsTakeOverFromTheEstimateSmoothly() {
        GearRead g = new GearRead();
        g.noteTaken(8.0);
        g.update(20.0, 20.0, 4.0, 4.0);
        assertEquals(6.0, g.theirHit(), 1e-9, "first hit: half estimate, half observed");
        g.noteTaken(8.0);
        g.update(20.0, 20.0, 4.0, 4.0);
        assertEquals(8.0, g.theirHit(), 1e-9);
        g.noteTaken(2.0); // one odd hit moves the average, it does not replace it
        g.update(20.0, 20.0, 4.0, 4.0);
        assertEquals(8.0 + GearRead.ALPHA * (2.0 - 8.0), g.theirHit(), 1e-9);
        g.noteTaken(0.1); // too small to be a weapon hit
        assertEquals(3, g.takenHits());
    }

    @Test
    void armourFormulaMatchesVanilla() {
        assertEquals(7.0, GearRead.afterArmor(7.0, 0.0, 0.0), 1e-9);
        // full diamond (20 armour, 8 toughness), 7 damage: 20 - 7/4 = 18.25 -> 7 * (1 - 0.73)
        assertEquals(7.0 * (1.0 - 18.25 / 25.0), GearRead.afterArmor(7.0, 20.0, 8.0), 1e-9);
        // armour reduction never above 80%
        assertEquals(1.0 * 0.2, GearRead.afterArmor(1.0, 30.0, 20.0), 1e-9);
    }

    @Test
    void tradeCautionScalesTheTakenWeightInsideTheClamp() {
        GearRead g = new GearRead();
        g.update(20, 20, 3.12, 3.12); // even iron kits
        assertEquals(1.0, g.ratio(), 1e-9);
        assertEquals(GearRead.EVEN_BIAS, g.takenWeight(false, 1.0), 1e-9);
        assertEquals(3.0, g.takenWeight(false, 2.5), 1e-9, "1.2 x 2.5 = 3.0, the cap");
        assertEquals(3.0, g.takenWeight(false, 3.0), 1e-9, "never above the cap");
        assertEquals(3.0 * GearRead.KITE_FACTOR, g.takenWeight(true, 2.5), 1e-9);
    }

    @Test
    void theCautionCapGrowsWithTheGearGap() {
        GearRead g = new GearRead();
        g.update(10, 20, 1.56, 5.08); // iron vs diamond + Sharpness II, down to half health
        assertEquals(GearRead.MAX_RATIO, g.ratio(), 1e-9, "3.3:1 per hit at half our health: clamped at 4");
        assertEquals(12.0, g.takenWeight(false, 2.5), 1e-9, "1.2 x 4 x 2.5 = 12, under the 3.0 x 4 cap");
        assertEquals(12.0, g.takenWeight(false, 3.0), 1e-9, "never above 3.0 x ratio");
        assertEquals(GearRead.MAX_WEIGHT, g.takenWeight(false), 1e-9, "the plain read keeps its fixed cap");
    }
}
