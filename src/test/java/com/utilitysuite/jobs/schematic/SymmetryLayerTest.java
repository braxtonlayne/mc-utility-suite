package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.gen.SymmetryTransform;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SymmetryLayerTest {

    @Test
    void radialLayerVolumeIsAnchoredAtTheCentre() {
        long centre = BlockPos.asLong(100, 64, -50);
        int[] images = SymmetryTransform.Mode.RADIAL_4.images();
        long[] keys = new long[images.length];
        String[] states = new String[images.length];
        for (int i = 0; i < images.length; i++) {
            int t = images[i];
            keys[i] = BlockPos.asLong(100 + SymmetryTransform.tx(t, 3, 1), 65, -50 + SymmetryTransform.tz(t, 3, 1));
            states[i] = SymmetryTransform.state(t, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]");
        }
        SchematicVolume v = SymmetryBuilder.layerVolume(centre, keys, states);
        assertEquals(3, v.blockCount());
        for (int i = 0; i < keys.length; i++) {
            int x = BlockPos.getX(keys[i]) - 100 - v.offsetX();
            int y = BlockPos.getY(keys[i]) - 64 - v.offsetY();
            int z = BlockPos.getZ(keys[i]) + 50 - v.offsetZ();
            assertEquals(states[i], v.state(x, y, z));
        }
        // the 90 degree image of (3,1) is (-1,3), facing south
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=straight]",
                v.state(-1 - v.offsetX(), 1 - v.offsetY(), 3 - v.offsetZ()));
    }

    @Test
    void generatedVolumesResolveByPath() {
        SchematicLibrary lib = new SchematicLibrary(List.of(Path.of("does-not-exist")));
        SchematicVolume v = SchematicVolume.builder("x", "generated", 1, 1, 1).set(0, 0, 0, "stone").build();
        String path = lib.registerGenerated("shape_a", v);
        assertEquals("generated:shape_a", path);
        assertSame(v, lib.generated(path));
    }
}
