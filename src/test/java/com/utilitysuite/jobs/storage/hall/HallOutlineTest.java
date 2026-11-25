package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.jobs.planner.PackedPos;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The nearly-full outline pass: one box per chest (no four-mark cap), camera relative, allocation free. */
class HallOutlineTest {

    static final class Counting implements IHoloWorld {
        int boxes;
        float minX;
        float minY;
        HoloToken token;

        @Override
        public void ribbon(float x0, float y0, float z0, float x1, float y1, float z1, HoloToken token, float alpha) {
        }

        @Override
        public void polyline(float[] xyz, int count, float dash, HoloToken token, float alpha) {
        }

        @Override
        public void ring(float cx, float cy, float cz, float radius, HoloToken token, float alpha) {
        }

        @Override
        public void boxEdges(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, HoloToken token,
                             float alpha) {
            boxes++;
            this.minX = minX;
            this.minY = minY;
            this.token = token;
        }

        @Override
        public void topBrackets(float minX, float minZ, float maxX, float maxZ, float y, float arm, HoloToken token,
                                float alpha) {
        }

        @Override
        public void groundMark(float x, float y, float z, float size, HoloToken token, float alpha) {
        }
    }

    @Test
    void everyChestGetsABoxRelativeToTheCamera() {
        long[] pos = new long[40];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = PackedPos.pack(i, 64, 0);
        }
        Counting w = new Counting();
        HallSorterModule.outline(w, pos, pos.length, 10.5, 60.0, 0.0);
        assertEquals(40, w.boxes);
        assertEquals(39 - 10.5 - HallSorterModule.OUTSET, w.minX, 1e-4);
        assertEquals(4 - HallSorterModule.OUTSET, w.minY, 1e-4);
        assertEquals(HoloToken.WARN, w.token);
    }

    @Test
    void theOutlinePassAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        long[] pos = new long[64];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = PackedPos.pack(i % 8, 64 + i / 8, 3);
        }
        Counting w = new Counting();
        long bytes = AllocationProbe.measure(10_000, () -> HallSorterModule.outline(w, pos, pos.length, 1, 2, 3));
        assertEquals(0, bytes);
    }
}
