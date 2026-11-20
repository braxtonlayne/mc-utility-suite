package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.format.SchematicFormats;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Finds schematic files in {@code <gameDir>/schematics/} (Litematica's folder) and
 * {@code <gameDir>/config/utilitysuite/schematics/}, and parses them on a single low-priority daemon thread. Every
 * method returns a future; nothing here blocks the client thread.
 */
public final class SchematicLibrary {

    private final List<Path> roots;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "utilitysuite-schematic");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public SchematicLibrary(Path gameDir) {
        this.roots = List.of(gameDir.resolve("schematics"), gameDir.resolve("config").resolve("utilitysuite")
                .resolve("schematics"));
    }

    /** Package-private for tests: explicit roots. */
    SchematicLibrary(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    public List<Path> roots() {
        return roots;
    }

    public ExecutorService executor() {
        return executor;
    }

    /** All schematic files under the roots (recursively, depth 4), sorted by path. */
    public CompletableFuture<List<Path>> list() {
        return CompletableFuture.supplyAsync(() -> {
            List<Path> out = new ArrayList<>();
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> s = Files.walk(root, 4)) {
                    s.filter(Files::isRegularFile).filter(SchematicFormats::isSchematicFile).forEach(out::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            out.sort(null);
            return out;
        }, executor);
    }

    /**
     * Resolves a user-given path: absolute paths as-is; otherwise relative to each root, trying the name as given and
     * with each known extension. Returns {@code null} when nothing exists. Touches the file system: call off-thread
     * (or accept a few stat calls on the client thread for a single user command).
     */
    public Path resolve(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) {
            return Files.isRegularFile(p) ? p : null;
        }
        String[] exts = {"", ".litematic", ".schem", ".nbt", ".schematic"};
        for (Path root : roots) {
            for (String ext : exts) {
                Path candidate = root.resolve(path + ext);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public CompletableFuture<SchematicVolume> load(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return SchematicFormats.read(file);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
            }
        }, executor);
    }

    /** Runs background analysis work on the schematic thread. */
    public <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    static String displayName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).toLowerCase(Locale.ROOT);
    }
}
