package com.utilitysuite.core.autopilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes black-box snapshots as compact JSON files (one per dump, the newest {@link #KEEP_FILES} kept) on a daemon
 * thread. Dumps closer than {@link #DEBOUNCE_TICKS} apart are dropped (a death right after a big hit writes once).
 */
public final class BlackBoxWriter {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/blackbox");
    public static final int DEBOUNCE_TICKS = 100;
    public static final int KEEP_FILES = 20;

    private static volatile ExecutorService executor;

    private volatile Path directory;
    private long lastDumpTick = Long.MIN_VALUE / 2;

    /** Where files go (game dir/utilitysuite/blackbox); null writes nothing. */
    public void setDirectory(Path dir) {
        this.directory = dir;
    }

    public Path directory() {
        return directory;
    }

    /** True when a dump at this tick is not debounced; marks it taken. Client thread. */
    public boolean claim(long nowTick) {
        if (nowTick - lastDumpTick < DEBOUNCE_TICKS) {
            return false;
        }
        lastDumpTick = nowTick;
        return true;
    }

    /** Formats and writes the snapshot off the client thread. */
    public void write(BlackBox.Snapshot snap) {
        Path dir = directory;
        if (dir == null) {
            return;
        }
        executor().execute(() -> {
            try {
                Files.createDirectories(dir);
                Path file = dir.resolve("blackbox-" + System.currentTimeMillis() + ".json");
                Files.writeString(file, snap.toJson(), StandardCharsets.UTF_8);
                prune(dir);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Black box write failed", e);
            }
        });
    }

    private static void prune(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            var list = files.filter(p -> p.getFileName().toString().startsWith("blackbox-")).sorted().toList();
            for (int i = 0; i < list.size() - KEEP_FILES; i++) {
                Files.deleteIfExists(list.get(i));
            }
        }
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (BlackBoxWriter.class) {
                e = executor;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "UtilitySuite-BlackBox");
                        t.setDaemon(true);
                        return t;
                    });
                    executor = e;
                }
            }
        }
        return e;
    }
}
