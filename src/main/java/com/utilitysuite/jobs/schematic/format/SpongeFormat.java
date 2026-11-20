package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Sponge {@code .schem} versions 1-3 (WorldEdit, FAWE). v1/v2 keep {@code Palette} (state string to index) and
 * varint-packed {@code BlockData} at the root; v3 nests everything under a {@code Schematic} compound with the blocks
 * in {@code Blocks.Palette} / {@code Blocks.Data}. Dimensions are unsigned shorts; cell order is
 * {@code x + z * Width + y * Width * Length}.
 */
public final class SpongeFormat implements SchematicFormatAdapter {

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
        return root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
    }

    @Override
    public SchematicVolume read(CompoundTag root, String name) throws SchematicParseException {
        CompoundTag s = unwrap(root);
        int version = s.contains("Version") ? s.getInt("Version") : 1;
        int width = s.getShort("Width") & 0xFFFF;
        int height = s.getShort("Height") & 0xFFFF;
        int length = s.getShort("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new SchematicParseException("Sponge schematic has an empty size");
        }
        CompoundTag paletteTag;
        byte[] blockData;
        if (version >= 3) {
            CompoundTag blocks = s.getCompound("Blocks");
            paletteTag = blocks.getCompound("Palette");
            blockData = blocks.getByteArray("Data");
        } else {
            paletteTag = s.getCompound("Palette");
            blockData = s.getByteArray("BlockData");
        }
        if (paletteTag.isEmpty()) {
            throw new SchematicParseException("Sponge schematic has no palette");
        }
        SchematicVolume.Builder b = SchematicVolume.builder(name, id() + "_v" + version, width, height, length);
        int maxIndex = 0;
        for (String key : paletteTag.getAllKeys()) {
            maxIndex = Math.max(maxIndex, paletteTag.getInt(key));
        }
        int[] remap = new int[maxIndex + 1];
        for (String key : paletteTag.getAllKeys()) {
            remap[paletteTag.getInt(key)] = b.paletteId(key);
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
