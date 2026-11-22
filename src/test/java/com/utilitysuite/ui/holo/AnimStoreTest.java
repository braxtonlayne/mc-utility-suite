package com.utilitysuite.ui.holo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.visual.HoloToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnimStoreTest {

    private long nanos;

    @BeforeEach
    void setUp() {
        FrameClock.resetForTests();
        HoloTheme.motion = Motion.FULL;
        nanos = 1_000_000_000L;
        FrameClock.sampleAt(nanos);
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = Motion.FULL;
        FrameClock.resetForTests();
    }

    private void frame(long ms) {
        nanos += ms * 1_000_000L;
        FrameClock.sampleAt(nanos);
    }

    @Test
    void settleStartsAtInitialAndConvergesWithoutOvershoot() {
        AnimStore s = new AnimStore(64);
        long k = AnimStore.key("row", 3);
        float prev = s.settle(k, 10f, AnimStore.TAU_NORMAL, 0f);
        assertEquals(0f, prev, 1e-6f, "first frame has no elapsed time");
        for (int i = 0; i < 60; i++) {
            frame(16);
            float v = s.settle(k, 10f, AnimStore.TAU_NORMAL, 0f);
            assertTrue(v >= prev && v <= 10f, "monotonic, never past the target");
            prev = v;
        }
        assertEquals(10f, prev, 1e-3f);
    }

    @Test
    void motionOffIsInstant() {
        HoloTheme.motion = Motion.OFF;
        AnimStore s = new AnimStore(64);
        assertEquals(7f, s.settle(AnimStore.key("a", 0), 7f, AnimStore.TAU_SLOW, 0f), 0f);
        assertEquals(1f, s.progress(AnimStore.key("b", 0), true, 0.2f), 0f);
        assertEquals(0, s.size(), "nothing is stored while motion is off");
    }

    @Test
    void progressRunsBothWaysOverItsDuration() {
        AnimStore s = new AnimStore(64);
        long k = AnimStore.key("toggle", 0);
        assertEquals(0f, s.progress(k, false, 0.1f), 0f);
        float v = 0f;
        for (int i = 0; i < 5; i++) {
            frame(20);
            v = s.progress(k, true, 0.1f);
        }
        assertEquals(1f, v, 1e-4f);
        frame(50);
        assertEquals(0.5f, s.progress(k, false, 0.1f), 1e-4f);
    }

    @Test
    void idleEntriesAreReclaimedAndLiveOnesKept() {
        AnimStore s = new AnimStore(16); // holds 8
        for (int i = 0; i < 8; i++) {
            s.set(AnimStore.key("old", i), i);
        }
        frame(AnimStore.IDLE_MS + 100);
        long live = AnimStore.key("live", 0);
        s.set(AnimStore.key("old", 0), 42f); // touched again: survives
        s.set(live, 5f); // forces the reclaim
        assertEquals(5f, s.peek(live, -1f), 0f);
        assertEquals(42f, s.peek(AnimStore.key("old", 0), -1f), 0f);
        assertEquals(-1f, s.peek(AnimStore.key("old", 5), -1f), 0f);
        assertEquals(2, s.size());
    }

    @Test
    void fullStoreDegradesToTheTarget() {
        AnimStore s = new AnimStore(8);
        for (int i = 0; i < 16; i++) {
            assertEquals(3f, s.settle(AnimStore.key("k", i), 3f, AnimStore.TAU_FAST), 0f);
        }
        assertTrue(s.size() <= 5);
    }

    @Test
    void easeOutBackEndsAtOneWithSmallOvershoot() {
        assertEquals(0f, AnimStore.easeOutBack(0f), 0f);
        assertEquals(1f, AnimStore.easeOutBack(1f), 0f);
        float max = 0f;
        for (int i = 0; i <= 100; i++) {
            max = Math.max(max, AnimStore.easeOutBack(i / 100f));
        }
        assertTrue(max > 1.02f && max < 1.12f, "overshoot " + max);
    }

    @Test
    void accentDerivesTheLineFamilyAndStatusHuesStay() {
        int danger = HoloTheme.argb(HoloToken.DANGER);
        HoloTheme.setAccent(0x4DFFA0);
        try {
            assertEquals(0xFF4DFFA0, HoloTheme.argb(HoloToken.ACCENT));
            assertEquals(0x4DFFA0, HoloTheme.argb(HoloToken.LINE_FAINT) & 0xFFFFFF);
            assertEquals(danger, HoloTheme.argb(HoloToken.DANGER));
            assertEquals(4, HoloTheme.bracketArm(10, 10));
            assertEquals(10, HoloTheme.bracketArm(400, 300));
        } finally {
            HoloTheme.setAccent(HoloTheme.DEFAULT_ACCENT_RGB);
        }
    }

    @Test
    void steppingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        AnimStore s = new AnimStore(256);
        long[] keys = new long[64];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = AnimStore.key("row", i);
        }
        Runnable body = () -> {
            for (int i = 0; i < keys.length; i++) {
                s.settle(keys[i], i, AnimStore.TAU_NORMAL, 0f);
                s.progress(keys[i] ^ 0x55L, (i & 1) == 0, 0.14f);
                HoloTheme.argb(HoloToken.LINE, 0.5f);
            }
        };
        for (int i = 0; i < 2_000; i++) {
            body.run();
        }
        long bytes = AllocationProbe.measure(2_000, body);
        assertTrue(bytes < 2_000, "allocated " + bytes + " bytes over 2000 iterations");
    }
}
