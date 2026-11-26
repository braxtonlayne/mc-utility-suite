package com.utilitysuite.jobs.schematic.format;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Litematica {@code .litematic} (versions 4-7). Root: {@code Version}, {@code Metadata}, {@code Regions}. Each region
 * has a {@code Position} relative to the schematic origin and a {@code Size} whose components may be negative (the
 * region then extends toward negative coordinates: its min corner is {@code position + size + 1}). Cells are stored
 * relative to the region's min corner in a tightly packed {@link BitPacking} long array ({@code BlockStates}) of
 * indices into {@code BlockStatePalette}.
 * <p>
 * All regions are merged into one volume spanning their union; the volume offset is the union's min corner relative
 * to the origin. Cells no region covers stay void.
 */
public final class LitematicaFormat implements SchematicFormatAdapter {

    private static final long[] EMPTY = new long[0];

    @Override
    public String id() {
        return "litematic";
    }

    @Override
    public String extension() {
        return "litematic";
    }

    @Override
    public boolean accepts(CompoundTag root) {
        return SchematicFormatAdapter.has(root, "Regions", Tag.TAG_COMPOUND);
    }

    @Override
    public SchematicVolume read(CompoundTag root, String name) throws SchematicParseException {
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        int dataVersion = SchematicFormatAdapter.dataVersion(root, "MinecraftDataVersion");
        if (regions.isEmpty()) {
            throw new SchematicParseException("Litematic has no regions");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(key);
            int[] pos = SchematicFormatAdapter.xyz(r.getCompoundOrEmpty("Position"));
            int[] size = SchematicFormatAdapter.xyz(r.getCompoundOrEmpty("Size"));
            for (int a = 0; a < 3; a++) {
                if (size[a] == 0) {
                    throw new SchematicParseException("Region '" + key + "' has a zero size");
                }
            }
            int rx = regionMin(pos[0], size[0]), ry = regionMin(pos[1], size[1]), rz = regionMin(pos[2], size[2]);
            minX = Math.min(minX, rx);
            minY = Math.min(minY, ry);
            minZ = Math.min(minZ, rz);
            maxX = Math.max(maxX, rx + Math.abs(size[0]) - 1);
            maxY = Math.max(maxY, ry + Math.abs(size[1]) - 1);
            maxZ = Math.max(maxZ, rz + Math.abs(size[2]) - 1);
        }
        SchematicVolume.Builder b = SchematicVolume.builder(name, id(), maxX - minX + 1, maxY - minY + 1,
                maxZ - minZ + 1).offset(minX, minY, minZ);

        for (String key : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(key);
            int[] pos = SchematicFormatAdapter.xyz(r.getCompoundOrEmpty("Position"));
            int[] size = SchematicFormatAdapter.xyz(r.getCompoundOrEmpty("Size"));
            int sx = Math.abs(size[0]), sy = Math.abs(size[1]), sz = Math.abs(size[2]);
            int ox = regionMin(pos[0], size[0]) - minX;
            int oy = regionMin(pos[1], size[1]) - minY;
            int oz = regionMin(pos[2], size[2]) - minZ;

            ListTag paletteTag = SchematicFormatAdapter.listOf(r, "BlockStatePalette", Tag.TAG_COMPOUND);
            if (paletteTag.isEmpty()) {
                throw new SchematicParseException("Region '" + key + "' has no palette");
            }
            int[] remap = new int[paletteTag.size()];
            for (int i = 0; i < remap.length; i++) {
                remap[i] = b.paletteId(SchematicFormatAdapter.stateFromCompound(paletteTag.getCompoundOrEmpty(i), dataVersion));
            }
            int bits = BitPacking.bitsFor(remap.length);
            long cells = (long) sx * sy * sz;
            long[] data = r.getLongArray("BlockStates").orElse(EMPTY);
            if (data.length < BitPacking.longsFor(cells, bits)) {
                throw new SchematicParseException("Region '" + key + "' block data too short: " + data.length
                        + " longs for " + cells + " cells at " + bits + " bits");
            }
            long index = 0;
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        int p = BitPacking.get(data, bits, index++);
                        if (p >= remap.length) {
                            throw new SchematicParseException("Region '" + key + "' palette index " + p
                                    + " out of range " + remap.length);
                        }
                        int id = remap[p];
                        if (id != SchematicVolume.VOID_INDEX) {
                            b.setId(ox + x, oy + y, oz + z, id);
                        }
                    }
                }
            }
        }
        return b.build();
    }

    /** Min corner of a region axis given its position and signed size. */
    static int regionMin(int pos, int size) {
        return size < 0 ? pos + size + 1 : pos;
    }
}
