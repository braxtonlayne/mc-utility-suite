package com.utilitysuite.combat.tactics.memory;

import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One small JSON file for {@link OpponentMemory}. The caller serialises on the client thread (once per fight); the
 * newest text is written {@value #DEBOUNCE_MS} ms later on a daemon thread, so a burst of fights costs one write
 * (temp file + atomic move). Loading happens lazily on the first fight that needs it.
 */
public final class OpponentMemoryStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/opponents");
    public static final long DEBOUNCE_MS = 5000;

    private final Path file;
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-OpponentWriter");
        t.setDaemon(true);
        return t;
    });

    public OpponentMemoryStore(Path file) {
        this.file = file;
    }

    public OpponentMemory load() {
        if (file == null || !Files.isRegularFile(file)) {
            return new OpponentMemory();
        }
        try {
            return OpponentMemory.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read opponent memory {}: {}", file, e.toString());
            return new OpponentMemory();
        }
    }

    /** Serialises now; writes the newest snapshot after the debounce delay. */
    public void saveDebounced(OpponentMemory memory) {
        memory.clearDirty();
        if (file == null) {
            return;
        }
        if (pending.getAndSet(memory.toJson().toString()) == null) {
            writer.schedule(this::writePending, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Writes anything pending now (shutdown, tests). */
    public void flush() {
        try {
            writer.submit(this::writePending).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Opponent memory flush did not complete", e);
        }
    }

    @Override
    public void close() {
        flush();
        writer.shutdown();
    }

    private void writePending() {
        String json = pending.getAndSet(null);
        if (json == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Could not write opponent memory {}", file, e);
        }
    }
}
