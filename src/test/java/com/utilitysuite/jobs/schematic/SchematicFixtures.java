package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.format.BitPacking;
import com.utilitysuite.jobs.schematic.format.VarInts;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes small schematic files in every supported format from a cell function (test fixtures, written with NbtIo). */
final class SchematicFixtures {

    /** State at a cell, or {@code null} for "not specified" (void / structure gap). */
    interface Cells {
        String at(int x, int y, int z);
    }

    private SchematicFixtures() {
    }

    // ---- the acceptance "hut": 3 x 4 x 3 ----------------------------------------------------------------------------

    static final String DOOR_LOWER = "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]";
    static final String DOOR_UPPER = "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]";
    static final String DOUBLE_SLAB = "minecraft:oak_slab[type=double,waterlogged=false]";
    static final String BOTTOM_SLAB_WET = "minecraft:oak_slab[type=bottom,waterlogged=true]";
    static final String WALL_TORCH = "minecraft:wall_torch[facing=north]";

    static final Cells HUT = (x, y, z) -> {
        boolean center = x == 1 && z == 1;
        return switch (y) {
            case 0 -> "minecraft:cobblestone";
            case 1 -> center ? "minecraft:air" : x == 1 && z == 0 ? DOOR_LOWER : "minecraft:oak_planks";
            case 2 -> {
                if (center) {
                    yield WALL_TORCH;
                }
                if (x == 1 && z == 0) {
                    yield DOOR_UPPER;
                }
                if (x == 0 && z == 0) {
                    yield DOUBLE_SLAB;
                }
                if (x == 2 && z == 2) {
                    yield BOTTOM_SLAB_WET;
                }
                yield "minecraft:oak_planks";
            }
            default -> "minecraft:stone_bricks";
        };
    };

    // ---- writers ------------------------------------------------------------------------------------------------

    static Path write(Path dir, String file, CompoundTag root) throws IOException {
        Path p = dir.resolve(file);
        NbtIo.writeCompressed(root, p);
        return p;
    }

    /** Vanilla structure: palette of {Name, Properties}, blocks with pos/state; {@code null} cells are left out. */
    static CompoundTag structure(int sx, int sy, int sz, Cells cells) {
        CompoundTag root = new CompoundTag();
        root.put("size", intList(sx, sy, sz));
        Map<String, Integer> palette = new LinkedHashMap<>();
        ListTag blocks = new ListTag();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    String s = cells.at(x, y, z);
                    if (s == null) {
                        continue;
                    }
                    int id = palette.computeIfAbsent(s, k -> palette.size());
                    CompoundTag b = new CompoundTag();
                    b.put("pos", intList(x, y, z));
                    b.putInt("state", id);
                    blocks.add(b);
                }
            }
        }
        ListTag pal = new ListTag();
        for (String s : palette.keySet()) {
            pal.add(stateCompound(s));
        }
        root.put("palette", pal);
        root.put("blocks", blocks);
        root.putInt("DataVersion", 3955);
        return root;
    }

    /** Sponge v2 (root-level Palette/BlockData) or v3 (nested Schematic.Blocks). {@code null} cells become air. */
    static CompoundTag sponge(int version, int sx, int sy, int sz, Cells cells) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        int[] data = new int[sx * sy * sz];
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    String s = cells.at(x, y, z);
                    // Sponge writers do not sort properties: shuffle order to exercise canonicalisation.
                    String key = s == null ? "minecraft:air" : reverseProperties(s);
                    data[i++] = palette.computeIfAbsent(key, k -> palette.size());
                }
            }
        }
        CompoundTag pal = new CompoundTag();
        palette.forEach(pal::putInt);
        CompoundTag s = new CompoundTag();
        s.putInt("Version", version);
        s.putInt("DataVersion", 3955);
        s.putShort("Width", (short) sx);
        s.putShort("Height", (short) sy);
        s.putShort("Length", (short) sz);
        if (version >= 3) {
            CompoundTag blocks = new CompoundTag();
            blocks.put("Palette", pal);
            blocks.putByteArray("Data", VarInts.encode(data));
            s.put("Blocks", blocks);
            CompoundTag root = new CompoundTag();
            root.put("Schematic", s);
            return root;
        }
        s.put("Palette", pal);
        s.putInt("PaletteMax", palette.size());
        s.putByteArray("BlockData", VarInts.encode(data));
        return s;
    }

    /** One Litematica region: absolute min corner and size; written with a signed size when {@code negative}. */
    record Region(String name, int minX, int minY, int minZ, int sx, int sy, int sz, boolean negative) {
    }

    static CompoundTag litematic(List<Region> regions, Cells cells) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", 3955);
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "fixture");
        root.put("Metadata", meta);
        CompoundTag regs = new CompoundTag();
        for (Region r : regions) {
            List<String> palette = new ArrayList<>();
            palette.add("minecraft:air");
            int cellsCount = r.sx * r.sy * r.sz;
            int[] ids = new int[cellsCount];
            int i = 0;
            for (int y = 0; y < r.sy; y++) {
                for (int z = 0; z < r.sz; z++) {
                    for (int x = 0; x < r.sx; x++) {
                        String s = cells.at(r.minX + x, r.minY + y, r.minZ + z);
                        String key = s == null ? "minecraft:air" : s;
                        int id = palette.indexOf(key);
                        if (id < 0) {
                            id = palette.size();
                            palette.add(key);
                        }
                        ids[i++] = id;
                    }
                }
            }
            int bits = BitPacking.bitsFor(palette.size());
            long[] packed = new long[BitPacking.longsFor(cellsCount, bits)];
            for (int k = 0; k < ids.length; k++) {
                BitPacking.set(packed, bits, k, ids[k]);
            }
            CompoundTag rt = new CompoundTag();
            // Negative size: Position is the max corner, Size negative (as Litematica saves boxes selected backwards).
            int px = r.negative ? r.minX + r.sx - 1 : r.minX;
            int py = r.negative ? r.minY + r.sy - 1 : r.minY;
            int pz = r.negative ? r.minZ + r.sz - 1 : r.minZ;
            rt.put("Position", xyz(px, py, pz));
            rt.put("Size", r.negative ? xyz(-r.sx, -r.sy, -r.sz) : xyz(r.sx, r.sy, r.sz));
            ListTag pal = new ListTag();
            for (String s : palette) {
                pal.add(stateCompound(s));
            }
            rt.put("BlockStatePalette", pal);
            rt.putLongArray("BlockStates", packed);
            rt.put("TileEntities", new ListTag());
            rt.put("Entities", new ListTag());
            regs.put(r.name, rt);
        }
        root.put("Regions", regs);
        return root;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static CompoundTag stateCompound(String state) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", BlockStateString.blockId(state));
        int b = state.indexOf('[');
        if (b >= 0) {
            CompoundTag props = new CompoundTag();
            for (String kv : state.substring(b + 1, state.length() - 1).split(",")) {
                String[] p = kv.split("=");
                props.put(p[0], StringTag.valueOf(p[1]));
            }
            t.put("Properties", props);
        }
        return t;
    }

    static String reverseProperties(String state) {
        int b = state.indexOf('[');
        if (b < 0) {
            return state;
        }
        String[] parts = state.substring(b + 1, state.length() - 1).split(",");
        StringBuilder sb = new StringBuilder(state.substring(0, b)).append('[');
        for (int i = parts.length - 1; i >= 0; i--) {
            sb.append(parts[i]);
            if (i > 0) {
                sb.append(',');
            }
        }
        return sb.append(']').toString();
    }

    private static ListTag intList(int... values) {
        ListTag l = new ListTag();
        for (int v : values) {
            l.add(IntTag.valueOf(v));
        }
        return l;
    }

    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        return t;
    }
}
