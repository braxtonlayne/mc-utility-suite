package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Picks the {@link SchematicFormatAdapter} for a file (by extension, then by content) and reads it. Blocking: call
 * from a background executor, never the client thread.
 */
public final class SchematicFormats {

    /** Hard cap on the NBT heap a schematic may use while being read (guards against corrupt or hostile files). */
    public static final long NBT_HEAP_LIMIT = 512L * 1024 * 1024;

    private static final List<SchematicFormatAdapter> ADAPTERS = List.of(new LitematicaFormat(), new SpongeFormat(),
            new StructureNbtFormat());

    private SchematicFormats() {
    }

    public static List<SchematicFormatAdapter> adapters() {
        return ADAPTERS;
    }

    /** True when the file name has a supported extension. */
    public static boolean isSchematicFile(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt");
    }

    public static SchematicVolume read(Path path) throws IOException, SchematicParseException {
        byte[] bytes = Files.readAllBytes(path);
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String name = dot > 0 ? file.substring(0, dot) : file;
        String ext = dot > 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return read(bytes, name, ext);
    }

    /** Reads GZIP-compressed (or, failing that, raw) NBT and dispatches to the matching adapter. */
    public static SchematicVolume read(byte[] bytes, String name, String extension)
            throws IOException, SchematicParseException {
        CompoundTag root = readRoot(bytes);
        for (SchematicFormatAdapter a : ADAPTERS) {
            if (a.extension().equals(extension) && a.accepts(root)) {
                return a.read(root, name);
            }
        }
        for (SchematicFormatAdapter a : ADAPTERS) {
            if (a.accepts(root)) {
                return a.read(root, name);
            }
        }
        throw new SchematicParseException("Unrecognised schematic format: " + name + "." + extension
                + " (legacy MCEdit .schematic files with numeric block ids are not supported)");
    }

    static CompoundTag readRoot(byte[] bytes) throws IOException {
        boolean gzip = bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        if (gzip) {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                return NbtIo.readCompressed(in, NbtAccounter.create(NBT_HEAP_LIMIT));
            }
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes)))) {
            return NbtIo.read(in, NbtAccounter.create(NBT_HEAP_LIMIT));
        }
    }
}
