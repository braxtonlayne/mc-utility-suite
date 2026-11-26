package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Vanilla structure block files ({@code .nbt}): {@code size} [x, y, z], {@code palette} (or {@code palettes}, a list of
 * alternative palettes of which the first is used) of {@code {Name, Properties}}, and {@code blocks} entries with
 * {@code pos} [x, y, z] and {@code state} (palette index). Positions not listed are void (the structure leaves them
 * untouched), as is {@code minecraft:structure_void}.
 */
public final class StructureNbtFormat implements SchematicFormatAdapter {

    @Override
    public String id() {
        return "structure";
    }

    @Override
    public String extension() {
        return "nbt";
    }

    @Override
    public boolean accepts(CompoundTag root) {
        return SchematicFormatAdapter.has(root, "blocks", Tag.TAG_LIST) && root.contains("size")
                && (SchematicFormatAdapter.has(root, "palette", Tag.TAG_LIST) || SchematicFormatAdapter.has(root, "palettes", Tag.TAG_LIST));
    }

    @Override
    public SchematicVolume read(CompoundTag root, String name) throws SchematicParseException {
        int[] size = SchematicFormatAdapter.intList(root, "size");
        if (size.length != 3) {
            throw new SchematicParseException("Structure size must have 3 entries");
        }
        ListTag paletteTag;
        if (SchematicFormatAdapter.has(root, "palette", Tag.TAG_LIST)) {
            paletteTag = SchematicFormatAdapter.listOf(root, "palette", Tag.TAG_COMPOUND);
        } else {
            ListTag palettes = SchematicFormatAdapter.listOf(root, "palettes", Tag.TAG_LIST);
            if (palettes.isEmpty()) {
                throw new SchematicParseException("Structure has no palette");
            }
            paletteTag = palettes.getListOrEmpty(0);
        }
        SchematicVolume.Builder b = SchematicVolume.builder(name, id(), size[0], size[1], size[2]);
        int dataVersion = SchematicFormatAdapter.dataVersion(root, "DataVersion");
        int[] remap = new int[paletteTag.size()];
        for (int i = 0; i < remap.length; i++) {
            remap[i] = b.paletteId(SchematicFormatAdapter.stateFromCompound(paletteTag.getCompoundOrEmpty(i), dataVersion));
        }
        ListTag blocks = SchematicFormatAdapter.listOf(root, "blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompoundOrEmpty(i);
            int[] pos = SchematicFormatAdapter.intList(entry, "pos");
            int state = entry.getIntOr("state", 0);
            if (state < 0 || state >= remap.length) {
                throw new SchematicParseException("Block " + i + " palette index " + state + " out of range");
            }
            if (pos.length != 3 || pos[0] < 0 || pos[1] < 0 || pos[2] < 0 || pos[0] >= size[0] || pos[1] >= size[1]
                    || pos[2] >= size[2]) {
                throw new SchematicParseException("Block " + i + " position outside the structure");
            }
            b.setId(pos[0], pos[1], pos[2], remap[state]);
        }
        return b.build();
    }
}
