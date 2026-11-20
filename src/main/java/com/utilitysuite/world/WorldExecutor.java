package com.utilitysuite.world;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One low-priority daemon thread shared by the world modules for heavy or blocking work (ore simulation, seed
 * comparison, ledger and waypoint writes). Work items publish immutable results; nothing here touches the level.
 */
public final class WorldExecutor {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-World");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private WorldExecutor() {
    }

    public static ExecutorService get() {
        return EXECUTOR;
    }
}
