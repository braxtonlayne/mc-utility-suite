package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LevelShapes} steady state (a new tick over cells whose states are already in the session table) and the shaped
 * {@link MovementPhysics} tick allocate nothing. The level is replaced by the engine-free cell source seam.
 */
class LevelShapesAllocationTest {

    private static LevelShapes shapes() {
        LevelShapes s = new LevelShapes();
        int air = s.defineEntry(null, MovementPhysics.SURFACE_NONE, 0.6f);
        int stone = s.defineEntry(new float[]{0, 0, 0, 1, 1, 1}, MovementPhysics.SURFACE_NORMAL, 0.6f);
        int fence = s.defineEntry(new float[]{0.375f, 0, 0.375f, 0.625f, 1.5f, 0.625f},
                MovementPhysics.SURFACE_NORMAL, 0.6f);
        int ice = s.defineEntry(new float[]{0, 0, 0, 1, 1, 1}, MovementPhysics.SURFACE_ICE, 0.98f);
        s.begin((x, y, z) -> {
            if (y == 0 && z >= -4 && z < 3 && x >= -4 && x <= 4) {
                return stone;
            }
            if (y == 0 && z == 4 && x == 0) {
                return fence;
            }
            if (y == 0 && z > 5 && x >= -4 && x <= 4) {
                return ice;
            }
            return air;
        });
        return s;
    }

    @Test
    void answersShapesSurfacesAndSlipperinessFromTheTable() {
        LevelShapes s = shapes();
        assertTrue(s.shaped());
        assertEquals(LevelShapes.SHAPE_CUBE, s.shapeId(0, 0, 0));
        assertTrue(s.fullBlock(0, 0, 0));
        assertEquals(1, s.boxCount(0, 0, 4));
        assertFalse(s.fullBlock(0, 0, 4));
        float[] b = new float[6];
        s.box(0, 0, 4, 0, b, 0);
        assertEquals(1.5f, b[4]);
        assertEquals(0, s.boxCount(0, 1, 0));
        assertFalse(s.solid(0, 1, 0));
        assertEquals(MovementPhysics.SURFACE_ICE, s.surface(0, 0, 8));
        assertEquals(0.98f, s.slipperiness(0, 0, 8));
        assertEquals(LevelShapes.SHAPE_CUBE, s.shapeId(0, 0, 8), "ice shares the cube shape id");
        MovementPhysics p = new MovementPhysics().set(0.5, 3, 4.5, 0, 0, 0, false);
        for (int t = 0; t < 30; t++) {
            p.tick(0, 0, 0f, false, false, s);
        }
        assertEquals(1.5, p.y, 1e-9, "fence top through LevelShapes");
    }

    @Test
    void steadyStateLookupsAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        LevelShapes s = shapes();
        LevelShapes.CellSource src = (x, y, z) -> (x + y + z) & 3;
        float[] out = new float[6];
        long[] sink = {0};
        long bytes = AllocationProbe.measure(2_000, () -> {
            s.begin(src);
            long acc = 0;
            for (int x = -6; x < 6; x++) {
                for (int y = -1; y < 3; y++) {
                    for (int z = -6; z < 6; z++) {
                        int n = s.boxCount(x, y, z);
                        for (int i = 0; i < n; i++) {
                            s.box(x, y, z, i, out, 0);
                        }
                        acc += n + s.surface(x, y, z) + (s.solid(x, y, z) ? 1 : 0) + (long) s.slipperiness(x, y, z);
                    }
                }
            }
            sink[0] += acc;
        });
        assertEquals(0, bytes, "bytes allocated by 2000 ticks of 576 cell lookups");
        assertTrue(sink[0] != 0);
    }

    @Test
    void shapedTickAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ShapeGrid g = new ShapeGrid().fill(-3, 0, -3, 3, 0, 2).fencePost(0, 0, 4).slabBottom(0, 1, 2);
        MovementPhysics p = new MovementPhysics();
        int[] k = {0};
        long bytes = AllocationProbe.measure(2_000, () -> {
            p.set(0.5, 1, 0.5, 0, -0.0784, 0, true);
            p.crouching = false;
            p.jumpDelay = 0;
            int i = k[0]++;
            for (int t = 0; t < 20; t++) {
                p.tick(1, (i & 1) == 0 ? 0 : 1, (i % 7) * 13f, (i & 2) != 0, t == 3, (i & 4) != 0, g);
            }
        });
        assertEquals(0, bytes, "bytes allocated by 40000 shaped ticks");
    }
}
