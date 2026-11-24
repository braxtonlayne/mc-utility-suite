package com.utilitysuite.tools.player;

import com.utilitysuite.combat.tactics.CombatVoxels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlPathCheckTest {

    /** Standing on the floor at y = 64 in the middle of cell (0, 64, 0): eyes 1.62 up. */
    private static final double EX = 0.5, EY = 65.62, EZ = 0.5;
    private static final float SOUTH = 0f;   // +Z
    private static final float WEST = 90f;   // -X
    private static final float LEVEL = 0f;
    private static final float UP = -90f;
    private static final float DOWN = 90f;

    private final PearlPathCheck check = new PearlPathCheck();

    private static CombatVoxels floorAnd(CombatVoxels extra) {
        return (x, y, z) -> y < 64 ? CombatVoxels.SOLID : extra.kind(x, y, z);
    }

    private static final CombatVoxels OPEN = floorAnd((x, y, z) -> CombatVoxels.AIR);

    private boolean blocked(float yaw, float pitch, CombatVoxels w) {
        return check.blocked(EX, EY, EZ, yaw, pitch, 0, 0, 0, w);
    }

    // ---- blocked -----------------------------------------------------------------------------------------------------

    @Test
    void wallOneBlockAheadBlocksTheThrow() {
        assertTrue(blocked(SOUTH, LEVEL, floorAnd((x, y, z) -> z == 1 ? CombatVoxels.SOLID : CombatVoxels.AIR)));
    }

    @Test
    void yawFollowsTheVanillaConvention() {
        CombatVoxels westWall = floorAnd((x, y, z) -> x == -1 ? CombatVoxels.SOLID : CombatVoxels.AIR);
        assertTrue(blocked(WEST, LEVEL, westWall), "yaw 90 looks at -X");
        assertEquals(-1.0, check.dirX(), 1.0e-9);
        assertEquals(0.0, check.dirZ(), 1.0e-9);
        assertFalse(blocked(SOUTH, LEVEL, westWall), "the wall is to the side");
    }

    @Test
    void lowCeilingBlocksAThrowStraightUp() {
        assertTrue(blocked(SOUTH, UP, floorAnd((x, y, z) -> y >= 66 ? CombatVoxels.SOLID : CombatVoxels.AIR)));
    }

    @Test
    void theEyeCellItselfCountsWhenItIsSolid() {
        // e.g. a ladder or an open trapdoor in the head cell: the caller's collision ray has the last word
        assertTrue(blocked(SOUTH, LEVEL, floorAnd((x, y, z) -> x == 0 && y == 65 && z == 0 ? CombatVoxels.SOLID
                : CombatVoxels.AIR)));
    }

    // ---- not blocked -------------------------------------------------------------------------------------------------

    @Test
    void openGroundDoesNotBlock() {
        assertFalse(blocked(SOUTH, LEVEL, OPEN));
        assertFalse(blocked(SOUTH, -30f, OPEN));
    }

    @Test
    void wallFourBlocksAheadDoesNotBlock() {
        assertFalse(blocked(SOUTH, LEVEL, floorAnd((x, y, z) -> z == 4 ? CombatVoxels.SOLID : CombatVoxels.AIR)));
    }

    @Test
    void theFloorUnderAStandingPlayerIsOutOfRange() {
        // start 1.52 above the floor: a pearl at your own feet is thrown
        assertFalse(blocked(SOUTH, DOWN, OPEN));
    }

    @Test
    void unknownOrLavaCellsNeverBlock() {
        assertFalse(blocked(SOUTH, LEVEL, floorAnd((x, y, z) -> z == 1 ? CombatVoxels.UNKNOWN : CombatVoxels.AIR)));
        assertFalse(blocked(SOUTH, LEVEL, floorAnd((x, y, z) -> z == 1 ? CombatVoxels.LAVA : CombatVoxels.AIR)));
    }

    @Test
    void waterIsFlownThrough() {
        assertFalse(blocked(SOUTH, LEVEL, floorAnd((x, y, z) -> z == 1 ? CombatVoxels.WATER : CombatVoxels.AIR)));
    }

    // ---- the throw itself --------------------------------------------------------------------------------------------

    @Test
    void theThrowStartsJustBelowTheEyesAlongTheLook() {
        blocked(SOUTH, LEVEL, OPEN);
        assertEquals(EX, check.startX(), 0.0);
        assertEquals(EY - PearlPathCheck.EYE_DROP, check.startY(), 1.0e-12);
        assertEquals(EZ, check.startZ(), 0.0);
        assertEquals(0.0, check.dirX(), 1.0e-9);
        assertEquals(0.0, check.dirY(), 1.0e-9);
        assertEquals(1.0, check.dirZ(), 1.0e-9);
        blocked(SOUTH, UP, OPEN);
        assertEquals(1.0, check.dirY(), 1.0e-9, "pitch -90 is straight up");
    }

    @Test
    void theThrowersMotionIsAdded() {
        CombatVoxels stepAhead = floorAnd((x, y, z) -> z == 1 && y == 64 ? CombatVoxels.SOLID : CombatVoxels.AIR);
        assertFalse(blocked(SOUTH, LEVEL, stepAhead), "a level throw clears a one-high step in the next cell");
        assertTrue(check.blocked(EX, EY, EZ, SOUTH, LEVEL, 0, -1.0, 0, stepAhead),
                "the same look while falling a block per tick goes into it");
        assertTrue(check.dirY() < -0.5);
    }

    @Test
    void aThrowWithNoSpeedIsNotBlocked() {
        // backing away exactly as fast as the pearl leaves: nothing to simulate
        assertFalse(check.blocked(EX, EY, EZ, SOUTH, LEVEL, 0, 0, -PearlPathCheck.SPEED,
                (x, y, z) -> CombatVoxels.SOLID));
    }

    // ---- hot-path hygiene --------------------------------------------------------------------------------------------

    @Test
    void repeatedChecksReuseTheSimulation() {
        CombatVoxels wall = floorAnd((x, y, z) -> z == 1 ? CombatVoxels.SOLID : CombatVoxels.AIR);
        for (int i = 0; i < 1000; i++) {
            assertTrue(blocked(SOUTH, LEVEL, wall));
            assertFalse(blocked(SOUTH, LEVEL, OPEN));
        }
    }
}
