package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scripted arenas: a flat floor at y 63 (feet 64), target at (0.5, 64, 0.5), grid centred on (0, 64, 0). */
class ArenaFieldTest {

    static final double TX = 0.5, TY = 64.0, TZ = 0.5;

    static final CombatVoxels FLAT = (x, y, z) -> y <= 63 ? CombatVoxels.SOLID : CombatVoxels.AIR;

    /** Lava pool one block east of the target. */
    static final CombatVoxels LAVA_EAST = (x, y, z) -> {
        if (x >= 1 && x <= 4 && Math.abs(z) <= 2 && y == 63) {
            return CombatVoxels.LAVA;
        }
        return FLAT.kind(x, y, z);
    };

    /** A 4-deep pit east of the target. */
    static final CombatVoxels PIT_EAST = pit(4);

    /** A wall (2 high) one block east of the target. */
    static final CombatVoxels WALL_EAST = (x, y, z) -> {
        if (x == 1 && Math.abs(z) <= 3 && (y == 64 || y == 65)) {
            return CombatVoxels.SOLID;
        }
        return FLAT.kind(x, y, z);
    };

    /** A hill 2 blocks up, east of the target, with a 1-block stair at x 2. */
    static final CombatVoxels HILL = (x, y, z) -> {
        if (Math.abs(z) <= 2 && x == 2) {
            return y <= 64 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        }
        if (Math.abs(z) <= 2 && x >= 3 && x <= 5) {
            return y <= 65 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        }
        return FLAT.kind(x, y, z);
    };

    /** A single 2-high pillar: no route up. */
    static final CombatVoxels PILLAR = (x, y, z) -> {
        if (x == 2 && z == 0) {
            return y <= 65 ? CombatVoxels.SOLID : CombatVoxels.AIR;
        }
        return FLAT.kind(x, y, z);
    };

    static CombatVoxels pit(int depth) {
        return (x, y, z) -> {
            if (x >= 1 && x <= 3 && Math.abs(z) <= 2) {
                return y <= 63 - depth ? CombatVoxels.SOLID : CombatVoxels.AIR;
            }
            return FLAT.kind(x, y, z);
        };
    }

    /** A corridor west of the player under a ceiling at y {@code ceilingY}. */
    static CombatVoxels corridor(int ceilingY) {
        return (x, y, z) -> {
            if (x >= -6 && x <= -2 && z == 0 && y == ceilingY) {
                return CombatVoxels.SOLID;
            }
            return FLAT.kind(x, y, z);
        };
    }

    static ArenaField scanned(CombatVoxels w) {
        ArenaField f = new ArenaField();
        f.recenter(0, 64, 0);
        f.scan(w, ArenaField.SIZE * ArenaField.SIZE);
        assertEquals(1.0, f.coverage(), 1e-9);
        return f;
    }

    @Test
    void flatArenaFindsNothing() {
        ArenaField f = scanned(FLAT);
        assertEquals(63, f.floorY(5, -7));
        assertEquals(ArenaField.MAX_HEADROOM, f.headroom(5, -7));
        assertEquals(CombatVoxels.SOLID, f.floorKind(5, -7));
        assertFalse(f.evalKnock(TX, TY, TZ));
        assertEquals(ArenaField.KNOCK_NONE, f.knockKind());
        assertFalse(f.findHighGround(-2.5, 64, 0.5, TX, TY, TZ, 8));
        assertFalse(f.findLowCeiling(-2.5, 64, 0.5, TX, TY, TZ, 8));
    }

    @Test
    void lavaEastSteersEast() {
        ArenaField f = scanned(LAVA_EAST);
        assertEquals(CombatVoxels.LAVA, f.floorKind(2, 0));
        assertTrue(f.evalKnock(TX, TY, TZ));
        assertEquals(ArenaField.KNOCK_LAVA, f.knockKind());
        assertTrue(f.knockX() > 0.9, "knockX " + f.knockX());
        assertTrue(f.knockValue() > 0.8, "value " + f.knockValue());
    }

    @Test
    void pitIsAMediumDropAndAChasmIsLethal() {
        ArenaField f = scanned(PIT_EAST);
        assertEquals(59, f.floorY(2, 0));
        assertTrue(f.evalKnock(TX, TY, TZ));
        assertEquals(ArenaField.KNOCK_DROP, f.knockKind());
        assertTrue(f.knockX() > 0.9);
        assertTrue(f.knockValue() > 0.45 && f.knockValue() < 0.75, "value " + f.knockValue());

        ArenaField deep = scanned(pit(20));
        assertEquals(ArenaField.NO_FLOOR, deep.floorY(2, 0));
        assertEquals(CombatVoxels.AIR, deep.floorKind(2, 0));
        assertTrue(deep.evalKnock(TX, TY, TZ));
        assertEquals(ArenaField.KNOCK_DROP, deep.knockKind());
        assertTrue(deep.knockValue() > 0.9);
    }

    @Test
    void wallIsWorthLessThanLava() {
        ArenaField lava = scanned(LAVA_EAST);
        lava.evalKnock(TX, TY, TZ);
        ArenaField f = scanned(WALL_EAST);
        assertTrue(f.evalKnock(TX, TY, TZ));
        assertEquals(ArenaField.KNOCK_WALL, f.knockKind());
        assertTrue(f.knockX() > 0.9);
        assertTrue(f.knockValue() > 0.1 && f.knockValue() < lava.knockValue(), "value " + f.knockValue());
    }

    @Test
    void budgetIsRespectedAndCoverageGrows() {
        ArenaField f = new ArenaField();
        int[] lookups = {0};
        CombatVoxels counting = (x, y, z) -> {
            lookups[0]++;
            return FLAT.kind(x, y, z);
        };
        assertEquals(0, f.scan(counting, 8), "nothing to scan before the first recentre");
        f.recenter(0, 64, 0);
        double last = 0.0;
        int calls = 0;
        while (f.coverage() < 1.0) {
            lookups[0] = 0;
            assertEquals(8, f.scan(counting, 8));
            assertTrue(lookups[0] <= 8 * ArenaField.WINDOW, "lookups " + lookups[0]);
            assertTrue(f.coverage() >= last);
            last = f.coverage();
            calls++;
            assertTrue(calls < 1000);
        }
        int n = ArenaField.SIZE * ArenaField.SIZE;
        assertEquals((n + 7) / 8, calls);
        assertEquals(8, f.scan(counting, 8), "round-robin refresh keeps scanning");
        assertEquals(1.0, f.coverage(), 1e-9);
    }

    @Test
    void scanIsNearestFirst() {
        ArenaField f = new ArenaField();
        f.recenter(0, 64, 0);
        f.scan(FLAT, 9);
        assertEquals(63, f.floorY(0, 0));
        assertEquals(63, f.floorY(1, 1));
        assertEquals(63, f.floorY(-1, -1));
        assertEquals(ArenaField.NO_FLOOR, f.floorY(10, 0));
        assertEquals(CombatVoxels.UNKNOWN, f.floorKind(10, 0));
    }

    @Test
    void recenterKeepsOverlappingColumns() {
        ArenaField f = scanned(FLAT);
        f.recenter(2, 64, 0);
        int s = ArenaField.SIZE;
        assertEquals((double) (s - 2) * s / (s * s), f.coverage(), 1e-9);
        assertEquals(63, f.floorY(-14, 0));
        assertEquals(ArenaField.NO_FLOOR, f.floorY(18, 0));
        f.scan(FLAT, 2 * s);
        assertEquals(1.0, f.coverage(), 1e-9);
        assertEquals(63, f.floorY(18, 0));

        f.recenter(2, 67, 0); // vertical move of 3: restart
        assertEquals(0.0, f.coverage(), 1e-9);
        assertEquals(ArenaField.NO_FLOOR, f.floorY(2, 0));

        f.scan(FLAT, s * s);
        f.recenter(14, 67, 0); // jump of 12: restart
        assertEquals(0.0, f.coverage(), 1e-9);
    }

    @Test
    void unknownNeverYieldsASpot() {
        ArenaField f = scanned((x, y, z) -> CombatVoxels.UNKNOWN);
        assertEquals(CombatVoxels.UNKNOWN, f.floorKind(0, 0));
        assertEquals(ArenaField.NO_FLOOR, f.floorY(0, 0));
        assertEquals(ArenaField.MAX_HEADROOM, f.headroom(0, 0));
        assertFalse(f.evalKnock(TX, TY, TZ));
        assertFalse(f.findHighGround(-2.5, 64, 0.5, TX, TY, TZ, 8));
        assertFalse(f.findLowCeiling(-2.5, 64, 0.5, TX, TY, TZ, 8));

        // the hill itself is unloaded
        ArenaField h = scanned((x, y, z) -> x >= 2 ? CombatVoxels.UNKNOWN : FLAT.kind(x, y, z));
        assertFalse(h.findHighGround(-2.5, 64, 0.5, TX, TY, TZ, 8));
        assertFalse(h.evalKnock(TX, TY, TZ), "an unloaded column is never a drop");
    }

    @Test
    void hillByStairsIsFoundPillarIsNot() {
        ArenaField f = scanned(HILL);
        assertTrue(f.findHighGround(-2.5, 64, 0.5, TX, TY, TZ, 10));
        assertTrue(f.spotGain() >= 1 && f.spotGain() <= 2, "gain " + f.spotGain());
        assertTrue(f.spotX() > 2.0);
        assertTrue(f.spotY() >= 65);
        double dx = f.spotX() - TX;
        double dz = f.spotZ() - TZ;
        assertTrue(Math.sqrt(dx * dx + dz * dz) <= 4.0);

        ArenaField p = scanned(PILLAR);
        assertEquals(65, p.floorY(2, 0));
        assertFalse(p.findHighGround(-2.5, 64, 0.5, TX, TY, TZ, 10));
    }

    @Test
    void lowCeilingOnlyWhenHeadroomIsTwo() {
        ArenaField f = scanned(corridor(66));
        assertEquals(2, f.headroom(-3, 0));
        assertTrue(f.findLowCeiling(-0.5, 64, 0.5, 2.5, 64, 0.5, 8));
        assertTrue(f.spotX() < -1.0, "spot behind the player, away from the target: " + f.spotX());
        assertEquals(0.5, f.spotZ(), 1e-9);
        assertEquals(64.0, f.spotY(), 1e-9);
        assertEquals(5.0, f.spotGain(), 1e-9);

        ArenaField high = scanned(corridor(67));
        assertEquals(3, high.headroom(-3, 0));
        assertFalse(high.findLowCeiling(-0.5, 64, 0.5, 2.5, 64, 0.5, 8));
    }

    @Test
    void clearForgetsEverything() {
        ArenaField f = scanned(LAVA_EAST);
        f.clear();
        assertEquals(0.0, f.coverage(), 1e-9);
        assertEquals(CombatVoxels.UNKNOWN, f.floorKind(2, 0));
        assertFalse(f.evalKnock(TX, TY, TZ));
    }
}
