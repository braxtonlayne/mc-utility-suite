package com.utilitysuite.world.atlas;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-frame atlas paths (mesh rebuild, projection, region compose, hit test) allocate nothing. */
class AtlasAllocationTest {

    @Test
    void frameMathIsAllocationFree() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        AtlasTileStore store = new AtlasTileStore(256);
        for (int x = -4; x < 4; x++) {
            for (int z = -4; z < 4; z++) {
                store.markWritten(store.obtain(x, z), 0);
            }
        }
        AtlasMesh mesh = new AtlasMesh(48);
        AtlasIso iso = new AtlasIso();
        AtlasView view = new AtlasView();
        view.setViewport(800, 600);
        AtlasPins pins = new AtlasPins(64);
        for (int i = 0; i < 64; i++) {
            pins.add(i % AtlasPins.KINDS, i * 3, 64, -i * 2, 0xFFFFFFFF, "p");
        }
        int[] compose = new int[AtlasTileStore.REGION_BLOCKS * AtlasTileStore.REGION_BLOCKS];
        int[] tick = {0};
        long bytes = AllocationProbe.measure(200, () -> {
            store.markWritten(store.get(0, 0), tick[0]++); // forces a mesh rebuild every call
            mesh.rebuildIfNeeded(store, 0, 0, 2, 64, 0);
            iso.rotate(0.01, 0);
            for (int i = 0; i < mesh.height.length; i++) {
                iso.project(i % mesh.side, mesh.height[i] - 64, i / mesh.side);
            }
            view.zoomAt(100, 100, 1.0);
            for (int i = 0; i < pins.count(); i++) {
                pins.screenX[i] = view.toScreenX(pins.x[i]);
                pins.screenY[i] = view.toScreenY(pins.z[i]);
            }
            pins.hitTest(400, 300, 6, 31);
            store.composeRegion(0, 0, compose, 0);
        });
        assertTrue(bytes < 1024, "allocated " + bytes + " bytes");
    }
}
