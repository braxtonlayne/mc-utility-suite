package com.utilitysuite.world.atlas;

import java.util.Arrays;

/** Engine-free centre-out chunk offsets within a square radius, packed (dx + 32768) &lt;&lt; 16 | (dz + 32768). */
final class AtlasSpiral {

    private AtlasSpiral() {
    }

    static int[] build(int radius) {
        int side = radius * 2 + 1;
        long[] keyed = new long[side * side];
        int k = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                keyed[k++] = (long) (dx * dx + dz * dz) << 32 | (((dx + 32768) << 16) | (dz + 32768)) & 0xFFFFFFFFL;
            }
        }
        Arrays.sort(keyed);
        int[] out = new int[keyed.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) keyed[i];
        }
        return out;
    }

    static int dx(int packed) {
        return (packed >>> 16) - 32768;
    }

    static int dz(int packed) {
        return (packed & 0xFFFF) - 32768;
    }
}
