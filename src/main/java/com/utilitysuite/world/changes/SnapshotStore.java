package com.utilitysuite.world.changes;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Snapshots for one world + dimension, keyed by packed chunk position. Serialised as gzip-compressed primitive arrays
 * (air-heavy hash arrays compress to a few hundred bytes). Engine-free; callers write the bytes off-thread.
 */
public final class SnapshotStore {

    private static final int MAGIC = 0x55534E50; // "USNP"
    private static final int VERSION = 1;
    public static final int MAX_CHUNKS = 4096;

    private final Long2ObjectOpenHashMap<ChunkSnapshot> chunks = new Long2ObjectOpenHashMap<>();
    private boolean dirty;

    public synchronized void put(ChunkSnapshot s) {
        if (chunks.size() >= MAX_CHUNKS && !chunks.containsKey(BlockPosPacking.chunk(s.chunkX, s.chunkZ))) {
            return; // keep the footprint bounded
        }
        chunks.put(BlockPosPacking.chunk(s.chunkX, s.chunkZ), s);
        dirty = true;
    }

    public synchronized ChunkSnapshot get(int chunkX, int chunkZ) {
        return chunks.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    public synchronized int size() {
        return chunks.size();
    }

    public synchronized void clear() {
        chunks.clear();
        dirty = true;
    }

    /** Returns and clears the dirty flag (for debounced saving). */
    public synchronized boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized byte[] toBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(chunks.size());
            for (ChunkSnapshot s : chunks.values()) {
                s.write(out);
            }
        }
        return bytes.toByteArray();
    }

    public synchronized void fromBytes(byte[] data) throws IOException {
        chunks.clear();
        if (data == null || data.length == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not a snapshot file");
            }
            if (in.readInt() != VERSION) {
                throw new IOException("unsupported snapshot version");
            }
            int n = in.readInt();
            if (n < 0 || n > MAX_CHUNKS) {
                throw new IOException("bad chunk count " + n);
            }
            for (int i = 0; i < n; i++) {
                ChunkSnapshot s = ChunkSnapshot.read(in);
                chunks.put(BlockPosPacking.chunk(s.chunkX, s.chunkZ), s);
            }
        }
        dirty = false;
    }
}
