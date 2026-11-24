package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeinRulesTest {

    private static VeinRules.Probe world(LongOpenHashSet lava, LongOpenHashSet falling) {
        return new VeinRules.Probe() {
            @Override
            public boolean lavaAt(int x, int y, int z) {
                return lava.contains(PackedPos.pack(x, y, z));
            }

            @Override
            public boolean fallingBlockAt(int x, int y, int z) {
                return falling.contains(PackedPos.pack(x, y, z));
            }
        };
    }

    @Test
    void sixDistinctFaceNeighbours() {
        assertEquals(6, VeinRules.faceCount());
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int i = 0; i < 6; i++) {
            int dx = VeinRules.faceOffset(i, 0), dy = VeinRules.faceOffset(i, 1), dz = VeinRules.faceOffset(i, 2);
            assertEquals(1, Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
            assertTrue(seen.add(PackedPos.pack(dx, dy, dz)));
        }
    }

    @Test
    void lavaOnAnyFaceIsAHazard() {
        for (int i = 0; i < 6; i++) {
            LongOpenHashSet lava = new LongOpenHashSet();
            lava.add(PackedPos.pack(10 + VeinRules.faceOffset(i, 0), 20 + VeinRules.faceOffset(i, 1),
                    30 + VeinRules.faceOffset(i, 2)));
            assertEquals(VeinRules.LAVA, VeinRules.hazard(world(lava, new LongOpenHashSet()), 10, 20, 30));
        }
    }

    @Test
    void diagonalLavaIsNotAHazard() {
        LongOpenHashSet lava = new LongOpenHashSet();
        lava.add(PackedPos.pack(11, 21, 30));
        assertEquals(VeinRules.SAFE, VeinRules.hazard(world(lava, new LongOpenHashSet()), 10, 20, 30));
    }

    @Test
    void gravityBlockOnlyCountsAbove() {
        LongOpenHashSet falling = new LongOpenHashSet();
        falling.add(PackedPos.pack(10, 19, 30)); // below: harmless
        falling.add(PackedPos.pack(11, 20, 30)); // beside: harmless
        assertEquals(VeinRules.SAFE, VeinRules.hazard(world(new LongOpenHashSet(), falling), 10, 20, 30));
        falling.add(PackedPos.pack(10, 21, 30));
        assertEquals(VeinRules.FALLING_ABOVE, VeinRules.hazard(world(new LongOpenHashSet(), falling), 10, 20, 30));
    }

    @Test
    void lavaWinsOverFallingBlock() {
        LongOpenHashSet lava = new LongOpenHashSet();
        lava.add(PackedPos.pack(10, 20, 31));
        LongOpenHashSet falling = new LongOpenHashSet();
        falling.add(PackedPos.pack(10, 21, 30));
        assertEquals(VeinRules.LAVA, VeinRules.hazard(world(lava, falling), 10, 20, 30));
    }

    @Test
    void walkCapLimitsHorizontalDistanceFromTheStart() {
        double reach = 4.5, cap = 4.0, eyeY = 65.62;
        // within cap + reach - 0.5 = 8 horizontally of the start at (0.5, 0.5)
        assertTrue(VeinRules.walkable(0.5, 0.5, eyeY, 7, 64, 0, reach, cap));
        assertFalse(VeinRules.walkable(0.5, 0.5, eyeY, 9, 64, 0, reach, cap));
        assertFalse(VeinRules.walkable(0.5, 0.5, eyeY, 6, 64, 6, reach, cap)); // diagonal ~8.5
    }

    @Test
    void walkSkipsBlocksTooFarAboveOrBelow() {
        double reach = 4.5, cap = 4.0, eyeY = 65.62;
        assertTrue(VeinRules.walkable(0.5, 0.5, eyeY, 5, 68, 0, reach, cap));
        assertFalse(VeinRules.walkable(0.5, 0.5, eyeY, 5, 70, 0, reach, cap)); // tree top out of any reach
        assertFalse(VeinRules.walkable(0.5, 0.5, eyeY, 5, 60, 0, reach, cap));
    }
}
