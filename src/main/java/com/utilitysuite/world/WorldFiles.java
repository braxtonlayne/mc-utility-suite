package com.utilitysuite.world;

import com.utilitysuite.UtilitySuiteMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Per-profile files of the world modules, under {@code config/utilitysuite/world/}. Writes are atomic (temp file +
 * move) and run on the world executor, so the client thread only serialises.
 */
public final class WorldFiles {

    private WorldFiles() {
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(UtilitySuiteMod.MOD_ID).resolve("world");
    }

    /** File name safe on every OS: anything outside [A-Za-z0-9._-] becomes '_'. */
    public static String safe(String profileId) {
        StringBuilder sb = new StringBuilder(profileId.length());
        for (int i = 0; i < profileId.length(); i++) {
            char c = profileId.charAt(i);
            sb.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    public static Path file(String profileId, String suffix) {
        return dir().resolve(safe(profileId) + suffix);
    }

    /** Writes the bytes atomically on the world executor. */
    public static void writeAsync(Path file, byte[] data) {
        WorldExecutor.get().execute(() -> writeNow(file, data));
    }

    public static void writeNow(Path file, byte[] data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            UtilitySuiteMod.LOGGER.warn("Could not write {}", file, e);
        }
    }

    /** Reads the file, or null when it does not exist / cannot be read. */
    public static byte[] read(Path file) {
        try {
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            UtilitySuiteMod.LOGGER.warn("Could not read {}", file, e);
            return null;
        }
    }
}
