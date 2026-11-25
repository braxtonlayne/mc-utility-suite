package com.utilitysuite.world.changes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Compact record of one chunk column at a point in time: per 16x16x16 section a block-state palette hash, plus (for
 * detailed chunks: marked by the user or near a waypoint) a one-byte hash per block, where 0 always means air. Known
 * non-empty containers (from StorageMemory) are kept as packed positions. Immutable after capture; engine-free.
 */
public final class ChunkSnapshot {

    public static final int SECTION_VOLUME = 4096;

    public final int chunkX;
    public final int chunkZ;
    public final int minSectionY;
    public final long capturedMs;
    /** Palette hash per section (index 0 = {@link #minSectionY}); 0 = all air. */
    public final int[] paletteHash;
    /** Per-block hashes per section, or {@code null} for coarse snapshots or all-air sections. Index = y<<8|z<<4|x. */
    public final byte[][] blocks;
    /** Packed block positions of containers known to hold items at capture time. */
    public final long[] containers;

    public ChunkSnapshot(int chunkX, int chunkZ, int minSectionY, long capturedMs, int[] paletteHash, byte[][] blocks,
                         long[] containers) {
        if (blocks != null && blocks.length != paletteHash.length) {
            throw new IllegalArgumentException("section count mismatch");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.capturedMs = capturedMs;
        this.paletteHash = paletteHash;
        this.blocks = blocks;
        this.containers = containers == null ? new long[0] : containers;
    }

    public int sectionCount() {
        return paletteHash.length;
    }

    public boolean detailed() {
        return blocks != null;
    }

    /** One-byte hash of a block-state id; 0 is reserved for air (pass {@code air = true}). */
    public static byte blockHash(int stateId, boolean air) {
        if (air) {
            return 0;
        }
        int h = stateId * 0x9E3779B9;
        h ^= h >>> 16;
        return (byte) (1 + Integer.remainderUnsigned(h, 255));
    }

    /** Order-independent palette hash: mixes each distinct state id; 0 for an all-air section. */
    public static int paletteHash(int[] stateIds, int count) {
        int acc = 0;
        for (int i = 0; i < count; i++) {
            int h = stateIds[i] * 0x85EBCA6B;
            h ^= h >>> 13;
            h *= 0xC2B2AE35;
            h ^= h >>> 16;
            acc += h;
        }
        return acc;
    }

    public static int index(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    void write(DataOutputStream out) throws IOException {
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeByte(minSectionY);
        out.writeLong(capturedMs);
        out.writeByte(paletteHash.length);
        for (int h : paletteHash) {
            out.writeInt(h);
        }
        out.writeBoolean(blocks != null);
        if (blocks != null) {
            for (byte[] b : blocks) {
                out.writeBoolean(b != null);
                if (b != null) {
                    out.write(b);
                }
            }
        }
        out.writeInt(containers.length);
        for (long c : containers) {
            out.writeLong(c);
        }
    }

    static ChunkSnapshot read(DataInputStream in) throws IOException {
        int cx = in.readInt();
        int cz = in.readInt();
        int minY = in.readByte();
        long ts = in.readLong();
        int n = in.readUnsignedByte();
        int[] ph = new int[n];
        for (int i = 0; i < n; i++) {
            ph[i] = in.readInt();
        }
        byte[][] blocks = null;
        if (in.readBoolean()) {
            blocks = new byte[n][];
            for (int i = 0; i < n; i++) {
                if (in.readBoolean()) {
                    blocks[i] = new byte[SECTION_VOLUME];
                    in.readFully(blocks[i]);
                }
            }
        }
        int cn = in.readInt();
        if (cn < 0 || cn > 65536) {
            throw new IOException("bad container count " + cn);
        }
        long[] cs = new long[cn];
        for (int i = 0; i < cn; i++) {
            cs[i] = in.readLong();
        }
        return new ChunkSnapshot(cx, cz, minY, ts, ph, blocks, cs);
    }
}
