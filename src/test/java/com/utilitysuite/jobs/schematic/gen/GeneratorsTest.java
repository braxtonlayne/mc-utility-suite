package com.utilitysuite.jobs.schematic.gen;

import com.utilitysuite.jobs.schematic.gen.ShapeVoxelizer.Kind;
import com.utilitysuite.jobs.schematic.gen.ShapeVoxelizer.Params;
import com.utilitysuite.jobs.schematic.gen.SymmetryTransform.Mode;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorsTest {

    // ---- shapes -----------------------------------------------------------------------------------------------------

    private static boolean at(SchematicVolume v, int wx, int wy, int wz) {
        int x = wx - v.offsetX(), y = wy - v.offsetY(), z = wz - v.offsetZ();
        return v.contains(x, y, z) && v.isBlock(v.paletteIndex(x, y, z));
    }

    @Test
    void sphereIsSymmetricAndHollowIsSmaller() {
        SchematicVolume solid = ShapeVoxelizer.voxelize(Params.of(Kind.SPHERE, 6, 1, 0, "stone"));
        SchematicVolume hollow = ShapeVoxelizer.voxelize(Params.of(Kind.SPHERE, 6, 1, 1, "stone"));
        assertEquals(13, solid.sizeX());
        assertEquals(-6, solid.offsetY());
        for (int x = -6; x <= 6; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -6; z <= 6; z++) {
                    boolean a = at(solid, x, y, z);
                    assertEquals(a, at(solid, -x, y, z));
                    assertEquals(a, at(solid, x, -y, z));
                    assertEquals(a, at(solid, z, y, x));
                    assertEquals(at(hollow, x, y, z), at(hollow, -x, -y, -z));
                }
            }
        }
        assertTrue(at(solid, 0, 0, 0));
        assertFalse(at(hollow, 0, 0, 0));
        assertTrue(at(hollow, 6, 0, 0));
        assertTrue(hollow.blockCount() < solid.blockCount() / 2);
        // volume close to 4/3 pi r^3 (r + 0.5)
        double expect = 4.0 / 3 * Math.PI * Math.pow(6.5, 3);
        assertTrue(Math.abs(solid.blockCount() - expect) / expect < 0.08, "count " + solid.blockCount());
    }

    @Test
    void hollowShellIsWatertight() {
        SchematicVolume hollow = ShapeVoxelizer.voxelize(Params.of(Kind.SPHERE, 8, 1, 1, "stone"));
        // flood from the centre through empty cells must never leave the box
        int sx = hollow.sizeX(), sy = hollow.sizeY(), sz = hollow.sizeZ();
        boolean[] seen = new boolean[sx * sy * sz];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[]{8, 8, 8});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int x = c[0], y = c[1], z = c[2];
            int i = hollow.index(x, y, z);
            if (seen[i] || hollow.isBlock(hollow.paletteIndex(i))) {
                continue;
            }
            assertTrue(x > 0 && y > 0 && z > 0 && x < sx - 1 && y < sy - 1 && z < sz - 1, "leak at " + x + "," + y + "," + z);
            seen[i] = true;
            q.add(new int[]{x + 1, y, z});
            q.add(new int[]{x - 1, y, z});
            q.add(new int[]{x, y + 1, z});
            q.add(new int[]{x, y - 1, z});
            q.add(new int[]{x, y, z + 1});
            q.add(new int[]{x, y, z - 1});
        }
    }

    @Test
    void domeHasNoFloorWhenHollowAndCylinderHasNoCaps() {
        SchematicVolume dome = ShapeVoxelizer.voxelize(Params.of(Kind.DOME, 5, 1, 1, "stone"));
        assertEquals(0, dome.offsetY());
        assertEquals(6, dome.sizeY());
        assertFalse(at(dome, 0, 0, 0));
        assertTrue(at(dome, 5, 0, 0));
        assertTrue(at(dome, 0, 5, 0));
        SchematicVolume cyl = ShapeVoxelizer.voxelize(Params.of(Kind.CYLINDER, 4, 7, 1, "stone"));
        assertEquals(7, cyl.sizeY());
        assertFalse(at(cyl, 0, 0, 0));
        assertFalse(at(cyl, 0, 6, 0));
        assertTrue(at(cyl, 4, 3, 0));
        SchematicVolume solidCyl = ShapeVoxelizer.voxelize(Params.of(Kind.CYLINDER, 4, 7, 0, "stone"));
        assertEquals(0, solidCyl.blockCount() % 7, "same disc on every layer");
    }

    @Test
    void torusHasAHoleAndArchHasAnOpening() {
        SchematicVolume torus = ShapeVoxelizer.voxelize(Params.of(Kind.TORUS, 8, 2, 0, "stone"));
        assertFalse(at(torus, 0, 0, 0), "hole in the middle");
        assertTrue(at(torus, 8, 0, 0));
        assertTrue(at(torus, 0, 0, -8));
        assertTrue(at(torus, -8, 2, 0));
        assertFalse(at(torus, 8, 3, 0));
        SchematicVolume hollowTorus = ShapeVoxelizer.voxelize(Params.of(Kind.TORUS, 8, 3, 1, "stone"));
        assertFalse(at(hollowTorus, 8, 0, 0), "tube is hollow");
        assertTrue(hollowTorus.blockCount() < torus.blockCount() * 3);

        SchematicVolume arch = ShapeVoxelizer.voxelize(new Params(Kind.ARCH, 5, 3, 1, "stone", 0, 0, 0, 2));
        assertTrue(at(arch, 5, 0, 0) && at(arch, -5, 0, 0), "legs");
        assertFalse(at(arch, 0, 0, 0), "walk-through opening");
        assertFalse(at(arch, 0, 7, 0));
        assertTrue(at(arch, 0, 8, 0), "keystone at legs + radius");
        assertEquals(2, arch.sizeZ());
        for (int y = 0; y < arch.sizeY(); y++) {
            for (int x = 0; x < arch.sizeX(); x++) {
                assertEquals(arch.isBlock(arch.paletteIndex(x, y, 0)),
                        arch.isBlock(arch.paletteIndex(arch.sizeX() - 1 - x, y, 0)), "mirror symmetric");
            }
        }
    }

    @Test
    void bridgeSpansStartToEndWithWidth() {
        SchematicVolume bridge = ShapeVoxelizer.voxelize(new Params(Kind.BRIDGE, 1, 1, 1, "oak_planks", 10, 5, 0, 3));
        assertTrue(at(bridge, 0, 0, 0));
        assertTrue(at(bridge, 10, 5, 0));
        assertTrue(at(bridge, 10, 5, -1) && at(bridge, 10, 5, 1));
        assertEquals(3, bridge.sizeZ());
        // every column along the span has exactly one top block per width row, rising monotonically
        int lastTop = -1;
        for (int x = 0; x <= 10; x++) {
            int top = -1;
            for (int y = 0; y <= 5; y++) {
                if (at(bridge, x, y, 0)) {
                    top = y;
                }
            }
            assertTrue(top >= lastTop && top - lastTop <= 1, "no gaps at x=" + x);
            lastTop = top;
        }
        assertEquals("minecraft:oak_planks", bridge.paletteEntry(1));
    }

    // ---- symmetry ---------------------------------------------------------------------------------------------------

    @Test
    void transformsFormTheSquareGroup() {
        Set<String> images = new HashSet<>();
        for (int t = 0; t < 8; t++) {
            images.add(SymmetryTransform.tx(t, 2, 1) + "," + SymmetryTransform.tz(t, 2, 1));
            // a position transform agrees with the direction transform
            assertEquals(SymmetryTransform.direction(t, "north"),
                    dir(SymmetryTransform.tx(t, 0, -1), SymmetryTransform.tz(t, 0, -1)));
            assertEquals(SymmetryTransform.direction(t, "east"),
                    dir(SymmetryTransform.tx(t, 1, 0), SymmetryTransform.tz(t, 1, 0)));
        }
        assertEquals(8, images.size(), "eight distinct images of an off-axis point");
        assertEquals("-2,1", SymmetryTransform.tx(4, 2, 1) + "," + SymmetryTransform.tz(4, 2, 1));
        assertEquals("2,-1", SymmetryTransform.tx(6, 2, 1) + "," + SymmetryTransform.tz(6, 2, 1));
        assertEquals(7, Mode.RADIAL_8.images().length);
        assertEquals(Mode.RADIAL_4, Mode.parse("radial 4"));
        assertEquals(Mode.MIRROR_X, Mode.parse("nonsense"));
    }

    private static String dir(int x, int z) {
        return x == 1 ? "east" : x == -1 ? "west" : z == 1 ? "south" : "north";
    }

    @Test
    void blockStatesFollowTheTransform() {
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=inner_right]",
                SymmetryTransform.state(4, "minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left]"));
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=straight]",
                SymmetryTransform.state(1, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"));
        assertEquals("minecraft:oak_log[axis=z]", SymmetryTransform.state(1, "minecraft:oak_log[axis=x]"));
        assertEquals("minecraft:oak_log[axis=y]", SymmetryTransform.state(3, "minecraft:oak_log[axis=y]"));
        assertEquals("minecraft:oak_fence[east=true,north=false,south=false,west=false]",
                SymmetryTransform.state(1, "minecraft:oak_fence[east=false,north=true,south=false,west=false]"));
        assertEquals("minecraft:oak_door[facing=north,hinge=right]",
                SymmetryTransform.state(6, "minecraft:oak_door[facing=south,hinge=left]"));
        assertEquals("minecraft:oak_sign[rotation=12]", SymmetryTransform.state(4, "minecraft:oak_sign[rotation=4]"));
        assertEquals("minecraft:oak_sign[rotation=8]", SymmetryTransform.state(6, "minecraft:oak_sign[rotation=0]"));
        assertEquals("minecraft:oak_sign[rotation=4]", SymmetryTransform.state(1, "minecraft:oak_sign[rotation=0]"));
        assertEquals("minecraft:rail[shape=east_west]", SymmetryTransform.state(1, "minecraft:rail[shape=north_south]"));
        assertEquals("minecraft:rail[shape=north_east]", SymmetryTransform.state(1, "minecraft:rail[shape=north_west]"));
        assertEquals("minecraft:rail[shape=ascending_south]",
                SymmetryTransform.state(1, "minecraft:rail[shape=ascending_east]"));
        assertEquals("minecraft:stone", SymmetryTransform.state(5, "minecraft:stone"));
        assertEquals("minecraft:torch_wall[facing=up]", SymmetryTransform.state(3, "minecraft:torch_wall[facing=up]"));
        // applying a mirror twice is the identity
        String s = "minecraft:oak_stairs[facing=north,half=top,shape=outer_left]";
        assertEquals(s, SymmetryTransform.state(4, SymmetryTransform.state(4, s)));
    }

    // ---- map art ----------------------------------------------------------------------------------------------------

    @Test
    void paletteTableIsCompleteAndShadesMatchVanilla() {
        assertEquals(62, MapArtPalette.BASE.length);
        assertEquals(62, MapArtPalette.BLOCK.length);
        assertEquals(0x6D9930, MapArtPalette.shade(0x7FB238, 220)); // grass at normal shade
        MapArtPalette flat = MapArtPalette.of(false, true);
        MapArtPalette stairs = MapArtPalette.of(true, true);
        assertEquals(flat.size() * 3, stairs.size());
        for (int i = 0; i < flat.size(); i++) {
            assertEquals(220, flat.entry(i).shade());
            assertNotNull(flat.entry(i).block());
            assertFalse(flat.entry(i).block().contains("diamond"));
        }
        int white = flat.nearest(255 * 220 / 255.0, 255 * 220 / 255.0, 255 * 220 / 255.0);
        assertEquals("minecraft:white_wool", flat.entry(white).block());
    }

    @Test
    void ditherKeepsAverageColourAndFlatQuantiseIsExact() {
        MapArtPalette flat = MapArtPalette.of(false, true);
        int w = 32, h = 32;
        int grey = 0x808080; // between palette greys: dithering mixes them
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, grey);
        MapArtPlanner.Quantized q = MapArtPlanner.quantize(px, w, h, flat, true);
        double sum = 0;
        Set<Integer> used = new HashSet<>();
        for (int e : q.entry()) {
            sum += (flat.entry(e).rgb() >> 8) & 0xFF;
            used.add(e);
        }
        assertEquals(0x80, sum / (w * h), 6);
        assertTrue(used.size() >= 2, "dither mixes colours");
        // an exact palette colour stays exact without dithering
        int exact = flat.entry(5).rgb();
        java.util.Arrays.fill(px, exact);
        MapArtPlanner.Quantized q2 = MapArtPlanner.quantize(px, w, h, flat, false);
        for (int e : q2.entry()) {
            assertEquals(exact, flat.entry(e).rgb());
        }
    }

    @Test
    void staircaseHeightsReproduceTheShades() {
        MapArtPalette stairs = MapArtPalette.of(true, true);
        int w = 4, h = 64;
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int z = i / w;
            px[i] = MapArtPalette.shade(0x7FB238, z % 3 == 0 ? 255 : z % 3 == 1 ? 180 : 220);
        }
        MapArtPlanner.Quantized q = MapArtPlanner.quantize(px, w, h, stairs, false);
        int[] hts = MapArtPlanner.staircase(q);
        for (int x = 0; x < w; x++) {
            int min = Integer.MAX_VALUE;
            for (int z = 0; z <= h; z++) {
                min = Math.min(min, hts[z * w + x]);
            }
            assertEquals(0, min, "lowest block at 0");
            for (int z = 0; z < h; z++) {
                int d = hts[(z + 1) * w + x] - hts[z * w + x];
                int shade = stairs.entry(q.entry()[z * w + x]).shade();
                assertEquals(shade == 255 ? 1 : shade == 180 ? -1 : 0, d);
            }
        }
        SchematicVolume v = MapArtPlanner.toVolume("t", q, true);
        assertEquals(h + 1, v.sizeZ());
        assertEquals(w * (h + 1), v.blockCount(), "one block per pixel plus the reference row");
    }

    @Test
    void fullPipelineBuildsOneMapFlat() {
        int sw = 300, sh = 200;
        int[] px = new int[sw * sh];
        for (int i = 0; i < px.length; i++) {
            int x = i % sw;
            px[i] = 0xFF000000 | ((x * 255 / sw) << 16) | 0x4020;
        }
        SchematicVolume v = MapArtPlanner.plan("art", px, sw, sh, new MapArtPlanner.Options(2, false, true, true));
        assertEquals(256, v.sizeX());
        assertEquals(1, v.sizeY());
        assertEquals(129, v.sizeZ());
        assertEquals(256 * 129, v.blockCount());
        assertFalse(MapArtPlanner.blockCounts(v).isEmpty());
    }
}
