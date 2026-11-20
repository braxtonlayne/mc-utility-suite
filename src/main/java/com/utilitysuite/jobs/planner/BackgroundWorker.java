package com.utilitysuite.jobs.planner;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared single daemon thread for the jobs area: plan computation, dry-run previews and storage-index writes. Work
 * items publish immutable results through volatile fields that the client thread polls; nothing here touches the world.
 */
public final class BackgroundWorker {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-Jobs");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private BackgroundWorker() {
    }

    public static ExecutorService executor() {
        return EXECUTOR;
    }
}
