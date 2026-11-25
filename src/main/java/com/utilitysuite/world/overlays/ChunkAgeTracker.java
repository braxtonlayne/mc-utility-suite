package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Exploration trail heuristic: freshly generated chunks still have liquids settling, so the server sends flowing-fluid
 * block updates for them right after they arrive; chunks that existed before are already settled. A chunk with at
 * least {@code minUpdates} fluid updates within {@code windowTicks} of loading is classified NEW, one that stayed quiet
 * for the window (and contained fluids) is classified OLD. Also records palette/block-entity forensic signals and
 * supports compact binary persistence. Pure logic; client thread.
 * <p>Timestamps (R5 recency): when a chunk is first classified NEW, the game time it loaded at and the matching wall
 * clock time are kept ({@link #seenTickOf}, {@link #seenWallOf}; 0 = unknown, e.g. chunks from a version-1 save). Game
 * time gives trail speeds in blocks per game tick (server lag does not distort them); wall time gives the "how long
 * ago" that survives sessions. File format v2 stores the entries as packed columns (keys, states, flags, ticks, wall
 * times); v1 files (no timestamps) still load.
 */
public final class ChunkAgeTracker {

    public static final byte UNKNOWN = 0;
    public static final byte NEW = 1;
    public static final byte OLD = 2;

    public static final byte FLAG_NONE = 0;
    public static final byte PALETTE_NEW = 1 << 0;
    public static final byte PALETTE_OLD = 1 << 1;
    public static final byte SPAWNER_ACTIVE = 1 << 2;
    public static final byte OXIDIZED_COPPER = 1 << 3;

    private static final int MAGIC = 0x5554524C; // "UTRL"
    static final int VERSION_1 = 1;
    static final int VERSION = 2;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final long MS_PER_TICK = 50L;

    private final Long2LongOpenHashMap loadTick = new Long2LongOpenHashMap();
    private final Long2ByteOpenHashMap updates = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap state = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap flags = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap hasFluid = new Long2ByteOpenHashMap();
    /** Game time the chunk loaded at when it was first classified NEW (0 = unknown). */
    private final Long2LongOpenHashMap seenTick = new Long2LongOpenHashMap();
    /** Wall clock (epoch ms) of that load (0 = unknown). */
    private final Long2LongOpenHashMap seenWall = new Long2LongOpenHashMap();
    private int windowTicks = 60;
    private int minUpdates = 2;
    private boolean dirty;

    public void configure(int windowTicks, int minUpdates) {
        this.windowTicks = Math.max(1, windowTicks);
        this.minUpdates = Math.max(1, minUpdates);
    }

    public void onChunkLoad(int chunkX, int chunkZ, long tick, boolean containsFluid) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        loadTick.put(k, tick);
        updates.remove(k);
        hasFluid.put(k, containsFluid ? (byte) 1 : 0);
        if (!state.containsKey(k)) {
            state.put(k, UNKNOWN);
        }
    }

    public void onChunkUnload(int chunkX, int chunkZ) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        loadTick.remove(k);
        updates.remove(k);
        hasFluid.remove(k);
        // the classification is kept so the trail stays visible after the chunk unloads
    }

    /** A flowing (non-source) fluid state arrived for a block in the chunk (wall time: now). */
    public void onFluidUpdate(int chunkX, int chunkZ, long tick) {
        onFluidUpdate(chunkX, chunkZ, tick, System.currentTimeMillis());
    }

    /** A flowing (non-source) fluid state arrived for a block in the chunk at game {@code tick}, wall {@code nowMs}. */
    public void onFluidUpdate(int chunkX, int chunkZ, long tick, long nowMs) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        if (!loadTick.containsKey(k)) {
            return;
        }
        long loaded = loadTick.get(k);
        if (tick - loaded > windowTicks) {
            return;
        }
        byte n = (byte) Math.min(127, updates.get(k) + 1);
        updates.put(k, n);
        if (n >= minUpdates && state.get(k) != NEW) {
            state.put(k, NEW);
            dirty = true;
            if (!seenTick.containsKey(k)) {
                seenTick.put(k, Math.max(1L, loaded)); // 0 is "unknown"
                seenWall.put(k, Math.max(1L, nowMs - Math.max(0L, tick - loaded) * MS_PER_TICK));
            }
        }
    }

    /** Settles chunks whose window passed without updates. Call once per second; cost O(loaded chunks). */
    public void settle(long tick) {
        for (Long2LongOpenHashMap.Entry e : loadTick.long2LongEntrySet()) {
            long k = e.getLongKey();
            if (state.get(k) == UNKNOWN && tick - e.getLongValue() > windowTicks && hasFluid.get(k) == 1) {
                state.put(k, OLD);
                dirty = true;
            }
        }
    }

    /**
     * Records palette and block-entity forensic signals for a chunk.
     *
     * @param paletteLooksOld true if the section palette suggests an older/modified chunk
     * @param modifiedSignals true if explicit modification signals (e.g. active spawner, oxidized copper) were found
     */
    public void onPaletteCheck(int chunkX, int chunkZ, boolean paletteLooksOld, boolean modifiedSignals) {
        onPaletteCheck(chunkX, chunkZ, paletteLooksOld, modifiedSignals, FLAG_NONE);
    }

    /** Records palette and block-entity forensic signals with explicit forensic flags. */
    public void onPaletteCheck(int chunkX, int chunkZ, boolean paletteLooksOld, boolean modifiedSignals,
                               byte forensicFlags) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        byte f = (byte) (flags.get(k) | forensicFlags);
        if (modifiedSignals || paletteLooksOld) {
            f |= PALETTE_OLD;
            if (state.get(k) == UNKNOWN || modifiedSignals) {
                state.put(k, OLD);
            }
        } else {
            f |= PALETTE_NEW;
        }
        flags.put(k, f);
        dirty = true;
    }

    public byte stateOf(int chunkX, int chunkZ) {
        return state.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    public byte flagsOf(int chunkX, int chunkZ) {
        return flags.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    /** Game time the chunk loaded at when first classified NEW, or 0 when unknown. */
    public long seenTickOf(int chunkX, int chunkZ) {
        return seenTick.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    /** Wall clock (epoch ms) of that, or 0 when unknown. */
    public long seenWallOf(int chunkX, int chunkZ) {
        return seenWall.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    Long2ByteOpenHashMap stateMap() {
        return state;
    }

    /** Returns and clears the changed-since-last-save flag (debounced saving). */
    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    /** Version 2: header, then the entries as packed columns. */
    public void write(DataOutputStream out) throws IOException {
        int n = state.size();
        long[] keys = new long[n];
        int i = 0;
        var it = ((Long2ByteMap.FastEntrySet) state.long2ByteEntrySet()).fastIterator();
        while (it.hasNext() && i < n) {
            keys[i++] = it.next().getLongKey();
        }
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(n);
        for (int j = 0; j < n; j++) {
            out.writeLong(keys[j]);
        }
        for (int j = 0; j < n; j++) {
            out.writeByte(state.get(keys[j]));
        }
        for (int j = 0; j < n; j++) {
            out.writeByte(flags.get(keys[j]));
        }
        for (int j = 0; j < n; j++) {
            out.writeLong(seenTick.get(keys[j]));
        }
        for (int j = 0; j < n; j++) {
            out.writeLong(seenWall.get(keys[j]));
        }
    }

    /** The version-1 layout (no timestamps), kept for the backward-compatibility test. */
    void writeV1(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION_1);
        out.writeInt(state.size());
        var it = ((Long2ByteMap.FastEntrySet) state.long2ByteEntrySet()).fastIterator();
        while (it.hasNext()) {
            Long2ByteMap.Entry e = it.next();
            long k = e.getLongKey();
            out.writeLong(k);
            out.writeByte(e.getByteValue());
            out.writeByte(flags.get(k));
        }
    }

    /**
     * Reads a v1 or v2 file and merges it in: chunks already classified in this session keep their live state (and
     * timestamps), everything else comes from the file. Throws on a foreign magic, an unknown version or a bad count.
     */
    public void read(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid ChunkAgeTracker magic: 0x" + Integer.toHexString(magic));
        }
        int version = in.readInt();
        if (version != VERSION && version != VERSION_1) {
            throw new IOException("Unsupported ChunkAgeTracker version: " + version);
        }
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Invalid ChunkAgeTracker entry count: " + count);
        }
        long[] keys = new long[count];
        byte[] st = new byte[count];
        byte[] fl = new byte[count];
        long[] tk = null;
        long[] wl = null;
        if (version == VERSION_1) {
            for (int i = 0; i < count; i++) {
                keys[i] = in.readLong();
                st[i] = in.readByte();
                fl[i] = in.readByte();
            }
        } else {
            tk = new long[count];
            wl = new long[count];
            for (int i = 0; i < count; i++) {
                keys[i] = in.readLong();
            }
            in.readFully(st);
            in.readFully(fl);
            for (int i = 0; i < count; i++) {
                tk[i] = in.readLong();
            }
            for (int i = 0; i < count; i++) {
                wl[i] = in.readLong();
            }
        }
        state.ensureCapacity(state.size() + count);
        for (int i = 0; i < count; i++) {
            long k = keys[i];
            if (state.containsKey(k) && state.get(k) != UNKNOWN) {
                continue; // live classification wins
            }
            state.put(k, st[i]);
            if (fl[i] != FLAG_NONE) {
                flags.put(k, (byte) (flags.get(k) | fl[i]));
            }
            if (tk != null && tk[i] != 0) {
                seenTick.put(k, tk[i]);
            }
            if (wl != null && wl[i] != 0) {
                seenWall.put(k, wl[i]);
            }
        }
    }

    public void clear() {
        loadTick.clear();
        updates.clear();
        state.clear();
        flags.clear();
        hasFluid.clear();
        seenTick.clear();
        seenWall.clear();
        dirty = false;
    }

    public int size() {
        return state.size();
    }
}
