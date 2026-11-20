package com.utilitysuite.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.api.ProfileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JSON persistence under {@code <gameDir>/config/utilitysuite/}: {@code global.json} and
 * {@code profiles/<sanitized id>.json}. Pure Java (Gson + NIO), no Minecraft classes.
 * <p>
 * Reads are synchronous (login, small files). A corrupt or unreadable file is moved aside to
 * {@code <name>.corrupt-<millis>.json} and replaced by defaults; loading never throws. Writes are atomic (temp file +
 * move). {@link #writeAsync} snapshots the JSON on the calling (client) thread and writes on a single daemon thread.
 */
public final class ConfigStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/config");

    private final Path root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-ConfigWriter");
        t.setDaemon(true);
        return t;
    });

    public ConfigStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path fileFor(String profileId) {
        if (ProfileIds.GLOBAL.equals(profileId)) {
            return root.resolve("global.json");
        }
        return root.resolve("profiles").resolve(ProfileIds.fileName(profileId) + ".json");
    }

    /** Loads a profile, or defaults (empty) when the file is missing or corrupt. Never throws. */
    public ProfileConfig load(String profileId) {
        Path file = fileFor(profileId);
        if (!Files.isRegularFile(file)) {
            return new ProfileConfig(profileId, new JsonObject());
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(text);
            if (el != null && el.isJsonObject()) {
                return new ProfileConfig(profileId, el.getAsJsonObject());
            }
            LOG.warn("Config {} is not a JSON object; replacing with defaults", file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Config {} could not be read ({}); replacing with defaults", file, e.toString());
        }
        backupCorrupt(file);
        ProfileConfig fresh = new ProfileConfig(profileId, new JsonObject());
        writeNow(fresh);
        return fresh;
    }

    private void backupCorrupt(Path file) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        Path backup = file.resolveSibling(base + ".corrupt-" + System.currentTimeMillis() + ".json");
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOG.warn("Backed up corrupt config to {}", backup);
        } catch (IOException e) {
            LOG.error("Could not back up corrupt config {}", file, e);
        }
    }

    /** Snapshot now (caller's thread), write later on the writer thread. */
    public void writeAsync(ProfileConfig profile) {
        String id = profile.profileId();
        JsonObject snapshot = profile.root().deepCopy();
        writer.execute(() -> write(id, snapshot));
    }

    /** Synchronous write (logout, shutdown). Never throws. */
    public void writeNow(ProfileConfig profile) {
        write(profile.profileId(), profile.root().deepCopy());
    }

    /** Waits for queued writes (tests, shutdown). */
    public void flush() {
        try {
            writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Config flush interrupted: {}", e.toString());
        }
    }

    private synchronized void write(String profileId, JsonObject json) {
        Path file = fileFor(profileId);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not write config {}", file, e);
        }
    }

    @Override
    public void close() {
        flush();
        writer.shutdown();
    }
}
