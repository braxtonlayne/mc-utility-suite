package com.utilitysuite.jobs.schematic.format;

/**
 * Litematica's tightly packed bit array ({@code LitematicaBitArray}): entries of {@code bits} bits laid end to end in a
 * {@code long[]}, least significant bit first, with an entry allowed to span two longs (unlike vanilla's padded
 * chunk format since 1.16).
 */
public final class BitPacking {

    private BitPacking() {
    }

    /** Bits per entry Litematica uses for a palette: {@code max(2, ceil(log2(paletteSize)))}. */
    public static int bitsFor(int paletteSize) {
        int needed = paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(2, needed);
    }

    /** Length of the long array holding {@code entries} entries of {@code bits} bits. */
    public static int longsFor(long entries, int bits) {
        return (int) ((entries * bits + 63) / 64);
    }

    /** Reads entry {@code index}. */
    public static int get(long[] data, int bits, long index) {
        long mask = (1L << bits) - 1L;
        long startOffset = index * bits;
        int startLong = (int) (startOffset >> 6);
        int endLong = (int) (((index + 1) * bits - 1) >> 6);
        int startBit = (int) (startOffset & 63);
        if (startLong == endLong) {
            return (int) ((data[startLong] >>> startBit) & mask);
        }
        int endBits = 64 - startBit;
        return (int) (((data[startLong] >>> startBit) | (data[endLong] << endBits)) & mask);
    }

    /** Writes entry {@code index} (used by tests and for writing fixtures). */
    public static void set(long[] data, int bits, long index, int value) {
        long mask = (1L << bits) - 1L;
        long v = value & mask;
        long startOffset = index * bits;
        int startLong = (int) (startOffset >> 6);
        int endLong = (int) (((index + 1) * bits - 1) >> 6);
        int startBit = (int) (startOffset & 63);
        data[startLong] = data[startLong] & ~(mask << startBit) | (v << startBit);
        if (startLong != endLong) {
            int endBits = 64 - startBit;
            int spill = bits - endBits;
            data[endLong] = (data[endLong] >>> spill << spill) | (v >> endBits);
        }
    }
}
