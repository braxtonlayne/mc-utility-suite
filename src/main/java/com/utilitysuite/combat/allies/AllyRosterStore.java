package com.utilitysuite.combat.allies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.config.ProfileIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Files for the {@link AllyRoster}: {@code <dir>/<profile file name>.json} per world/server and {@code <dir>/global.json}.
 * Snapshots are serialised on the calling thread and written atomically (temp file + move) on one daemon thread.
 */
public final class AllyRosterStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/allies");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String GLOBAL_FILE = "global";

    private final Path dir;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-AllyWriter");
        t.setDaemon(true);
        return t;
    });

    public AllyRosterStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public Path fileFor(String profileId) {
        // "global" is reserved for the global list; a profile literally named so gets a prefix.
        String name = ProfileIds.fileName(profileId);
        if (name.equalsIgnoreCase(GLOBAL_FILE)) {
            name = "profile_" + name;
        }
        return dir.resolve(name + ".json");
    }

    public Path globalFile() {
        return dir.resolve(GLOBAL_FILE + ".json");
    }

    public List<AllyEntry> loadProfile(String profileId) {
        return AllyRoster.fromJson(read(fileFor(profileId)), false);
    }

    public List<AllyEntry> loadGlobal() {
        return AllyRoster.fromJson(read(globalFile()), true);
    }

    /** Serialises both lists now and writes them off-thread. {@code profileId} null skips the world list. */
    public void saveAsync(AllyRoster roster, String profileId) {
        String globalJson = GSON.toJson(AllyRoster.toJson(roster.globalEntries()));
        String worldJson = profileId == null ? null : GSON.toJson(AllyRoster.toJson(roster.worldEntries()));
        Path worldPath = profileId == null ? null : fileFor(profileId);
        Path globalPath = globalFile();
        writer.execute(() -> {
            write(globalPath, globalJson);
            if (worldPath != null) {
                write(worldPath, worldJson);
            }
        });
    }

    /** Blocks until queued writes are done (tests, shutdown). */
    public void flush() {
        try {
            writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Ally roster flush did not complete", e);
        }
    }

    @Override
    public void close() {
        flush();
        writer.shutdown();
    }

    private static JsonObject read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read ally roster {}: {}", file, e.toString());
            return null;
        }
    }

    private static void write(Path file, String json) {
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
            LOG.error("Could not write ally roster {}", file, e);
        }
    }
}
