package com.utilitysuite.jobs.schematic.generated;

import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighwayDesignTest {

    /** World-coordinate state lookup in a segment. */
    private static String at(HighwayDesign.Segment s, int x, int y, int z) {
        SchematicVolume v = s.volume();
        int lx = x - s.anchorX() - v.offsetX();
        int ly = y - s.anchorY() - v.offsetY();
        int lz = z - s.anchorZ() - v.offsetZ();
        if (!v.contains(lx, ly, lz)) {
            return SchematicVolume.VOID;
        }
        return v.state(lx, ly, lz);
    }

    @Test
    void snapsToEightDirections() {
        assertEquals(1, HighwayDesign.dirX(100, 3));
        assertEquals(0, HighwayDesign.dirZ(100, 3));
        assertEquals(-1, HighwayDesign.dirX(-50, 48));
        assertEquals(1, HighwayDesign.dirZ(-50, 48));
        assertEquals(0, HighwayDesign.dirX(2, -90));
        assertEquals(-1, HighwayDesign.dirZ(2, -90));
        assertEquals(100, HighwayDesign.steps(100, 3));
        assertEquals(48, HighwayDesign.steps(-50, 48));
    }

    @Test
    void segmentsCoverThePathAheadOfThePlayer() {
        assertEquals(4, HighwayDesign.segmentCount(100, 32));
        assertEquals(0, HighwayDesign.segmentAhead(0, 16, 32, 4));
        assertEquals(1, HighwayDesign.segmentAhead(20, 16, 32, 4));
        assertEquals(3, HighwayDesign.segmentAhead(500, 16, 32, 4));
    }

    @Test
    void straightTunnelIsThreeWideFloorWithAirAboveAndSealedLava() {
        HighwayDesign.Spec spec = new HighwayDesign.Spec();
        // Lava in the left wall at step 5; the floor is already solid at step 2.
        HighwayDesign.Probe probe = new HighwayDesign.Probe() {
            @Override
            public boolean solidAt(int x, int y, int z) {
                return x == 2 && y == 63;
            }

            @Override
            public boolean fluidAt(int x, int y, int z) {
                return x == 5 && z == -2 && y == 65;
            }
        };
        HighwayDesign.Segment s = HighwayDesign.segment(spec, 0, 64, 0, 100, 0, 0, probe);
        assertEquals(0, s.fromStep());
        assertEquals(31, s.toStep());
        assertTrue(s.volume().name().startsWith("generated:highway/tunnel"));
        for (int x = 0; x <= 31; x++) {
            for (int z = -1; z <= 1; z++) {
                String floor = at(s, x, 63, z);
                assertEquals(x == 2 ? SchematicVolume.VOID : "minecraft:netherrack", floor, "floor " + x + "," + z);
                for (int y = 64; y <= 66; y++) {
                    assertEquals(SchematicVolume.AIR, at(s, x, y, z));
                }
            }
        }
        assertEquals("minecraft:netherrack", at(s, 5, 65, -2));
        assertEquals(SchematicVolume.VOID, at(s, 6, 65, -2));
        assertEquals(0, s.digCells().length, "the probe reports the tunnel space empty: nothing to dig");
    }

    @Test
    void secondSegmentStartsWhereTheFirstEnds() {
        HighwayDesign.Spec spec = new HighwayDesign.Spec();
        HighwayDesign.Segment s = HighwayDesign.segment(spec, 0, 64, 0, 0, 70, 1, null);
        assertEquals(32, s.fromStep());
        assertEquals(63, s.toStep());
        assertEquals("minecraft:netherrack", at(s, 0, 63, 32));
        assertEquals(SchematicVolume.VOID, at(s, 0, 63, 31));
        assertEquals(32 * 3 * 3, s.digCells().length, "without a probe every air cell is a dig cell");
        assertThrows(IllegalArgumentException.class, () -> HighwayDesign.segment(spec, 0, 64, 0, 0, 70, 3, null));
    }

    @Test
    void spawnProofingUsesSlabsOrButtons() {
        HighwayDesign.Spec spec = new HighwayDesign.Spec();
        spec.spawnProof = HighwayDesign.SpawnProof.SLAB;
        HighwayDesign.Segment s = HighwayDesign.segment(spec, 0, 64, 0, 40, 0, 0, null);
        assertTrue(at(s, 3, 63, 0).startsWith("minecraft:blackstone_slab"));
        assertTrue(at(s, 3, 63, 0).contains("type=bottom"));
        spec.spawnProof = HighwayDesign.SpawnProof.BUTTON;
        s = HighwayDesign.segment(spec, 0, 64, 0, 40, 0, 0, null);
        assertTrue(at(s, 3, 64, 0).contains("button"));
        assertEquals("minecraft:netherrack", at(s, 3, 63, 0));
    }

    @Test
    void iceHighwayHasRailsAndHeadroom() {
        HighwayDesign.Spec spec = new HighwayDesign.Spec();
        spec.mode = HighwayDesign.Mode.ICE;
        spec.width = 2;
        HighwayDesign.Segment s = HighwayDesign.segment(spec, 0, 64, 0, 0, 0 - 200, 0, null);
        // Direction -Z: perpendicular is (+1, 0) scaled by k = 0..1 -> x 0..1 is ice, x -1 and x 2... sign depends.
        int iceCells = 0;
        int railCells = 0;
        for (int x = -3; x <= 3; x++) {
            String f = at(s, x, 63, -10);
            if (f.equals("minecraft:packed_ice")) {
                iceCells++;
                assertEquals(SchematicVolume.AIR, at(s, x, 64, -10));
                assertEquals(SchematicVolume.AIR, at(s, x, 65, -10));
            } else if (f.equals("minecraft:netherrack")) {
                railCells++;
                assertEquals("minecraft:netherrack", at(s, x, 64, -10));
            }
        }
        assertEquals(2, iceCells);
        assertEquals(2, railCells);
    }

    @Test
    void diagonalFloorIsEdgeConnected() {
        HighwayDesign.Spec spec = new HighwayDesign.Spec();
        spec.mode = HighwayDesign.Mode.ICE;
        spec.width = 1;
        HighwayDesign.Segment s = HighwayDesign.segment(spec, 0, 64, 0, 50, 50, 0, null);
        for (int i = 0; i < 31; i++) {
            assertEquals("minecraft:packed_ice", at(s, i, 63, i));
            // Step i to i+1 goes through (i+1, i): edge-connected.
            assertEquals("minecraft:packed_ice", at(s, i + 1, 63, i));
            assertFalse(at(s, i, 64, i).equals("minecraft:netherrack"), "no rail over the path at " + i);
        }
        long first = s.digCells()[0];
        assertTrue(PackedPos.y(first) >= 64);
    }
}
