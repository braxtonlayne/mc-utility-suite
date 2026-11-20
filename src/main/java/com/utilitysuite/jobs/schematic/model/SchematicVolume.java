package com.utilitysuite.jobs.schematic.model;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable, format-independent schematic: a box of {@code sizeX * sizeY * sizeZ} cells, each holding an index into a
 * palette of canonical block state strings (see {@link BlockStateString}). Cells are stored in a {@code short[]} (or an
 * {@code int[]} for palettes above 65 536 entries), indexed {@code (y * sizeZ + z) * sizeX + x}, the same layout
 * Litematica and Sponge use, so a cell costs two bytes.
 * <p>
 * Palette index 0 is always {@link #VOID}: a cell the schematic does not care about (vanilla structure files leave
 * unlisted positions unset, and {@code minecraft:structure_void} means the same). Air ({@link #isAir}) means "should be
 * empty". The offset is the min corner relative to the schematic origin (Litematica regions may sit at negative
 * positions); the world position of cell (x, y, z) is {@code anchor + offset + (x, y, z)}.
 */
public final class SchematicVolume {

    public static final String VOID = "minecraft:structure_void";
    public static final String AIR = "minecraft:air";
    public static final int VOID_INDEX = 0;
    /** Upper bound on cells (16 M, e.g. 256 x 256 x 256) so a corrupt header cannot exhaust the heap. */
    public static final long MAX_CELLS = 16L * 1024 * 1024;

    private final String name;
    private final String format;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final String[] palette;
    private final boolean[] airFlags;
    private final short[] small;
    private final int[] large;

    private SchematicVolume(String name, String format, int sizeX, int sizeY, int sizeZ, int offsetX, int offsetY,
                            int offsetZ, String[] palette, short[] small, int[] large) {
        this.name = name;
        this.format = format;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.palette = palette;
        this.small = small;
        this.large = large;
        this.airFlags = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            String id = BlockStateString.blockId(palette[i]);
            airFlags[i] = id.equals(AIR) || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
        }
    }

    public String name() { return name; }
    public String format() { return format; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int offsetX() { return offsetX; }
    public int offsetY() { return offsetY; }
    public int offsetZ() { return offsetZ; }

    /** Number of cells. */
    public int volume() {
        return sizeX * sizeY * sizeZ;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public int xOf(int index) {
        return index % sizeX;
    }

    public int zOf(int index) {
        return (index / sizeX) % sizeZ;
    }

    public int yOf(int index) {
        return index / (sizeX * sizeZ);
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    public int paletteIndex(int index) {
        return small != null ? small[index] & 0xFFFF : large[index];
    }

    public int paletteIndex(int x, int y, int z) {
        return paletteIndex(index(x, y, z));
    }

    public String state(int x, int y, int z) {
        return palette[paletteIndex(index(x, y, z))];
    }

    public String paletteEntry(int paletteIndex) {
        return palette[paletteIndex];
    }

    public int paletteSize() {
        return palette.length;
    }

    /** Copy of the palette. */
    public List<String> palette() {
        return List.of(palette);
    }

    /** True for a cell the schematic leaves unspecified. */
    public static boolean isVoid(int paletteIndex) {
        return paletteIndex == VOID_INDEX;
    }

    public boolean isAir(int paletteIndex) {
        return airFlags[paletteIndex];
    }

    /** True when the cell requires a block (neither void nor air). */
    public boolean isBlock(int paletteIndex) {
        return paletteIndex != VOID_INDEX && !airFlags[paletteIndex];
    }

    /** Cells per palette index. */
    public int[] histogram() {
        int[] h = new int[palette.length];
        int n = volume();
        for (int i = 0; i < n; i++) {
            h[paletteIndex(i)]++;
        }
        return h;
    }

    /** Number of cells that require a block. */
    public int blockCount() {
        int[] h = histogram();
        int c = 0;
        for (int i = 0; i < h.length; i++) {
            if (isBlock(i)) {
                c += h[i];
            }
        }
        return c;
    }

    /** Approximate heap bytes of the cell storage. */
    public long storageBytes() {
        return small != null ? 2L * small.length : 4L * large.length;
    }

    public static Builder builder(String name, String format, int sizeX, int sizeY, int sizeZ) {
        return new Builder(name, format, sizeX, sizeY, sizeZ);
    }

    @Override
    public String toString() {
        return "SchematicVolume[" + name + " " + format + " " + sizeX + "x" + sizeY + "x" + sizeZ + ", palette "
                + palette.length + "]";
    }

    /** Mutable builder used by the format adapters. Cells start as {@link #VOID}. */
    public static final class Builder {
        private final String name;
        private final String format;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private int offsetX;
        private int offsetY;
        private int offsetZ;
        private final int[] cells;
        private final List<String> palette = new ArrayList<>();
        private final Object2IntOpenHashMap<String> lookup = new Object2IntOpenHashMap<>();

        private Builder(String name, String format, int sizeX, int sizeY, int sizeZ) {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("Empty schematic size " + sizeX + "x" + sizeY + "x" + sizeZ);
            }
            long volume = (long) sizeX * sizeY * sizeZ;
            if (volume > MAX_CELLS) {
                throw new IllegalArgumentException("Schematic too large: " + volume + " cells");
            }
            this.name = name;
            this.format = format;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.cells = new int[(int) volume];
            lookup.defaultReturnValue(-1);
            palette.add(VOID);
            lookup.put(VOID, 0);
        }

        public Builder offset(int x, int y, int z) {
            offsetX = x;
            offsetY = y;
            offsetZ = z;
            return this;
        }

        /** Palette index of a state (canonicalized), adding it when new. {@code structure_void} maps to 0. */
        public int paletteId(String state) {
            String canonical = BlockStateString.canonical(state);
            if (BlockStateString.blockId(canonical).equals(VOID)) {
                return VOID_INDEX;
            }
            int id = lookup.getInt(canonical);
            if (id < 0) {
                id = palette.size();
                palette.add(canonical);
                lookup.put(canonical, id);
            }
            return id;
        }

        public Builder set(int x, int y, int z, String state) {
            return setId(x, y, z, paletteId(state));
        }

        public Builder setId(int x, int y, int z, int paletteId) {
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                throw new IndexOutOfBoundsException(x + "," + y + "," + z);
            }
            cells[(y * sizeZ + z) * sizeX + x] = paletteId;
            return this;
        }

        public int sizeX() { return sizeX; }
        public int sizeY() { return sizeY; }
        public int sizeZ() { return sizeZ; }

        public SchematicVolume build() {
            String[] pal = palette.toArray(new String[0]);
            if (pal.length <= 65536) {
                short[] s = new short[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    s[i] = (short) cells[i];
                }
                return new SchematicVolume(name, format, sizeX, sizeY, sizeZ, offsetX, offsetY, offsetZ, pal, s, null);
            }
            return new SchematicVolume(name, format, sizeX, sizeY, sizeZ, offsetX, offsetY, offsetZ, pal, null,
                    Arrays.copyOf(cells, cells.length));
        }
    }
}
