package com.utilitysuite.core.autopilot;

/**
 * Time source for the danger model and the reflex scheduler, in 20 Hz ticks. The default counts wall-clock ticks so
 * reports age even while the autopilot module itself is off (its {@code onTick} only runs while it is on). Tests use
 * a manual clock. Allocation free.
 */
@FunctionalInterface
public interface TickClock {

    long now();

    /** Wall-clock ticks (50 ms each) from {@link System#nanoTime()}. */
    TickClock SYSTEM = () -> System.nanoTime() / 50_000_000L;
}
