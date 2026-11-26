package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
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
        String id = entry.getStringOr("Name", "");
        if (id.isEmpty()) {
            id = "minecraft:air";
        }
        if (!(entry.get("Properties") instanceof CompoundTag props)) {
            return com.utilitysuite.jobs.schematic.model.BlockStateString.normalizeId(id);
        }
        TreeMap<String, String> map = new TreeMap<>();
        for (String key : props.keySet()) {
            map.put(key, props.getStringOr(key, ""));
        }
        return com.utilitysuite.jobs.schematic.model.BlockStateString.of(id, map);
    }

    /** {@link #stateFromCompound(CompoundTag)} upgraded from the file's data version ({@code -1} unknown). */
    static String stateFromCompound(CompoundTag entry, int dataVersion) {
        return SchematicFormats.upgrade(stateFromCompound(entry), dataVersion);
    }

    /** The {@code key} data version, or {@code -1} when the file has none. */
    static int dataVersion(CompoundTag tag, String key) {
        return tag.getIntOr(key, -1);
    }

    /** Reads an {x, y, z} int compound (Litematica). */
    static int[] xyz(CompoundTag tag) {
        return new int[]{tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)};
    }

    /**
     * The list under {@code key} when it holds only elements of {@code elementType}, else an empty list (the 1.21.1
     * {@code getList(key, type)} contract; 26.3 lists no longer carry a declared element type).
     */
    static ListTag listOf(CompoundTag parent, String key, byte elementType) {
        if (parent.get(key) instanceof ListTag list) {
            for (int i = 0, n = list.size(); i < n; i++) {
                if (list.get(i).getId() != elementType) {
                    return new ListTag();
                }
            }
            return list;
        }
        return new ListTag();
    }

    /** True when {@code key} holds a tag of the given type (the 1.21.1 {@code contains(key, type)}). */
    static boolean has(CompoundTag parent, String key, byte type) {
        Tag t = parent.get(key);
        return t != null && t.getId() == type;
    }

    static int[] intList(CompoundTag parent, String key) throws SchematicParseException {
        if (parent.get(key) instanceof IntArrayTag arr) {
            return arr.getAsIntArray();
        }
        ListTag list = listOf(parent, key, Tag.TAG_INT);
        if (list.isEmpty()) {
            throw new SchematicParseException("Missing int list '" + key + "'");
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.getIntOr(i, 0);
        }
        return out;
    }
}
