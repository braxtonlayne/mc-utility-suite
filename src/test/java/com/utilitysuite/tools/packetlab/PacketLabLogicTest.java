package com.utilitysuite.tools.packetlab;

import com.utilitysuite.core.AllocationProbe;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ring buffer, rates, correction bursts and the latency scheduler. */
class PacketLabLogicTest {

    @Test
    void ringBufferWrapsAndSnapshotsNewestInOrder() {
        PacketRingBuffer ring = new PacketRingBuffer(8);
        assertEquals(8, ring.capacity());
        for (int i = 0; i < 20; i++) {
            ring.record(1000 + i, i, (byte) (i % 2), i * 10);
        }
        assertEquals(20, ring.written());
        long[] t = new long[16];
        int[] type = new int[16];
        int[] size = new int[16];
        byte[] dir = new byte[16];
        int n = ring.snapshot(16, t, type, size, dir);
        assertEquals(8, n, "only the newest capacity records survive");
        for (int k = 0; k < n; k++) {
            assertEquals(12 + k, type[k], "oldest first");
            assertEquals(1012 + k, t[k]);
            assertEquals((12 + k) * 10, size[k]);
            assertEquals((12 + k) % 2, dir[k]);
        }
        n = ring.snapshot(3, t, type, size, dir);
        assertEquals(3, n);
        assertEquals(17, type[0]);
        assertEquals(19, type[2]);
    }

    @Test
    void capacityRoundsUpToAPowerOfTwo() {
        assertEquals(8, new PacketRingBuffer(5).capacity());
        assertEquals(8192, new PacketRingBuffer(8192).capacity());
    }

    @Test
    void outboundFrameSizeIsAddedToTheNewestOutboundRecord() {
        PacketRingBuffer ring = new PacketRingBuffer(4);
        ring.record(1, 7, PacketRingBuffer.OUTBOUND, PacketRingBuffer.UNKNOWN_SIZE);
        ring.record(2, 8, PacketRingBuffer.INBOUND, 50);
        ring.addSizeToLast(33);
        long[] t = new long[4];
        int[] type = new int[4];
        int[] size = new int[4];
        byte[] dir = new byte[4];
        assertEquals(2, ring.snapshot(4, t, type, size, dir));
        assertEquals(33, size[0]);
        assertEquals(50, size[1]);
        ring.clear();
        assertEquals(0, ring.snapshot(4, t, type, size, dir));
    }

    @Test
    void recordingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        PacketRingBuffer ring = new PacketRingBuffer(1024);
        RateRing rates = new RateRing(60, 4);
        PacketTypeTable types = new PacketTypeTable(String::valueOf);
        Object typeA = new Object();
        types.idOf(typeA);
        long bytes = AllocationProbe.measure(200_000, () -> {
            long now = System.currentTimeMillis();
            ring.record(now, types.idOf(typeA), PacketRingBuffer.INBOUND, 42);
            ring.addSizeToLast(10);
            rates.add(now / 1000L, 0, 1);
        });
        assertTrue(bytes < 16 * 1024, "allocated " + bytes + " bytes");
    }

    @Test
    void typeTableInternsOnce() {
        PacketTypeTable types = new PacketTypeTable(o -> "type-" + o);
        Object a = "a";
        Object b = "b";
        assertEquals(0, types.idOf(a));
        assertEquals(1, types.idOf(b));
        assertEquals(0, types.idOf(a));
        assertEquals("type-b", types.name(1));
        assertEquals("?", types.name(9));
        for (int i = 0; i < 200; i++) {
            types.idOf(Integer.valueOf(1000 + i));
        }
        assertEquals(202, types.size());
        assertEquals("type-1199", types.name(201));
    }

    @Test
    void rateRingBucketsPerSecondAndForgetsOldSeconds() {
        RateRing r = new RateRing(10, 2);
        r.add(100, 0, 3);
        r.add(100, 0, 2);
        r.add(101, 1, 7);
        assertEquals(5, r.get(100, 0));
        assertEquals(7, r.get(101, 1));
        assertEquals(12, r.sum(101, 2, 0) + r.sum(101, 2, 1));
        r.add(110, 0, 1); // reuses the bucket of second 100
        assertEquals(0, r.get(100, 0));
        assertEquals(1, r.get(110, 0));
        assertEquals(0, r.get(105, 0));
    }

    @Test
    void repeatedCorrectionsAreDetectedAsOneBurst() {
        CorrectionBursts b = new CorrectionBursts(3, 5000);
        assertFalse(b.record(0));
        assertFalse(b.record(10_000));
        assertFalse(b.record(12_000));
        assertTrue(b.record(13_000), "third correction within 5 s");
        assertTrue(b.record(14_000), "still the same burst");
        assertEquals(1, b.bursts());
        assertFalse(b.record(40_000));
        assertFalse(b.record(50_000));
        assertEquals(1, b.bursts());
        b.record(50_500);
        assertTrue(b.record(51_000));
        assertEquals(2, b.bursts());
    }

    @Test
    void latencySchedulerPreservesOrderEvenWithLargeJitter() {
        LatencyScheduler s = new LatencyScheduler(new SplittableRandom(42));
        long now = 0;
        long prevIn = Long.MIN_VALUE;
        long prevOut = Long.MIN_VALUE;
        for (int i = 0; i < 10_000; i++) {
            now += 100_000; // 0.1 ms between packets
            long in = s.nextDue(now, PacketRingBuffer.INBOUND, 50, 200);
            long out = s.nextDue(now, PacketRingBuffer.OUTBOUND, 50, 200);
            assertTrue(in >= now && out >= now, "never in the past");
            assertTrue(in >= prevIn, "inbound order preserved");
            assertTrue(out >= prevOut, "outbound order preserved");
            prevIn = in;
            prevOut = out;
        }
    }

    @Test
    void latencySchedulerWithoutJitterAddsExactlyTheLatencyAndDrainsInOrder() {
        LatencyScheduler s = new LatencyScheduler(new SplittableRandom(1));
        assertEquals(1_000_000_000L + 150_000_000L, s.nextDue(1_000_000_000L, PacketRingBuffer.INBOUND, 150, 0));
        // switched off while that packet is still queued: the next one may not overtake it
        assertEquals(1_150_000_000L, s.drainDue(1_000_000_001L, PacketRingBuffer.INBOUND));
        assertEquals(2_000_000_000L, s.drainDue(2_000_000_000L, PacketRingBuffer.INBOUND));
        assertEquals(5L, s.drainDue(5L, PacketRingBuffer.OUTBOUND), "directions are independent");
    }

    @Test
    void latencySimulatorAllowList() {
        assertTrue(LatencySimulator.isAllowedServer("localhost", "localhost,127.0.0.1"));
        assertTrue(LatencySimulator.isAllowedServer("127.0.0.1:25566", "localhost, 127.0.0.1"));
        assertTrue(LatencySimulator.isAllowedServer("Test.Example.org:25570", "test.example.org:25570"));
        assertFalse(LatencySimulator.isAllowedServer("play.example.net", "localhost,127.0.0.1"));
        assertFalse(LatencySimulator.isAllowedServer("", "localhost"));
        assertFalse(LatencySimulator.isAllowedServer("test.example.org:25565", "test.example.org:25570"));
    }

    @Test
    void timelineEventsKeepTheNewest() {
        TimelineEvents ev = new TimelineEvents(3);
        for (int i = 0; i < 5; i++) {
            ev.add(i, TimelineEvents.Kind.DIAGNOSTIC, "e" + i);
        }
        assertEquals(3, ev.size());
        assertEquals("e2", ev.label(0));
        assertEquals("e4", ev.label(2));
        assertEquals(4, ev.millis(2));
    }
}
