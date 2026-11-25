package com.utilitysuite.world.replay;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The saved kill-cam clips of one world: files {@code clip-<savedMs>.urc.gz} in one directory, at most
 * {@link #MAX_CLIPS} (saving another deletes the oldest). Every file operation runs on the given executor (the world
 * executor in the game); the list is an immutable snapshot published after each change, so the menu reads it without
 * I/O. Engine-free apart from {@code java.nio}.
 */
public final class ClipShelf {

    public static final int MAX_CLIPS = 10;
    private static final String PREFIX = "clip-";
    private static final String SUFFIX = ".urc.gz";

    /** One saved clip: its file name (the id) and header. */
    public record Entry(String id, ReplayClipCodec.Info info, long bytes) {
    }

    private final Executor executor;
    private volatile Path dir;
    private volatile List<Entry> entries = List.of();

    public ClipShelf(Executor executor) {
        this.executor = executor;
    }

    /** Points the shelf at a world's directory (null = none) and lists it in the background. */
    public void open(Path directory) {
        dir = directory;
        entries = List.of();
        if (directory != null) {
            executor.execute(() -> rescan(directory));
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isOpen() {
        return dir != null;
    }

    /**
     * Saves a raw clip ({@link ReplayClipCodec#encode}); compression, the write and the cap run on the executor.
     * {@code done} gets the new id (or null on failure) on the executor thread.
     */
    public void save(byte[] raw, long savedMs, Consumer<String> done) {
        Path d = dir;
        if (d == null) {
            if (done != null) {
                done.accept(null);
            }
            return;
        }
        executor.execute(() -> {
            String id = null;
            try {
                Files.createDirectories(d);
                id = PREFIX + savedMs + SUFFIX;
                Path f = d.resolve(id);
                Path tmp = d.resolve(id + ".tmp");
                Files.write(tmp, ReplayClipCodec.compress(raw));
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                List<Entry> list = scan(d);
                for (int i = MAX_CLIPS; i < list.size(); i++) {
                    Files.deleteIfExists(d.resolve(list.get(i).id()));
                }
            } catch (IOException e) {
                id = null;
            }
            rescan(d);
            if (done != null) {
                done.accept(id);
            }
        });
    }

    /** Deletes a clip in the background. */
    public void delete(String id) {
        Path d = dir;
        if (d == null || !valid(id)) {
            return;
        }
        executor.execute(() -> {
            try {
                Files.deleteIfExists(d.resolve(id));
            } catch (IOException ignored) {
                // listed again below either way
            }
            rescan(d);
        });
    }

    /** Reads and decodes a clip in the background; {@code done} gets it, or null, on the executor thread. */
    public void load(String id, Consumer<ReplayClipCodec.Clip> done) {
        Path d = dir;
        if (d == null || !valid(id)) {
            done.accept(null);
            return;
        }
        executor.execute(() -> {
            ReplayClipCodec.Clip clip = null;
            try {
                Path f = d.resolve(id);
                if (Files.exists(f) && Files.size(f) <= ReplayClipCodec.MAX_FILE_BYTES) {
                    clip = ReplayClipCodec.decode(Files.readAllBytes(f));
                }
            } catch (IOException ignored) {
                clip = null;
            }
            done.accept(clip);
        });
    }

    /** Only names this shelf writes (no paths). */
    static boolean valid(String id) {
        return id != null && id.startsWith(PREFIX) && id.endsWith(SUFFIX) && id.indexOf('/') < 0
                && id.indexOf('\\') < 0 && !id.contains("..");
    }

    private void rescan(Path d) {
        List<Entry> list = scan(d);
        if (d.equals(dir)) {
            entries = List.copyOf(list);
        }
    }

    /** Newest first; unreadable files are skipped. */
    private static List<Entry> scan(Path d) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, PREFIX + "*" + SUFFIX)) {
            for (Path f : ds) {
                try {
                    long size = Files.size(f);
                    if (size > ReplayClipCodec.MAX_FILE_BYTES) {
                        continue;
                    }
                    ReplayClipCodec.Info info = ReplayClipCodec.readInfo(Files.readAllBytes(f));
                    out.add(new Entry(f.getFileName().toString(), info, size));
                } catch (IOException ignored) {
                    // skip
                }
            }
        } catch (IOException ignored) {
            // empty list
        }
        out.sort(Comparator.comparingLong((Entry e) -> savedAt(e)).reversed());
        return out;
    }

    /** Save time from the file name (what the cap orders by), else the header's. */
    static long savedAt(Entry e) {
        String id = e.id();
        try {
            return Long.parseLong(id.substring(PREFIX.length(), id.length() - SUFFIX.length()));
        } catch (RuntimeException ex) {
            return e.info().savedMs();
        }
    }
}
