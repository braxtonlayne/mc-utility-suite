package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.TreeMap;

/** Reads one schematic file format into a {@link SchematicVolume}. Pure: NBT classes only, no registry access. */
public interface SchematicFormatAdapter {

    /** Short format id ("litematic", "sponge", "structure"). */
    String id();

    /** File extension this format normally uses, without the dot. */
    String extension();

    /** True when the root tag looks like this format (used when the extension is missing or misleading). */
    boolean accepts(CompoundTag root);

    SchematicVolume read(CompoundTag root, String name) throws SchematicParseException;

    /** {@code {Name: "minecraft:x", Properties: {k: "v"}}} to a canonical state string (Litematica and structures). */
    static String stateFromCompound(CompoundTag entry) {
        String id = entry.getString("Name");
        if (id.isEmpty()) {
            id = "minecraft:air";
        }
        if (!entry.contains("Properties", Tag.TAG_COMPOUND)) {
            return com.utilitysuite.jobs.schematic.model.BlockStateString.normalizeId(id);
        }
        CompoundTag props = entry.getCompound("Properties");
        TreeMap<String, String> map = new TreeMap<>();
        for (String key : props.getAllKeys()) {
            map.put(key, props.getString(key));
        }
        return com.utilitysuite.jobs.schematic.model.BlockStateString.of(id, map);
    }

    /** Reads an {x, y, z} int compound (Litematica). */
    static int[] xyz(CompoundTag tag) {
        return new int[]{tag.getInt("x"), tag.getInt("y"), tag.getInt("z")};
    }

    static int[] intList(CompoundTag parent, String key) throws SchematicParseException {
        if (parent.contains(key, Tag.TAG_INT_ARRAY)) {
            return parent.getIntArray(key);
        }
        ListTag list = parent.getList(key, Tag.TAG_INT);
        if (list.isEmpty()) {
            throw new SchematicParseException("Missing int list '" + key + "'");
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.getInt(i);
        }
        return out;
    }
}
