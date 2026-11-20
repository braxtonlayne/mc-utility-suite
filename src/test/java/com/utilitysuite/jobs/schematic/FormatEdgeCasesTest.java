package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.format.BitPacking;
import com.utilitysuite.jobs.schematic.format.SchematicFormats;
import com.utilitysuite.jobs.schematic.format.SchematicParseException;
import com.utilitysuite.jobs.schematic.format.VarInts;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatEdgeCasesTest {

    @TempDir
    Path dir;

    // ---- bit packing ------------------------------------------------------------------------------------------------

    @Test
    void bitsFollowLitematicaRule() {
        assertEquals(2, BitPacking.bitsFor(1));
        assertEquals(2, BitPacking.bitsFor(2));
        assertEquals(2, BitPacking.bitsFor(4));
        assertEquals(3, BitPacking.bitsFor(5));
        assertEquals(4, BitPacking.bitsFor(16));
        assertEquals(5, BitPacking.bitsFor(17));
        assertEquals(9, BitPacking.bitsFor(257));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 7, 9, 13, 17})
    void entriesSpanningTwoLongsRoundTrip(int bits) {
        int n = 500;
        int[] values = new int[n];
        Random r = new Random(bits);
        for (int i = 0; i < n; i++) {
            values[i] = r.nextInt(1 << bits);
        }
        long[] data = new long[BitPacking.longsFor(n, bits)];
        for (int i = 0; i < n; i++) {
            BitPacking.set(data, bits, i, values[i]);
        }
        int spanning = 0;
        for (int i = 0; i < n; i++) {
            assertEquals(values[i], BitPacking.get(data, bits, i), "entry " + i);
            if ((i * (long) bits) >> 6 != ((i + 1L) * bits - 1) >> 6) {
                spanning++;
            }
        }
        if (64 % bits != 0) {
            assertTrue(spanning > 0, "test must cover entries that span two longs");
        }
    }

    @Test
    void handPackedSpanningEntry() {
        // 5-bit entries: entry 12 occupies bits 60..64, i.e. 4 bits in long 0 and 1 bit in long 1.
        long[] data = new long[2];
        data[0] = 0b1011L << 60;
        data[1] = 0b1L;
        assertEquals(0b11011, BitPacking.get(data, 5, 12));
    }

    // ---- varints ----------------------------------------------------------------------------------------------------

    @Test
    void varintsRoundTripAndMultiByte() throws Exception {
        int[] values = {0, 1, 127, 128, 255, 300, 16383, 16384, 2097151, 1 << 28};
        assertArrayEquals(values, VarInts.decode(VarInts.encode(values), values.length));
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, VarInts.encode(new int[]{300}));
    }

    @Test
    void truncatedVarintsAreRejected() {
        assertThrows(SchematicParseException.class, () -> VarInts.decode(new byte[]{(byte) 0x80}, 1));
        assertThrows(SchematicParseException.class, () -> VarInts.decode(new byte[]{1, 2}, 3));
    }

    // ---- litematica palettes of size 1, 2 and 17+ ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 17, 40})
    void litematicPaletteSizes(int distinct) throws Exception {
        // Region 5x3x4 = 60 cells; cell i gets state (i % distinct). Palette entry 0 (air) is always present.
        String[] states = new String[distinct];
        states[0] = "minecraft:air";
        for (int i = 1; i < distinct; i++) {
            states[i] = "minecraft:block_" + i;
        }
        SchematicFixtures.Cells cells = (x, y, z) -> states[((y * 4 + z) * 5 + x) % distinct];
        CompoundTag root = SchematicFixtures.litematic(List.of(
                new SchematicFixtures.Region("R", 0, 0, 0, 5, 3, 4, false)), cells);
        SchematicVolume v = SchematicFormats.read(SchematicFixtures.write(dir, "p" + distinct + ".litematic", root));
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 5; x++) {
                    assertEquals(cells.at(x, y, z), v.state(x, y, z));
                }
            }
        }
        // Air plus the distinct blocks plus the reserved void entry.
        assertEquals(distinct + 1, v.paletteSize());
    }

    @Test
    void litematicNegativeRegionOffsetsAreNormalised() throws Exception {
        // One region at negative coordinates (saved backwards) and one at the origin.
        SchematicFixtures.Cells cells = (x, y, z) -> x < 0 ? "minecraft:stone" : "minecraft:glass";
        CompoundTag root = SchematicFixtures.litematic(List.of(
                new SchematicFixtures.Region("West", -3, 0, 0, 3, 1, 1, true),
                new SchematicFixtures.Region("East", 0, 0, 0, 2, 2, 1, false)), cells);
        SchematicVolume v = SchematicFormats.read(SchematicFixtures.write(dir, "neg.litematic", root));
        assertEquals(5, v.sizeX());
        assertEquals(2, v.sizeY());
        assertEquals(-3, v.offsetX());
        assertEquals("minecraft:stone", v.state(0, 0, 0));
        assertEquals("minecraft:glass", v.state(3, 0, 0));
        assertEquals("minecraft:glass", v.state(4, 1, 0));
        assertEquals(SchematicVolume.VOID, v.state(0, 1, 0), "cells no region covers stay void");
    }

    // ---- structures leave gaps as void --------------------------------------------------------------------------

    @Test
    void structureGapsAndStructureVoidAreVoid() throws Exception {
        SchematicFixtures.Cells cells = (x, y, z) -> x == 0 ? "minecraft:stone"
                : x == 1 ? "minecraft:structure_void" : null;
        SchematicVolume v = SchematicFormats.read(SchematicFixtures.write(dir, "gap.nbt",
                SchematicFixtures.structure(3, 1, 1, cells)));
        assertEquals("minecraft:stone", v.state(0, 0, 0));
        assertTrue(SchematicVolume.isVoid(v.paletteIndex(1, 0, 0)));
        assertTrue(SchematicVolume.isVoid(v.paletteIndex(2, 0, 0)));
        assertEquals(1, v.blockCount());
    }

    @Test
    void unknownFormatIsRejected() throws Exception {
        CompoundTag junk = new CompoundTag();
        junk.putString("hello", "world");
        Path p = SchematicFixtures.write(dir, "junk.schematic", junk);
        assertThrows(SchematicParseException.class, () -> SchematicFormats.read(p));
    }

    @Test
    void volumeStorageIsTwoBytesPerCell() throws Exception {
        SchematicVolume v = SchematicFormats.read(SchematicFixtures.write(dir, "hut.schem",
                SchematicFixtures.sponge(2, 3, 4, 3, SchematicFixtures.HUT)));
        assertEquals(2L * 36, v.storageBytes());
    }

    // ---- state strings ----------------------------------------------------------------------------------------------

    @Test
    void stateStringsAreCanonical() {
        assertEquals("minecraft:oak_slab[type=double,waterlogged=false]",
                BlockStateString.canonical("oak_slab[waterlogged=false, type=double]"));
        assertEquals("double", BlockStateString.property("minecraft:oak_slab[type=double,waterlogged=false]", "type"));
        assertEquals(null, BlockStateString.property("minecraft:oak_slab[type=double]", "typ"));
        assertEquals(3, BlockStateString.intProperty("minecraft:candle[candles=3,lit=false]", "candles", -1));
    }
}
