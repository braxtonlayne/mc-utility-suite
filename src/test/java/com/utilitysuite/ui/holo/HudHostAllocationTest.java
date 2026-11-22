package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** A host frame with elements in every slot, appearing, leaving and reflowing, allocates nothing. */
class HudHostAllocationTest {

    @AfterEach
    void tearDown() {
        FrameClock.resetForTests();
    }

    @Test
    void hostFrameAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        FrameClock.resetForTests();
        HudHost host = new HudHost(new AnimStore(256));
        host.sampleClock = false;
        HudHostTest.Box[] boxes = new HudHostTest.Box[12];
        HudSlot[] slots = HudSlot.values();
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = new HudHostTest.Box("box" + i, slots[i % slots.length], i % 3, 30 + i, 8 + i % 4);
            host.register(boxes[i]);
        }
        RecordingCanvas canvas = new RecordingCanvas();
        long[] nanos = {1_000_000_000L};
        int[] frame = {0};
        long bytes = AllocationProbe.measure(5_000, () -> {
            nanos[0] += 16_000_000L;
            FrameClock.sampleAt(nanos[0]);
            int f = ++frame[0];
            boxes[f % boxes.length].wants = (f / boxes.length) % 2 == 0; // elements come and go: fades and reflow
            canvas.reset();
            host.frame(canvas, (f & 63) == 0);
        });
        assertEquals(0L, bytes, "host frame allocated " + bytes + " bytes");
    }
}
