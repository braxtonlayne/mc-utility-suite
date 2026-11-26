package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;

/**
 * Sponge {@code .schem} versions 1-3 (WorldEdit, FAWE). v1/v2 keep {@code Palette} (state string to index) and
 * varint-packed {@code BlockData} at the root; v3 nests everything under a {@code Schematic} compound with the blocks
 * in {@code Blocks.Palette} / {@code Blocks.Data}. Dimensions are unsigned shorts; cell order is
 * {@code x + z * Width + y * Width * Length}.
 */
public final class SpongeFormat implements SchematicFormatAdapter {

    private static final byte[] EMPTY = new byte[0];

    @Override
    public String id() {
        return "sponge";
    }

    @Override
    public String extension() {
        return "schem";
    }

    @Override
    public boolean accepts(CompoundTag root) {
        CompoundTag s = unwrap(root);
        return s.contains("Width") && s.contains("Length") && (s.contains("Palette") || s.contains("Blocks"));
    }

    private static CompoundTag unwrap(CompoundTag root) {
        return root.get("Schematic") instanceof CompoundTag inner ? inner : root;
    }

    @Override
    public SchematicVolume read(CompoundTag root, String name) throws SchematicParseException {
        CompoundTag s = unwrap(root);
        int version = s.getIntOr("Version", 1);
        int dataVersion = SchematicFormatAdapter.dataVersion(s, "DataVersion");
        int width = s.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = s.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = s.getShortOr("Length", (short) 0) & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new SchematicParseException("Sponge schematic has an empty size");
        }
        CompoundTag paletteTag;
        byte[] blockData;
        if (version >= 3) {
            CompoundTag blocks = s.getCompoundOrEmpty("Blocks");
            paletteTag = blocks.getCompoundOrEmpty("Palette");
            blockData = blocks.getByteArray("Data").orElse(EMPTY);
        } else {
            paletteTag = s.getCompoundOrEmpty("Palette");
            blockData = s.getByteArray("BlockData").orElse(EMPTY);
        }
        if (paletteTag.isEmpty()) {
            throw new SchematicParseException("Sponge schematic has no palette");
        }
        SchematicVolume.Builder b = SchematicVolume.builder(name, id() + "_v" + version, width, height, length);
        int maxIndex = 0;
        for (String key : paletteTag.keySet()) {
            maxIndex = Math.max(maxIndex, paletteTag.getIntOr(key, 0));
        }
        int[] remap = new int[maxIndex + 1];
        for (String key : paletteTag.keySet()) {
            remap[paletteTag.getIntOr(key, 0)] = b.paletteId(SchematicFormats.upgrade(key, dataVersion));
        }
        int cells = width * height * length;
        int[] values = VarInts.decode(blockData, cells);
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int v = values[i++];
                    if (v < 0 || v >= remap.length) {
                        throw new SchematicParseException("Palette index " + v + " out of range " + remap.length);
                    }
                    b.setId(x, y, z, remap[v]);
                }
            }
        }
        return b.build();
    }
}
