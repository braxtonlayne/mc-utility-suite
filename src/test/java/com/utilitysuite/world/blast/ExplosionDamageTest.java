package com.utilitysuite.world.blast;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionDamageTest {

    private static final ExplosionDamage.Solid OPEN = (x, y, z) -> y < 64; // floor only

    @Test
    void rawDamageMatchesVanilla() {
        // point blank, full exposure, crystal (power 6): (1 + 1) / 2 * 7 * 12 + 1 = 85
        assertEquals(85.0, ExplosionDamage.raw(6, 0, 1.0), 1e-9);
        // halfway out (6 blocks): i = 0.5 -> (0.25 + 0.5) / 2 * 84 + 1 = 32.5
        assertEquals(32.5, ExplosionDamage.raw(6, 6, 1.0), 1e-9);
        // fully hidden but in range: only the flat 1
        assertEquals(1.0, ExplosionDamage.raw(6, 3, 0.0), 1e-9);
        assertEquals(0.0, ExplosionDamage.raw(6, 12.01, 1.0), 1e-9);
        // respawn anchor (power 5) at 2 blocks: i = 0.8 -> (0.64 + 0.8) / 2 * 70 + 1 = 51.4
        assertEquals(51.4, ExplosionDamage.raw(ExplosionDamage.POWER_ANCHOR, 2, 1.0), 1e-9);
    }

    @Test
    void difficultyScaling() {
        assertEquals(0.0, ExplosionDamage.difficulty(20, ExplosionDamage.PEACEFUL), 1e-9);
        assertEquals(11.0, ExplosionDamage.difficulty(20, ExplosionDamage.EASY), 1e-9);
        assertEquals(1.0, ExplosionDamage.difficulty(1, ExplosionDamage.EASY), 1e-9);
        assertEquals(20.0, ExplosionDamage.difficulty(20, ExplosionDamage.NORMAL), 1e-9);
        assertEquals(30.0, ExplosionDamage.difficulty(20, ExplosionDamage.HARD), 1e-9);
    }

    @Test
    void armourToughnessResistanceAndEnchantments() {
        // full netherite (20, 12) vs 85: 20 - 85 / 5 = 3 is under the 20 % floor (4) -> 85 * 0.84
        assertEquals(85 * 0.84, ExplosionDamage.armour(85, 20, 12), 1e-9);
        // 10 vs diamond (20, 8): 20 - 10 / 4 = 17.5 -> 10 * 0.3
        assertEquals(3.0, ExplosionDamage.armour(10, 20, 8), 1e-9);
        // Blast Protection IV x4 = 32 EPF, capped at 20 (80 %)
        assertEquals(32, ExplosionDamage.epf(0, 16));
        assertEquals(20.0 * 0.2, ExplosionDamage.enchantments(20, 32), 1e-9);
        // Protection IV x4 = 16 EPF -> 64 %
        assertEquals(20.0 * 0.36, ExplosionDamage.enchantments(20, ExplosionDamage.epf(16, 0)), 1e-9);
        // Resistance II: 40 %
        assertEquals(12.0, ExplosionDamage.resistance(20, 1), 1e-9);
        assertEquals(20.0, ExplosionDamage.resistance(20, -1), 1e-9);
        assertEquals(0.0, ExplosionDamage.resistance(20, 4), 1e-9);
    }

    @Test
    void closeCrystalKillsUnarmouredButNotBlastProtNetherite() {
        double naked = ExplosionDamage.taken(6, 1, 1.0, ExplosionDamage.NORMAL, 0, 0, -1, 0);
        assertTrue(ExplosionDamage.lethal(naked, 20, 0));
        double geared = ExplosionDamage.taken(6, 1, 1.0, ExplosionDamage.NORMAL, 20, 12, -1,
                ExplosionDamage.epf(0, 16));
        // raw: i = 11/12 -> (i^2 + i) / 2 * 84 + 1; armour: 20 - raw / 5 (above the 20 % floor); EPF 20 -> * 0.2
        double i = 11.0 / 12.0;
        double raw = (i * i + i) / 2 * 84 + 1;
        assertEquals(raw * (1 - (20 - raw / 5) / 25) * 0.2, geared, 1e-9);
        assertFalse(ExplosionDamage.lethal(geared, 20, 0));
        assertTrue(ExplosionDamage.lethal(geared, 6, 2)); // about 8.9 vs 8 total
    }

    @Test
    void exposureOpenAirIsFull() {
        assertEquals(1.0, ExplosionDamage.exposure(3.5, 64, 0.5, 0.5, 64, 0.5, OPEN), 1e-9);
    }

    @Test
    void exposureBehindAFullWallIsZeroAndBehindAHalfWallIsPartial() {
        ExplosionDamage.Solid wall = (x, y, z) -> y < 64 || (x == 2 && y <= 66);
        assertEquals(0.0, ExplosionDamage.exposure(4.5, 64, 0.5, 0.5, 64, 0.5, wall), 1e-9);
        ExplosionDamage.Solid low = (x, y, z) -> y < 64 || (x == 2 && y == 64);
        double e = ExplosionDamage.exposure(4.5, 65, 0.5, 0.5, 64, 0.5, low); // crystal on a block
        assertTrue(e > 0.0 && e < 1.0, "half wall " + e);
    }

    @Test
    void vanillaSampleGridForAPlayerBox() {
        int[] rays = {0};
        // every ray ends in the blast's cell: counting visits there counts the rays (3 x 5 x 3 for 0.6 x 1.8 x 0.6)
        ExplosionDamage.exposure(3.5, 64, 0.5, 0.5, 64, 0.5, (x, y, z) -> {
            if (x == 3 && y == 64 && z == 0) {
                rays[0]++;
            }
            return false;
        });
        assertEquals(3 * 5 * 3, rays[0]);
    }

    @Test
    void blockedWalksEveryCellOnTheLine() {
        ExplosionDamage.Solid one = (x, y, z) -> x == 5 && y == 0 && z == 0;
        assertTrue(ExplosionDamage.blocked(0.5, 0.5, 0.5, 9.5, 0.5, 0.5, one));
        assertFalse(ExplosionDamage.blocked(0.5, 1.5, 0.5, 9.5, 1.5, 0.5, one));
        assertTrue(ExplosionDamage.blocked(9.5, 0.5, 0.5, 0.5, 0.5, 0.5, one));
        assertTrue(ExplosionDamage.blocked(0.2, 0.5, 0.5, 9.9, 0.5, 0.9, one));
    }

    @Test
    void boxInNeedsThreeSidesRecently() {
        assertTrue(CrystalExposureModule.BoxIn.boxed(3, 10, 100));
        assertTrue(CrystalExposureModule.BoxIn.boxed(4, 100, 100));
        assertFalse(CrystalExposureModule.BoxIn.boxed(2, 0, 100));
        assertFalse(CrystalExposureModule.BoxIn.boxed(4, 101, 100));
    }

    @Test
    void exposureAllocatesNothing() {
        if (!AllocationProbe.supported()) {
            return;
        }
        ExplosionDamage.Solid wall = (x, y, z) -> y < 64 || (x == 2 && y == 64);
        long bytes = AllocationProbe.measure(500, () ->
                ExplosionDamage.taken(6, 4, ExplosionDamage.exposure(4.5, 64, 0.5, 0.5, 64, 0.5, wall),
                        ExplosionDamage.HARD, 20, 8, -1, 16));
        assertEquals(0L, bytes);
    }
}
