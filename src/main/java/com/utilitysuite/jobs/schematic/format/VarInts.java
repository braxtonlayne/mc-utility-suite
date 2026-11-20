package com.utilitysuite.jobs.schematic.format;

/** Unsigned LEB128 varints, as used by Sponge {@code .schem} {@code BlockData}. */
public final class VarInts {

    private VarInts() {
    }

    /**
     * Decodes exactly {@code count} varints from {@code data}.
     *
     * @throws SchematicParseException when the data ends early or a varint is longer than 5 bytes
     */
    public static int[] decode(byte[] data, int count) throws SchematicParseException {
        int[] out = new int[count];
        int pos = 0;
        for (int i = 0; i < count; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new SchematicParseException("Block data ended after " + i + " of " + count + " entries");
                }
                byte b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new SchematicParseException("Varint longer than 5 bytes at entry " + i);
                }
            }
            out[i] = value;
        }
        return out;
    }

    /** Encodes values as varints (tests and fixture writing). */
    public static byte[] encode(int[] values) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(values.length);
        for (int v : values) {
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }
}
