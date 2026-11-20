package com.utilitysuite.core;

import java.lang.management.ManagementFactory;

/** Measures bytes allocated by the current thread (HotSpot's com.sun.management extension). */
public final class AllocationProbe {

    private static final com.sun.management.ThreadMXBean BEAN =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private AllocationProbe() {
    }

    public static boolean supported() {
        return BEAN.isThreadAllocatedMemorySupported() && BEAN.isThreadAllocatedMemoryEnabled();
    }

    /** Bytes allocated by this thread while running {@code body} {@code iterations} times (after a warm-up). */
    public static long measure(int iterations, Runnable body) {
        for (int i = 0; i < 20_000; i++) {
            body.run(); // warm-up so the JIT has compiled (and escape-analysed) the path
        }
        long tid = Thread.currentThread().threadId();
        long before = BEAN.getThreadAllocatedBytes(tid);
        for (int i = 0; i < iterations; i++) {
            body.run();
        }
        return BEAN.getThreadAllocatedBytes(tid) - before;
    }
}
